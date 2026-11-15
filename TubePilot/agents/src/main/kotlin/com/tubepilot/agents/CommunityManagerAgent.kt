package com.tubepilot.agents

import com.tubepilot.agents.contracts.CommentRunSummary
import com.tubepilot.agents.core.Agent
import com.tubepilot.agents.core.AgentRuntime
import com.tubepilot.core.domain.CommentRepository
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.CommentCategory
import com.tubepilot.core.model.CommentItem
import com.tubepilot.core.model.CommentStatus
import com.tubepilot.core.model.LessonCategory
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.youtube.YouTubeClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triages new comments, drafts replies, hides spam when allowed and flags anything sensitive.
 * Replies stay drafts unless the user turned on auto-post; sensitive comments are never answered
 * automatically.
 */
@Singleton
class CommunityManagerAgent @Inject constructor(
    runtime: AgentRuntime,
    private val youtube: YouTubeClient,
    private val comments: CommentRepository,
    private val memory: MemoryRepository,
    private val time: TimeSource,
) : Agent(AgentRole.COMMUNITY_MANAGER, runtime) {
    override val rolePrompt = "You are the Community Manager: friendly, fast, safe replies and a clean comment section."
    override val memoryFocus = setOf(LessonCategory.COMMUNITY)
    override val skillIds = listOf("comment_triage", "reply_drafter")

    suspend fun processNewComments(maxPages: Int = 3): CommentRunSummary = task("Check new comments", null) {
        val channelId = memory.profile().channelId.ifBlank { youtube.myChannel().id }
        val fetched = ArrayList<CommentItem>()
        var page: String? = null
        repeat(maxPages) {
            val response = youtube.commentThreads(channelId, page)
            response.items.forEach { t ->
                val c = t.snippet.topLevelComment
                fetched += CommentItem(
                    commentId = c.id,
                    threadId = t.id,
                    videoId = t.snippet.videoId,
                    videoTitle = "",
                    author = c.snippet.authorDisplayName,
                    authorChannelId = c.snippet.authorChannelId?.value.orEmpty(),
                    text = c.snippet.textOriginal.ifBlank { c.snippet.textDisplay },
                    publishedAt = TimeUtil.parseRfc3339(c.snippet.publishedAt) ?: time.now(),
                    likeCount = c.snippet.likeCount,
                    category = null,
                    status = CommentStatus.NEW,
                    draftReply = null,
                    flagReason = null,
                    updatedAt = time.now(),
                )
            }
            page = response.nextPageToken ?: return@repeat
        }
        // Skip the channel's own comments and anything already known.
        val known = comments.knownIds(fetched.map { it.commentId })
        val fresh = fetched.filter { it.commentId !in known && it.authorChannelId != channelId }
        if (fresh.isNotEmpty()) {
            val titles = runCatching { youtube.videos(fresh.map { it.videoId }.distinct()).associate { it.id to it.title } }.getOrDefault(emptyMap())
            comments.upsert(fresh.map { it.copy(videoTitle = titles[it.videoId].orEmpty()) })
        }
        triageAndRespond(fetched.size, fresh.size)
    }

    private suspend fun triageAndRespond(fetched: Int, fresh: Int): CommentRunSummary {
        val settings = runtime.settings.current()
        val pending = comments.byStatus(CommentStatus.NEW, 120)
        if (pending.isEmpty()) return CommentRunSummary(fetched, fresh, 0, 0, 0, 0)

        val triage = runtime.skillRaw(
            role, "comment_triage",
            JsonObject(mapOf("comments" to JsonArray(pending.map { c ->
                JsonObject(mapOf("id" to JsonPrimitive(c.commentId), "text" to JsonPrimitive(c.text.take(1500)), "author" to JsonPrimitive(c.author)))
            }))),
            null,
        )
        val verdicts = triage["results"]?.jsonArray.orEmpty().map { it.jsonObject }.associateBy { it["id"]?.jsonPrimitive?.content.orEmpty() }

        var hidden = 0
        var flagged = 0
        val toReply = ArrayList<CommentItem>()
        val toHide = ArrayList<String>()
        for (c in pending) {
            val v = verdicts[c.commentId] ?: continue
            val category = runCatching { CommentCategory.valueOf(v["category"]!!.jsonPrimitive.content.uppercase(Locale.ROOT)) }.getOrDefault(CommentCategory.OTHER)
            val sensitive = v["sensitive"]?.jsonPrimitive?.booleanOrNull == true || category == CommentCategory.SENSITIVE
            val reason = v["reason"]?.jsonPrimitive?.content.orEmpty()
            when {
                sensitive -> {
                    flagged++
                    comments.update(c.commentId) { it.copy(category = category, status = CommentStatus.FLAGGED, flagReason = "Sensitive: $reason", updatedAt = time.now()) }
                    runtime.audit.record(role.displayName, "comment.flagged", "Sensitive comment by ${c.author} on ${c.videoId}: $reason", level = AuditLevel.WARNING)
                }
                category == CommentCategory.SPAM || category == CommentCategory.TOXIC -> {
                    if (settings.autoHideSpam) {
                        toHide += c.commentId
                        comments.update(c.commentId) { it.copy(category = category, status = CommentStatus.HIDDEN, flagReason = reason, updatedAt = time.now()) }
                    } else {
                        flagged++
                        comments.update(c.commentId) { it.copy(category = category, status = CommentStatus.FLAGGED, flagReason = "${category.name.lowercase()}: $reason", updatedAt = time.now()) }
                    }
                }
                category == CommentCategory.QUESTION || category == CommentCategory.PRAISE || category == CommentCategory.FEEDBACK -> {
                    comments.update(c.commentId) { it.copy(category = category, updatedAt = time.now()) }
                    toReply += c.copy(category = category)
                }
                else -> comments.update(c.commentId) { it.copy(category = category, status = CommentStatus.IGNORED, updatedAt = time.now()) }
            }
        }
        if (toHide.isNotEmpty()) {
            // heldForReview hides the comment publicly but keeps it recoverable in YouTube Studio.
            runCatching { youtube.moderate(toHide, "heldForReview") }.onSuccess { hidden = toHide.size }.onFailure { e ->
                runtime.audit.record(role.displayName, "comment.hide_failed", e.message.orEmpty(), level = AuditLevel.WARNING)
                toHide.forEach { id -> comments.update(id) { it.copy(status = CommentStatus.FLAGGED) } }
            }
        }

        var drafted = 0
        var posted = 0
        if (toReply.isNotEmpty() && runtime.hasSkill(role, "reply_drafter")) {
            val drafts = runtime.skillRaw(
                role, "reply_drafter",
                JsonObject(mapOf("comments" to JsonArray(toReply.map { c ->
                    JsonObject(mapOf(
                        "id" to JsonPrimitive(c.commentId), "text" to JsonPrimitive(c.text.take(1500)),
                        "category" to JsonPrimitive(c.category?.name ?: "OTHER"), "videoTitle" to JsonPrimitive(c.videoTitle),
                    ))
                }))),
                null,
            )["replies"]?.jsonArray.orEmpty().map { it.jsonObject }
            for (d in drafts) {
                val id = d["id"]?.jsonPrimitive?.content ?: continue
                val reply = d["reply"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: continue
                val needsHuman = d["needsHuman"]?.jsonPrimitive?.booleanOrNull == true
                if (settings.autoPostReplies && !needsHuman) {
                    val ok = runCatching { youtube.reply(id, reply) }.isSuccess
                    comments.update(id) { it.copy(status = if (ok) CommentStatus.REPLIED else CommentStatus.DRAFTED, draftReply = reply, updatedAt = time.now()) }
                    if (ok) posted++ else drafted++
                } else {
                    comments.update(id) { it.copy(status = CommentStatus.DRAFTED, draftReply = reply, flagReason = if (needsHuman) "Check before sending" else null, updatedAt = time.now()) }
                    drafted++
                }
            }
        }
        return CommentRunSummary(fetched, fresh, drafted, posted, hidden, flagged)
    }

    /** Sends a reply the user approved (possibly edited) from the Comments inbox. */
    suspend fun sendReply(commentId: String, text: String) = task("Reply to comment", null) {
        youtube.reply(commentId, text.trim())
        comments.update(commentId) { it.copy(status = CommentStatus.REPLIED, draftReply = text.trim(), updatedAt = time.now()) }
        runtime.audit.record(role.displayName, "comment.replied", "Replied to $commentId (approved by user)", level = AuditLevel.INFO)
    }

    suspend fun hide(commentId: String) = task("Hide comment", null) {
        youtube.moderate(listOf(commentId), "heldForReview")
        comments.update(commentId) { it.copy(status = CommentStatus.HIDDEN, updatedAt = time.now()) }
        runtime.audit.record(role.displayName, "comment.hidden", "Hid $commentId (user action)", level = AuditLevel.INFO)
    }

    suspend fun ignore(commentId: String) = comments.update(commentId) { it.copy(status = CommentStatus.IGNORED, updatedAt = time.now()) }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
