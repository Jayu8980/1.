package com.tubepilot.pipeline

import com.tubepilot.core.domain.AgentStatusRepository
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AgentState
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.core.model.SeoPackage
import com.tubepilot.core.model.TopicIdea
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoProject
import com.tubepilot.agents.SeoLimits
import com.tubepilot.media.ProjectStorage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-facing control of the pipeline: kill switch, approvals, manual projects, retries. Every
 * action is written to the audit log with the user as the actor.
 */
@Singleton
class PipelineController @Inject constructor(
    private val projects: ProjectRepository,
    private val scheduler: PipelineScheduler,
    private val settings: SettingsRepository,
    private val agentStatus: AgentStatusRepository,
    private val storage: ProjectStorage,
    private val audit: AuditLog,
) {
    /** Kill switch: stops all queued and running pipeline work immediately. */
    suspend fun setKillSwitch(enabled: Boolean) {
        settings.update { it.copy(killSwitch = enabled) }
        audit.record(USER, if (enabled) "killswitch.on" else "killswitch.off", if (enabled) "All agents paused" else "Agents resumed", level = AuditLevel.SECURITY)
        if (enabled) {
            scheduler.cancelAllPipelineWork()
            projects.inProgress().filter { it.status.isActive }.forEach { p -> projects.update(p.id) { it.copy(status = ProjectStatus.PAUSED) } }
            AgentRole.entries.forEach { agentStatus.set(it, AgentState.PAUSED, null, null) }
        } else {
            AgentRole.entries.forEach { agentStatus.set(it, AgentState.IDLE, null, null) }
            resumePaused()
        }
    }

    /** Clears the budget pause (after the user raised the cap or a new month started). */
    suspend fun resumeAfterBudget() {
        settings.update { it.copy(budgetPaused = false) }
        audit.record(USER, "budget.resumed", "Budget pause cleared", level = AuditLevel.INFO)
        resumePaused()
    }

    private suspend fun resumePaused() {
        val s = settings.current()
        if (s.killSwitch || s.budgetPaused) return
        projects.inProgress().filter { it.status == ProjectStatus.PAUSED }.forEach { p ->
            val restored = if (p.stage.ordinal > PipelineStage.UPLOAD.ordinal) ProjectStatus.PUBLISHED else ProjectStatus.QUEUED
            projects.update(p.id) { it.copy(status = restored) }
            scheduler.ensureScheduled(p.id, p.stage)
        }
    }

    /** On app start: make sure every active project has pending work (KEEP avoids duplicates). */
    suspend fun reconcile() {
        scheduler.schedulePeriodic()
        val s = settings.current()
        if (s.killSwitch || s.budgetPaused) return
        projects.inProgress().filter { PipelineEngine.isRunnable(it) }.forEach { scheduler.ensureScheduled(it.id, it.stage) }
    }

    suspend fun createManualProject(title: String, angle: String, format: VideoFormat, language: ContentLanguage): VideoProject {
        val project = projects.create(title.trim(), format, language, null, null)
        projects.update(project.id) { it.copy(artifacts = it.artifacts.copy(idea = TopicIdea(title.trim(), angle.trim(), format = format))) }
        audit.record(USER, "project.created", "Manual project '$title' (${format.label}, ${language.displayName})", project.id)
        scheduler.enqueue(project.id, PipelineStage.IDEA, append = false)
        return project
    }

    /** Approves the gate the project is waiting at and continues the pipeline. */
    suspend fun approve(projectId: String) {
        val p = projects.get(projectId) ?: return
        require(p.status == ProjectStatus.WAITING_APPROVAL && p.stage.isApprovalGate) { "Project is not waiting for approval" }
        val next = projects.update(projectId) { it.copy(stage = it.stage.next(), status = ProjectStatus.QUEUED, lastError = null) }
        audit.record(USER, "approval.approved", "Approved ${p.stage.label}", projectId, AuditLevel.SECURITY)
        scheduler.enqueue(projectId, next.stage, append = false)
    }

    /** Sends the project back to be redone with the reviewer's notes. */
    suspend fun requestChanges(projectId: String, notes: String) {
        val p = projects.get(projectId) ?: return
        val target = PipelineEngine.rewindTarget(p.stage)
        val updated = projects.update(projectId) {
            it.copy(stage = target, status = ProjectStatus.QUEUED, lastError = null, artifacts = it.artifacts.copy(reviewerNotes = notes.trim().ifBlank { null }))
        }
        audit.record(USER, "approval.changes_requested", "At ${p.stage.label}: $notes", projectId, AuditLevel.SECURITY)
        scheduler.enqueue(projectId, updated.stage, append = false)
    }

    /** Saves the user's edits to metadata and thumbnail choice at the publish gate. */
    suspend fun editPublishDetails(projectId: String, seo: SeoPackage, thumbnailIndex: Int) {
        projects.update(projectId) { p ->
            val thumbs = p.artifacts.thumbnails
            val durationSec = ((p.artifacts.voice?.durationMs ?: 0L) / 1000).toInt()
            p.copy(
                artifacts = p.artifacts.copy(
                    seo = SeoLimits.apply(seo, durationSec, p.format),
                    thumbnails = thumbs?.copy(selectedIndex = thumbnailIndex.coerceIn(0, (thumbs.variants.size - 1).coerceAtLeast(0))),
                ),
            )
        }
        audit.record(USER, "approval.edited", "Edited title/metadata/thumbnail", projectId, AuditLevel.INFO)
    }

    suspend fun reject(projectId: String, reason: String) {
        scheduler.cancel(projectId)
        projects.update(projectId) { it.copy(status = ProjectStatus.REJECTED, lastError = reason.ifBlank { "Rejected" }) }
        audit.record(USER, "approval.rejected", reason.ifBlank { "Rejected" }, projectId, AuditLevel.SECURITY)
    }

    /** Re-runs the current stage of a failed or blocked project (after the user fixed the cause). */
    suspend fun retry(projectId: String) {
        val p = projects.update(projectId) { it.copy(status = ProjectStatus.QUEUED, lastError = null, attempts = 0) }
        audit.record(USER, "project.retry", "Retry ${p.stage.label}", projectId)
        scheduler.enqueue(projectId, p.stage, append = false)
    }

    /** Overrides a compliance or QA block after the user reviewed it. Logged as a security event. */
    suspend fun overrideBlock(projectId: String, justification: String) {
        val p = projects.get(projectId) ?: return
        require(p.status == ProjectStatus.BLOCKED) { "Project is not blocked" }
        require(justification.isNotBlank()) { "A justification is required" }
        val next = projects.update(projectId) { it.copy(stage = it.stage.next(), status = ProjectStatus.QUEUED, lastError = null) }
        audit.record(USER, "block.override", "Overrode ${p.stage.label} block: $justification", projectId, AuditLevel.SECURITY)
        scheduler.enqueue(projectId, next.stage, append = false)
    }

    suspend fun delete(projectId: String) {
        scheduler.cancel(projectId)
        projects.delete(projectId)
        storage.delete(projectId)
        audit.record(USER, "project.deleted", projectId, projectId, AuditLevel.INFO)
    }

    private companion object {
        const val USER = "You"
    }
}
