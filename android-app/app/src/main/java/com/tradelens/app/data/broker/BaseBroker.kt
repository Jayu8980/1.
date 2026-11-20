package com.tradelens.app.data.broker

import com.tradelens.app.data.market.BrokerField
import com.tradelens.app.data.market.BrokerProvider
import com.tradelens.app.data.net.Http
import com.tradelens.app.data.net.RateLimiter
import com.tradelens.app.data.net.obj
import com.tradelens.app.data.net.parseJson
import com.tradelens.app.data.secure.SecureStore
import com.tradelens.app.domain.Instrument
import com.tradelens.app.domain.MarketHours
import com.tradelens.app.domain.Quote
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.time.ZonedDateTime

abstract class BaseBroker(protected val secure: SecureStore) : BrokerProvider {
    override val realtime = true
    protected abstract val limiter: RateLimiter
    /** Hour (IST) at which this broker's session token expires on the following day. */
    protected open val expiryHourIst: Int = 6

    protected fun cfg(field: String) = secure.get(SecureStore.brokerKey(id, field))
    protected fun setCfg(field: String, v: String?) = secure.put(SecureStore.brokerKey(id, field), v)
    protected val token: String? get() = cfg("access_token")

    override fun isConfigured() = configFields.filter { !it.key.startsWith("opt_") }.all { cfg(it.key) != null }
    override fun isLoggedIn() = token != null && !isExpired()

    override fun isExpired(): Boolean {
        if (token == null) return false
        val exp = secure.getLong(SecureStore.brokerKey(id, "expires_at"))
        return exp in 1..System.currentTimeMillis()
    }

    protected fun saveSession(accessToken: String, extra: Map<String, String> = emptyMap()) {
        setCfg("access_token", accessToken)
        extra.forEach { (k, v) -> setCfg(k, v) }
        var exp = ZonedDateTime.now(MarketHours.IST).withHour(expiryHourIst).withMinute(0).withSecond(0)
        if (!exp.isAfter(ZonedDateTime.now(MarketHours.IST))) exp = exp.plusDays(1)
        secure.putLong(SecureStore.brokerKey(id, "expires_at"), exp.toInstant().toEpochMilli())
    }

    override fun logout() {
        secure.remove(SecureStore.brokerKey(id, "access_token"), SecureStore.brokerKey(id, "expires_at"),
            SecureStore.brokerKey(id, "refresh_token"), SecureStore.brokerKey(id, "feed_token"))
    }

    protected fun requireToken(): String = token?.takeIf { !isExpired() }
        ?: throw IllegalStateException("$displayName session expired – tap Reconnect broker")

    protected abstract fun authHeaders(rb: Request.Builder): Request.Builder

    protected suspend fun get(url: String): JsonObject =
        parseJson(Http.execute(authHeaders(Request.Builder().url(url)).get().build(), limiter)).obj() ?: JsonObject(emptyMap())

    protected suspend fun postJson(url: String, body: String): JsonObject =
        parseJson(Http.execute(authHeaders(Request.Builder().url(url)).post(body.toRequestBody(JSON)).build(), limiter)).obj() ?: JsonObject(emptyMap())

    protected suspend fun postForm(url: String, form: Map<String, String>, auth: Boolean = false, headers: Map<String, String> = emptyMap()): JsonObject {
        val fb = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val rb = Request.Builder().url(url).post(fb).header("Accept", "application/json")
        headers.forEach { (k, v) -> rb.header(k, v) }
        return parseJson(Http.execute((if (auth) authHeaders(rb) else rb).build(), limiter)).obj() ?: JsonObject(emptyMap())
    }

    /** Fallback "stream" by batched quote polling when a broker socket is unavailable. */
    protected fun pollingStream(list: List<Instrument>, periodMs: Long = 3000): Flow<Quote> = flow {
        while (true) {
            runCatching { getQuotes(list) }.onSuccess { q -> q.forEach { emit(it) } }
            delay(if (list.any { MarketHours.isOpen(it) }) periodMs else 60_000)
        }
    }

    protected fun field(key: String, label: String, secret: Boolean = false, hint: String = "") = BrokerField(key, label, secret, hint)

    companion object {
        val JSON = "application/json".toMediaType()
        fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
