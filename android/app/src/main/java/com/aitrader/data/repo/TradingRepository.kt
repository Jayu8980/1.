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
        is HttpException -> when (e.code()) {
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

    // ------------------------------------------------------------ reads
    suspend fun dashboard() = load("dashboard", Dashboard.serializer(), demo::dashboard) { dashboard() }
    suspend fun positions() = load("positions", ListSerializer(Position.serializer()), demo::positions) { positions() }
    suspend fun orders() = load("orders", ListSerializer(Order.serializer()), demo::orders) { orders() }
    suspend fun signals() = load("signals", ListSerializer(Signal.serializer()), demo::signals) { signals() }
    suspend fun strategies() = load("strategies", ListSerializer(StrategyStats.serializer()), demo::strategies) { strategies() }
    suspend fun risk() = load("risk", RiskConfig.serializer(), demo::risk) { risk() }
    suspend fun audit() = load("audit", ListSerializer(AuditEntry.serializer()), demo::audit) { audit() }
    suspend fun analyst(symbol: String) =
        load("analyst:$symbol", Analysis.serializer(), { demo.analyst(symbol) }) { analyst(symbol) }
    suspend fun backtest(req: BacktestRequest) =
        load("bt:${req.strategyId}:${req.from}:${req.to}", Tearsheet.serializer(), { demo.backtest(req) }) { backtest(req) }

    // ------------------------------------------------------------ actions
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
