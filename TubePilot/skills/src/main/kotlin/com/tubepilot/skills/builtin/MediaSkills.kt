package com.tubepilot.skills.builtin

import android.graphics.Color
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.error.SkillException
import com.tubepilot.core.model.RenderOutput
import com.tubepilot.core.model.ThumbnailSet
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoProject
import com.tubepilot.media.ProjectStorage
import com.tubepilot.media.image.ImageRouter
import com.tubepilot.media.probe.MediaProbe
import com.tubepilot.media.subtitle.Cue
import com.tubepilot.media.subtitle.SubtitleBuilder
import com.tubepilot.media.thumbnail.ThumbnailCompositor
import com.tubepilot.media.thumbnail.ThumbnailLayout
import com.tubepilot.media.video.RenderSpec
import com.tubepilot.media.video.Timeline
import com.tubepilot.media.video.VideoRenderer
import com.tubepilot.skills.SkillContext
import com.tubepilot.skills.SkillExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal suspend fun ProjectRepository.require(context: SkillContext, input: JsonObject): VideoProject {
    val id = input.str("projectId").ifBlank { context.projectId.orEmpty() }
    return get(id) ?: throw SkillException(context.skill.id, "project '$id' not found")
}

/** subtitle_generator: SRT from exact scene timings, optionally translated into a second language. */
@Singleton
class SubtitleGeneratorExecutor @Inject constructor(
    private val projects: ProjectRepository,
    private val storage: ProjectStorage,
) : SkillExecutor {
    override val id = "subtitle_generator"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val project = projects.require(context, input)
        val script = project.artifacts.script ?: throw SkillException(id, "script missing")
        val voice = project.artifacts.voice ?: throw SkillException(id, "voiceover missing")
        val cues = SubtitleBuilder.cues(script, voice.sceneTimings, project.format)
        val dir = storage.sub(project.id, "renders")
        val srt = SubtitleBuilder.writeSrt(cues, File(dir, "captions_${script.language.youtubeCode}.srt"))
        var translatedPath: String? = null
        val translateTo = input.str("translateTo")
        if (translateTo.isNotBlank() && translateTo != script.language.youtubeCode) {
            val result = context.runtime.runPrompt(
                context, input,
                mapOf("lines" to JsonArray(cues.map { JsonPrimitive(it.text) }).toString(), "targetLanguage" to translateTo),
            )
            val lines = result.strings("lines")
            if (lines.size == cues.size) {
                val translated = cues.zip(lines) { cue, text -> Cue(cue.startMs, cue.endMs, text) }
                translatedPath = SubtitleBuilder.writeSrt(translated, File(dir, "captions_$translateTo.srt")).absolutePath
            }
        }
        return jsonOf(
            "srtPath" to srt.absolutePath,
            "language" to script.language.youtubeCode,
            "cueCount" to cues.size,
            "translatedSrtPath" to translatedPath,
        )
    }
}

/**
 * shorts_cutter: picks the strongest self-contained segments of a long-form video and renders
 * each as a vertical Short from the original scene assets and the matching slice of audio, so
 * the Short is re-framed and re-captioned rather than a crop of the burned-in long video.
 */
@Singleton
class ShortsCutterExecutor @Inject constructor(
    private val projects: ProjectRepository,
    private val storage: ProjectStorage,
    private val renderer: VideoRenderer,
    private val probe: MediaProbe,
    private val memory: MemoryRepository,
) : SkillExecutor {
    override val id = "shorts_cutter"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val project = projects.require(context, input)
        val a = project.artifacts
        val script = a.script ?: throw SkillException(id, "script missing")
        val voice = a.voice ?: throw SkillException(id, "voiceover missing")
        val visuals = a.visuals ?: throw SkillException(id, "visuals missing")
        val clips = Timeline.build(script, voice, visuals)
        val maxShorts = input.int("maxShorts", 2).coerceIn(1, 5)

        val sceneTable = JsonArray(clips.mapIndexed { i, c ->
            jsonOf("scene" to i, "startSec" to c.startMs / 1000.0, "endSec" to (c.startMs + c.durationMs) / 1000.0, "narration" to script.scenes.getOrNull(i)?.narration.orEmpty())
        })
        val picked = runCatching { context.runtime.runPrompt(context, input, mapOf("scenes" to sceneTable.toString(), "maxShorts" to maxShorts.toString())) }
            .getOrNull()?.objects("segments").orEmpty()
            .map { it.int("startScene", -1) to it.int("endScene", -1) }
        val segments = ShortsSegmenter.validate(picked, clips.map { it.durationMs }, maxShorts)

        val brand = memory.styleGuide().brandColors.firstNotNullOfOrNull { runCatching { Color.parseColor(it) }.getOrNull() } ?: Color.YELLOW
        val cues = SubtitleBuilder.cues(script, voice.sceneTimings, VideoFormat.SHORT)
        val dir = storage.sub(project.id, "renders")
        val outputs = segments.mapIndexed { k, (from, to) ->
            val startMs = clips[from].startMs
            val endMs = clips[to].startMs + clips[to].durationMs
            val out = File(dir, "short_${k + 1}.mp4")
            val srt = File(dir, "short_${k + 1}.srt")
            val segmentCues = SubtitleBuilder.shift(cues, startMs, endMs)
            if (!probe.isCompleteRender(out.absolutePath, endMs - startMs)) {
                renderer.render(
                    RenderSpec(
                        format = VideoFormat.SHORT,
                        scenes = clips.subList(from, to + 1).map { it.copy(startMs = it.startMs - startMs) },
                        audioPath = voice.mixPath,
                        audioStartMs = startMs,
                        audioEndMs = endMs,
                        cues = segmentCues,
                        output = out,
                        accentColor = brand,
                    ),
                )
            }
            SubtitleBuilder.writeSrt(segmentCues, srt)
            RenderOutput(
                format = VideoFormat.SHORT,
                videoPath = out.absolutePath,
                srtPath = srt.absolutePath,
                durationMs = endMs - startMs,
                width = VideoFormat.SHORT.width,
                height = VideoFormat.SHORT.height,
                sizeBytes = out.length(),
                derivedFromSec = startMs / 1000.0,
                label = "Short ${k + 1} (from ${startMs / 1000}s)",
            )
        }
        return jsonOf("shorts" to outputs.toJsonArray(RenderOutput.serializer()))
    }
}

/** Pure segment validation, unit-tested. Segments are inclusive scene ranges of 15-58 seconds. */
object ShortsSegmenter {
    const val MIN_MS = 15_000L
    const val MAX_MS = 58_000L

    fun validate(candidates: List<Pair<Int, Int>>, sceneDurations: List<Long>, max: Int): List<Pair<Int, Int>> {
        fun length(a: Int, b: Int) = (a..b).sumOf { sceneDurations[it] }
        val valid = candidates.filter { (a, b) -> a in sceneDurations.indices && b in sceneDurations.indices && a <= b && length(a, b) in MIN_MS..MAX_MS }
        val chosen = ArrayList<Pair<Int, Int>>()
        for (c in valid) if (chosen.none { overlaps(it, c) } && chosen.size < max) chosen += c
        // Fill with greedy windows if the model's picks were unusable.
        var start = 0
        while (chosen.size < max && start < sceneDurations.size) {
            var end = start
            while (end + 1 < sceneDurations.size && length(start, end + 1) <= MAX_MS) end++
            val window = start to end
            if (length(start, end) >= MIN_MS && chosen.none { overlaps(it, window) }) chosen += window
            start = end + 1
        }
        return chosen.sortedBy { it.first }
    }

    private fun overlaps(a: Pair<Int, Int>, b: Pair<Int, Int>) = a.first <= b.second && b.first <= a.second
}

/** thumbnail_maker: headline and image ideas from the model, then 2-3 composited variants. */
@Singleton
class ThumbnailMakerExecutor @Inject constructor(
    private val projects: ProjectRepository,
    private val storage: ProjectStorage,
    private val images: ImageRouter,
    private val compositor: ThumbnailCompositor,
    private val memory: MemoryRepository,
    private val settings: SettingsRepository,
) : SkillExecutor {
    override val id = "thumbnail_maker"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val project = projects.require(context, input)
        val count = input.int("count", 3).coerceIn(2, 3)
        val style = memory.styleGuide()
        val s = settings.current()
        val ideas = context.runtime.runPrompt(context, input, mapOf("count" to count.toString(), "thumbnailStyle" to style.thumbnailStyle))
            .objects("variants").take(count)
        val colors = style.brandColors.mapNotNull { runCatching { Color.parseColor(it) }.getOrNull() }
        val dir = storage.sub(project.id, "thumbnails")
        val layouts = ThumbnailLayout.entries
        val variants = (0 until count).map { i ->
            val idea = ideas.getOrNull(i)
            val headline = idea?.str("headline")?.ifBlank { null } ?: project.title.split(" ").take(4).joinToString(" ")
            val prompt = idea?.str("imagePrompt")?.ifBlank { null } ?: "Bold, high-contrast scene about ${project.title}"
            val base = File(dir, "base_$i.jpg")
            if (!base.isFile) {
                images.generate(s.imageProvider, s.imageModel, style.brandColors, prompt, VideoFormat.LONG, base, seed = (project.id.hashCode() + i).toLong(), project.id)
            }
            compositor.compose(base, headline, layouts[i % layouts.size], colors, File(dir, "thumb_$i.jpg"))
        }
        return ThumbnailSet(variants = variants, selectedIndex = 0).toJsonObject(ThumbnailSet.serializer())
    }
}
