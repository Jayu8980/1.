package com.tradelens.app.data.engine

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.tradelens.app.data.market.WsEvent
import com.tradelens.app.data.market.reconnectingSocket
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.data.net.Http
import com.tradelens.app.data.net.obj
import com.tradelens.app.data.net.parseJson
import com.tradelens.app.data.net.s
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.data.secure.SettingsStore
import com.tradelens.app.domain.RiskLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class EngineStatus(
    val mode: String = "paper", val equity: Double = 0.0, val cash: Double = 0.0, val dayPnl: Double = 0.0, val dayPnlPct: Double = 0.0,
    val drawdownPct: Double = 0.0, val halted: Boolean = false, val circuitBroken: Boolean = false, val globalKill: Boolean = false,
    val regime: String = "unknown", val regimeProbs: Map<String, Double> = emptyMap(), val openPositions: Int = 0, val ordersToday: Int = 0,
    val lastUpdate: Long = 0, val liveEnabled: Boolean = false, val version: String = "",
)

@Serializable data class EngineSignal(
    val id: String, val strategy: String, val symbol: String, val direction: String, val strength: Double, val confidence: Double,
    val entry: Double? = null, val stop: Double? = null, val target: Double? = null, val horizon: String = "", val qty: Double = 0.0,
    val status: String = "PENDING", val createdAt: Long = 0, val reason: String = "", val venue: String = "paper",
    val charges: Double = 0.0, val marginRequired: Double = 0.0, val leverage: Double = 1.0, val liquidationPrice: Double? = null,
)

@Serializable data class StrategyInfo(
    val name: String, val description: String = "", val stage: String = "RESEARCH", val enabled: Boolean = false, val auto: Boolean = false,
    val weight: Double = 0.0, val oosSharpe: Double = 0.0, val liveSharpe: Double? = null, val pbo: Double? = null, val deflatedSharpe: Double? = null,
    val trades: Int = 0, val regimeActive: Boolean = true, val killed: Boolean = false, val drift: Double? = null, val notes: String = "",
)

@Serializable data class Point(val t: Long, val v: Double)

@Serializable data class StrategyPerf(
    val name: String, val cagr: Double = 0.0, val sharpe: Double = 0.0, val sortino: Double = 0.0, val calmar: Double = 0.0, val maxDd: Double = 0.0,
    val winRate: Double = 0.0, val profitFactor: Double = 0.0, val expectancy: Double = 0.0, val alpha: Double = 0.0, val beta: Double = 0.0,
    val ir: Double = 0.0, val turnover: Double = 0.0, val trades: Int = 0, val benchmark: String = "NIFTY 50 TRI",
    val equity: List<Point> = emptyList(), val benchmarkEquity: List<Point> = emptyList(), val drawdown: List<Point> = emptyList(),
    val monthly: Map<String, Double> = emptyMap(),
)

@Serializable data class Greeks(val delta: Double = 0.0, val gamma: Double = 0.0, val theta: Double = 0.0, val vega: Double = 0.0)

@Serializable data class RiskSnapshot(
    val limits: RiskLimits = RiskLimits(), val grossExposurePct: Double = 0.0, val netExposurePct: Double = 0.0, val marginUtilPct: Double = 0.0,
    val greeks: Greeks = Greeks(), val avgSlippageBps: Double = 0.0, val latencyMs: Double = 0.0, val apiErrors: Int = 0,
    val sectorExposure: Map<String, Double> = emptyMap(), val portfolioVolPct: Double = 0.0,
)

@Serializable data class AuditRow(val time: Long, val category: String, val message: String, val payload: String = "")

@Serializable data class EnginePosition(val symbol: String, val qty: Double, val avgPrice: Double, val ltp: Double, val pnl: Double, val strategy: String = "", val venue: String = "paper")

@Serializable data class Brief(val kind: String, val title: String, val text: String, val time: Long, val anomalies: List<String> = emptyList())

@Serializable data class FlowRow(val date: String, val fiiNet: Double, val diiNet: Double)
@Serializable data class ToggleBody(val enabled: Boolean)
@Serializable data class OkResponse(val ok: Boolean = true, val message: String = "")
@Serializable data class ModelMeta(val name: String = "", val features: List<String> = emptyList(), val threshold: Double = 0.55, val version: String = "", val trainedAt: Long = 0)

interface EngineApi {
    @GET("status") suspend fun status(): EngineStatus
    @GET("signals") suspend fun signals(@Query("status") status: String? = null): List<EngineSignal>
    @POST("signals/{id}/approve") suspend fun approve(@Path("id") id: String): OkResponse
    @POST("signals/{id}/reject") suspend fun reject(@Path("id") id: String): OkResponse
    @GET("strategies") suspend fun strategies(): List<StrategyInfo>
    @POST("strategies/{name}/enabled") suspend fun setEnabled(@Path("name") name: String, @Body b: ToggleBody): OkResponse
    @POST("strategies/{name}/auto") suspend fun setAuto(@Path("name") name: String, @Body b: ToggleBody): OkResponse
    @POST("strategies/{name}/kill") suspend fun kill(@Path("name") name: String, @Body b: ToggleBody): OkResponse
    @POST("kill") suspend fun globalKill(@Body b: ToggleBody): OkResponse
    @POST("restart") suspend fun restart(): OkResponse
    @POST("flatten") suspend fun flatten(): OkResponse
    @GET("risk") suspend fun risk(): RiskSnapshot
    @PUT("risk/limits") suspend fun setLimits(@Body l: RiskLimits): OkResponse
    @GET("positions") suspend fun positions(): List<EnginePosition>
    @GET("audit") suspend fun audit(@Query("limit") limit: Int = 300): List<AuditRow>
    @GET("reports/leaderboard") suspend fun leaderboard(): List<StrategyPerf>
    @GET("briefs/latest") suspend fun briefs(): List<Brief>
    @GET("regime") suspend fun regime(): JsonObject
    @GET("market/flows") suspend fun flows(): List<FlowRow>
    @GET("calendar/holidays") suspend fun holidays(): List<String>
    @GET("models/meta") suspend fun modelMeta(): ModelMeta
    @Streaming @GET("models/latest.onnx") suspend fun model(): ResponseBody
    @Streaming @GET("reports/export") suspend fun export(@Query("format") format: String): ResponseBody
}

data class EngineEvent(val type: String, val data: JsonElement?)

/** Connects to the user's own Python engine (URL + bearer token from Settings). */
@Singleton
class EngineClient @Inject constructor(private val settings: SettingsStore, private val secure: SecureStore) {
    private var cached: Pair<String, EngineApi>? = null

    fun isConfigured() = settings.value.engineUrl.isNotBlank() && secure.get(SecureStore.ENGINE_TOKEN) != null

    private fun baseUrl(): String {
        val u = com.tradelens.app.data.net.UrlPolicy.normalize(settings.value.engineUrl)
        require(u.isNotBlank()) { "Engine URL not set (Settings → Engine)" }
        com.tradelens.app.data.net.UrlPolicy.check(u, "Engine")
        return "$u/api/"
    }

    val api: EngineApi
        get() {
            val url = baseUrl()
            cached?.takeIf { it.first == url }?.let { return it.second }
            val client = Http.client.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${secure.get(SecureStore.ENGINE_TOKEN).orEmpty()}").build())
            }.build()
            val api = Retrofit.Builder().baseUrl(url).client(client)
                .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType())).build().create(EngineApi::class.java)
            cached = url to api
            return api
        }

    /** Step-by-step connectivity check used by Settings → Test engine. */
    suspend fun diagnose(): String {
        val base = runCatching { baseUrl().removeSuffix("api/") }.getOrElse { return "✗ ${it.message}" }
        if (secure.get(SecureStore.ENGINE_TOKEN).isNullOrBlank()) return "✗ Engine token is empty – paste the token printed by run_windows.ps1"
        runCatching { Http.execute(Request.Builder().url(base + "health").build(), retries = 0) }
            .onFailure { return "✗ Cannot reach ${base}health\n" + com.tradelens.app.data.net.UrlPolicy.explain(it, base, "Engine") }
        return runCatching { val s = api.status(); "✓ Engine OK – mode ${s.mode}, regime ${s.regime}, v${s.version}" }
            .getOrElse {
                val e = (it as? retrofit2.HttpException)?.let { h -> com.tradelens.app.data.net.ApiException(h.code(), "HTTP ${h.code()}", h.response()?.errorBody()?.string()) } ?: it
                "✗ Reached the engine but /api/status failed\n" + com.tradelens.app.data.net.UrlPolicy.explain(e, base, "Engine")
            }
    }

    /** Live event stream (signals, fills, risk breaches, status) with auto-reconnect. */
    fun events(): Flow<EngineEvent> = reconnectingSocket(
        request = {
            val ws = baseUrl().replaceFirst("http", "ws") + "ws"
            Request.Builder().url(ws).header("Authorization", "Bearer ${secure.get(SecureStore.ENGINE_TOKEN).orEmpty()}").build()
        },
        onOpen = { it.send("{\"type\":\"hello\"}") },
        heartbeat = { it.send("{\"type\":\"ping\"}") },
    ).mapNotNull { ev ->
        val o = (ev as? WsEvent.Text)?.text?.let { runCatching { parseJson(it).obj() }.getOrNull() } ?: return@mapNotNull null
        EngineEvent(o.s("type") ?: return@mapNotNull null, o["data"])
    }
}
