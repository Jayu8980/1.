package com.tubepilot.pipeline

import com.tubepilot.agents.AgentTeam
import com.tubepilot.agents.contracts.BriefRequest
import com.tubepilot.agents.contracts.ScriptRequest
import com.tubepilot.core.domain.CalendarRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.model.CalendarStatus
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectArtifacts
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.core.model.ResearchBrief
import com.tubepilot.core.model.TopicIdea
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.core.util.TubeJson
import com.tubepilot.media.ProjectStorage
import javax.inject.Inject
import javax.inject.Singleton

/** Runs one pipeline stage for a project by delegating to the owning agent, and persists its output. */
@Singleton
class StageExecutor @Inject constructor(
    private val team: AgentTeam,
    private val projects: ProjectRepository,
    private val calendar: CalendarRepository,
    private val settings: SettingsRepository,
    private val storage: ProjectStorage,
    private val time: TimeSource,
) {
    /** [progress] must be cheap and non-blocking: it is called from render/upload callbacks. */
    suspend fun run(project: VideoProject, progress: (String, Int) -> Unit): StageOutcome {
        val id = project.id
        val a = project.artifacts
        return when (project.stage) {
            PipelineStage.IDEA -> {
                if (a.idea == null) save(id) { it.copy(idea = TopicIdea(project.title, format = project.format)) }
                StageOutcome.Advance()
            }
            PipelineStage.RESEARCH -> {
                val brief = team.trendResearcher.brief(BriefRequest(a.idea ?: TopicIdea(project.title, format = project.format), project.language), id)
                save(id) { it.copy(brief = brief) }
                StageOutcome.Advance()
            }
            PipelineStage.SCRIPT -> {
                val brief = a.brief ?: ResearchBrief(a.idea ?: TopicIdea(project.title, format = project.format))
                val script = team.scriptwriter.write(
                    ScriptRequest(
                        title = a.idea?.title ?: project.title,
                        format = project.format,
                        language = project.language,
                        briefJson = TubeJson.encodeToString(ResearchBrief.serializer(), brief),
                        reviewerNotes = a.reviewerNotes,
                    ),
                    id,
                )
                // A new script invalidates everything produced from the old one.
                save(id) { it.copy(script = script, voice = null, visuals = null, edit = null, reviewerNotes = null) }
                projects.update(id) { it.copy(title = script.title) }
                StageOutcome.Advance()
            }
            PipelineStage.SCRIPT_APPROVAL, PipelineStage.PUBLISH_APPROVAL ->
                if (settings.current().requiresApproval(project.stage)) StageOutcome.AwaitApproval else StageOutcome.Advance()
            PipelineStage.VOICE -> {
                progress("Recording voiceover", -1)
                val voice = team.voice.produce(project)
                save(id) { it.copy(voice = voice) }
                StageOutcome.Advance()
            }
            PipelineStage.VISUALS -> {
                progress("Creating visuals", -1)
                val visuals = team.editor.prepareVisuals(project)
                save(id) { it.copy(visuals = visuals) }
                StageOutcome.Advance()
            }
            PipelineStage.EDIT -> {
                val edit = team.editor.edit(project) { pct -> progress("Rendering video", pct) }
                save(id) { it.copy(edit = edit) }
                StageOutcome.Advance()
            }
            PipelineStage.THUMBNAIL -> {
                val set = team.thumbnails.design(project)
                save(id) { it.copy(thumbnails = set) }
                StageOutcome.Advance()
            }
            PipelineStage.SEO -> {
                val seo = team.seo.optimize(project)
                save(id) { it.copy(seo = seo) }
                StageOutcome.Advance()
            }
            PipelineStage.COMPLIANCE -> {
                val fresh = projects.get(id) ?: project
                val report = team.compliance.review(fresh)
                save(id) { it.copy(compliance = report) }
                if (report.blocked) {
                    StageOutcome.Blocked("Compliance blocked publishing: " + report.issues.filter { it.severity.name == "BLOCKER" }.joinToString("; ") { it.detail })
                } else {
                    StageOutcome.Advance()
                }
            }
            PipelineStage.QA -> {
                val report = team.qa.check(projects.get(id) ?: project)
                save(id) { it.copy(qa = report) }
                if (report.passed) StageOutcome.Advance()
                else StageOutcome.Blocked("QA failed: " + report.checks.filter { it.blocking && !it.passed }.joinToString("; ") { "${it.name} (${it.detail})" })
            }
            PipelineStage.UPLOAD -> {
                val uploads = team.uploader.publish(projects.get(id) ?: project) { pct -> progress("Uploading", pct) }
                val main = uploads.firstOrNull()
                val publishAt = main?.publishAtIso?.let { TimeUtil.parseRfc3339(it) }
                project.calendarEntryId?.let { entryId ->
                    calendar.get(entryId)?.let { calendar.upsert(listOf(it.copy(status = CalendarStatus.PUBLISHED))) }
                }
                val now = time.now()
                val scheduled = publishAt != null && publishAt > now
                StageOutcome.Advance(
                    delayMs = (publishAt ?: now) - now + PipelineEngine.COMMENT_CHECK_AFTER_PUBLISH_MS,
                    status = if (scheduled) ProjectStatus.SCHEDULED else ProjectStatus.PUBLISHED,
                )
            }
            PipelineStage.COMMENT_MONITORING -> {
                team.community.processNewComments()
                StageOutcome.Advance(delayMs = PipelineEngine.ANALYTICS_AFTER_COMMENTS_MS, status = ProjectStatus.PUBLISHED)
            }
            PipelineStage.ANALYTICS -> {
                val finished = team.analytics.evaluateProject(project)
                if (finished) StageOutcome.Advance() else StageOutcome.Revisit(12 * TimeUtil.HOUR)
            }
            PipelineStage.MEMORY_UPDATE -> {
                if (!a.lessonsRecorded) team.analytics.recordProjectLessons(project)
                storage.pruneIntermediates(id)
                StageOutcome.Advance()
            }
            PipelineStage.COMPLETE -> StageOutcome.Advance()
        }
    }

    private suspend fun save(id: String, transform: (ProjectArtifacts) -> ProjectArtifacts) {
        projects.update(id) { it.copy(artifacts = transform(it.artifacts)) }
    }
}
