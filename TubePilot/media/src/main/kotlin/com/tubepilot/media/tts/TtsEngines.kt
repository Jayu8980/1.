package com.tubepilot.media.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.tubepilot.core.error.MissingConfigurationException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.TtsProviderType
import com.tubepilot.core.security.SecretKey
import com.tubepilot.core.security.SecretStore
import com.tubepilot.core.util.TextUtil
import com.tubepilot.media.audio.AudioConverter
import com.tubepilot.media.audio.AudioMixer
import com.tubepilot.media.audio.Wav
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Speech synthesis. Each implementation writes canonical 48 kHz mono WAV, one file per segment. */
interface TtsEngine {
    val type: TtsProviderType
    suspend fun isAvailable(): Boolean
    suspend fun synthesize(segments: List<String>, language: ContentLanguage, voice: String, outDir: File): List<File>
}

/** Splits text into chunks below a provider's input limit, on sentence boundaries where possible. */
internal fun chunkText(text: String, maxChars: Int): List<String> {
    val chunks = ArrayList<String>()
    val current = StringBuilder()
    for (sentence in TextUtil.sentences(text)) {
        if (sentence.length > maxChars) {
            if (current.isNotEmpty()) { chunks += current.toString(); current.clear() }
            sentence.chunked(maxChars).forEach { chunks += it }
            continue
        }
        if (current.length + sentence.length + 1 > maxChars) { chunks += current.toString(); current.clear() }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks.ifEmpty { listOf(text) }
}

/** Offline Android system TTS. Hindi needs the device's Hindi voice data installed. */
@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val converter: AudioConverter,
    private val mixer: AudioMixer,
) : TtsEngine {
    override val type = TtsProviderType.ANDROID

    override suspend fun isAvailable() = true

    override suspend fun synthesize(segments: List<String>, language: ContentLanguage, voice: String, outDir: File): List<File> {
        val tts = init()
        try {
            val locale = Locale.forLanguageTag(language.bcp47)
            val result = withContext(Dispatchers.Main) { tts.setLanguage(locale) }
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw MissingConfigurationException(
                    "The system text-to-speech engine has no ${language.displayName} voice. Install it in Android Settings > " +
                        "Text-to-speech, or choose a cloud TTS provider.",
                )
            }
            if (voice.isNotBlank()) tts.voices?.firstOrNull { it.name == voice }?.let { tts.voice = it }
            val maxLen = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3_500)
            return segments.mapIndexed { index, text ->
                val out = File(outDir, "seg_$index.wav")
                if (text.isBlank()) {
                    mixer.silence(800, out)
                    return@mapIndexed out
                }
                val parts = chunkText(text, maxLen).mapIndexed { p, chunk ->
                    val raw = File(outDir, "seg_${index}_$p.raw.wav")
                    speakToFile(tts, chunk, raw)
                    File(outDir, "seg_${index}_$p.wav").also { converter.toCanonical(raw, it); raw.delete() }
                }
                if (parts.size == 1) parts[0].renameTo(out) else { mixer.concat(parts, out, 120); parts.forEach { it.delete() } }
                out
            }
        } finally {
            tts.shutdown()
        }
    }

    private suspend fun init(): TextToSpeech = withContext(Dispatchers.Main) {
        withTimeout(15_000) {
            suspendCancellableCoroutine { cont ->
                var engine: TextToSpeech? = null
                engine = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) cont.resume(engine!!)
                    else cont.resumeWithException(ProviderException("Android TTS", "Engine failed to start", null, retryable = true))
                }
                cont.invokeOnCancellation { engine.shutdown() }
            }
        }
    }

    private suspend fun speakToFile(tts: TextToSpeech, text: String, out: File) = withTimeout(120_000) {
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resumeWithException(ProviderException("Android TTS", "Synthesis failed", null, retryable = true))
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) cont.resumeWithException(ProviderException("Android TTS", "Synthesis failed ($errorCode)", null, retryable = true))
                }
            })
            val status = tts.synthesizeToFile(text, null, out, id)
            if (status != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resumeWithException(ProviderException("Android TTS", "synthesizeToFile returned $status", null, retryable = true))
            }
        }
    }
}

/** OpenAI text-to-speech (/v1/audio/speech), WAV output. */
@Singleton
class OpenAiTtsEngine @Inject constructor(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
    private val converter: AudioConverter,
    private val mixer: AudioMixer,
) : TtsEngine {
    override val type = TtsProviderType.OPENAI

    override suspend fun isAvailable() = secrets.isSet(SecretKey.OPENAI_API_KEY)

    override suspend fun synthesize(segments: List<String>, language: ContentLanguage, voice: String, outDir: File): List<File> = withContext(Dispatchers.IO) {
        val key = secrets.get(SecretKey.OPENAI_API_KEY) ?: throw MissingConfigurationException("Add an OpenAI API key for OpenAI TTS")
        segments.mapIndexed { index, text ->
            val out = File(outDir, "seg_$index.wav")
            if (text.isBlank()) { mixer.silence(800, out); return@mapIndexed out }
            val parts = chunkText(text, 3_800).mapIndexed { p, chunk ->
                val body = buildJsonObject {
                    put("model", "gpt-4o-mini-tts")
                    put("voice", voice.ifBlank { "alloy" })
                    put("input", chunk)
                    put("response_format", "wav")
                    put("instructions", "Speak naturally in ${language.displayName} for a YouTube video: clear, warm and energetic.")
                }
                val request = Request.Builder().url("https://api.openai.com/v1/audio/speech")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val raw = File(outDir, "seg_${index}_$p.raw")
                download("OpenAI TTS", request, raw)
                File(outDir, "seg_${index}_$p.wav").also { converter.toCanonical(raw, it); raw.delete() }
            }
            if (parts.size == 1) parts[0].renameTo(out) else { mixer.concat(parts, out, 120); parts.forEach { it.delete() } }
            out
        }
    }

    private fun download(provider: String, request: Request, out: File) = downloadTo(http, provider, request, out)
}

/** ElevenLabs multilingual TTS, requested as raw 44.1 kHz PCM and wrapped into WAV. */
@Singleton
class ElevenLabsTtsEngine @Inject constructor(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
    private val converter: AudioConverter,
    private val mixer: AudioMixer,
) : TtsEngine {
    override val type = TtsProviderType.ELEVENLABS

    override suspend fun isAvailable() = secrets.isSet(SecretKey.ELEVENLABS_API_KEY) && secrets.isSet(SecretKey.ELEVENLABS_VOICE_ID)

    override suspend fun synthesize(segments: List<String>, language: ContentLanguage, voice: String, outDir: File): List<File> = withContext(Dispatchers.IO) {
        val key = secrets.get(SecretKey.ELEVENLABS_API_KEY) ?: throw MissingConfigurationException("Add an ElevenLabs API key")
        val voiceId = voice.ifBlank { secrets.get(SecretKey.ELEVENLABS_VOICE_ID).orEmpty() }
            .ifBlank { throw MissingConfigurationException("Add an ElevenLabs voice ID") }
        segments.mapIndexed { index, text ->
            val out = File(outDir, "seg_$index.wav")
            if (text.isBlank()) { mixer.silence(800, out); return@mapIndexed out }
            val parts = chunkText(text, 2_400).mapIndexed { p, chunk ->
                val body = buildJsonObject {
                    put("text", chunk)
                    put("model_id", "eleven_multilingual_v2")
                }
                val request = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=pcm_44100")
                    .header("xi-api-key", key)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val raw = File(outDir, "seg_${index}_$p.pcm")
                downloadTo(http, "ElevenLabs", request, raw)
                val wav = File(outDir, "seg_${index}_$p.44k.wav")
                Wav.fromRawPcm16(raw, 44_100, 1, wav)
                raw.delete()
                File(outDir, "seg_${index}_$p.wav").also { converter.toCanonical(wav, it); wav.delete() }
            }
            if (parts.size == 1) parts[0].renameTo(out) else { mixer.concat(parts, out, 120); parts.forEach { it.delete() } }
            out
        }
    }
}

internal fun downloadTo(http: OkHttpClient, provider: String, request: Request, out: File) {
    val response = try {
        http.newCall(request).execute()
    } catch (e: IOException) {
        throw ProviderException(provider, "Network error: ${e.message}", null, retryable = true, cause = e)
    }
    response.use {
        if (!it.isSuccessful) {
            val code = it.code
            throw ProviderException(provider, "HTTP $code: ${it.body?.string().orEmpty().take(400)}", code, retryable = code == 429 || code >= 500)
        }
        out.outputStream().use { os -> it.body!!.byteStream().copyTo(os) }
    }
}

/** Picks the configured engine, falling back to the offline system engine when unavailable. */
@Singleton
class TtsRouter @Inject constructor(
    private val android: AndroidTtsEngine,
    private val openAi: OpenAiTtsEngine,
    private val elevenLabs: ElevenLabsTtsEngine,
) {
    suspend fun engine(preferred: TtsProviderType): TtsEngine {
        val chosen = when (preferred) {
            TtsProviderType.ANDROID -> android
            TtsProviderType.OPENAI -> openAi
            TtsProviderType.ELEVENLABS -> elevenLabs
        }
        return if (chosen.isAvailable()) chosen else android
    }
}
