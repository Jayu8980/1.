package com.tubepilot.data.repo

import androidx.room.withTransaction
import com.tubepilot.core.domain.AgentStatusRepository
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.CalendarRepository
import com.tubepilot.core.domain.CommentFilter
import com.tubepilot.core.domain.CommentRepository
import com.tubepilot.core.domain.KeyValueStore
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.QuotaRepository
import com.tubepilot.core.domain.ReportRepository
import com.tubepilot.core.domain.SkillStateRepository
import com.tubepilot.core.domain.UsageRepository
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AgentState
import com.tubepilot.core.model.AgentStatus
import com.tubepilot.core.model.AuditEvent
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.ChannelProfile
import com.tubepilot.core.model.CommentItem
import com.tubepilot.core.model.CommentStatus
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.ImportedSkillRecord
import com.tubepilot.core.model.Lesson
import com.tubepilot.core.model.LessonCategory
import com.tubepilot.core.model.LlmProviderId
import com.tubepilot.core.model.LlmUsage
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectArtifacts
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.core.model.SkillCallRecord
import com.tubepilot.core.model.StyleGuide
import com.tubepilot.core.model.UsageSummary
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoPerformance
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.model.VideoScript
import com.tubepilot.core.model.WeeklyReport
import com.tubepilot.core.util.JsonText
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.core.util.TubeJson
import com.tubepilot.data.db.AgentStatusEntity
import com.tubepilot.data.db.AuditEntity
import com.tubepilot.data.db.KeyValueEntity
import com.tubepilot.data.db.QuotaEntity
import com.tubepilot.data.db.SkillStateEntity
import com.tubepilot.data.db.TubePilotDatabase
import com.tubepilot.data.db.UsageEntity
import com.tubepilot.data.db.WeeklyReportEntity
import com.tubepilot.data.db.enumOr
import com.tubepilot.data.db.toEntity
import com.tubepilot.data.db.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomKeyValueStore @Inject constructor(
    private val db: TubePilotDatabase,
    private val time: TimeSource,
) : KeyValueStore {
    override suspend fun get(key: String): String? = db.keyValueDao().get(key)
    override suspend fun put(key: String, value: String) = db.keyValueDao().put(KeyValueEntity(key, value, time.now()))
    override fun observe(key: String): Flow<String?> = db.keyValueDao().observe(key).distinctUntilChanged()
}

@Singleton
class RoomMemoryRepository @Inject constructor(
    private val db: TubePilotDatabase,
    private val kv: RoomKeyValueStore,
) : MemoryRepository {

    private fun <T> observeDoc(key: String, serializer: KSerializer<T>, default: T): Flow<T> =
        kv.observe(key).map { json -> json?.let { decode(it, serializer) } ?: default }

    private fun <T> decode(json: String, serializer: KSerializer<T>): T? =
        runCatching { TubeJson.decodeFromString(serializer, json) }.getOrNull()

    override fun observeProfile(): Flow<ChannelProfile> = observeDoc(KEY_PROFILE, ChannelProfile.serializer(), ChannelProfile())
    override suspend fun profile(): ChannelProfile =
        kv.get(KEY_PROFILE)?.let { decode(it, ChannelProfile.serializer()) } ?: ChannelProfile()
    override suspend fun saveProfile(profile: ChannelProfile) =
        kv.put(KEY_PROFILE, TubeJson.encodeToString(ChannelProfile.serializer(), profile))

    override fun observeStyleGuide(): Flow<StyleGuide> = observeDoc(KEY_STYLE, StyleGuide.serializer(), StyleGuide())
    override suspend fun styleGuide(): StyleGuide =
        kv.get(KEY_STYLE)?.let { decode(it, StyleGuide.serializer()) } ?: StyleGuide()
    override suspend fun saveStyleGuide(guide: StyleGuide) =
        kv.put(KEY_STYLE, TubeJson.encodeToString(StyleGuide.serializer(), guide))

    override fun observeLessons(): Flow<List<Lesson>> = db.lessonDao().observeAll().map { rows -> rows.map { it.toModel() } }
    override suspend fun activeLessons(limit: Int, categories: Set<LessonCategory>?): List<Lesson> =
        (if (categories.isNullOrEmpty()) db.lessonDao().active(limit) else db.lessonDao().activeIn(categories.map { it.name }, limit))
            .map { it.toModel() }

    override suspend fun addLesson(lesson: Lesson): Long = db.lessonDao().insert(lesson.copy(id = 0).toEntity())
    override suspend fun setLessonActive(id: Long, active: Boolean) = db.lessonDao().setActive(id, active)

    override fun observePerformance(): Flow<List<VideoPerformance>> =
        db.performanceDao().observeAll().map { rows -> rows.map { it.toModel() } }
    override suspend fun recentPerformance(limit: Int) = db.performanceDao().recent(limit).map { it.toModel() }
    override suspend fun performanceSince(sinceMs: Long) = db.performanceDao().since(sinceMs).map { it.toModel() }
    override suspend fun upsertPerformance(items: List<VideoPerformance>) = db.performanceDao().upsert(items.map { it.toEntity() })

    private companion object {
        const val KEY_PROFILE = "memory.channel_profile"
        const val KEY_STYLE = "memory.style_guide"
    }
}

@Singleton
class RoomProjectRepository @Inject constructor(
    private val db: TubePilotDatabase,
    private val time: TimeSource,
) : ProjectRepository {
    // Single-process app: a mutex plus a Room transaction makes read-modify-write atomic.
    private val writeLock = Mutex()

    override fun observeAll(): Flow<List<VideoProject>> = db.projectDao().observeAll().map { rows -> rows.map { it.toModel() } }
    override fun observe(id: String): Flow<VideoProject?> = db.projectDao().observe(id).map { it?.toModel() }
    override suspend fun get(id: String): VideoProject? = db.projectDao().get(id)?.toModel()

    override suspend fun create(
        title: String,
        format: VideoFormat,
        language: ContentLanguage,
        calendarEntryId: String?,
        scheduledPublishAt: Long?,
    ): VideoProject {
        val now = time.now()
        val project = VideoProject(
            id = UUID.randomUUID().toString(),
            title = title,
            format = format,
            language = language,
            stage = PipelineStage.IDEA,
            status = ProjectStatus.QUEUED,
            artifacts = ProjectArtifacts(),
            scheduledPublishAt = scheduledPublishAt,
            calendarEntryId = calendarEntryId,
            createdAt = now,
            updatedAt = now,
        )
        db.projectDao().insert(project.toEntity())
        return project
    }

    override suspend fun update(id: String, transform: (VideoProject) -> VideoProject): VideoProject = writeLock.withLock {
        db.withTransaction {
            val current = db.projectDao().get(id)?.toModel() ?: throw NoSuchElementException("Project $id not found")
            val updated = transform(current).copy(id = current.id, updatedAt = time.now())
            db.projectDao().upsert(updated.toEntity())
            updated
        }
    }

    override suspend fun inProgress(): List<VideoProject> = db.projectDao().inProgress().map { it.toModel() }

    override suspend fun recentScripts(limit: Int): List<VideoScript> =
        db.projectDao().recent(limit * 2).mapNotNull { it.toModel().artifacts.script }.take(limit)

    override suspend fun publishedSince(sinceMs: Long): List<VideoProject> =
        db.projectDao().publishedSince(sinceMs).map { it.toModel() }

    override suspend fun delete(id: String) = db.projectDao().delete(id)
}

@Singleton
class RoomCalendarRepository @Inject constructor(private val db: TubePilotDatabase) : CalendarRepository {
    override fun observeRange(fromMs: Long, toMs: Long): Flow<List<CalendarEntry>> =
        db.calendarDao().observeRange(fromMs, toMs).map { rows -> rows.map { it.toModel() } }
    override suspend fun entriesBetween(fromMs: Long, toMs: Long) = db.calendarDao().between(fromMs, toMs).map { it.toModel() }
    override suspend fun upsert(entries: List<CalendarEntry>) = db.calendarDao().upsert(entries.map { it.toEntity() })
    override suspend fun get(id: String): CalendarEntry? = db.calendarDao().get(id)?.toModel()
    override suspend fun delete(id: String) = db.calendarDao().delete(id)
}

@Singleton
class RoomAuditLog @Inject constructor(
    private val db: TubePilotDatabase,
    private val time: TimeSource,
) : AuditLog {
    private var lastPrune = 0L

    override suspend fun record(actor: String, action: String, detail: String, projectId: String?, level: AuditLevel) {
        val now = time.now()
        db.auditDao().insert(AuditEntity(0, now, actor, action, JsonText.truncate(detail, 8000), projectId, level.name))
        if (now - lastPrune > TimeUtil.DAY) {
            lastPrune = now
            db.auditDao().pruneBefore(now - RETENTION_MS)
        }
    }

    override fun observe(limit: Int): Flow<List<AuditEvent>> = db.auditDao().observe(limit).map { r -> r.map { it.toModel() } }
    override fun observeActor(actor: String, limit: Int) = db.auditDao().observeActor(actor, limit).map { r -> r.map { it.toModel() } }
    override fun observeProject(projectId: String, limit: Int) = db.auditDao().observeProject(projectId, limit).map { r -> r.map { it.toModel() } }

    private companion object {
        const val RETENTION_MS = 180L * TimeUtil.DAY
    }
}

@Singleton
class RoomUsageRepository @Inject constructor(private val db: TubePilotDatabase) : UsageRepository {
    override suspend fun record(usage: LlmUsage) = db.usageDao().insert(
        UsageEntity(
            0, usage.timestamp, usage.provider.name, usage.model, usage.agent?.name,
            usage.inputTokens, usage.outputTokens, usage.cacheReadTokens, usage.costUsd, usage.projectId,
        ),
    )

    override suspend fun spentSince(sinceMs: Long): Double = db.usageDao().costSince(sinceMs)

    override fun observeSummary(sinceMs: Long): Flow<UsageSummary> = db.usageDao().observeSince(sinceMs).map { rows ->
        UsageSummary(
            monthStart = sinceMs,
            totalCostUsd = rows.sumOf { it.costUsd },
            inputTokens = rows.sumOf { it.inputTokens },
            outputTokens = rows.sumOf { it.outputTokens },
            calls = rows.size,
            byAgent = rows.filter { it.agent != null }
                .groupBy { enumOr(it.agent, AgentRole.ORCHESTRATOR) }
                .mapValues { (_, v) -> v.sumOf { it.costUsd } },
            byModel = rows.groupBy { "${enumOr(it.provider, LlmProviderId.ANTHROPIC).displayName} · ${it.model}" }
                .mapValues { (_, v) -> v.sumOf { it.costUsd } },
        )
    }
}

@Singleton
class RoomQuotaRepository @Inject constructor(
    private val db: TubePilotDatabase,
    private val time: TimeSource,
) : QuotaRepository {
    override suspend fun record(dayKey: String, method: String, bucket: String, units: Int) =
        db.quotaDao().insert(QuotaEntity(0, time.now(), dayKey, method, bucket, units))

    override suspend fun usedOn(dayKey: String): Map<String, Int> = db.quotaDao().usedOn(dayKey).associate { it.bucket to it.units }
    override fun observeDay(dayKey: String): Flow<Map<String, Int>> =
        db.quotaDao().observeDay(dayKey).map { rows -> rows.associate { it.bucket to it.units } }
}

@Singleton
class RoomCommentRepository @Inject constructor(private val db: TubePilotDatabase) : CommentRepository {
    override fun observe(filter: CommentFilter): Flow<List<CommentItem>> = db.commentDao().observeAll().map { rows ->
        val statuses = filter.statuses
        val categories = filter.categories
        rows.map { it.toModel() }.filter { c ->
            (statuses == null || c.status in statuses) &&
                (categories == null || (c.category != null && c.category in categories))
        }
    }

    override suspend fun get(commentId: String): CommentItem? = db.commentDao().get(commentId)?.toModel()

    override suspend fun knownIds(ids: Collection<String>): Set<String> =
        ids.chunked(500).flatMap { db.commentDao().existing(it) }.toSet()

    override suspend fun upsert(items: List<CommentItem>) = db.commentDao().upsert(items.map { it.toEntity() })

    override suspend fun byStatus(status: CommentStatus, limit: Int): List<CommentItem> =
        db.commentDao().byStatus(status.name, limit).map { it.toModel() }

    override suspend fun update(commentId: String, transform: (CommentItem) -> CommentItem) {
        db.withTransaction {
            val current = db.commentDao().get(commentId)?.toModel() ?: return@withTransaction
            db.commentDao().upsert(listOf(transform(current).toEntity()))
        }
    }
}

@Singleton
class RoomAgentStatusRepository @Inject constructor(
    private val db: TubePilotDatabase,
    private val time: TimeSource,
) : AgentStatusRepository {
    override fun observe(): Flow<Map<AgentRole, AgentStatus>> = db.agentStatusDao().observeAll().map { rows ->
        val known = rows.map { it.toModel() }.associateBy { it.role }
        AgentRole.entries.associateWith { role ->
            known[role] ?: AgentStatus(role, AgentState.IDLE, null, null, 0, null)
        }
    }

    override suspend fun set(role: AgentRole, state: AgentState, task: String?, projectId: String?, error: String?) =
        db.agentStatusDao().upsert(AgentStatusEntity(role.name, state.name, task, projectId, time.now(), error))
}

@Singleton
class RoomSkillStateRepository @Inject constructor(private val db: TubePilotDatabase) : SkillStateRepository {
    override fun observeOverrides(): Flow<Map<Pair<String, AgentRole>, Boolean>> =
        db.skillDao().observeStates().map { rows -> rows.toOverrideMap() }

    override suspend fun overrides(): Map<Pair<String, AgentRole>, Boolean> = db.skillDao().states().toOverrideMap()

    private fun List<SkillStateEntity>.toOverrideMap() =
        mapNotNull { row -> AgentRole.entries.firstOrNull { it.name == row.role }?.let { (row.skillId to it) to row.enabled } }.toMap()

    override suspend fun setEnabled(skillId: String, role: AgentRole, enabled: Boolean) =
        db.skillDao().upsertState(SkillStateEntity(skillId, role.name, enabled))

    override fun observeImported(): Flow<List<ImportedSkillRecord>> = db.skillDao().observeImported().map { r -> r.map { it.toModel() } }
    override suspend fun imported(): List<ImportedSkillRecord> = db.skillDao().imported().map { it.toModel() }
    override suspend fun saveImported(record: ImportedSkillRecord) = db.skillDao().upsertImported(record.toEntity())
    override suspend fun deleteImported(id: String) = db.skillDao().deleteImportedAndState(id)

    override suspend fun logCall(record: SkillCallRecord) = db.skillDao().insertCall(
        record.copy(input = JsonText.truncate(record.input, 6000), output = record.output?.let { JsonText.truncate(it, 6000) }).toEntity(),
    )

    override fun observeCalls(limit: Int): Flow<List<SkillCallRecord>> = db.skillDao().observeCalls(limit).map { r -> r.map { it.toModel() } }
}

@Singleton
class RoomReportRepository @Inject constructor(private val db: TubePilotDatabase) : ReportRepository {
    override suspend fun save(report: WeeklyReport) = db.reportDao().insert(
        WeeklyReportEntity(0, report.weekStart, report.createdAt, TubeJson.encodeToString(WeeklyReport.serializer(), report)),
    )

    override fun observeLatest(): Flow<WeeklyReport?> = db.reportDao().observeLatest().map { it?.decode() }
    override fun observeAll(): Flow<List<WeeklyReport>> = db.reportDao().observeAll().map { rows -> rows.mapNotNull { it.decode() } }

    private fun WeeklyReportEntity.decode(): WeeklyReport? =
        runCatching { TubeJson.decodeFromString(WeeklyReport.serializer(), json) }.getOrNull()
}
