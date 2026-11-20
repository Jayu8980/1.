package com.tradelens.app.data.market

import com.tradelens.app.data.net.Http
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.math.min

sealed interface WsEvent {
    data class Text(val text: String) : WsEvent
    data class Binary(val bytes: ByteString) : WsEvent
}

/**
 * WebSocket that reconnects with exponential backoff (1s → 60s, jittered) and relies on OkHttp's
 * ping/pong (pingInterval on the shared client) to detect dead connections. [onOpen] is called after
 * every (re)connect so subscriptions are replayed.
 */
fun reconnectingSocket(
    request: suspend () -> Request,
    onOpen: (WebSocket) -> Unit,
    heartbeat: ((WebSocket) -> Unit)? = null,
    heartbeatMs: Long = 25_000,
): Flow<WsEvent> = callbackFlow {
    var backoff = 1000L
    val job = launch {
        while (isActive) {
            val closed = Mutex(locked = true)
            var ws: WebSocket? = null
            try {
                ws = Http.client.newWebSocket(request(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { backoff = 1000L; onOpen(webSocket) }
                    override fun onMessage(webSocket: WebSocket, text: String) { trySendBlocking(WsEvent.Text(text)) }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { trySendBlocking(WsEvent.Binary(bytes)) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (closed.isLocked) closed.unlock() }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (closed.isLocked) closed.unlock() }
                })
                val hb = heartbeat?.let { h -> launch { while (isActive) { delay(heartbeatMs); ws?.let(h) } } }
                closed.lock()
                hb?.cancel()
            } catch (_: Exception) {
            } finally {
                ws?.cancel()
            }
            delay(backoff + (Math.random() * 500).toLong())
            backoff = min(backoff * 2, 60_000L)
        }
    }
    awaitClose { job.cancel() }
}
