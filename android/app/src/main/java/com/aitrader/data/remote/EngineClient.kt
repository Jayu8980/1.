package com.aitrader.data.remote

import com.aitrader.data.local.SettingsStore
import com.aitrader.security.ActionSigner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class ConfigError(msg: String) : Exception(msg)

/** Must be an IOException: OkHttp rethrows any other exception from an interceptor on its own thread. */
class SigningAborted(msg: String) : java.io.IOException(msg)

/** Paths whose requests move money or risk and must carry a biometric signature. */
internal fun requiresSignature(method: String, path: String): Boolean = when {
    method == "POST" && path.endsWith("/positions/exit") -> true
    method == "PATCH" && path.contains("/strategies/") -> true
    method == "PUT" && path.endsWith("/risk") -> true
    method == "POST" && path.endsWith("/mode") -> true
    method == "POST" && path.contains("/brokers/") && (path.endsWith("/connect") || path.endsWith("/activate")) -> true
    method == "POST" && path.endsWith("/autotrade/on") -> true
    method == "POST" && path.endsWith("/api/v1/orders") -> true
    method == "POST" && path.endsWith("/api/v1/apikeys") -> true
    method == "POST" && path.contains("/intents/") && path.endsWith("/approve") -> true
    method == "POST" && path.contains("/strategies/") && path.endsWith("/promote") -> true
    else -> false
}

/** Comma/space separated "sha256/…" pins (leaf + backup, or the CA root). */
internal fun parsePins(raw: String): List<String> =
    raw.split(',', ' ', '\n').map { it.trim() }.filter { it.startsWith("sha256/") && it.length > 20 }

/** Canonical string the engine re-computes and verifies. */
internal fun signingPayload(method: String, path: String, ts: Long, body: ByteArray): ByteArray {
    val h = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    return "$method\n$path\n$ts\n$h".toByteArray()
}

@Singleton
class EngineClient @Inject constructor(
    private val settings: SettingsStore,
    private val signer: ActionSigner,
    val json: Json,
) {
    @Volatile private var cached: Pair<String, EngineApi>? = null
    @Volatile private var cachedHttp: Pair<String, OkHttpClient>? = null

    /** WebSocket request for /api/v1/stream on the same pinned client (auth header added here). */
    suspend fun streamRequest(): Pair<OkHttpClient, okhttp3.Request> {
        api()
        val s = settings.current()
        val http = cachedHttp!!.second.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()
        val wsUrl = s.engineUrl.trimEnd('/').replaceFirst("https://", "wss://") + "/api/v1/stream"
        val b = okhttp3.Request.Builder().url(wsUrl)
        settings.token()?.let { b.header("Authorization", "Bearer $it") }
        return http to b.build()
    }

    suspend fun api(): EngineApi {
        val s = settings.current()
        if (s.engineUrl.isBlank()) throw ConfigError("Set the engine URL in Settings")
        val url = s.engineUrl.trimEnd('/') + "/api/v1/"
        val parsed = runCatching { url.toHttpUrl() }.getOrElse { throw ConfigError("Invalid engine URL") }
        if (!parsed.isHttps) throw ConfigError("Engine URL must be https://")
        val pins = parsePins(s.certPin)
        if (pins.isEmpty()) throw ConfigError("Set the TLS certificate pin (sha256/…) in Settings")
        val cacheKey = "$url|${s.certPin}"
        cached?.takeIf { it.first == cacheKey }?.let { return it.second }

        val pinner = CertificatePinner.Builder().add(parsed.host, *pins.toTypedArray()).build()
        val auth = Interceptor { chain ->
            val token = runBlocking { settings.token() }
            val b = chain.request().newBuilder()
            if (token != null) b.header("Authorization", "Bearer $token")
            chain.proceed(b.build())
        }
        val signing = Interceptor { chain ->
            val req = chain.request()
            if (!requiresSignature(req.method, req.url.encodedPath)) return@Interceptor chain.proceed(req)
            val body = Buffer().also { req.body?.writeTo(it) }.readByteArray()
            val ts = System.currentTimeMillis()
            val sig = try { runBlocking {
                signer.sign("Authorise ${req.method} ${req.url.encodedPath.substringAfter("/api/v1/")}",
                    "This action affects real or paper money", signingPayload(req.method, req.url.encodedPath, ts, body))
            } } catch (e: Exception) { throw SigningAborted(e.message ?: "Authentication failed") }
            chain.proceed(req.newBuilder().header("X-Action-Ts", ts.toString()).header("X-Action-Signature", sig).build())
        }
        val http = OkHttpClient.Builder()
            .certificatePinner(pinner)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // biometric prompt + backtests
            .addInterceptor(auth)
            .addInterceptor(signing)
            .build()
        val api = Retrofit.Builder().baseUrl(url).client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(EngineApi::class.java)
        cached = cacheKey to api
        cachedHttp = cacheKey to http
        return api
    }
}
