package com.aitrader.data.repo

import com.aitrader.data.local.SettingsStore
import com.aitrader.data.model.Quote
import com.aitrader.data.remote.EngineClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FeedState {
    data object Idle : FeedState
    data object Connecting : FeedState
    data class Live(val sources: Map<String, List<String>>, val unassigned: List<String>, val activeBroker: String?) : FeedState
    data class Error(val message: String, val retryInSec: Int) : FeedState
}

/** Parses one engine /stream message. Pure, unit-tested. */
object StreamParser {
    sealed interface Msg
    data class TickMsg(val key: String, val symbol: String, val exchange: String, val ltp: Double, val ts: Long, val source: String) : Msg
    data class StatusMsg(val sources: Map<String, List<String>>, val unassigned: List<String>,
                         val activeBroker: String?, val killSwitch: Boolean, val mode: String) : Msg

    fun parse(text: String): Msg? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        when (o["type"]?.jsonPrimitive?.content) {
            "tick" -> TickMsg(o.str("key"), o.str("symbol"), o.str("exchange"), o["ltp"]!!.jsonPrimitive.double,
                o["ts"]!!.jsonPrimitive.long, o.str("source"))
            "status" -> {
                val feed = o["feed"]!!.jsonObject
                StatusMsg(feed["sources"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } },
                    feed["unassigned"]!!.jsonArray.map { it.jsonPrimitive.content },
                    o["activeBroker"]?.jsonPrimitive?.contentOrNull, o["killSwitch"]?.jsonPrimitive?.boolean ?: false,
                    o["mode"]?.jsonPrimitive?.content ?: "PAPER")
            }
            else -> null
        }
    }.getOrNull()

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
}

/**
 * Live quotes for the Markets screen. Engine mode: OkHttp WebSocket to /api/v1/stream over the
 * same TLS-pinned client, with reconnect + backoff. Demo mode: simulated ticks every second.
 * Ref-counted: runs only while a screen is observing (saves battery and data).
 */
@Singleton
class MarketFeed @Inject constructor(
    private val settings: SettingsStore,
    private val client: EngineClient,
    private val repo: TradingRepository,
) {
    private val _quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val quotes: StateFlow<Map<String, Quote>> = _quotes.asStateFlow()
    private val _state = MutableStateFlow<FeedState>(FeedState.Idle)
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var users = 0

    @Synchronized fun acquire() { if (users++ == 0) job = scope.launch { loop() } }
    @Synchronized fun release() {
        if (--users <= 0) { users = 0; job?.cancel(); job = null; _state.value = FeedState.Idle }
    }

    fun apply(key: String, symbol: String, exchange: String, ltp: Double, ts: Long, source: String) {
        _quotes.value = _quotes.value.toMutableMap().also { m ->
            val old = m[key]
            m[key] = Quote(key, symbol, exchange, ltp, ts, source, prev = old?.ltp, sessionOpen = old?.sessionOpen ?: ltp)
        }
    }

    fun drop(key: String) { _quotes.value = _quotes.value - key }

    private suspend fun loop() {
        var backoff = 1
        while (currentCoroutineContext().isActive) {
            if (settings.current().demoMode) {
                _state.value = FeedState.Live(mapOf("demo" to emptyList()), emptyList(), "demo")
                while (currentCoroutineContext().isActive && settings.current().demoMode) {
                    repo.demo.quotes().forEach { apply(it.key, it.symbol, it.exchange, it.ltp, it.ts, it.source) }
                    delay(1_000)
                }
                continue
            }
            _state.value = FeedState.Connecting
            val started = System.currentTimeMillis()
            val err = runCatching { runSocket() }.exceptionOrNull()
            if (System.currentTimeMillis() - started > 10_000) backoff = 1   // was healthy: retry fast
            if (!currentCoroutineContext().isActive) break
            val msg = when {
                err == null -> "Stream closed"
                err.message?.contains("4401") == true -> "Session expired — log in again in Settings"
                else -> repo.describe(err)
            }
            _state.value = FeedState.Error(msg, backoff)
            delay(backoff * 1_000L)
            backoff = (backoff * 2).coerceAtMost(30)
        }
    }

    /** Suspends until the socket closes or fails. */
    private suspend fun runSocket() {
        val (http, req) = client.streamRequest()
        val done = CompletableDeferred<Unit>()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val m = StreamParser.parse(text)) {
                    is StreamParser.TickMsg -> apply(m.key, m.symbol, m.exchange, m.ltp, m.ts, m.source)
                    is StreamParser.StatusMsg -> _state.value = FeedState.Live(m.sources, m.unassigned, m.activeBroker)
                    null -> {}
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (code == 4401) done.completeExceptionally(IllegalStateException("4401 unauthorised"))
                else done.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.completeExceptionally(t)
            }
        })
        try { done.await() } finally { ws.cancel() }
    }
}
