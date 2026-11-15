package com.tubepilot.agents

import com.tubepilot.agents.contracts.LessonDraft
import com.tubepilot.agents.core.Agent
import com.tubepilot.agents.core.AgentRuntime
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.ReportRepository
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.Lesson
import com.tubepilot.core.model.LessonCategory
import com.tubepilot.core.model.TitleTest
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.model.WeeklyReport
import com.tubepilot.core.util.TextUtil
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.core.util.TubeJson
import com.tubepilot.youtube.YouTubeClient
import com.tubepilot.youtube.api.VideoSnippet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Title A/B rotation rules: each variant runs for [WINDOW_MS]; the winner gets the most views per window. */
object TitleTestPlanner {
    const val WINDOW_MS = 48 * TimeUtil.HOUR

    sealed interface Step {
        data object Wait : Step
        data class Switch(val toIndex: Int, val test: TitleTest) : Step
        data class Finish(val winnerIndex: Int, val test: TitleTest) : Step
    }

    fun next(test: TitleTest, currentViews: Long, now: Long): Step {
        if (test.finished) return Step.Wait
        if (now - test.switchedAt < WINDOW_MS) return Step.Wait
        val gained = (currentViews - test.baselineViews).coerceAtLeast(0)
        val views = test.viewsPerVariant.toMutableList().apply {
            while (size < test.variants.size) add(0)
            this[test.activeIndex] = this[test.activeIndex] + gained
        }
        val updated = test.copy(viewsPerVariant = views, baselineViews = currentViews, switchedAt = now)
        val nextIndex = test.activeIndex + 1
        return if (nextIndex < test.variants.size) {
            Step.Switch(nextIndex, updated.copy(activeIndex = nextIndex))
        } else {
            val winner = views.indices.maxByOrNull { views[it] } ?: 0
            Step.Finish(winner, updated.copy(activeIndex = winner, finished = true, winnerIndex = winner))
        }
    }
}

/** Merges new lessons into memory, skipping near-duplicates of existing ones. */
object LessonDeduper {
    fun isDuplicate(candidate: String, existing: List<String>): Boolean {
        val s = TextUtil.shingles(candidate, 2)
        return existing.any { TextUtil.jaccard(s, TextUtil.shingles(it, 2)) >= 0.6 }
    }
}

/** Tracks per-video performance, writes the weekly report and feeds lessons into memory. */
@Singleton
class AnalyticsAgent @Inject constructor(
    runtime: AgentRuntime,
    private val youtube: YouTubeClient,
    private val memory: MemoryRepository,
    private val reports: ReportRepository,
    private val projects: ProjectRepository,
    private val time: TimeSource,
) : Agent(AgentRole.ANALYTICS, runtime) {
    override val rolePrompt = """
        You are the Analytics agent of a YouTube channel team. You read the numbers honestly, compare
        like with like (Shorts vs long-form), and turn evidence into specific lessons the team can use.
    """.trimIndent()
    override val skillIds = listOf("analytics_report")

    suspend fun weeklyReport(): WeeklyReport = task("Weekly report", null) {
        val out = runtime.skillRaw(role, "analytics_report", JsonObject(mapOf("days" to JsonPrimitive(7))), null)
        val report = TubeJson.decodeFromJsonElement(WeeklyReport.serializer(), out["report"]!!.jsonObject)
        reports.save(report)
        val drafts = TubeJson.decodeFromJsonElement(ListSerializer(LessonDraft.serializer()), out["lessons"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
        val added = addLessons(drafts)
        runtime.audit.record(role.displayName, "report.saved", "${report.headline}; $added new lesson(s)", level = AuditLevel.INFO)
        report
    }

    suspend fun addLessons(drafts: List<LessonDraft>): Int {
        val existing = memory.activeLessons(200).map { it.text }.toMutableList()
        var added = 0
        drafts.filter { it.text.isNotBlank() && !LessonDeduper.isDuplicate(it.text, existing) }.forEach { d ->
            memory.addLesson(Lesson(text = d.text.take(300), category = d.category, evidence = d.evidence.take(500), confidence = d.confidence.coerceIn(0.0, 1.0), createdAt = time.now()))
            existing += d.text
            added++
        }
        return added
    }

    /**
     * Per-video follow-up: advances the title A/B rotation. Returns true when the test is finished
     * (or there is none) so the pipeline can move to the memory update.
     */
    suspend fun evaluateProject(project: VideoProject): Boolean = task("Evaluate '${project.title}'", project.id) {
        val test = project.artifacts.titleTest ?: return@task true
        if (test.finished) return@task true
        val video = youtube.videos(listOf(test.videoId)).firstOrNull() ?: return@task true
        when (val step = TitleTestPlanner.next(test, video.views, time.now())) {
            TitleTestPlanner.Step.Wait -> {
                if (test.viewsPerVariant.isEmpty() && test.baselineViews == 0L && video.views > 0 && time.now() - test.switchedAt < TimeUtil.HOUR) {
                    projects.update(project.id) { it.copy(artifacts = it.artifacts.copy(titleTest = test.copy(baselineViews = video.views))) }
                }
                false
            }
            is TitleTestPlanner.Step.Switch -> {
                updateTitle(test.videoId, test.variants[step.toIndex], project)
                projects.update(project.id) { it.copy(artifacts = it.artifacts.copy(titleTest = step.test)) }
                runtime.audit.record(role.displayName, "titletest.switch", "Title variant ${step.toIndex + 1}/${test.variants.size} is now live", project.id)
                false
            }
            is TitleTestPlanner.Step.Finish -> {
                if (step.winnerIndex != test.activeIndex) updateTitle(test.videoId, test.variants[step.winnerIndex], project)
                projects.update(project.id) { it.copy(artifacts = it.artifacts.copy(titleTest = step.test)) }
                runtime.audit.record(role.displayName, "titletest.finished", "Winner: \"${test.variants[step.winnerIndex]}\" (views per variant ${step.test.viewsPerVariant})", project.id)
                true
            }
        }
    }

    private suspend fun updateTitle(videoId: String, title: String, project: VideoProject) {
        val current = youtube.videos(listOf(videoId)).firstOrNull() ?: return
        val seo = project.artifacts.seo
        youtube.updateVideoSnippet(
            videoId,
            VideoSnippet(
                title = title,
                description = current.description.ifBlank { seo?.fullDescription().orEmpty() },
                tags = current.tags.ifEmpty { seo?.tags.orEmpty() },
                categoryId = seo?.categoryId ?: "22",
                defaultLanguage = project.language.youtubeCode,
            ),
        )
    }

    /** Memory update for one finished video: a lesson from its title test and its retention. */
    suspend fun recordProjectLessons(project: VideoProject): Int = task("Lessons from '${project.title}'", project.id) {
        val drafts = ArrayList<LessonDraft>()
        project.artifacts.titleTest?.takeIf { it.finished && it.viewsPerVariant.size >= 2 }?.let { t ->
            val winner = t.winnerIndex ?: 0
            val best = t.viewsPerVariant[winner]
            val others = t.viewsPerVariant.filterIndexed { i, _ -> i != winner }.average()
            if (best > 0 && others >= 0) {
                drafts += LessonDraft(
                    text = "Title style like \"${t.variants[winner].take(80)}\" beat alternatives (${best} vs %.0f views per 48h)".format(others),
                    category = LessonCategory.TITLE,
                    evidence = "A/B test on ${t.videoId}: ${t.variants.zip(t.viewsPerVariant)}",
                    confidence = if (best > others * 1.3 && best > 200) 0.7 else 0.4,
                )
            }
        }
        val perf = project.artifacts.uploads.firstOrNull()?.let { u -> memory.recentPerformance(60).firstOrNull { it.videoId == u.videoId } }
        val hook = project.artifacts.script?.hook
        if (perf?.avgViewPercentage != null && hook != null) {
            val avg = memory.recentPerformance(30).filter { it.format == perf.format }.mapNotNull { it.avgViewPercentage }.average()
            if (!avg.isNaN() && kotlin.math.abs(perf.avgViewPercentage!! - avg) >= 10) {
                val better = perf.avgViewPercentage!! > avg
                drafts += LessonDraft(
                    text = (if (better) "Hook pattern worked: " else "Hook underperformed: ") + "\"${hook.take(90)}\"",
                    category = LessonCategory.HOOK,
                    evidence = "Avg viewed %.0f%% vs channel %.0f%% for ${perf.format}".format(perf.avgViewPercentage, avg),
                    confidence = 0.5,
                )
            }
        }
        val added = addLessons(drafts)
        projects.update(project.id) { it.copy(artifacts = it.artifacts.copy(lessonsRecorded = true)) }
        added
    }
}
