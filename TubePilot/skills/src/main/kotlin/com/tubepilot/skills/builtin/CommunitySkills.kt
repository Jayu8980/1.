package com.tubepilot.skills.builtin

import com.tubepilot.core.model.CommentCategory
import com.tubepilot.skills.SkillContext
import com.tubepilot.skills.SkillExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Deterministic first pass so obvious spam never costs tokens. */
object CommentHeuristics {
    private val URL = Regex("(https?://|www\\.|\\b[a-z0-9-]+\\.(com|in|xyz|link|ly|io|me)\\b)", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("(\\+?\\d[\\d\\s-]{9,}\\d)")
    private val SPAM_PHRASES = listOf(
        "check my channel", "sub4sub", "subscribe to me", "telegram", "whatsapp me", "dm me", "crypto", "bitcoin",
        "investment plan", "earn money from home", "free giveaway", "click the link", "promote your channel",
    )
    private val SENSITIVE = listOf("suicide", "kill myself", "self harm", "abuse", "depressed", "lawsuit", "lawyer", "copyright strike", "doxx")

    data class Verdict(val category: CommentCategory, val reason: String, val sensitive: Boolean)

    fun prefilter(text: String): Verdict? {
        val lower = text.lowercase(Locale.ROOT)
        SENSITIVE.firstOrNull { lower.contains(it) }?.let { return Verdict(CommentCategory.SENSITIVE, "mentions '$it'", sensitive = true) }
        if (URL.containsMatchIn(text) && SPAM_PHRASES.any { lower.contains(it) }) return Verdict(CommentCategory.SPAM, "link with promotional phrasing", false)
        if (PHONE.containsMatchIn(text) && (lower.contains("whatsapp") || lower.contains("call"))) return Verdict(CommentCategory.SPAM, "phone number solicitation", false)
        if (SPAM_PHRASES.count { lower.contains(it) } >= 2) return Verdict(CommentCategory.SPAM, "multiple spam phrases", false)
        return null
    }
}

/** comment_triage: classifies comments into question / praise / feedback / spam / toxic / sensitive. */
@Singleton
class CommentTriageExecutor @Inject constructor() : SkillExecutor {
    override val id = "comment_triage"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val comments = input.objects("comments").filter { it.str("id").isNotBlank() }
        val decided = LinkedHashMap<String, JsonObject>()
        val remaining = ArrayList<JsonObject>()
        comments.forEach { c ->
            val v = CommentHeuristics.prefilter(c.str("text"))
            if (v != null) {
                decided[c.str("id")] = jsonOf("id" to c.str("id"), "category" to v.category.name, "sensitive" to v.sensitive, "reason" to v.reason, "source" to "rules")
            } else {
                remaining += c
            }
        }
        remaining.chunked(40).forEach { batch ->
            val result = context.runtime.runPrompt(context, jsonOf("comments" to JsonArray(batch)))
            result.objects("results").forEach { r ->
                val id = r.str("id")
                if (batch.none { it.str("id") == id }) return@forEach
                val category = runCatching { CommentCategory.valueOf(r.str("category").uppercase(Locale.ROOT)) }.getOrDefault(CommentCategory.OTHER)
                decided[id] = jsonOf(
                    "id" to id, "category" to category.name,
                    "sensitive" to (r.bool("sensitive", false) || category == CommentCategory.SENSITIVE),
                    "reason" to r.str("reason"), "source" to "model",
                )
            }
        }
        // Anything the model skipped stays unclassified rather than guessed.
        comments.filter { it.str("id") !in decided }.forEach {
            decided[it.str("id")] = jsonOf("id" to it.str("id"), "category" to CommentCategory.OTHER.name, "sensitive" to false, "reason" to "not classified", "source" to "default")
        }
        return jsonOf("results" to JsonArray(decided.values.toList()))
    }
}

/**
 * reply_drafter: replies in the channel's voice. Drafts are sanitised (no links, no contact
 * details, length cap) and replies needing expertise or judgement are marked for a human.
 */
@Singleton
class ReplyDrafterExecutor @Inject constructor() : SkillExecutor {
    override val id = "reply_drafter"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val comments = input.objects("comments")
        val drafts = comments.chunked(25).flatMap { batch ->
            context.runtime.runPrompt(context, jsonOf("comments" to JsonArray(batch))).objects("replies")
        }
        val ids = comments.map { it.str("id") }.toSet()
        val cleaned = drafts.filter { it.str("id") in ids && it.str("reply").isNotBlank() }.map { d ->
            val (reply, forced) = sanitize(d.str("reply"))
            jsonOf("id" to d.str("id"), "reply" to reply, "needsHuman" to (d.bool("needsHuman", false) || forced), "reason" to d.str("reason"))
        }
        return jsonOf("replies" to JsonArray(cleaned))
    }

    companion object {
        private val LINK = Regex("(https?://\\S+|www\\.\\S+)", RegexOption.IGNORE_CASE)
        private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")

        /** Returns the cleaned reply and whether a human should check it. */
        fun sanitize(reply: String): Pair<String, Boolean> {
            var forced = false
            var text = reply.trim()
            if (LINK.containsMatchIn(text) || EMAIL.containsMatchIn(text)) {
                text = EMAIL.replace(LINK.replace(text, ""), "").trim()
                forced = true
            }
            if (text.length > 400) {
                text = text.take(397).trimEnd() + "..."
                forced = true
            }
            return text to forced
        }
    }
}
