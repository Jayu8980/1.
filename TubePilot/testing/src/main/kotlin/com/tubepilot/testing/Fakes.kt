package com.tubepilot.testing

import com.tubepilot.core.domain.AgentStatusRepository
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.CalendarRepository
import com.tubepilot.core.domain.KeyValueStore
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.QuotaRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.domain.SkillStateRepository
import com.tubepilot.core.domain.UsageRepository
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AgentState
import com.tubepilot.core.model.AgentStatus
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.model.AuditEvent
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.ChannelProfile
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
import com.tubepilot.core.security.SecretKey
import com.tubepilot.core.security.SecretStore
import com.tubepilot.core.util.TimeSource
import com.tubepilot.llm.LlmContent
import com.tubepilot.llm.LlmProvider
import com.tubepilot.llm.LlmRequest
import com.tubepilot.llm.LlmResponse
import com.tubepilot.llm.LlmStreamEvent
import com.tubepilot.llm.StopReason
import com.tubepilot.llm.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import java.util.UUID

class FakeTime(var nowMs: Long = 1_763_200_000_000L) : TimeSource {
    override fun now(): Long = nowMs
    fun advance(ms: Long) { nowMs += ms }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings(onboardingComplete = true)) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current(): AppSettings = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = transform(state.value).also { state.value = it }
}

class FakeAuditLog : AuditLog {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(actor: String, action: String, detail: String, projectId: String?, level: AuditLevel) {
        events += AuditEvent(events.size.toLong(), 0, actor, action, detail, projectId, level)
    }
    override fun observe(limit: Int): Flow<List<AuditEvent>> = MutableStateFlow(events.toList())
    override fun observeActor(actor: String, limit: Int): Flow<List<AuditEvent>> = MutableStateFlow(events.filter { it.actor == actor })
    override fun observeProject(projectId: String, limit: Int): Flow<List<AuditEvent>> = MutableStateFlow(events.filter { it.projectId == projectId })
    fun actions() = events.map { it.action }
}

class FakeUsageRepository : UsageRepository {
    val records = mutableListOf<LlmUsage>()
    var extraSpend = 0.0
    override suspend fun record(usage: LlmUsage) { records += usage }
    override suspend fun spentSince(sinceMs: Long): Double = extraSpend + records.filter { it.timestamp >= sinceMs }.sumOf { it.costUsd }
    override fun observeSummary(sinceMs: Long): Flow<UsageSummary> = flow {
        emit(UsageSummary(sinceMs, records.sumOf { it.costUsd }, 0, 0, records.size, emptyMap(), emptyMap()))
    }
}

class FakeMemoryRepository(
    var profileValue: ChannelProfile = ChannelProfile(niche = "Budget phones", audience = "Students", bannedTopics = listOf("gambling")),
    var styleValue: StyleGuide = StyleGuide(),
) : MemoryRepository {
    val lessons = mutableListOf<Lesson>()
    val performance = mutableListOf<VideoPerformance>()
    override fun observeProfile(): Flow<ChannelProfile> = MutableStateFlow(profileValue)
    override suspend fun profile() = profileValue
    override suspend fun saveProfile(profile: ChannelProfile) { profileValue = profile }
    override fun observeStyleGuide(): Flow<StyleGuide> = MutableStateFlow(styleValue)
    override suspend fun styleGuide() = styleValue
    override suspend fun saveStyleGuide(guide: StyleGuide) { styleValue = guide }
    override fun observeLessons(): Flow<List<Lesson>> = MutableStateFlow(lessons.toList())
    override suspend fun activeLessons(limit: Int, categories: Set<LessonCategory>?): List<Lesson> =
        lessons.filter { it.active && (categories == null || it.category in categories) }.take(limit)
    override suspend fun addLesson(lesson: Lesson): Long {
        val id = lessons.size + 1L
        lessons += lesson.copy(id = id)
        return id
    }
    override suspend fun setLessonActive(id: Long, active: Boolean) {
        lessons.replaceAll { if (it.id == id) it.copy(active = active) else it }
    }
    override fun observePerformance(): Flow<List<VideoPerformance>> = MutableStateFlow(performance.toList())
    override suspend fun recentPerformance(limit: Int) = performance.take(limit)
    override suspend fun performanceSince(sinceMs: Long) = performance.filter { it.publishedAt >= sinceMs }
    override suspend fun upsertPerformance(items: List<VideoPerformance>) {
        items.forEach { p -> performance.removeAll { it.videoId == p.videoId }; performance += p }
    }
}

class FakeProjectRepository(private val time: TimeSource = FakeTime()) : ProjectRepository {
    val projects = LinkedHashMap<String, VideoProject>()
    private val flow = MutableStateFlow<List<VideoProject>>(emptyList())
    private fun publish() { flow.value = projects.values.toList() }

    override fun observeAll(): Flow<List<VideoProject>> = flow
    override fun observe(id: String): Flow<VideoProject?> = flow.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun get(id: String) = projects[id]
    override suspend fun create(title: String, format: VideoFormat, language: ContentLanguage, calendarEntryId: String?, scheduledPublishAt: Long?): VideoProject {
        val p = VideoProject(UUID.randomUUID().toString(), title, format, language, PipelineStage.IDEA, ProjectStatus.QUEUED, ProjectArtifacts(), scheduledPublishAt, calendarEntryId, time.now(), time.now())
        projects[p.id] = p
        publish()
        return p
    }
    override suspend fun update(id: String, transform: (VideoProject) -> VideoProject): VideoProject {
        val updated = transform(projects.getValue(id)).copy(updatedAt = time.now())
        projects[id] = updated
        publish()
        return updated
    }
    override suspend fun inProgress() = projects.values.filter { !it.status.isTerminal }
    override suspend fun recentScripts(limit: Int): List<VideoScript> = projects.values.mapNotNull { it.artifacts.script }.take(limit)
    override suspend fun publishedSince(sinceMs: Long) = projects.values.filter { it.status == ProjectStatus.PUBLISHED || it.status == ProjectStatus.SCHEDULED }
    override suspend fun delete(id: String) { projects.remove(id); publish() }
    fun put(project: VideoProject) { projects[project.id] = project; publish() }
}

class FakeCalendarRepository : CalendarRepository {
    val entries = LinkedHashMap<String, CalendarEntry>()
    override fun observeRange(fromMs: Long, toMs: Long): Flow<List<CalendarEntry>> = MutableStateFlow(entries.values.filter { it.slotStart in fromMs until toMs })
    override suspend fun entriesBetween(fromMs: Long, toMs: Long) = entries.values.filter { it.slotStart in fromMs until toMs }.sortedBy { it.slotStart }
    override suspend fun upsert(entries: List<CalendarEntry>) { entries.forEach { this.entries[it.id] = it } }
    override suspend fun get(id: String) = entries[id]
    override suspend fun delete(id: String) { entries.remove(id) }
}

class FakeAgentStatusRepository : AgentStatusRepository {
    val states = LinkedHashMap<AgentRole, AgentStatus>()
    val history = mutableListOf<Pair<AgentRole, AgentState>>()
    override fun observe(): Flow<Map<AgentRole, AgentStatus>> = MutableStateFlow(states.toMap())
    override suspend fun set(role: AgentRole, state: AgentState, task: String?, projectId: String?, error: String?) {
        states[role] = AgentStatus(role, state, task, projectId, 0, error)
        history += role to state
    }
}

class FakeSkillStateRepository : SkillStateRepository {
    val overridesMap = LinkedHashMap<Pair<String, AgentRole>, Boolean>()
    val importedMap = LinkedHashMap<String, ImportedSkillRecord>()
    val calls = mutableListOf<SkillCallRecord>()
    override fun observeOverrides(): Flow<Map<Pair<String, AgentRole>, Boolean>> = MutableStateFlow(overridesMap.toMap())
    override suspend fun overrides() = overridesMap.toMap()
    override suspend fun setEnabled(skillId: String, role: AgentRole, enabled: Boolean) { overridesMap[skillId to role] = enabled }
    override fun observeImported(): Flow<List<ImportedSkillRecord>> = MutableStateFlow(importedMap.values.toList())
    override suspend fun imported() = importedMap.values.toList()
    override suspend fun saveImported(record: ImportedSkillRecord) { importedMap[record.id] = record }
    override suspend fun deleteImported(id: String) { importedMap.remove(id); overridesMap.keys.removeAll { it.first == id } }
    override suspend fun logCall(record: SkillCallRecord) { calls += record }
    override fun observeCalls(limit: Int): Flow<List<SkillCallRecord>> = MutableStateFlow(calls.toList())
}

class FakeQuotaRepository : QuotaRepository {
    val used = LinkedHashMap<String, MutableMap<String, Int>>()
    override suspend fun record(dayKey: String, method: String, bucket: String, units: Int) {
        val day = used.getOrPut(dayKey) { LinkedHashMap() }
        day[bucket] = (day[bucket] ?: 0) + units
    }
    override suspend fun usedOn(dayKey: String): Map<String, Int> = used[dayKey].orEmpty()
    override fun observeDay(dayKey: String): Flow<Map<String, Int>> = MutableStateFlow(used[dayKey].orEmpty())
}

class FakeKeyValueStore : KeyValueStore {
    val values = LinkedHashMap<String, String>()
    override suspend fun get(key: String) = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override fun observe(key: String): Flow<String?> = MutableStateFlow(values[key])
}

class FakeSecretStore(initial: Map<SecretKey, String> = mapOf(SecretKey.ANTHROPIC_API_KEY to "test-key")) : SecretStore {
    private val values = initial.toMutableMap()
    private val _version = MutableStateFlow(0)
    override val version: StateFlow<Int> = _version
    override suspend fun get(key: SecretKey) = values[key]
    override suspend fun put(key: SecretKey, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
        _version.value++
    }
}

/**
 * LLM provider that replays scripted responses and records every request, so tests can drive an
 * agent's tool loop deterministically. A queued Throwable is thrown instead of answering.
 */
class ScriptedLlmProvider(override val id: LlmProviderId = LlmProviderId.ANTHROPIC) : LlmProvider {
    val requests = mutableListOf<LlmRequest>()
    private val queue = ArrayDeque<Any>()
    var configured = true

    fun enqueueText(text: String, usage: TokenUsage = TokenUsage(100, 50)) = apply {
        queue += LlmResponse(listOf(LlmContent.Text(text)), usage, StopReason.END_TURN, "test-model", id)
    }

    fun enqueueToolCall(name: String, input: JsonObject, id: String = "call_${queue.size}") = apply {
        queue += LlmResponse(listOf(LlmContent.ToolUse(id, name, input)), TokenUsage(100, 20), StopReason.TOOL_USE, "test-model", this.id)
    }

    fun enqueueError(error: Throwable) = apply { queue += error }

    val remaining: Int get() = queue.size

    override suspend fun isConfigured() = configured

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        val next = queue.removeFirstOrNull() ?: error("ScriptedLlmProvider: no response queued for request #${requests.size}")
        if (next is Throwable) throw next
        return next as LlmResponse
    }

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val response = complete(request)
        emit(LlmStreamEvent.TextDelta(response.text))
        emit(LlmStreamEvent.Completed(response))
    }
}
