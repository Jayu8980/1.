package com.tubepilot.skills.builtin

import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.ScriptScene
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoScript
import com.tubepilot.llm.Effort
import com.tubepilot.skills.SkillContext
import com.tubepilot.skills.SkillExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** hook_generator: several opening hooks in different patterns, informed by past lessons. */
@Singleton
class HookGeneratorExecutor @Inject constructor() : SkillExecutor {
    override val id = "hook_generator"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val result = context.runtime.runPrompt(context, input)
        val maxWords = if (input.str("format") == VideoFormat.SHORT.name) 18 else 35
        val hooks = result.objects("hooks").filter { it.str("text").isNotBlank() }
            .map { h -> JsonObject(h + ("wordCount" to kotlinx.serialization.json.JsonPrimitive(h.str("text").split(Regex("\\s+")).size))) }
            .sortedBy { if (it.int("wordCount", 0) <= maxWords) 0 else 1 }
        return jsonOf("hooks" to JsonArray(hooks))
    }
}

/** Words spoken per second, used to estimate scene durations before TTS exists. */
internal fun wordsPerSecond(language: ContentLanguage) = when (language) {
    ContentLanguage.ENGLISH -> 2.6
    ContentLanguage.HINGLISH -> 2.5
    ContentLanguage.HINDI -> 2.3
}

/**
 * script_writer: writes a scene-by-scene script and normalises it: scene indices, duration
 * estimates from word counts, the Shorts length cap, and a CTA scene at the end.
 */
@Singleton
class ScriptWriterExecutor @Inject constructor() : SkillExecutor {
    override val id = "script_writer"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val format = runCatching { VideoFormat.valueOf(input.str("format", "SHORT")) }.getOrDefault(VideoFormat.SHORT)
        val language = runCatching { ContentLanguage.valueOf(input.str("language", "ENGLISH")) }.getOrDefault(ContentLanguage.ENGLISH)
        val target = input.int("targetDurationSec", if (format == VideoFormat.SHORT) 45 else 480)
        val result = context.runtime.runPrompt(
            context, input,
            mapOf("targetDurationSec" to target.toString(), "formatRules" to formatRules(format), "languageRules" to languageRules(language)),
            effort = Effort.HIGH,
            maxTokens = if (format == VideoFormat.LONG) 24_000 else 8_000,
        )
        return normalize(result, format, language, input.str("title")).toJsonObject(VideoScript.serializer())
    }

    companion object {
        fun normalize(raw: JsonObject, format: VideoFormat, language: ContentLanguage, fallbackTitle: String): VideoScript {
            val wps = wordsPerSecond(language)
            var scenes = raw.objects("scenes").mapNotNull { s ->
                val narration = s.str("narration").trim()
                if (narration.isEmpty()) return@mapNotNull null
                ScriptScene(
                    index = 0,
                    narration = narration,
                    visualPrompt = s.str("visualPrompt").ifBlank { narration.take(140) },
                    onScreenText = s.str("onScreenText").take(48),
                    brollQuery = s.str("brollQuery"),
                    durationHintSec = (narration.split(Regex("\\s+")).size / wps * 10).roundToInt() / 10.0,
                )
            }
            val hook = raw.str("hook").ifBlank { scenes.firstOrNull()?.narration.orEmpty() }
            // The hook must be spoken first.
            if (hook.isNotBlank() && scenes.firstOrNull()?.narration?.startsWith(hook.take(20)) != true) {
                scenes = listOf(ScriptScene(0, hook, raw.str("hookVisual").ifBlank { "Eye-catching opening shot: $hook".take(160) }, onScreenText = raw.str("hookText").take(48), durationHintSec = hook.split(" ").size / wps)) + scenes
            }
            val cta = raw.str("cta")
            if (cta.isNotBlank() && scenes.none { it.narration.contains(cta.take(25)) }) {
                scenes = scenes + ScriptScene(0, cta, "Friendly closing frame inviting viewers to subscribe", durationHintSec = cta.split(" ").size / wps)
            }
            if (format == VideoFormat.SHORT) {
                // Shorts must stay under 60 s including pauses: drop middle scenes if needed.
                while (scenes.size > 3 && scenes.sumOf { it.durationHintSec } + scenes.size * 0.25 > 57) {
                    scenes = scenes.toMutableList().also { it.removeAt(it.size - 2) }
                }
            }
            scenes = scenes.mapIndexed { i, s -> s.copy(index = i) }
            return VideoScript(
                title = raw.str("title").ifBlank { fallbackTitle },
                language = language,
                format = format,
                hook = hook,
                scenes = scenes,
                cta = cta,
                estimatedDurationSec = (scenes.sumOf { it.durationHintSec } + scenes.size * 0.25).roundToInt(),
                realisticSyntheticContent = raw.bool("realisticSyntheticContent", false),
            )
        }

        fun formatRules(format: VideoFormat) = when (format) {
            VideoFormat.SHORT -> "Vertical YouTube Short, 30-55 seconds spoken. 4-8 scenes of 1-3 sentences. The first 2 seconds must hook. No 'in this video' preamble."
            VideoFormat.LONG -> "Horizontal long-form video. 8-25 scenes. Open with a hook in the first 15 seconds, deliver value in clear sections, recap, then the CTA."
        }

        fun languageRules(language: ContentLanguage) = when (language) {
            ContentLanguage.ENGLISH -> "Write narration in simple, natural English."
            ContentLanguage.HINDI -> "Write narration in Hindi using Devanagari script, conversational (bolchaal ki bhasha), not formal or literary."
            ContentLanguage.HINGLISH -> "Write narration in Hinglish: Hindi words written in Latin script mixed naturally with English, as young Indian creators speak."
        }
    }
}

/**
 * title_ab_test: generates title variants and ranks them by a blend of the model's CTR estimate
 * and deterministic heuristics derived from YouTube best practice and the channel's own lessons.
 */
@Singleton
class TitleAbTestExecutor @Inject constructor(
    private val memory: MemoryRepository,
) : SkillExecutor {
    override val id = "title_ab_test"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val result = context.runtime.runPrompt(context, input)
        val lessons = memory.activeLessons(30).map { it.text.lowercase(Locale.ROOT) }
        val keyword = input.strings("keywords").firstOrNull().orEmpty()
        val variants = result.objects("variants").map { it.str("title").trim() to it }.filter { it.first.isNotEmpty() }
            .distinctBy { it.first.lowercase(Locale.ROOT) }
            .map { (title, obj) ->
                val (heuristic, reasons) = TitleHeuristics.score(title, keyword, lessons)
                val model = obj.double("predictedCtrScore", 5.0).coerceIn(0.0, 10.0)
                jsonOf(
                    "title" to title.take(100),
                    "score" to "%.2f".format(Locale.ROOT, 0.6 * model + 0.4 * heuristic).toDouble(),
                    "modelScore" to model,
                    "heuristicScore" to heuristic,
                    "rationale" to obj.str("rationale"),
                    "reasons" to reasons,
                )
            }
            .sortedByDescending { it.double("score", 0.0) }
            .take(input.int("count", 5).coerceIn(2, 10))
        return jsonOf("variants" to JsonArray(variants))
    }
}

object TitleHeuristics {
    private val CLICKBAIT = listOf("you won't believe", "shocking", "gone wrong", "100% guaranteed", "free iphone", "must watch")

    fun score(title: String, keyword: String, lessons: List<String>): Pair<Double, List<String>> {
        var score = 5.0
        val reasons = ArrayList<String>()
        val length = title.length
        when {
            length in 35..70 -> { score += 1.5; reasons += "good length ($length chars)" }
            length > 100 -> { score -= 4.0; reasons += "over YouTube's 100-character limit" }
            length > 80 -> { score -= 1.0; reasons += "may be truncated on mobile" }
            length < 20 -> { score -= 1.0; reasons += "very short" }
        }
        if (keyword.isNotBlank()) {
            val pos = title.lowercase(Locale.ROOT).indexOf(keyword.lowercase(Locale.ROOT))
            if (pos in 0..40) { score += 1.5; reasons += "keyword early" } else if (pos < 0) { score -= 0.5; reasons += "missing main keyword" }
        }
        if (title.any { it.isDigit() } && lessons.any { "number" in it }) { score += 1.0; reasons += "uses a number (worked before)" }
        val shouting = title.split(" ").count { it.length > 3 && it == it.uppercase(Locale.ROOT) && it.any(Char::isLetter) }
        if (shouting > 2) { score -= 1.5; reasons += "too many all-caps words" }
        if (CLICKBAIT.any { title.lowercase(Locale.ROOT).contains(it) }) { score -= 2.5; reasons += "clickbait phrasing risks viewer trust" }
        if (title.contains('<') || title.contains('>')) { score -= 5.0; reasons += "angle brackets are rejected by YouTube" }
        return score.coerceIn(0.0, 10.0) to reasons
    }
}
