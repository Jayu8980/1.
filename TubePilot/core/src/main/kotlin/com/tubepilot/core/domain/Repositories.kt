package com.tubepilot.core.domain

import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AgentState
import com.tubepilot.core.model.AgentStatus
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.model.AuditEvent
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.ChannelProfile
import com.tubepilot.core.model.CommentCategory
import com.tubepilot.core.model.CommentItem
import com.tubepilot.core.model.CommentStatus
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.ImportedSkillRecord
import com.tubepilot.core.model.Lesson
import com.tubepilot.core.model.LessonCategory
import com.tubepilot.core.model.LlmUsage
import com.tubepilot.core.model.SkillCallRecord
import com.tubepilot.core.model.StyleGuide
import com.tubepilot.core.model.UsageSummary
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoPerformance
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.model.VideoScript
import com.tubepilot.core.model.WeeklyReport
import kotlinx.coroutines.flow.Flow

/*
 * Repository contracts. Implementations live in :data (Room + DataStore); agents, skills and the
 * pipeline depend only on these interfaces, which keeps them unit-testable with in-memory fakes.
 */

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
}

interface MemoryRepository {
    fun observeProfile(): Flow<ChannelProfile>
    suspend fun profile(): ChannelProfile
    suspend fun saveProfile(profile: ChannelProfile)

    fun observeStyleGuide(): Flow<StyleGuide>
    suspend fun styleGuide(): StyleGuide
    suspend fun saveStyleGuide(guide: StyleGuide)

    fun observeLessons(): Flow<List<Lesson>>
    suspend fun activeLessons(limit: Int = 20, categories: Set<LessonCategory>? = null): List<Lesson>
    suspend fun addLesson(lesson: Lesson): Long
    suspend fun setLessonActive(id: Long, active: Boolean)

    fun observePerformance(): Flow<List<VideoPerformance>>
    suspend fun recentPerformance(limit: Int = 30): List<VideoPerformance>
    suspend fun performanceSince(sinceMs: Long): List<VideoPerformance>
    suspend fun upsertPerformance(items: List<VideoPerformance>)
}

interface ProjectRepository {
    fun observeAll(): Flow<List<VideoProject>>
    fun observe(id: String): Flow<VideoProject?>
    suspend fun get(id: String): VideoProject?
    suspend fun create(
        title: String,
        format: VideoFormat,
        language: ContentLanguage,
        calendarEntryId: String?,
        scheduledPublishAt: Long?,
    ): VideoProject
    /** Atomically reads, transforms and writes a project. Returns the stored result. */
    suspend fun update(id: String, transform: (VideoProject) -> VideoProject): VideoProject
    suspend fun inProgress(): List<VideoProject>
    suspend fun recentScripts(limit: Int): List<VideoScript>
    suspend fun publishedSince(sinceMs: Long): List<VideoProject>
    suspend fun delete(id: String)
}

interface CalendarRepository {
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<CalendarEntry>>
    suspend fun entriesBetween(fromMs: Long, toMs: Long): List<CalendarEntry>
    suspend fun upsert(entries: List<CalendarEntry>)
    suspend fun get(id: String): CalendarEntry?
    suspend fun delete(id: String)
}

interface AuditLog {
    suspend fun record(
        actor: String,
        action: String,
        detail: String,
        projectId: String? = null,
        level: AuditLevel = AuditLevel.INFO,
    )
    fun observe(limit: Int = 300): Flow<List<AuditEvent>>
    fun observeActor(actor: String, limit: Int = 200): Flow<List<AuditEvent>>
    fun observeProject(projectId: String, limit: Int = 200): Flow<List<AuditEvent>>
}

interface UsageRepository {
    suspend fun record(usage: LlmUsage)
    suspend fun spentSince(sinceMs: Long): Double
    fun observeSummary(sinceMs: Long): Flow<UsageSummary>
}

interface QuotaRepository {
    suspend fun record(dayKey: String, method: String, bucket: String, units: Int)
    suspend fun usedOn(dayKey: String): Map<String, Int>
    fun observeDay(dayKey: String): Flow<Map<String, Int>>
}

data class CommentFilter(val statuses: Set<CommentStatus>? = null, val categories: Set<CommentCategory>? = null)

interface CommentRepository {
    fun observe(filter: CommentFilter = CommentFilter()): Flow<List<CommentItem>>
    suspend fun get(commentId: String): CommentItem?
    suspend fun knownIds(ids: Collection<String>): Set<String>
    suspend fun upsert(items: List<CommentItem>)
    suspend fun byStatus(status: CommentStatus, limit: Int): List<CommentItem>
    suspend fun update(commentId: String, transform: (CommentItem) -> CommentItem)
}

interface AgentStatusRepository {
    fun observe(): Flow<Map<AgentRole, AgentStatus>>
    suspend fun set(role: AgentRole, state: AgentState, task: String?, projectId: String?, error: String? = null)
}

interface SkillStateRepository {
    /** Explicit per-agent overrides; skills without an override use their manifest default. */
    fun observeOverrides(): Flow<Map<Pair<String, AgentRole>, Boolean>>
    suspend fun overrides(): Map<Pair<String, AgentRole>, Boolean>
    suspend fun setEnabled(skillId: String, role: AgentRole, enabled: Boolean)

    fun observeImported(): Flow<List<ImportedSkillRecord>>
    suspend fun imported(): List<ImportedSkillRecord>
    suspend fun saveImported(record: ImportedSkillRecord)
    suspend fun deleteImported(id: String)

    suspend fun logCall(record: SkillCallRecord)
    fun observeCalls(limit: Int = 200): Flow<List<SkillCallRecord>>
}

interface ReportRepository {
    suspend fun save(report: WeeklyReport)
    fun observeLatest(): Flow<WeeklyReport?>
    fun observeAll(): Flow<List<WeeklyReport>>
}

/** Small JSON key-value store for configuration documents such as the quota cost table. */
interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    fun observe(key: String): Flow<String?>
}
