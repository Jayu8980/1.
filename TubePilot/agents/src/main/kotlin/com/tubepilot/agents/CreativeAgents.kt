package com.tubepilot.agents

import com.tubepilot.agents.contracts.ScriptRequest
import com.tubepilot.agents.contracts.SeoDraft
import com.tubepilot.agents.core.Agent
import com.tubepilot.agents.core.AgentRuntime
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.Chapter
import com.tubepilot.core.model.LessonCategory
import com.tubepilot.core.model.ScriptScene
import com.tubepilot.core.model.SeoPackage
import com.tubepilot.core.model.ThumbnailSet
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.model.VideoScript
import com.tubepilot.core.model.VoiceTrack
import com.tubepilot.core.util.TextUtil
import com.tubepilot.core.util.TubeJson
import com.tubepilot.llm.Effort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Writes hooks and full scripts in the channel's voice and language (Hindi, English, Hinglish). */
@Singleton
class ScriptwriterAgent @Inject constructor(runtime: AgentRuntime) : Agent(AgentRole.SCRIPTWRITER, runtime) {

    override val rolePrompt = """
        You are the Scriptwriter of a YouTube channel team. You write in the channel's own voice and
        language, open with a hook that earns attention honestly, deliver real value, and close with a
        short CTA. Process: call hook_generator, choose the strongest hook for this audience (consider
        the lessons), then call script_writer with that hook and the research brief.
    """.trimIndent()
    override val memoryFocus = setOf(LessonCategory.HOOK, LessonCategory.RETENTION)
    override val skillIds = listOf("hook_generator", "script_writer", "repurpose_content")

    suspend fun write(request: ScriptRequest, projectId: String): VideoScript = task("Write script '${request.title}'", projectId) {
        val notes = request.reviewerNotes?.let { "\nThe creator reviewed a previous draft and asked for these changes: $it" }.orEmpty()
        val script = runCatching {
            runtime.reason(
                role = role,
                rolePrompt = rolePrompt,
                focus = memoryFocus,
                task = "Write the ${request.format.label} script for \"${request.title}\" in ${request.language.displayName}.$notes " +
                    "When script_writer succeeds, reply with {\"done\":true}.",
                input = TubeJson.encodeToJsonElement(ScriptRequest.serializer(), request).jsonObject,
                output = VideoScript.serializer(),
                outputExample = sampleScript(request),
                projectId = projectId,
                deliverableSkill = "script_writer",
                maxSteps = 4,
                effort = Effort.HIGH,
            )
        }.getOrElse { error ->
            // If the model never produced a usable script via tools, call the skill directly once.
            if (error is com.tubepilot.core.error.AgentsPausedException || error is com.tubepilot.core.error.BudgetExceededException) throw error
            runtime.skill(
                role, "script_writer",
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive(request.title),
                        "brief" to runCatching { TubeJson.parseToJsonElement(request.briefJson) }.getOrDefault(JsonObject(emptyMap())),
                        "format" to JsonPrimitive(request.format.name),
                        "language" to JsonPrimitive(request.language.name),
                    ),
                ),
                projectId, VideoScript.serializer(),
            )
        }
        require(script.scenes.isNotEmpty()) { "Script has no scenes" }
        script.copy(format = request.format, language = request.language)
    }

    private fun sampleScript(r: ScriptRequest) = VideoScript(
        r.title, r.language, r.format, "Hook line", listOf(ScriptScene(0, "Narration", "Visual description", "TEXT")), "CTA", 45,
    )
}

/** Deterministic YouTube metadata limits, applied after the model writes metadata. */
object SeoLimits {
    const val TITLE_MAX = 100
    const val DESCRIPTION_MAX_BYTES = 5000
    const val TAGS_MAX_CHARS = 500
    const val HASHTAGS_MAX = 5

    fun cleanTitle(title: String): String = title.replace("<", "").replace(">", "").replace(Regex("\\s+"), " ").trim().take(TITLE_MAX)

    /** YouTube counts tag length including separators, and tags with spaces as if quoted. */
    fun tagsLength(tags: List<String>): Int = tags.sumOf { it.length + (if (it.contains(' ')) 2 else 0) } + (tags.size - 1).coerceAtLeast(0)

    fun cleanTags(tags: List<String>): List<String> {
        val result = ArrayList<String>()
        for (raw in tags) {
            val tag = raw.replace("<", "").replace(">", "").replace(",", " ").trim().removePrefix("#").take(100)
            if (tag.isEmpty() || result.any { it.equals(tag, ignoreCase = true) }) continue
            if (tagsLength(result + tag) > TAGS_MAX_CHARS) break
            result += tag
        }
        return result
    }

    fun cleanHashtags(hashtags: List<String>): List<String> = hashtags.map { "#" + it.trim().removePrefix("#").replace(Regex("[^\\p{L}\\p{N}_]"), "") }
        .filter { it.length > 1 }.distinctBy { it.lowercase(Locale.ROOT) }.take(HASHTAGS_MAX)

    /** Chapters must start at 0:00, be ascending, at least 10 s apart, and there must be at least 3. */
    fun validChapters(chapters: List<Chapter>, durationSec: Int): List<Chapter> {
        val sorted = chapters.filter { it.startSec in 0 until durationSec && it.title.isNotBlank() }.sortedBy { it.startSec }.toMutableList()
        if (sorted.isEmpty()) return emptyList()
        if (sorted.first().startSec != 0) sorted[0] = sorted[0].copy(startSec = 0)
        val spaced = ArrayList<Chapter>()
        for (c in sorted) if (spaced.isEmpty() || c.startSec - spaced.last().startSec >= 10) spaced += c.copy(title = c.title.take(80))
        return if (spaced.size >= 3) spaced else emptyList()
    }

    fun apply(seo: SeoPackage, durationSec: Int, format: VideoFormat): SeoPackage {
        val titles = seo.titleVariants.map(::cleanTitle).filter { it.isNotEmpty() }.distinct()
        val hashtags = cleanHashtags(seo.hashtags).let { if (format == VideoFormat.SHORT && it.none { h -> h.equals("#shorts", true) }) (listOf("#Shorts") + it).take(HASHTAGS_MAX) else it }
        val chapters = if (format == VideoFormat.LONG) validChapters(seo.chapters, durationSec) else emptyList()
        var description = seo.description.replace("<", "").replace(">", "")
        val draft = seo.copy(titleVariants = titles, description = description, tags = cleanTags(seo.tags), hashtags = hashtags, chapters = chapters)
        // Trim the body (not the chapters/hashtags) until the full description fits.
        while (TextUtil.utf8Length(draft.copy(description = description).fullDescription()) > DESCRIPTION_MAX_BYTES && description.length > 50) {
            description = description.dropLast(maxOf(50, description.length / 20))
        }
        return draft.copy(description = description, pinnedComment = seo.pinnedComment.take(500), selectedTitleIndex = seo.selectedTitleIndex.coerceIn(0, (titles.size - 1).coerceAtLeast(0)))
    }

    /** Builds chapter candidates from scene timings: one per scene that has on-screen text. */
    fun chaptersFromScenes(script: VideoScript, voice: VoiceTrack): List<Chapter> =
        voice.sceneTimings.mapNotNull { t ->
            val scene = script.scenes.firstOrNull { it.index == t.sceneIndex } ?: return@mapNotNull null
            scene.onScreenText.takeIf { it.isNotBlank() }?.let { Chapter((t.startMs / 1000).toInt(), it.lowercase(Locale.ROOT).replaceFirstChar { c -> c.titlecase(Locale.ROOT) }) }
        }
}

/** Titles (A/B variants), description, tags, hashtags, chapters and the pinned comment. */
@Singleton
class SeoSpecialistAgent @Inject constructor(runtime: AgentRuntime) : Agent(AgentRole.SEO_SPECIALIST, runtime) {

    override val rolePrompt = """
        You are the SEO Specialist of a YouTube channel team. You make each video discoverable without
        misleading anyone: a title that matches the content, a description whose first two lines sell
        the video and contain the main keyword, relevant tags, 3-5 hashtags, chapters for long videos,
        and a pinned comment that starts a conversation. Use keyword_research for search phrasing and
        title_ab_test for ranked title variants; keep the top variants in titleVariants, best first.
    """.trimIndent()
    override val memoryFocus = setOf(LessonCategory.TITLE)
    override val skillIds = listOf("keyword_research", "title_ab_test")

    suspend fun optimize(project: VideoProject): SeoPackage = task("SEO for '${project.title}'", project.id) {
        val script = requireNotNull(project.artifacts.script) { "script missing" }
        val voice = project.artifacts.voice
        val durationSec = ((voice?.durationMs ?: (script.estimatedDurationSec * 1000L)) / 1000).toInt()
        val chapterHints = voice?.let { SeoLimits.chaptersFromScenes(script, it) }.orEmpty()
        val input = JsonObject(
            mapOf(
                "title" to JsonPrimitive(script.title),
                "language" to JsonPrimitive(script.language.name),
                "format" to JsonPrimitive(project.format.name),
                "durationSec" to JsonPrimitive(durationSec),
                "keywords" to JsonArray((project.artifacts.brief?.keywords ?: project.artifacts.idea?.keywords).orEmpty().map { JsonPrimitive(it) }),
                "summary" to JsonPrimitive(script.scenes.joinToString(" ") { it.narration }.take(2500)),
                "chapterCandidates" to TubeJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Chapter.serializer()), chapterHints),
            ),
        )
        val draft = runtime.reason(
            role = role,
            rolePrompt = rolePrompt,
            focus = memoryFocus,
            task = "Write the full metadata package. Call title_ab_test first (and keyword_research if keywords are thin). " +
                "Chapters only for long-form, using startSec values from chapterCandidates or scene timing. " +
                "Description: 2 strong opening lines, then a short summary, no external links unless they are the creator's own.",
            input = input,
            output = SeoDraft.serializer(),
            outputExample = SeoDraft(listOf("Best title", "Second title"), "Description", listOf("tag one", "tag two"), listOf("#tag"), listOf(Chapter(0, "Intro")), "Which tip will you try first?"),
            projectId = project.id,
        )
        val titles = draft.titleVariants.ifEmpty { listOf(script.title) }
        SeoLimits.apply(
            SeoPackage(
                titleVariants = titles.take(3),
                description = draft.description,
                tags = draft.tags,
                hashtags = draft.hashtags,
                chapters = draft.chapters.ifEmpty { chapterHints },
                pinnedComment = draft.pinnedComment,
                categoryId = draft.categoryId.ifBlank { "22" },
            ),
            durationSec, project.format,
        )
    }
}

/** Generates 2-3 thumbnail variants with the thumbnail_maker skill. */
@Singleton
class ThumbnailDesignerAgent @Inject constructor(runtime: AgentRuntime) : Agent(AgentRole.THUMBNAIL_DESIGNER, runtime) {
    override val rolePrompt = "You are the Thumbnail Designer. You create honest, high-contrast thumbnails that match the video and the brand."
    override val memoryFocus = setOf(LessonCategory.THUMBNAIL)
    override val skillIds = listOf("thumbnail_maker")

    suspend fun design(project: VideoProject): ThumbnailSet = task("Thumbnails for '${project.title}'", project.id) {
        val title = project.artifacts.seo?.title ?: project.artifacts.script?.title ?: project.title
        val set = runtime.skill(
            role, "thumbnail_maker",
            JsonObject(mapOf("projectId" to JsonPrimitive(project.id), "title" to JsonPrimitive(title), "count" to JsonPrimitive(3))),
            project.id, ThumbnailSet.serializer(),
        )
        require(set.variants.isNotEmpty()) { "No thumbnails produced" }
        set
    }
}
