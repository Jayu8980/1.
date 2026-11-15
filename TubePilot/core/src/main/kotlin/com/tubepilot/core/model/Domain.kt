package com.tubepilot.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ChannelProfile(
    val channelId: String = "",
    val channelTitle: String = "",
    val niche: String = "",
    val audience: String = "",
    val tone: String = "Friendly, energetic and clear",
    val primaryLanguage: ContentLanguage = ContentLanguage.ENGLISH,
    val languages: List<ContentLanguage> = listOf(ContentLanguage.ENGLISH),
    val contentPillars: List<String> = emptyList(),
    val bannedTopics: List<String> = emptyList(),
    val shortsPerWeek: Int = 3,
    val longFormPerWeek: Int = 1,
    /** Hours of day (0-23, channel timezone) preferred for publishing. */
    val preferredPublishHours: List<Int> = listOf(18),
    val timezone: String = "Asia/Kolkata",
    val competitorChannelIds: List<String> = emptyList(),
    val defaultPlaylistId: String? = null,
    val ctaStyle: String = "Ask viewers to subscribe for more in one short sentence",
    val madeForKids: Boolean = false,
    val defaultCategoryId: String = "22",
) {
    val isConfigured: Boolean get() = niche.isNotBlank() && audience.isNotBlank()
}

@Serializable
data class StyleGuide(
    val voiceRules: List<String> = listOf("Short sentences", "Speak directly to the viewer as 'you'"),
    val dos: List<String> = emptyList(),
    val donts: List<String> = listOf("No clickbait that the video does not deliver", "No real people's likeness"),
    val thumbnailStyle: String = "Bold 2-4 word headline, high contrast, one focal subject",
    val brandColors: List<String> = listOf("#FFD400", "#111111", "#FFFFFF"),
    val fontStyle: String = "Heavy sans-serif",
)

@Serializable
data class Lesson(
    val id: Long = 0,
    val text: String,
    val category: LessonCategory,
    val evidence: String = "",
    val confidence: Double = 0.5,
    val createdAt: Long,
    val active: Boolean = true,
)

@Serializable
data class VideoPerformance(
    val videoId: String,
    val projectId: String? = null,
    val title: String,
    val format: VideoFormat,
    val publishedAt: Long,
    val views: Long = 0,
    val likes: Long = 0,
    val comments: Long = 0,
    val impressions: Long? = null,
    val ctrPercent: Double? = null,
    val avgViewPercentage: Double? = null,
    val avgViewDurationSec: Double? = null,
    val watchMinutes: Double = 0.0,
    val subscribersGained: Long = 0,
    val capturedAt: Long,
)

data class VideoProject(
    val id: String,
    val title: String,
    val format: VideoFormat,
    val language: ContentLanguage,
    val stage: PipelineStage,
    val status: ProjectStatus,
    val artifacts: ProjectArtifacts,
    val scheduledPublishAt: Long?,
    val calendarEntryId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
    val attempts: Int = 0,
)

@Serializable
data class CalendarEntry(
    val id: String,
    val slotStart: Long,
    val format: VideoFormat,
    val topic: String,
    val angle: String = "",
    val pillar: String = "",
    val keywords: List<String> = emptyList(),
    val status: CalendarStatus = CalendarStatus.PLANNED,
    val projectId: String? = null,
)

data class AuditEvent(
    val id: Long,
    val timestamp: Long,
    val actor: String,
    val action: String,
    val detail: String,
    val projectId: String?,
    val level: AuditLevel,
)

data class LlmUsage(
    val timestamp: Long,
    val provider: LlmProviderId,
    val model: String,
    val agent: AgentRole?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val costUsd: Double,
    val projectId: String? = null,
)

data class UsageSummary(
    val monthStart: Long,
    val totalCostUsd: Double,
    val inputTokens: Long,
    val outputTokens: Long,
    val calls: Int,
    val byAgent: Map<AgentRole, Double>,
    val byModel: Map<String, Double>,
)

data class CommentItem(
    val commentId: String,
    val threadId: String,
    val videoId: String,
    val videoTitle: String,
    val author: String,
    val authorChannelId: String,
    val text: String,
    val publishedAt: Long,
    val likeCount: Long,
    val category: CommentCategory?,
    val status: CommentStatus,
    val draftReply: String?,
    val flagReason: String?,
    val updatedAt: Long,
)

data class AgentStatus(
    val role: AgentRole,
    val state: AgentState,
    val currentTask: String?,
    val projectId: String?,
    val updatedAt: Long,
    val lastError: String?,
)

@Serializable
data class WeeklyReport(
    val weekStart: Long,
    val createdAt: Long,
    val headline: String,
    val totals: ReportTotals,
    val topVideos: List<String>,
    val underperformers: List<String>,
    val lessons: List<String>,
    val nextWeekFocus: List<String>,
    val markdown: String,
)

@Serializable
data class ReportTotals(
    val views: Long,
    val watchMinutes: Double,
    val subscribersGained: Long,
    val avgCtrPercent: Double?,
    val avgViewPercentage: Double?,
    val videosPublished: Int,
)

/** Audit record of one skill invocation. */
data class SkillCallRecord(
    val id: Long = 0,
    val timestamp: Long,
    val skillId: String,
    val agent: AgentRole,
    val projectId: String?,
    val input: String,
    val output: String?,
    val success: Boolean,
    val durationMs: Long,
    val error: String?,
)

data class ImportedSkillRecord(
    val id: String,
    val manifestJson: String,
    val promptTemplate: String,
    val importedAt: Long,
    val sourceName: String,
)
