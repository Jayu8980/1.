package com.tubepilot.data.db

import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AgentState
import com.tubepilot.core.model.AgentStatus
import com.tubepilot.core.model.AuditEvent
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.CalendarStatus
import com.tubepilot.core.model.CommentCategory
import com.tubepilot.core.model.CommentItem
import com.tubepilot.core.model.CommentStatus
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.ImportedSkillRecord
import com.tubepilot.core.model.Lesson
import com.tubepilot.core.model.LessonCategory
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectArtifacts
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.core.model.SkillCallRecord
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoPerformance
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.util.TubeJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Enum lookup that survives renamed/removed constants instead of crashing on old rows. */
internal inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

private val stringList = ListSerializer(String.serializer())

fun LessonEntity.toModel() = Lesson(id, text, enumOr(category, LessonCategory.OTHER), evidence, confidence, createdAt, active)
fun Lesson.toEntity() = LessonEntity(id, text, category.name, evidence, confidence, createdAt, active)

fun PerformanceEntity.toModel() = VideoPerformance(
    videoId, projectId, title, enumOr(format, VideoFormat.SHORT), publishedAt, views, likes, comments,
    impressions, ctrPercent, avgViewPercentage, avgViewDurationSec, watchMinutes, subscribersGained, capturedAt,
)

fun VideoPerformance.toEntity() = PerformanceEntity(
    videoId, projectId, title, format.name, publishedAt, views, likes, comments,
    impressions, ctrPercent, avgViewPercentage, avgViewDurationSec, watchMinutes, subscribersGained, capturedAt,
)

fun ProjectEntity.toModel() = VideoProject(
    id = id,
    title = title,
    format = enumOr(format, VideoFormat.SHORT),
    language = enumOr(language, ContentLanguage.ENGLISH),
    stage = enumOr(stage, PipelineStage.IDEA),
    status = enumOr(status, ProjectStatus.QUEUED),
    artifacts = runCatching { TubeJson.decodeFromString(ProjectArtifacts.serializer(), artifactsJson) }.getOrDefault(ProjectArtifacts()),
    scheduledPublishAt = scheduledPublishAt,
    calendarEntryId = calendarEntryId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastError = lastError,
    attempts = attempts,
)

fun VideoProject.toEntity() = ProjectEntity(
    id, title, format.name, language.name, stage.name, status.name,
    TubeJson.encodeToString(ProjectArtifacts.serializer(), artifacts),
    scheduledPublishAt, calendarEntryId, createdAt, updatedAt, lastError, attempts,
)

fun CalendarEntity.toModel() = CalendarEntry(
    id, slotStart, enumOr(format, VideoFormat.SHORT), topic, angle, pillar,
    runCatching { TubeJson.decodeFromString(stringList, keywordsJson) }.getOrDefault(emptyList()),
    enumOr(status, CalendarStatus.PLANNED), projectId,
)

fun CalendarEntry.toEntity() = CalendarEntity(
    id, slotStart, format.name, topic, angle, pillar, TubeJson.encodeToString(stringList, keywords), status.name, projectId,
)

fun AuditEntity.toModel() = AuditEvent(id, timestamp, actor, action, detail, projectId, enumOr(level, AuditLevel.INFO))

fun CommentEntity.toModel() = CommentItem(
    commentId, threadId, videoId, videoTitle, author, authorChannelId, text, publishedAt, likeCount,
    category?.let { enumOr(it, CommentCategory.OTHER) }, enumOr(status, CommentStatus.NEW), draftReply, flagReason, updatedAt,
)

fun CommentItem.toEntity() = CommentEntity(
    commentId, threadId, videoId, videoTitle, author, authorChannelId, text, publishedAt, likeCount,
    category?.name, status.name, draftReply, flagReason, updatedAt,
)

fun AgentStatusEntity.toModel() = AgentStatus(
    enumOr(role, AgentRole.ORCHESTRATOR), enumOr(state, AgentState.IDLE), currentTask, projectId, updatedAt, lastError,
)

fun ImportedSkillEntity.toModel() = ImportedSkillRecord(id, manifestJson, promptTemplate, importedAt, sourceName)
fun ImportedSkillRecord.toEntity() = ImportedSkillEntity(id, manifestJson, promptTemplate, importedAt, sourceName)

fun SkillCallEntity.toModel() = SkillCallRecord(
    id, timestamp, skillId, enumOr(agent, AgentRole.ORCHESTRATOR), projectId, input, output, success, durationMs, error,
)

fun SkillCallRecord.toEntity() = SkillCallEntity(
    0, timestamp, skillId, agent.name, projectId, input, output, success, durationMs, error,
)
