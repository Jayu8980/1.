package com.tubepilot.skills.builtin

import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.model.LessonCategory
import com.tubepilot.core.model.ReportTotals
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoPerformance
import com.tubepilot.core.model.WeeklyReport
import com.tubepilot.core.util.PrettyJson
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.skills.SkillContext
import com.tubepilot.skills.SkillExecutor
import com.tubepilot.youtube.YouTubeClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * analytics_report: pulls per-video metrics from the YouTube Analytics API, refreshes the
 * performance memory, and asks the model for a report and evidence-backed lessons.
 */
@Singleton
class AnalyticsReportExecutor @Inject constructor(
    private val youtube: YouTubeClient,
    private val memory: MemoryRepository,
    private val projects: ProjectRepository,
    private val time: TimeSource,
) : SkillExecutor {
    override val id = "analytics_report"

    override suspend fun execute(context: SkillContext, input: JsonObject): JsonObject {
        val days = input.int("days", 7).coerceIn(1, 90)
        val now = time.now()
        val notes = mutableListOf<String>()
        val end = TimeUtil.isoDate(now, ZoneOffset.UTC)
        // Analytics data lags ~2 days; look back over the full window plus 28 days for context.
        val start = TimeUtil.isoDate(now - (days + 28) * TimeUtil.DAY, ZoneOffset.UTC)

        val rows = evidence(notes, "Analytics core metrics") {
            youtube.analyticsReport(
                startDate = start, endDate = end,
                metrics = "views,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,subscribersGained,likes,comments",
                dimensions = "video", sort = "-views", maxResults = 50,
            )
        }.orEmpty()
        // Thumbnail impressions/CTR are only available to some channels; failure is not fatal.
        val reach = evidence(notes, "Analytics reach metrics") {
            youtube.analyticsReport(
                startDate = start, endDate = end,
                metrics = "videoThumbnailImpressions,videoThumbnailImpressionsClickRate",
                dimensions = "video", sort = "-videoThumbnailImpressions", maxResults = 50,
            )
        }.orEmpty().associateBy { it["video"].text() }

        val ids = rows.mapNotNull { it["video"].text().ifBlank { null } }
        val details = if (ids.isEmpty()) emptyList() else evidence(notes, "Video details") { youtube.videos(ids) }.orEmpty()
        val byId = details.associateBy { it.id }
        val projectByVideo = projects.publishedSince(now - 400 * TimeUtil.DAY)
            .flatMap { p -> p.artifacts.uploads.map { it.videoId to p.id } }.toMap()

        val performance = rows.mapNotNull { row ->
            val vid = row["video"].text().ifBlank { return@mapNotNull null }
            val d = byId[vid]
            val r = reach[vid]
            VideoPerformance(
                videoId = vid,
                projectId = projectByVideo[vid],
                title = d?.title ?: vid,
                format = if ((d?.durationSec ?: 0) in 1..180) VideoFormat.SHORT else VideoFormat.LONG,
                publishedAt = d?.publishedAtMs ?: 0,
                views = row["views"].num().toLong(),
                likes = row["likes"].num().toLong(),
                comments = row["comments"].num().toLong(),
                impressions = r?.get("videoThumbnailImpressions")?.num()?.toLong(),
                ctrPercent = r?.get("videoThumbnailImpressionsClickRate")?.num()?.let { if (it <= 1.0) it * 100 else it },
                avgViewPercentage = row["averageViewPercentage"].num(),
                avgViewDurationSec = row["averageViewDuration"].num(),
                watchMinutes = row["estimatedMinutesWatched"].num(),
                subscribersGained = row["subscribersGained"].num().toLong(),
                capturedAt = now,
            )
        }
        if (performance.isNotEmpty()) memory.upsertPerformance(performance)

        val windowStart = now - days * TimeUtil.DAY
        val recent = performance.filter { it.publishedAt >= windowStart }
        val totals = ReportTotals(
            views = performance.sumOf { it.views },
            watchMinutes = performance.sumOf { it.watchMinutes },
            subscribersGained = performance.sumOf { it.subscribersGained },
            avgCtrPercent = performance.mapNotNull { it.ctrPercent }.takeIf { it.isNotEmpty() }?.average(),
            avgViewPercentage = performance.mapNotNull { it.avgViewPercentage }.takeIf { it.isNotEmpty() }?.average(),
            videosPublished = recent.size,
        )
        val existing = memory.activeLessons(30).joinToString("\n") { "- ${it.text}" }
        val result = context.runtime.runPrompt(
            context, input,
            mapOf(
                "performance" to PrettyJson.encodeToString(ListSerializer(VideoPerformance.serializer()), performance.take(40)),
                "totals" to PrettyJson.encodeToString(ReportTotals.serializer(), totals),
                "existingLessons" to existing.ifBlank { "(none)" },
                "notes" to notes.joinToString("; "),
            ),
        )
        val report = WeeklyReport(
            weekStart = TimeUtil.startOfWeekMs(now, TimeUtil.zoneOrDefault(memory.profile().timezone)),
            createdAt = now,
            headline = result.str("headline", "Weekly report"),
            totals = totals,
            topVideos = result.strings("topVideos"),
            underperformers = result.strings("underperformers"),
            lessons = result.objects("lessons").map { it.str("text") }.filter { it.isNotBlank() },
            nextWeekFocus = result.strings("nextWeekFocus"),
            markdown = result.str("markdown"),
        )
        val lessons = result.objects("lessons").filter { it.str("text").isNotBlank() }.map { l ->
            val category = runCatching { LessonCategory.valueOf(l.str("category").uppercase(Locale.ROOT)) }.getOrDefault(LessonCategory.OTHER)
            jsonOf("text" to l.str("text"), "category" to category.name, "evidence" to l.str("evidence"), "confidence" to l.double("confidence", 0.5).coerceIn(0.0, 1.0))
        }
        return jsonOf(
            "report" to report.toJsonObject(WeeklyReport.serializer()),
            "lessons" to JsonArray(lessons),
            "videosAnalysed" to performance.size,
            "notes" to notes,
        )
    }

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.content.orEmpty()
    private fun JsonElement?.num(): Double = (this as? JsonPrimitive)?.doubleOrNull ?: 0.0
}
