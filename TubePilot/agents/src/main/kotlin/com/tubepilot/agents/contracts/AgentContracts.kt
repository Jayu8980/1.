package com.tubepilot.agents.contracts

import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.Chapter
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.LessonCategory
import com.tubepilot.core.model.TopicIdea
import com.tubepilot.core.model.VideoFormat
import kotlinx.serialization.Serializable

/*
 * Agent-level input/output contracts (JSON). Stage artefacts such as VideoScript or SeoPackage
 * live in :core because they are persisted with the project.
 */

@Serializable
data class IdeaRequest(
    val count: Int = 8,
    val format: String = "ANY",
    val focusKeywords: List<String> = emptyList(),
)

@Serializable
data class IdeaList(val ideas: List<TopicIdea>, val notes: String = "")

@Serializable
data class BriefRequest(val idea: TopicIdea, val language: ContentLanguage)

@Serializable
data class ScriptRequest(
    val title: String,
    val format: VideoFormat,
    val language: ContentLanguage,
    val briefJson: String,
    val reviewerNotes: String? = null,
)

@Serializable
data class PlanRequest(val weekStartMs: Long, val replaceUnstarted: Boolean = false)

@Serializable
data class WeekPlan(val weekStartMs: Long, val theme: String, val entries: List<CalendarEntry>, val removed: List<String> = emptyList())

/** What the SEO agent's model returns; titles come from the title_ab_test skill. */
@Serializable
data class SeoDraft(
    val titleVariants: List<String> = emptyList(),
    val description: String,
    val tags: List<String>,
    val hashtags: List<String>,
    val chapters: List<Chapter> = emptyList(),
    val pinnedComment: String = "",
    val categoryId: String = "22",
)

@Serializable
data class LessonDraft(val text: String, val category: LessonCategory, val evidence: String = "", val confidence: Double = 0.5)

@Serializable
data class CommentRunSummary(
    val fetched: Int,
    val newComments: Int,
    val drafted: Int,
    val posted: Int,
    val hidden: Int,
    val flagged: Int,
)
