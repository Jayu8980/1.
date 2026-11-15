package com.tubepilot.skills.builtin

import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.error.AuthRequiredException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.error.QuotaExhaustedException
import com.tubepilot.core.model.CalendarEntry
import com.tubepilot.core.model.TopicIdea
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.util.PrettyJson
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.skills.SkillContext
import com.tubepilot.skills.SkillExecutor
import com.tubepilot.youtube.YouTubeClient
import com.tubepilot.youtube.quota.QuotaManager
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Runs [block] and turns "not connected / out of quota / network" into a note instead of a failure. */
internal suspend fun <T> evidence(notes: MutableList<String>, label: String, block: suspend () -> T): T? = try {
    block()
} catch (e: AuthRequiredException) {
    notes += "$label skipped: YouTube not connected"
    null
} catch (e: QuotaExhaustedException) {
    notes += "$label skipped: ${e.bucket} quota exhausted for today"
    null
} catch (e: ProviderException) {
    notes += "$label failed: ${e.message?.take(160)}"
    null
}

/** Weighted idea score; competition counts against an idea. */
internal fun scoreIdea(idea: TopicIdea): TopicIdea {
    val demand = idea.demandScore.coerceIn(0.0, 10.0)
    val competition = idea.competitionScore.coerceIn(0.0, 10.0)
    val fit = idea.nicheFitScore.coerceIn(0.0, 10.0)
    val total = 0.4 * demand + 0.25 * (10 - competition) + 0.35 * fit
    return idea.copy(demandScore = demand, competitionScore = competition, nicheFitScore = fit, totalScore = "%.2f".format(Locale.ROOT, total).toDouble())
}

internal fun mentionsBanned(text: String, banned: List<String>): String? =
    banned.firstOrNull { it.isNotBlank() && text.lowercase(Locale.ROOT).contains(it.lowercase(Locale.ROOT).trim()) }

/**
 * trend_scan: gathers demand signals from the official APIs (competitors' recent uploads through
 * the cheap uploads-playlist path, search.list when its daily bucket allows, and the channel's own
 * best performers) and asks the model to propose and score ideas from that evidence.
 */
@Singleton
class TrendScanExecutor @Inject constructor(
    private val youtube: YouTubeClient,
    private val quota: QuotaManager,
    private val memory: MemoryRepository,
    private val time: TimeSource,
) : SkillExecutor {
    override val id = "trend_scan"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val profile = memory.profile()
        val notes = mutableListOf<String>()
        val lookbackDays = input.int("lookbackDays", 30).coerceIn(3, 365)
        val since = time.now() - lookbackDays * TimeUtil.DAY
        val competitors = (input.strings("competitorChannelIds") + profile.competitorChannelIds).distinct().take(5)
        val seeds = (input.strings("seedKeywords") + profile.contentPillars).distinct().filter { it.isNotBlank() }

        val competitorEvidence = competitors.mapNotNull { channelId ->
            evidence(notes, "Competitor $channelId") {
                youtube.recentUploads(channelId, 10).filter { it.publishedAtMs >= since || it.publishedAtMs == 0L }.map { v ->
                    val ageDays = ((time.now() - v.publishedAtMs) / TimeUtil.DAY).coerceAtLeast(1)
                    jsonOf("channel" to v.channelTitle, "title" to v.title, "views" to v.views, "viewsPerDay" to v.views / ageDays, "durationSec" to v.durationSec)
                }
            }
        }.flatten().sortedByDescending { (it["viewsPerDay"] as JsonPrimitive).content.toLong() }.take(25)

        val searchEvidence = seeds.take(2).mapNotNull { seed ->
            if (!quota.canAfford(mapOf("search.list" to 1, "videos.list" to 1))) {
                notes += "Search for '$seed' skipped to preserve quota"
                return@mapNotNull null
            }
            evidence(notes, "Search '$seed'") {
                val snapshot = youtube.search(seed, since, 15, profile.primaryLanguage.youtubeCode.substringBefore('-'),
                    shortsOnly = input.str("format") == VideoFormat.SHORT.name)
                jsonOf(
                    "query" to seed,
                    "totalResults" to snapshot.totalResults,
                    "top" to JsonArray(snapshot.videos.take(8).map { jsonOf("title" to it.title, "views" to it.views, "channel" to it.channelTitle) }),
                )
            }
        }

        val ownTop = memory.recentPerformance(30).sortedByDescending { it.views }.take(8).map {
            jsonOf("title" to it.title, "views" to it.views, "ctrPercent" to it.ctrPercent, "avgViewPercentage" to it.avgViewPercentage, "format" to it.format.name)
        }

        val evidenceJson = jsonOf(
            "competitorRecentUploads" to JsonArray(competitorEvidence),
            "searchDemand" to JsonArray(searchEvidence),
            "ownTopVideos" to JsonArray(ownTop),
            "notes" to notes,
        )
        val result = context.runtime.runPrompt(context, input, mapOf("evidence" to PrettyJson.encodeToString(JsonObject.serializer(), evidenceJson)))
        val maxIdeas = input.int("maxIdeas", 8).coerceIn(1, 30)
        val ideas = result.objects("ideas").mapNotNull { runCatching { it.decodeAs(TopicIdea.serializer()) }.getOrNull() }
            .filter { mentionsBanned(it.title + " " + it.angle, profile.bannedTopics) == null }
            .map { scoreIdea(it) }
            .sortedByDescending { it.totalScore }
            .take(maxIdeas)
        return jsonOf(
            "ideas" to ideas.toJsonArray(TopicIdea.serializer()),
            "evidenceNotes" to notes,
            "evidenceCounts" to jsonOf("competitorVideos" to competitorEvidence.size, "searches" to searchEvidence.size, "ownVideos" to ownTop.size),
        )
    }
}

/** keyword_research: search.list demand/competition evidence for a seed plus model-expanded keywords. */
@Singleton
class KeywordResearchExecutor @Inject constructor(
    private val youtube: YouTubeClient,
    private val quota: QuotaManager,
    private val memory: MemoryRepository,
    private val time: TimeSource,
) : SkillExecutor {
    override val id = "keyword_research"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val seed = input.str("seed")
        val notes = mutableListOf<String>()
        val profile = memory.profile()
        val evidenceJson = if (quota.canAfford(mapOf("search.list" to 1, "videos.list" to 1))) {
            evidence(notes, "Search '$seed'") {
                val snapshot = youtube.search(seed, time.now() - 90 * TimeUtil.DAY, 20, profile.primaryLanguage.youtubeCode.substringBefore('-'))
                val views = snapshot.videos.map { it.views }.sorted()
                jsonOf(
                    "totalResults" to snapshot.totalResults,
                    "medianViews" to (views.getOrNull(views.size / 2) ?: 0L),
                    "topTitles" to snapshot.videos.take(10).map { it.title },
                    "topChannels" to snapshot.videos.map { it.channelTitle }.distinct().take(8),
                )
            }
        } else {
            notes += "search.list bucket reserved; keywords estimated by the model only"
            null
        }
        val vars = mapOf("evidence" to (evidenceJson?.toString() ?: "{}"), "notes" to notes.joinToString("; "))
        val result = context.runtime.runPrompt(context, input, vars)
        return JsonObject(result + ("evidence" to (evidenceJson ?: JsonObject(emptyMap()))) + ("notes" to JsonArray(notes.map { JsonPrimitive(it) })))
    }
}

/**
 * content_calendar: deterministic slot planning from the posting frequency, then the model maps
 * the best ideas onto slots. Invalid or missing assignments are filled greedily by score.
 */
@Singleton
class ContentCalendarExecutor @Inject constructor(
    private val memory: MemoryRepository,
) : SkillExecutor {
    override val id = "content_calendar"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val profile = memory.profile()
        val zone = TimeUtil.zoneOrDefault(input.str("timezone", profile.timezone))
        val weekStart = input.long("weekStartMs", System.currentTimeMillis())
        val shorts = input.int("shortsPerWeek", profile.shortsPerWeek).coerceIn(0, 21)
        val longs = input.int("longFormPerWeek", profile.longFormPerWeek).coerceIn(0, 7)
        val hours = profile.preferredPublishHours.ifEmpty { listOf(18) }
        val ideas = input.objects("ideas").mapNotNull { runCatching { it.decodeAs(TopicIdea.serializer()) }.getOrNull() }
            .filter { mentionsBanned(it.title, profile.bannedTopics) == null }

        val slots = CalendarPlanner.slots(weekStart, zone, shorts, longs, hours)
        val slotJson = JsonArray(slots.mapIndexed { i, s -> jsonOf("slot" to i, "start" to Instant.ofEpochMilli(s.first).atZone(zone).toString(), "format" to s.second.name) })
        val result = runCatching {
            context.runtime.runPrompt(
                context, input,
                mapOf("slots" to slotJson.toString(), "ideas" to PrettyJson.encodeToString(ListSerializer(TopicIdea.serializer()), ideas)),
            )
        }.getOrNull()
        val assigned = result?.objects("assignments").orEmpty().associateBy { it.int("slot", -1) }
        val entries = CalendarPlanner.assign(slots, ideas, assigned.mapValues { (_, o) -> o.str("ideaTitle") to o.str("angle") })
        return jsonOf(
            "theme" to (result?.str("theme") ?: ""),
            "entries" to entries.toJsonArray(CalendarEntry.serializer()),
        )
    }
}

/** Pure scheduling logic, unit-tested separately. */
object CalendarPlanner {
    /** Spreads [shorts] and [longs] publish slots across the 7 days starting at [weekStartMs]. */
    fun slots(weekStartMs: Long, zone: java.time.ZoneId, shorts: Int, longs: Int, hours: List<Int>): List<Pair<Long, VideoFormat>> {
        val start = Instant.ofEpochMilli(weekStartMs).atZone(zone).toLocalDate()
        val result = ArrayList<Pair<Long, VideoFormat>>()
        // Long-form prefers weekends and mid-week; Shorts are spread evenly.
        val longDays = listOf(DayOfWeek.SATURDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY, DayOfWeek.FRIDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.MONDAY)
        for (i in 0 until longs) {
            val dow = longDays[i % longDays.size]
            val date = (0..6).map { start.plusDays(it.toLong()) }.first { it.dayOfWeek == dow }
            val hour = hours.max().coerceIn(0, 23)
            result += date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli() to VideoFormat.LONG
        }
        for (i in 0 until shorts) {
            val dayOffset = (i * 7) / shorts.coerceAtLeast(1)
            val date = start.plusDays(dayOffset.toLong())
            val hour = hours[i % hours.size].coerceIn(0, 23)
            var ms = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            // Keep at least 3 hours between two uploads.
            while (result.any { kotlin.math.abs(it.first - ms) < 3 * TimeUtil.HOUR }) ms += 3 * TimeUtil.HOUR
            result += ms to VideoFormat.SHORT
        }
        return result.sortedBy { it.first }
    }

    fun assign(
        slots: List<Pair<Long, VideoFormat>>,
        ideas: List<TopicIdea>,
        modelChoices: Map<Int, Pair<String, String>>,
    ): List<CalendarEntry> {
        val remaining = ideas.sortedByDescending { it.totalScore }.toMutableList()
        return slots.mapIndexed { index, (start, format) ->
            val choice = modelChoices[index]
            val picked = choice?.let { (title, _) -> remaining.firstOrNull { it.title.equals(title, ignoreCase = true) && it.format == format } }
                ?: remaining.firstOrNull { it.format == format }
                ?: remaining.firstOrNull()
            picked?.let { remaining.remove(it) }
            CalendarEntry(
                id = UUID.randomUUID().toString(),
                slotStart = start,
                format = format,
                topic = picked?.title ?: "Open slot: choose a topic",
                angle = choice?.second?.takeIf { it.isNotBlank() } ?: picked?.angle.orEmpty(),
                pillar = picked?.pillar.orEmpty(),
                keywords = picked?.keywords.orEmpty(),
            )
        }
    }
}

/** repurpose_content: turns a finished script into Shorts ideas, a community post and social copy. */
@Singleton
class RepurposeExecutor @Inject constructor(
    private val projects: ProjectRepository,
) : SkillExecutor {
    override val id = "repurpose_content"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val projectId = input.str("projectId").ifBlank { context.projectId.orEmpty() }
        val script = projectId.takeIf { it.isNotBlank() }?.let { projects.get(it)?.artifacts?.script }
        val text = script?.let { "${it.title}\nHook: ${it.hook}\n" + it.scenes.joinToString("\n") { s -> s.narration } + "\nCTA: ${it.cta}" }
            ?: input.str("scriptText")
        require(text.isNotBlank()) { "repurpose_content needs a projectId with a script or scriptText" }
        val result = context.runtime.runPrompt(context, input, mapOf("script" to text))
        val ideas = result.objects("shortIdeas").mapNotNull { runCatching { it.decodeAs(TopicIdea.serializer()) }.getOrNull() }
            .map { scoreIdea(it.copy(format = VideoFormat.SHORT)) }
        return JsonObject(result + ("shortIdeas" to ideas.toJsonArray(TopicIdea.serializer())))
    }
}
