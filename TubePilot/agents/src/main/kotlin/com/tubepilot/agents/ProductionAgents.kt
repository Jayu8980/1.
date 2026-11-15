package com.tubepilot.agents

import android.graphics.Color
import com.tubepilot.agents.core.Agent
import com.tubepilot.agents.core.AgentRuntime
import com.tubepilot.agents.core.RetryPolicy
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.EditResult
import com.tubepilot.core.model.RenderOutput
import com.tubepilot.core.model.SceneVisual
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.model.VisualKind
import com.tubepilot.core.model.VisualSet
import com.tubepilot.core.model.VoiceTrack
import com.tubepilot.core.util.TubeJson
import com.tubepilot.media.ProjectStorage
import com.tubepilot.media.image.ImageRouter
import com.tubepilot.media.library.AssetLibrary
import com.tubepilot.media.probe.MediaProbe
import com.tubepilot.media.subtitle.SubtitleBuilder
import com.tubepilot.media.tts.VoiceoverBuilder
import com.tubepilot.media.tts.VoiceoverOptions
import com.tubepilot.media.video.RenderSpec
import com.tubepilot.media.video.Timeline
import com.tubepilot.media.video.VideoRenderer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Voiceover (system or cloud TTS), background music from the user's library, loudness normalisation. */
@Singleton
class VoiceAudioAgent @Inject constructor(
    runtime: AgentRuntime,
    private val builder: VoiceoverBuilder,
    private val library: AssetLibrary,
    private val storage: ProjectStorage,
) : Agent(AgentRole.VOICE_AUDIO, runtime) {
    override val rolePrompt = "You are the Voice & Audio engineer: clear narration, tasteful music, broadcast-safe loudness."
    override val skillIds = emptyList<String>()
    override val retryPolicy = RetryPolicy(maxAttempts = 4, initialBackoffMs = 60_000)

    suspend fun produce(project: VideoProject): VoiceTrack = task("Voiceover for '${project.title}'", project.id) {
        val script = requireNotNull(project.artifacts.script) { "script missing" }
        val s = runtime.settings.current()
        val moodHints = (project.artifacts.idea?.keywords.orEmpty() + listOf(project.artifacts.idea?.pillar.orEmpty())).filter { it.isNotBlank() }
        val music = library.pickMusic(s.musicFolderUri, project.id, moodHints)
        val musicFile = music?.let { item ->
            val ext = item.name.substringAfterLast('.', "audio")
            library.copyToFile(item, File(storage.sub(project.id, "audio"), "music_source.$ext"))
        }
        val track = builder.build(
            project.id, script,
            VoiceoverOptions(s.ttsProvider, s.ttsVoice, musicFile, music?.name, s.musicVolumeDb, s.targetLufs),
        )
        runtime.audit.record(
            role.displayName, "voice.done",
            "Engine ${track.engine}, ${track.durationMs / 1000}s, %.1f LUFS, peak %.1f dBFS, music: ${track.musicTrack ?: "none"}".format(track.integratedLufs, track.peakDbfs),
            project.id,
        )
        track
    }
}

/** Visuals per scene (own B-roll first, generated images otherwise), the edit, subtitles and Shorts cut-downs. */
@Singleton
class VideoEditorAgent @Inject constructor(
    runtime: AgentRuntime,
    private val images: ImageRouter,
    private val library: AssetLibrary,
    private val storage: ProjectStorage,
    private val renderer: VideoRenderer,
    private val probe: MediaProbe,
    private val memory: MemoryRepository,
) : Agent(AgentRole.VIDEO_EDITOR, runtime) {
    override val rolePrompt = "You are the Video Editor: clean pacing, readable captions and correct formats."
    override val skillIds = listOf("subtitle_generator", "shorts_cutter")
    override val retryPolicy = RetryPolicy(maxAttempts = 4, initialBackoffMs = 2 * 60_000)

    suspend fun prepareVisuals(project: VideoProject): VisualSet = task("Visuals for '${project.title}'", project.id) {
        val script = requireNotNull(project.artifacts.script) { "script missing" }
        val timings = project.artifacts.voice?.sceneTimings.orEmpty().associateBy { it.sceneIndex }
        val s = runtime.settings.current()
        val style = memory.styleGuide()
        val dir = storage.sub(project.id, "visuals")
        val usedClips = HashSet<String>()
        val visuals = script.scenes.map { scene ->
            val neededMs = timings[scene.index]?.durationMs ?: (scene.durationHintSec * 1000).toLong()
            // 1) The creator's own B-roll, when a clip matches and is long enough.
            val clip = library.findBroll(s.brollFolderUri, scene.brollQuery, usedClips)
            if (clip != null) {
                val file = library.copyToFile(clip, File(dir, "scene_${scene.index}.${clip.name.substringAfterLast('.', "mp4")}"))
                val length = probe.video(file)?.durationMs ?: 0
                if (length >= neededMs) {
                    usedClips += clip.uri.toString()
                    return@map SceneVisual(scene.index, file.absolutePath, VisualKind.VIDEO, "user_library:${clip.name}")
                }
                file.delete()
            }
            // 2) A generated, original image (reused when a retry finds it already on disk).
            val image = File(dir, "scene_${scene.index}.jpg")
            val source = if (image.isFile && image.length() > 0) {
                File(dir, "scene_${scene.index}.source").takeIf { it.isFile }?.readText() ?: "generated:cached"
            } else {
                val result = images.generate(s.imageProvider, s.imageModel, style.brandColors, scene.visualPrompt, project.format, image, seed = (project.id.hashCode() * 31 + scene.index).toLong(), project.id)
                ("generated:" + result.source).also { File(dir, "scene_${scene.index}.source").writeText(it) }
            }
            SceneVisual(scene.index, image.absolutePath, VisualKind.IMAGE, source)
        }
        VisualSet(project.format, visuals)
    }

    suspend fun edit(project: VideoProject, onProgress: (Int) -> Unit = {}): EditResult = task("Edit '${project.title}'", project.id) {
        val a = project.artifacts
        val script = requireNotNull(a.script) { "script missing" }
        val voice = requireNotNull(a.voice) { "voiceover missing" }
        val visuals = requireNotNull(a.visuals) { "visuals missing" }
        val profile = memory.profile()
        val accent = memory.styleGuide().brandColors.firstNotNullOfOrNull { runCatching { Color.parseColor(it) }.getOrNull() } ?: Color.YELLOW
        val renders = storage.sub(project.id, "renders")
        val output = File(renders, "primary_${project.format.name.lowercase()}.mp4")

        if (!probe.isCompleteRender(output.absolutePath, voice.durationMs)) {
            renderer.render(
                RenderSpec(
                    format = project.format,
                    scenes = Timeline.build(script, voice, visuals),
                    audioPath = voice.mixPath,
                    cues = SubtitleBuilder.cues(script, voice.sceneTimings, project.format),
                    output = output,
                    accentColor = accent,
                ),
                onProgress,
            )
        } else {
            runtime.audit.record(role.displayName, "edit.resume", "Existing render is complete; skipping re-encode", project.id)
        }

        // Subtitle file for the captions upload (plus a translation if the channel publishes in two languages).
        val second = profile.languages.firstOrNull { it != script.language }?.youtubeCode
        val subtitleInput = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("projectId", JsonPrimitive(project.id))
            if (second != null) put("translateTo", JsonPrimitive(second))
        }
        val subtitles = runtime.skillRaw(role, "subtitle_generator", JsonObject(subtitleInput), project.id)
        val srtPath = (subtitles["srtPath"] as? JsonPrimitive)?.content.orEmpty()

        val p = requireNotNull(probe.video(output)) { "render is unreadable" }
        val primary = RenderOutput(project.format, output.absolutePath, srtPath, p.durationMs, p.displayWidth, p.displayHeight, p.sizeBytes)

        val shorts = if (project.format == com.tubepilot.core.model.VideoFormat.LONG && runtime.hasSkill(role, "shorts_cutter")) {
            runCatching {
                val out = runtime.skillRaw(role, "shorts_cutter", JsonObject(mapOf("projectId" to JsonPrimitive(project.id), "maxShorts" to JsonPrimitive(2))), project.id)
                TubeJson.decodeFromJsonElement(ListSerializer(RenderOutput.serializer()), out["shorts"]!!.jsonArray)
            }.onFailure {
                if (it is com.tubepilot.core.error.AgentsPausedException || it is com.tubepilot.core.error.BudgetExceededException) throw it
                runtime.audit.record(role.displayName, "shorts.skipped", "Shorts cut-down failed: ${it.message}", project.id, AuditLevel.WARNING)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        EditResult(primary, shorts)
    }
}
