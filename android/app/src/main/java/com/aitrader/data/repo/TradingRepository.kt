package com.aitrader.data.repo

import com.aitrader.data.demo.DemoEngine
import com.aitrader.data.local.AlertEntity
import com.aitrader.data.local.CacheDao
import com.aitrader.data.local.CacheEntity
import com.aitrader.data.local.SettingsStore
import com.aitrader.data.model.*
import com.aitrader.data.remote.EngineApi
import com.aitrader.data.remote.EngineClient
import com.aitrader.security.ActionSigner
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** A value plus where it came from. `fromCache` = server unreachable, showing last good copy. */
data class Loaded<T>(val data: T, val fromCache: Boolean, val updatedAt: Long, val error: String? = null)

class NoDataException(msg: String) : Exception(msg)

@Singleton
class TradingRepository @Inject constructor(
    private val settings: SettingsStore,
    private val client: EngineClient,
    private val dao: CacheDao,
    private val signer: ActionSigner,
    private val json: Json,
) {
    val demo = DemoEngine()

    /** Which account the Home/Positions screens show: "current" (what the engine trades now) or
     *  "live" (your real broker account, read-only, even while the engine is in PAPER). */
    val venue = kotlinx.coroutines.flow.MutableStateFlow("current")
    val alerts: Flow<List<AlertEntity>> = dao.alerts()

    suspend fun isDemo() = settings.current().demoMode

    private suspend fun <T> load(key: String, ser: KSerializer<T>, demoFn: () -> T, remote: suspend EngineApi.() -> T): Loaded<T> {
        if (isDemo()) return Loaded(demoFn(), false, System.currentTimeMillis())
        return try {
            val v = client.api().remote()
            dao.put(CacheEntity(key, json.encodeToString(ser, v), System.currentTimeMillis()))
            Loaded(v, false, System.currentTimeMillis())
        } catch (e: Exception) {
            val c = dao.get(key) ?: throw NoDataException(describe(e))
            Loaded(json.decodeFromString(ser, c.json), true, c.updatedAt, describe(e))
        }
    }

    private suspend fun act(title: String, demoFn: () -> ActionResult, remote: suspend EngineApi.() -> ActionResult): ActionResult =
        try {
            if (isDemo()) { signer.confirm(title, "Demo mode — paper only"); demoFn() }
            else client.api().remote()
        } catch (e: Exception) { ActionResult(false, describe(e)) }

    fun describe(e: Throwable): String = when (e) {
        is HttpException -> serverDetail(e) ?: when (e.code()) {
            401 -> "Session expired — log in again in Settings"
            403 -> "Engine rejected the request (signature / permission)"
            409 -> "Blocked by the risk engine"
            else -> "Engine error ${e.code()}"
        }
        is com.aitrader.data.remote.SigningAborted -> "Not authorised: ${e.message}"
        is javax.net.ssl.SSLPeerUnverifiedException -> "TLS pin mismatch — refusing to connect"
        is java.io.IOException -> "Engine unreachable (${e.javaClass.simpleName})"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** FastAPI errors carry {"detail": "..."}; show the engine's own explanation when present. */
    private fun serverDetail(e: HttpException): String? = runCatching {
        val body = e.response()?.errorBody()?.string() ?: return null
        json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }["detail"]
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }.getOrNull()?.takeIf { e.code() != 401 }

    // ------------------------------------------------------------ reads
    suspend fun dashboard() = venue.value.let { v -> load("dashboard:$v", Dashboard.serializer(), demo::dashboard) { dashboard(v) } }
    suspend fun positions() = venue.value.let { v -> load("positions:$v", ListSerializer(Position.serializer()), demo::positions) { positions(v) } }
    suspend fun orders() = venue.value.let { v -> load("orders:$v", ListSerializer(Order.serializer()), demo::orders) { orders(v) } }

    /** Manual order. Signed with biometric; goes to the REAL broker when the engine is LIVE. */
    suspend fun placeOrder(t: OrderTicket) = act(
        if (t.expectLive) "REAL ORDER: ${t.side} ${t.qty} ${t.symbol}" else "Paper order: ${t.side} ${t.qty} ${t.symbol}",
        { demo.placeOrder(t) }) { placeOrder(t) }

    suspend fun cancelOrder(id: String): ActionResult = try {
        if (isDemo()) demo.cancelOrder(id) else client.api().cancelOrder(id, venue.value)
    } catch (e: Exception) { ActionResult(false, describe(e)) }
    suspend fun signals() = load("signals", ListSerializer(Signal.serializer()), demo::signals) { signals() }
    suspend fun strategies() = load("strategies", ListSerializer(StrategyStats.serializer()), demo::strategies) { strategies() }
    suspend fun risk() = load("risk", RiskConfig.serializer(), demo::risk) { risk() }
    suspend fun audit() = load("audit", ListSerializer(AuditEntry.serializer()), demo::audit) { audit() }
    suspend fun analyst(symbol: String) =
        load("analyst:$symbol", Analysis.serializer(), { demo.analyst(symbol) }) { analyst(symbol) }
    suspend fun backtest(req: BacktestRequest) =
        load("bt:${req.strategyId}:${req.from}:${req.to}", Tearsheet.serializer(), { demo.backtest(req) }) { backtest(req) }

    suspend fun watchlist() = load("watchlist", ListSerializer(WatchItem.serializer()), demo::watchlist) { watchlist() }

    suspend fun search(q: String, exchange: String): List<InstrumentHit> =
        if (isDemo()) demo.search(q, exchange) else client.api().search(q, exchange)

    suspend fun addWatch(c: ContractDto): ActionResult = try {
        if (isDemo()) demo.addWatch(c) else client.api().addWatch(c)
    } catch (e: Exception) { ActionResult(false, describe(e)) }

    suspend fun removeWatch(c: ContractDto): ActionResult = try {
        if (isDemo()) demo.removeWatch(c.key) else client.api().removeWatch(c.exchange, c.symbol)
    } catch (e: Exception) { ActionResult(false, describe(e)) }

    suspend fun autotrade() = load("autotrade", AutoTradeStatus.serializer(), demo::autotrade) { autotrade() }

    /** ON moves money -> biometric-signed. */
    suspend fun autotradeOn() = act("Turn auto-trading ON", demo::autotradeOn) { autotradeOn() }

    /** OFF is instant (like the kill switch): stops new entries, open trades stay protected. */
    suspend fun autotradeOff(): ActionResult = try {
        if (isDemo()) demo.autotradeOff() else client.api().autotradeOff()
    } catch (e: Exception) { ActionResult(false, describe(e)) }

    suspend fun promote(id: String) = act("Promote $id toward LIVE", { demo.promote(id) }) { promote(id) }

    suspend fun demote(id: String): ActionResult = try {
        if (isDemo()) demo.demote(id) else client.api().demote(id)
    } catch (e: Exception) { ActionResult(false, describe(e)) }

    suspend fun brokers() = load("brokers", ListSerializer(BrokerInfo.serializer()), demo::brokers) { brokers() }

    // ------------------------------------------------------------ actions
    /** OAUTH brokers return a loginUrl for the system browser; TOTP/TOKEN brokers connect server-side. */
    suspend fun connectBroker(id: String): ConnectResult = try {
        if (isDemo()) { signer.confirm("Connect $id", "Demo mode — simulated"); demo.connectBroker(id) }
        else client.api().connectBroker(id)
    } catch (e: Exception) { ConnectResult(false, null, describe(e)) }

    suspend fun activateBroker(id: String) =
        act("Use $id for LIVE orders", { demo.activateBroker(id) }) { activateBroker(id) }

    suspend fun disconnectBroker(id: String): ActionResult = try {
        if (isDemo()) demo.disconnectBroker(id) else client.api().disconnectBroker(id)
    } catch (e: Exception) { ActionResult(false, describe(e)) }

    suspend fun exit(p: Position) = act("Exit ${p.symbol}", { demo.exit(p.symbol, p.product) }) {
        exit(ExitRequest(p.symbol, p.exchange, p.product))
    }
    suspend fun updateStrategy(id: String, u: StrategyUpdate) =
        act("Change strategy $id", { demo.updateStrategy(id, u) }) { updateStrategy(id, u) }
    suspend fun updateRisk(r: RiskConfig) = act("Change risk limits", { demo.updateRisk(r) }) { updateRisk(r) }
    suspend fun setMode(mode: TradingMode, phrase: String) =
        act("Switch to $mode", { demo.setMode(mode) }) { setMode(ModeRequest(mode, phrase)) }

    /** No biometric on purpose: the kill switch must work instantly, including from the widget. */
    suspend fun killSwitch(engaged: Boolean, reason: String): ActionResult = try {
        val r = if (isDemo()) demo.killSwitch(engaged, reason) else client.api().killSwitch(KillSwitchRequest(engaged, reason))
        if (r.ok) addAlert("kill", if (engaged) "Kill switch engaged" else "Kill switch released", reason)
        r
    } catch (e: Exception) { ActionResult(false, describe(e)) }

    suspend fun login(username: String, password: String): ActionResult = try {
        val pk = signer.publicKeyBase64()
            ?: return ActionResult(false, "Could not create the biometric signing key — enrol a fingerprint/face first")
        val r = client.api().login(LoginRequest(username, password, pk))
        settings.setToken(r.token)
        ActionResult(true, "Logged in; device key registered")
    } catch (e: Exception) { ActionResult(false, describe(e)) }

    suspend fun logout() = settings.setToken(null)

    suspend fun addAlert(kind: String, title: String, body: String) =
        dao.addAlert(AlertEntity(ts = System.currentTimeMillis(), kind = kind, title = title, body = body))
}
