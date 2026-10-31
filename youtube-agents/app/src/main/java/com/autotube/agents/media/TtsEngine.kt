package com.autotube.agents.media

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Wraps Android's on-device TextToSpeech so narration costs nothing and works offline. */
class TtsEngine private constructor(private val tts: TextToSpeech) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { fail(utteranceId, "TTS error") }
            override fun onError(utteranceId: String?, errorCode: Int) { fail(utteranceId, "TTS error $errorCode") }
        })
    }

    private fun fail(id: String?, why: String) {
        id?.let { pending.remove(it)?.completeExceptionally(IllegalStateException(why)) }
    }

    fun configure(languageTag: String, rate: Float = 1.05f) {
        val locale = Locale.forLanguageTag(languageTag)
        val res = tts.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) tts.language = Locale.US
        // Prefer the highest-quality offline voice for the language.
        try {
            tts.voices?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { it.quality }?.let { tts.voice = it }
        } catch (_: Exception) { }
        tts.setSpeechRate(rate)
    }

    suspend fun synthesize(text: String, out: File) {
        val id = "u" + System.nanoTime()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val r = tts.synthesizeToFile(text, Bundle(), out, id)
        if (r != TextToSpeech.SUCCESS) { pending.remove(id); throw IllegalStateException("TTS rejected text") }
        withTimeout(120_000) { done.await() }
    }

    fun shutdown() = tts.shutdown()

    companion object {
        suspend fun create(context: Context): TtsEngine = withContext(Dispatchers.Main) {
            val ready = CompletableDeferred<Int>()
            val tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status) }
            val status = withTimeout(30_000) { ready.await() }
            if (status != TextToSpeech.SUCCESS) {
                tts.shutdown()
                throw IllegalStateException("No text-to-speech engine available. Install Google Speech Services.")
            }
            TtsEngine(tts)
        }
    }
}
