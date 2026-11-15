package com.tubepilot.media.tts

import com.tubepilot.core.model.SceneTiming
import com.tubepilot.core.model.TtsProviderType
import com.tubepilot.core.model.VideoScript
import com.tubepilot.core.model.VoiceTrack
import com.tubepilot.media.ProjectStorage
import com.tubepilot.media.audio.AudioConverter
import com.tubepilot.media.audio.AudioMixer
import com.tubepilot.media.audio.LoudnessMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceoverOptions(
    val provider: TtsProviderType,
    val voice: String,
    val musicFile: File?,
    val musicName: String?,
    val musicDb: Double,
    val targetLufs: Double,
)

/**
 * Synthesises each scene separately so scene timings (and therefore subtitle sync) are exact,
 * joins them with short pauses, mixes in ducked background music and normalises loudness.
 * Scene parts already on disk are reused, so a retried job does not pay for TTS twice.
 */
@Singleton
class VoiceoverBuilder @Inject constructor(
    private val router: TtsRouter,
    private val storage: ProjectStorage,
    private val mixer: AudioMixer,
    private val meter: LoudnessMeter,
    private val converter: AudioConverter,
) {
    suspend fun build(projectId: String, script: VideoScript, options: VoiceoverOptions): VoiceTrack = withContext(Dispatchers.IO) {
        val parts = storage.sub(projectId, "voice_parts")
        val audioDir = storage.sub(projectId, "audio")
        val engine = router.engine(options.provider)

        val sceneFiles = script.scenes.mapIndexed { i, _ -> File(parts, "seg_$i.wav") }
        val missing = script.scenes.indices.filter { !sceneFiles[it].isFile || sceneFiles[it].length() <= 44 }
        if (missing.isNotEmpty()) {
            val texts = missing.map { script.scenes[it].narration }
            val tmpDir = File(parts, "batch").apply { mkdirs() }
            val produced = engine.synthesize(texts, script.language, options.voice, tmpDir)
            produced.forEachIndexed { k, file -> file.copyTo(sceneFiles[missing[k]], overwrite = true); file.delete() }
            tmpDir.deleteRecursively()
        }

        val voice = File(audioDir, "voice.wav")
        val ranges = mixer.concat(sceneFiles, voice, gapMs = SCENE_GAP_MS)
        val timings = ranges.mapIndexed { i, (start, end) ->
            // Each scene keeps the pause that follows it, so visuals never cut mid-breath.
            val next = ranges.getOrNull(i + 1)?.first ?: end
            SceneTiming(script.scenes[i].index, start, if (i == ranges.lastIndex) end else next)
        }

        val musicCanonical = options.musicFile?.let { src ->
            File(audioDir, "music.wav").also { if (!it.isFile) converter.toCanonical(src, it) }
        }
        val premix = File(audioDir, "premix.wav")
        mixer.mix(voice, musicCanonical, premix, musicDb = options.musicDb)
        val finalMix = File(audioDir, "mix.wav")
        val result = meter.normalize(premix, finalMix, options.targetLufs)
        premix.delete()

        VoiceTrack(
            voicePath = voice.absolutePath,
            mixPath = finalMix.absolutePath,
            durationMs = result.durationMs,
            sceneTimings = timings,
            integratedLufs = result.integratedLufs,
            peakDbfs = result.peakDbfs,
            engine = engine.type.displayName,
            musicTrack = options.musicName,
        )
    }

    private companion object {
        const val SCENE_GAP_MS = 250L
    }
}
