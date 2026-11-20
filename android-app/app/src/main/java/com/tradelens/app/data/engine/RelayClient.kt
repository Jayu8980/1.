package com.tradelens.app.data.engine

import com.tradelens.app.data.net.AppJson
import com.tradelens.app.data.net.Http
import com.tradelens.app.data.net.obj
import com.tradelens.app.data.net.parseJson
import com.tradelens.app.data.net.s
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.data.secure.SettingsStore
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RelayOrder(
    val broker: String,
    val exchange: String,
    val symbol: String,
    val token: String,
    val side: String,
    val qty: Int,
    val price: Double,
    val product: String,
    val clientRef: String,
    val approvedAt: Long,
)

@Serializable
data class RelayResult(val status: String, val brokerOrderId: String? = null, val message: String = "")

/**
 * Talks to the user's static-IP order relay (/relay-server). Indian broker orders are only accepted from
 * a registered static IP (SEBI retail-algo framework), so the phone never calls broker order APIs itself.
 * The relay accepts LIMIT orders only and tags them with the broker-issued algo ID.
 */
@Singleton
class RelayClient @Inject constructor(private val settings: SettingsStore, private val secure: SecureStore) {
    fun isConfigured() = settings.value.relayUrl.isNotBlank() && secure.get(SecureStore.RELAY_TOKEN) != null

    private fun base(): String = com.tradelens.app.data.net.UrlPolicy.normalize(settings.value.relayUrl).also { com.tradelens.app.data.net.UrlPolicy.check(it, "Relay") }

    private fun req(path: String) = Request.Builder().url(base() + path)
        .header("Authorization", "Bearer ${secure.get(SecureStore.RELAY_TOKEN).orEmpty()}")

    suspend fun diagnose(): String = runCatching { "✓ " + health() }.getOrElse {
        "✗ " + (runCatching { com.tradelens.app.data.net.UrlPolicy.explain(it, base(), "Relay") }.getOrElse { e -> e.message ?: "invalid URL" })
    }

    suspend fun health(): String {
        val o = parseJson(Http.execute(req("/health").get().build(), retries = 0)).obj()
        return "Relay OK – egress IP ${o.s("egress_ip") ?: "?"}, brokers: ${o.s("brokers") ?: "?"}"
    }

    suspend fun place(order: RelayOrder, brokerSession: String?): RelayResult {
        val body = AppJson.encodeToString(RelayOrder.serializer(), order).toRequestBody("application/json".toMediaType())
        val rb = req("/orders").post(body)
        brokerSession?.let { rb.header("X-Broker-Session", it) }
        return AppJson.decodeFromString(RelayResult.serializer(), Http.execute(rb.build(), retries = 0))
    }

    suspend fun cancel(broker: String, orderId: String, brokerSession: String?): RelayResult {
        val rb = req("/orders/$broker/$orderId").delete()
        brokerSession?.let { rb.header("X-Broker-Session", it) }
        return AppJson.decodeFromString(RelayResult.serializer(), Http.execute(rb.build(), retries = 0))
    }
}
