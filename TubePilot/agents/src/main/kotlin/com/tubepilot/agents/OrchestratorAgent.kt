package com.tubepilot.agents

import com.tubepilot.agents.contracts.IdeaRequest
import com.tubepilot.agents.contracts.PlanRequest
import com.tubepilot.agents.contracts.WeekPlan
import com.tubepilot.agents.core.Agent
import com.tubepilot.agents.core.AgentRuntime
import com.tubepilot.core.domain.CalendarRepository
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.CalendarStatus
import com.tubepilot.core.model.ChannelProfile
import com.tubepilot.core.model.ProjectArtifacts
import com.tubepilot.core.model.TopicIdea
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.core.util.TubeJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure rules the Channel Manager applies to any plan: drop banned topics, enforce the weekly
 * frequency, keep uploads at least [MIN_GAP_MS] apart, never touch entries already in production,
 * and avoid near-duplicate topics in the same week.
 */
object ConflictResolver {
    const val MIN_GAP_MS = 3 * TimeUtil.HOUR

    data class Resolution(val kept: List<CalendarEntry>, val removed: List<Pair<CalendarEntry, String>>)

    fun resolve(proposed: List<CalendarEntry>, existing: List<CalendarEntry>, profile: ChannelProfile): Resolution {
        val removed = ArrayList<Pair<CalendarEntry, String>>()
        val locked = existing.filter { it.status != CalendarStatus.PLANNED || it.projectId != null }
        val kept = ArrayList(locked)
        val caps = mapOf(VideoFormat.SHORT to profile.shortsPerWeek, VideoFormat.LONG to profile.longFormPerWeek)
        for (entry in proposed.sortedBy { it.slotStart }) {
            val banned = profile.bannedTopics.firstOrNull { it.isNotBlank() && "${entry.topic} ${entry.angle}".lowercase(Locale.ROOT).contains(it.lowercase(Locale.ROOT)) }
            val reason = when {
                banned != null -> "banned topic '$banned'"
                kept.count { it.format == entry.format } >= (caps[entry.format] ?: 0) -> "over the weekly ${entry.format} frequency"
                kept.any { kotlin.math.abs(it.slotStart - entry.slotStart) < MIN_GAP_MS } -> "too close to another upload"
                kept.any { similar(it.topic, entry.topic) } -> "duplicate of '${kept.first { similar(it.topic, entry.topic) }.topic}'"
                else -> null
            }
            if (reason == null) kept += entry else removed += entry to reason
        }
        return Resolution(kept.sortedBy { it.slotStart }, removed)
    }

    fun similar(a: String, b: String): Boolean {
        val wa = a.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        val wb = b.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return a.equals(b, ignoreCase = true)
        return wa.intersect(wb).size.toDouble() / minOf(wa.size, wb.size) >= 0.8
    }

    /** How long before its slot a video must start production. */
    fun leadTimeMs(format: VideoFormat) = if (format == VideoFormat.SHORT) 2 * TimeUtil.DAY else 4 * TimeUtil.DAY
}

/** Channel Manager: plans the weekly calendar, starts productions on time and enforces strategy. */
@Singleton
class OrchestratorAgent @Inject constructor(
    runtime: AgentRuntime,
    private val researcher: TrendResearcherAgent,
    private val calendar: CalendarRepository,
    private val projects: ProjectRepository,
    private val memory: MemoryRepository,
    private val time: TimeSource,
) : Agent(AgentRole.ORCHESTRATOR, runtime) {

    override val rolePrompt = """
        You are the Channel Manager of a YouTube channel team. You turn the channel strategy into a
        realistic weekly publishing plan, balance content pillars and formats, and make sure the team
        never publishes banned, repetitive or off-strategy content.
    """.trimIndent()
    override val skillIds = listOf("content_calendar", "trend_scan", "repurpose_content")

    /** Max videos in production at once: renders are heavy on an 8 GB phone. */
    val maxConcurrentProductions = 3

    suspend fun planWeek(request: PlanRequest): WeekPlan = task("Plan week of ${TimeUtil.isoDate(request.weekStartMs)}", null) {
        val profile = memory.profile()
        val weekEnd = request.weekStartMs + 7 * TimeUtil.DAY
        val existing = calendar.entriesBetween(request.weekStartMs, weekEnd)
        val needed = profile.shortsPerWeek + profile.longFormPerWeek
        val ideas = researcher.findIdeas(IdeaRequest(count = (needed * 2).coerceIn(4, 20)))
        val output = runtime.skillRaw(
            role, "content_calendar",
            JsonObject(
                mapOf(
                    "weekStartMs" to JsonPrimitive(request.weekStartMs),
                    "ideas" to TubeJson.encodeToJsonElement(ListSerializer(TopicIdea.serializer()), ideas.ideas),
                    "shortsPerWeek" to JsonPrimitive(profile.shortsPerWeek),
                    "longFormPerWeek" to JsonPrimitive(profile.longFormPerWeek),
                    "timezone" to JsonPrimitive(profile.timezone),
                ),
            ),
            null,
        )
        val proposed = (output["entries"]?.jsonArray ?: emptyList()).map { TubeJson.decodeFromJsonElement(CalendarEntry.serializer(), it) }
            .filter { it.slotStart > time.now() } // never schedule into the past
        val unstarted = existing.filter { it.status == CalendarStatus.PLANNED && it.projectId == null }
        val base = if (request.replaceUnstarted) existing - unstarted.toSet() else existing
        val resolution = ConflictResolver.resolve(proposed, base, profile)
        if (request.replaceUnstarted) unstarted.forEach { calendar.delete(it.id) }
        calendar.upsert(resolution.kept)
        resolution.removed.forEach { (entry, reason) ->
            runtime.audit.record(role.displayName, "plan.rejected", "Dropped '${entry.topic}': $reason", level = AuditLevel.INFO)
        }
        WeekPlan(
            weekStartMs = request.weekStartMs,
            theme = (output["theme"] as? JsonPrimitive)?.content.orEmpty(),
            entries = resolution.kept,
            removed = resolution.removed.map { "${it.first.topic}: ${it.second}" },
        )
    }

    /** Creates projects for calendar slots that are inside their production lead time. */
    suspend fun startDueProductions(): List<VideoProject> = task("Start due productions", null) {
        val now = time.now()
        val active = projects.inProgress().count { it.status.isActive && it.stage.ordinal < com.tubepilot.core.model.PipelineStage.UPLOAD.ordinal }
        var capacity = (maxConcurrentProductions - active).coerceAtLeast(0)
        val profile = memory.profile()
        val due = calendar.entriesBetween(now - TimeUtil.DAY, now + 5 * TimeUtil.DAY)
            .filter { it.status == CalendarStatus.PLANNED && it.projectId == null && it.slotStart - ConflictResolver.leadTimeMs(it.format) <= now }
            .sortedBy { it.slotStart }
        val started = ArrayList<VideoProject>()
        for (entry in due) {
            if (capacity <= 0) break
            val project = projects.create(entry.topic, entry.format, profile.primaryLanguage, entry.id, entry.slotStart.takeIf { it > now + TimeUtil.HOUR })
            projects.update(project.id) {
                it.copy(artifacts = ProjectArtifacts(idea = TopicIdea(entry.topic, entry.angle, entry.keywords, entry.format, entry.pillar)))
            }
            calendar.upsert(listOf(entry.copy(status = CalendarStatus.IN_PRODUCTION, projectId = project.id)))
            runtime.audit.record(role.displayName, "production.started", "'${entry.topic}' for slot ${TimeUtil.rfc3339(entry.slotStart)}", project.id)
            started += project
            capacity--
        }
        started
    }

    /** Helper used by the UI: does the given week already have a plan? */
    suspend fun hasPlan(weekStartMs: Long): Boolean = calendar.entriesBetween(weekStartMs, weekStartMs + 7 * TimeUtil.DAY).isNotEmpty()
}
