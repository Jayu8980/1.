package com.tubepilot.media.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Base64
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.error.MissingConfigurationException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.ImageProviderType
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.security.SecretKey
import com.tubepilot.core.security.SecretStore
import com.tubepilot.core.util.TubeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

interface ImageGenerator {
    val type: ImageProviderType
    suspend fun isAvailable(): Boolean
    /** Generates an original image for [prompt] in the orientation of [format] and writes a JPEG to [out]. */
    suspend fun generate(prompt: String, format: VideoFormat, out: File, seed: Long): File
}

/** Prefix that keeps generated visuals original and free of third-party IP. */
internal const val SAFE_PROMPT_PREFIX =
    "Original artwork for a YouTube video. No text, no logos, no watermarks, no real or famous people, no copyrighted characters. "

internal fun saveJpeg(bitmap: Bitmap, out: File, quality: Int = 90): File {
    out.parentFile?.mkdirs()
    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    return out
}

internal fun decodeBase64Image(b64: String, out: File): File {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Provider returned an undecodable image")
    try {
        return saveJpeg(bitmap, out)
    } finally {
        bitmap.recycle()
    }
}

internal fun postJson(http: OkHttpClient, provider: String, request: Request): JsonObject {
    val response = try {
        http.newCall(request).execute()
    } catch (e: IOException) {
        throw ProviderException(provider, "Network error: ${e.message}", null, retryable = true, cause = e)
    }
    return response.use {
        val text = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw ProviderException(provider, "HTTP ${it.code}: ${text.take(400)}", it.code, retryable = it.code == 429 || it.code >= 500)
        TubeJson.parseToJsonElement(text).jsonObject
    }
}

@Singleton
class OpenAiImageGenerator @Inject constructor(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
) : ImageGenerator {
    override val type = ImageProviderType.OPENAI
    var model: String = "gpt-image-1"

    override suspend fun isAvailable() = secrets.isSet(SecretKey.OPENAI_API_KEY)

    override suspend fun generate(prompt: String, format: VideoFormat, out: File, seed: Long): File = withContext(Dispatchers.IO) {
        val key = secrets.get(SecretKey.OPENAI_API_KEY) ?: throw MissingConfigurationException("Add an OpenAI API key for image generation")
        val body = buildJsonObject {
            put("model", model)
            put("prompt", SAFE_PROMPT_PREFIX + prompt)
            put("size", if (format == VideoFormat.SHORT) "1024x1536" else "1536x1024")
            put("quality", "medium")
            put("n", 1)
        }
        val json = postJson(
            http, "OpenAI Images",
            Request.Builder().url("https://api.openai.com/v1/images/generations")
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        )
        val b64 = ((json["data"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("b64_json") as? JsonPrimitive
            ?: throw ProviderException("OpenAI Images", "No image in response", null, retryable = true)
        decodeBase64Image(b64.content, out)
    }
}

@Singleton
class GeminiImageGenerator @Inject constructor(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
) : ImageGenerator {
    override val type = ImageProviderType.GEMINI
    var model: String = "imagen-4.0-generate-001"

    override suspend fun isAvailable() = secrets.isSet(SecretKey.GEMINI_API_KEY)

    override suspend fun generate(prompt: String, format: VideoFormat, out: File, seed: Long): File = withContext(Dispatchers.IO) {
        val key = secrets.get(SecretKey.GEMINI_API_KEY) ?: throw MissingConfigurationException("Add a Gemini API key for Imagen")
        val body = buildJsonObject {
            put("instances", buildJsonArray { add(buildJsonObject { put("prompt", SAFE_PROMPT_PREFIX + prompt) }) })
            put("parameters", buildJsonObject {
                put("sampleCount", 1)
                put("aspectRatio", if (format == VideoFormat.SHORT) "9:16" else "16:9")
                put("personGeneration", "allow_adult")
            })
        }
        val json = postJson(
            http, "Imagen",
            Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:predict")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        )
        val b64 = ((json["predictions"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("bytesBase64Encoded") as? JsonPrimitive
            ?: throw ProviderException("Imagen", "No image in response (it may have been filtered)", null, retryable = false)
        decodeBase64Image(b64.content, out)
    }
}

/**
 * Fully on-device fallback: abstract gradient backgrounds with soft light shapes in the brand
 * palette. Not photo-realistic, but always available, free and guaranteed original.
 */
@Singleton
class ProceduralImageGenerator @Inject constructor() : ImageGenerator {
    override val type = ImageProviderType.ON_DEVICE
    var palette: List<Int> = listOf(Color.parseColor("#1B1F3B"), Color.parseColor("#FFD400"), Color.parseColor("#E94560"))

    override suspend fun isAvailable() = true

    override suspend fun generate(prompt: String, format: VideoFormat, out: File, seed: Long): File = withContext(Dispatchers.Default) {
        val (w, h) = if (format == VideoFormat.SHORT) 1080 to 1920 else 1920 to 1080
        val random = Random(seed xor prompt.hashCode().toLong())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val base = palette.getOrElse(0) { Color.DKGRAY }
            val accent = palette.getOrElse(1 + random.nextInt(maxOf(1, palette.size - 1))) { Color.YELLOW }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), darken(base, 0.6f), shift(base, random.nextFloat() * 40f - 20f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            repeat(7 + random.nextInt(6)) {
                val cx = random.nextFloat() * w
                val cy = random.nextFloat() * h
                val r = (0.15f + random.nextFloat() * 0.45f) * minOf(w, h)
                val color = if (random.nextBoolean()) accent else shift(base, 60f)
                paint.shader = RadialGradient(cx, cy, r, withAlpha(color, 60 + random.nextInt(70)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, r, paint)
            }
            paint.shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            saveJpeg(bitmap, out, 88)
        } finally {
            bitmap.recycle()
        }
    }

    private fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun shift(c: Int, hueDelta: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + hueDelta + 360f) % 360f
        hsv[2] = (hsv[2] + 0.15f).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }
}

/** Uses the configured generator and falls back to the procedural one if it fails. */
@Singleton
class ImageRouter @Inject constructor(
    private val openAi: OpenAiImageGenerator,
    private val gemini: GeminiImageGenerator,
    private val procedural: ProceduralImageGenerator,
    private val audit: AuditLog,
) {
    data class Result(val file: File, val source: String)

    suspend fun generate(
        provider: ImageProviderType,
        model: String,
        brandColors: List<String>,
        prompt: String,
        format: VideoFormat,
        out: File,
        seed: Long,
        projectId: String?,
    ): Result {
        procedural.palette = brandColors.mapNotNull { runCatching { Color.parseColor(it) }.getOrNull() }.ifEmpty { procedural.palette }
        val primary: ImageGenerator = when (provider) {
            ImageProviderType.OPENAI -> openAi.also { it.model = model.ifBlank { "gpt-image-1" } }
            ImageProviderType.GEMINI -> gemini
            ImageProviderType.ON_DEVICE -> procedural
        }
        if (primary !== procedural && primary.isAvailable()) {
            try {
                return Result(primary.generate(prompt, format, out, seed), primary.type.displayName)
            } catch (e: Exception) {
                audit.record("ImageRouter", "image.fallback", "${primary.type} failed: ${e.message}; using on-device generator", projectId, AuditLevel.WARNING)
            }
        }
        return Result(procedural.generate(prompt, format, out, seed), procedural.type.displayName)
    }
}
