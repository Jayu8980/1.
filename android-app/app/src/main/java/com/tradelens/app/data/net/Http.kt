package com.tradelens.app.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

val AppJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }

class ApiException(val code: Int, message: String, val body: String? = null) : IOException(message) {
    val isAuth get() = code == 401 || code == 403
    val isRateLimit get() = code == 429
}

/**
 * Token-bucket limiter with a FIFO request queue (coroutines suspend in order on the mutex).
 */
class RateLimiter(private val permitsPerSecond: Double, private val burst: Int = 1) {
    private val mutex = Mutex()
    private var tokens = burst.toDouble()
    private var last = System.nanoTime()

    suspend fun acquire() = mutex.withLock {
        while (true) {
            val now = System.nanoTime()
            tokens = min(burst.toDouble(), tokens + (now - last) / 1e9 * permitsPerSecond)
            last = now
            if (tokens >= 1) { tokens -= 1; return@withLock }
            delay(((1 - tokens) / permitsPerSecond * 1000).toLong().coerceAtLeast(5))
        }
    }
}

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Executes with limiter + retries on 429/5xx/IO with exponential backoff. */
    suspend fun execute(req: Request, limiter: RateLimiter? = null, retries: Int = 3): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var backoff = 500L
        while (true) {
            limiter?.acquire()
            try {
                client.newCall(req).execute().use { r -> return@withContext handle(r) }
            } catch (e: ApiException) {
                if ((e.isRateLimit || e.code >= 500) && attempt < retries) { attempt++; delay(backoff); backoff *= 2; continue }
                throw e
            } catch (e: IOException) {
                if (attempt < retries) { attempt++; delay(backoff); backoff *= 2; continue }
                throw e
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    private fun handle(r: Response): String {
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw ApiException(r.code, "HTTP ${r.code}: ${body.take(300)}", body)
        return body
    }
}

// ---- small JSON helpers ----
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonObject?.o(k: String): JsonObject? = this?.get(k) as? JsonObject
fun JsonObject?.a(k: String): JsonArray? = this?.get(k) as? JsonArray
fun JsonObject?.s(k: String): String? = (this?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject?.d(k: String): Double? = (this?.get(k) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
fun JsonObject?.l(k: String): Long? = (this?.get(k) as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }
fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
fun parseJson(s: String): JsonElement = AppJson.parseToJsonElement(s)
