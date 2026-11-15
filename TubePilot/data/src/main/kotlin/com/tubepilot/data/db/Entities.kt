package com.tubepilot.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Enums are stored by name in String columns so schema migrations never depend on ordinal order.

@Entity(tableName = "kv")
data class KeyValueEntity(@PrimaryKey val key: String, val value: String, val updatedAt: Long)

@Entity(tableName = "lessons", indices = [Index("active"), Index("category")])
data class LessonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val category: String,
    val evidence: String,
    val confidence: Double,
    val createdAt: Long,
    val active: Boolean,
)

@Entity(tableName = "performance", indices = [Index("publishedAt")])
data class PerformanceEntity(
    @PrimaryKey val videoId: String,
    val projectId: String?,
    val title: String,
    val format: String,
    val publishedAt: Long,
    val views: Long,
    val likes: Long,
    val comments: Long,
    val impressions: Long?,
    val ctrPercent: Double?,
    val avgViewPercentage: Double?,
    val avgViewDurationSec: Double?,
    val watchMinutes: Double,
    val subscribersGained: Long,
    val capturedAt: Long,
)

@Entity(tableName = "projects", indices = [Index("status"), Index("updatedAt")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val format: String,
    val language: String,
    val stage: String,
    val status: String,
    val artifactsJson: String,
    val scheduledPublishAt: Long?,
    val calendarEntryId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String?,
    val attempts: Int,
)

@Entity(tableName = "calendar", indices = [Index("slotStart")])
data class CalendarEntity(
    @PrimaryKey val id: String,
    val slotStart: Long,
    val format: String,
    val topic: String,
    val angle: String,
    val pillar: String,
    val keywordsJson: String,
    val status: String,
    val projectId: String?,
)

@Entity(tableName = "audit", indices = [Index("timestamp"), Index("actor"), Index("projectId")])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val actor: String,
    val action: String,
    val detail: String,
    val projectId: String?,
    val level: String,
)

@Entity(tableName = "llm_usage", indices = [Index("timestamp")])
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val provider: String,
    val model: String,
    val agent: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val costUsd: Double,
    val projectId: String?,
)

@Entity(tableName = "quota_usage", indices = [Index("dayKey")])
data class QuotaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val dayKey: String,
    val method: String,
    val bucket: String,
    val units: Int,
)

@Entity(tableName = "comments", indices = [Index("status"), Index("videoId"), Index("publishedAt")])
data class CommentEntity(
    @PrimaryKey val commentId: String,
    val threadId: String,
    val videoId: String,
    val videoTitle: String,
    val author: String,
    val authorChannelId: String,
    val text: String,
    val publishedAt: Long,
    val likeCount: Long,
    val category: String?,
    val status: String,
    val draftReply: String?,
    val flagReason: String?,
    val updatedAt: Long,
)

@Entity(tableName = "agent_status")
data class AgentStatusEntity(
    @PrimaryKey val role: String,
    val state: String,
    val currentTask: String?,
    val projectId: String?,
    val updatedAt: Long,
    val lastError: String?,
)

@Entity(tableName = "skill_state", primaryKeys = ["skillId", "role"])
data class SkillStateEntity(val skillId: String, val role: String, val enabled: Boolean)

@Entity(tableName = "imported_skills")
data class ImportedSkillEntity(
    @PrimaryKey val id: String,
    val manifestJson: String,
    val promptTemplate: String,
    val importedAt: Long,
    val sourceName: String,
)

@Entity(tableName = "skill_calls", indices = [Index("timestamp"), Index("skillId")])
data class SkillCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val skillId: String,
    val agent: String,
    val projectId: String?,
    val input: String,
    val output: String?,
    val success: Boolean,
    val durationMs: Long,
    val error: String?,
)

@Entity(tableName = "weekly_reports", indices = [Index("weekStart")])
data class WeeklyReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: Long,
    val createdAt: Long,
    val json: String,
)
