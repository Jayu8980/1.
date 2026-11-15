package com.tubepilot.pipeline.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.tubepilot.agents.AgentTeam
import com.tubepilot.agents.core.RetryPolicy
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.core.model.VideoProject
import com.tubepilot.pipeline.ErrorAction
import com.tubepilot.pipeline.Notifier
import com.tubepilot.pipeline.PipelineEngine
import com.tubepilot.pipeline.PipelineScheduler
import com.tubepilot.pipeline.StageExecutor
import com.tubepilot.pipeline.StageOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Executes the current stage of one project, saves the result, and queues the next stage. All
 * state lives in Room, so a worker killed mid-way (reboot, low memory) simply re-runs the stage;
 * stages are written to be idempotent (skip finished renders, resume upload sessions).
 */
@HiltWorker
class StageWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val executor: StageExecutor,
    private val projects: ProjectRepository,
    private val settings: SettingsRepository,
    private val scheduler: PipelineScheduler,
    private val notifier: Notifier,
    private val audit: AuditLog,
    private val team: AgentTeam,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT) ?: return Result.failure()
        val project = projects.get(projectId) ?: return Result.success()
        val s = settings.current()
        if (s.killSwitch || s.budgetPaused) {
            projects.update(projectId) { it.copy(status = ProjectStatus.PAUSED) }
            return Result.success() // resumed by PipelineController when un-paused
        }
        if (!PipelineEngine.isRunnable(project)) return Result.success()

        val notificationId = projectId.hashCode()
        if (PipelineEngine.isHeavy(project.stage)) {
            runCatching { setForeground(foregroundInfo(notificationId, project, "Starting ${project.stage.label}", -1)) }
                .onFailure { audit.record("Pipeline", "foreground.denied", "Running ${project.stage.label} without a foreground service: ${it.message}", projectId, AuditLevel.DEBUG) }
        }
        val running = projects.update(projectId) { it.copy(status = PipelineEngine.runningStatus(it)) }
        audit.record("Pipeline", "stage.start", "${running.stage.label} (attempt ${runAttemptCount + 1})", projectId)

        return try {
            var lastPct = -2
            val outcome = executor.run(running) { message, pct ->
                if (pct == lastPct) return@run
                lastPct = pct
                notifier.updateWork(notificationId, notifier.workNotification(running.title, message + if (pct >= 0) " · $pct%" else "", pct))
            }
            val fresh = projects.get(projectId) ?: return Result.success()
            val transition = PipelineEngine.transition(fresh, outcome)
            projects.update(projectId) { it.copy(stage = transition.stage, status = transition.status, lastError = transition.error, attempts = 0) }
            audit.record("Pipeline", "stage.done", "${fresh.stage.label} → ${transition.stage.label} (${transition.status})", projectId)
            transition.nextRunDelayMs?.let { scheduler.enqueue(projectId, transition.stage, it) }
            when (outcome) {
                StageOutcome.AwaitApproval -> notifier.approvalNeeded(projectId, fresh.title, if (fresh.stage == PipelineStage.SCRIPT_APPROVAL) "script" else "video")
                is StageOutcome.Blocked -> notifier.problem(projectId, "Blocked: ${fresh.title}", outcome.reason)
                else -> if (fresh.stage == PipelineStage.UPLOAD) notifier.published(projectId, fresh.title, "Status: ${transition.status.name.lowercase()}")
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handleError(running, e)
        }
    }

    private suspend fun handleError(project: VideoProject, e: Throwable): Result {
        val policy: RetryPolicy = project.stage.owner?.let { team.byRole(it).retryPolicy } ?: RetryPolicy()
        val attempt = runAttemptCount + 1
        val message = e.message ?: e::class.java.simpleName
        audit.record("Pipeline", "stage.error", "${project.stage.label} attempt $attempt: $message", project.id, AuditLevel.WARNING)
        return when (val action = PipelineEngine.onError(e, attempt, policy)) {
            ErrorAction.Pause -> {
                projects.update(project.id) { it.copy(status = ProjectStatus.PAUSED, lastError = message) }
                notifier.problem(project.id, "Agents paused", message)
                Result.success()
            }
            is ErrorAction.WaitUntil -> {
                projects.update(project.id) { it.copy(status = ProjectStatus.QUEUED, lastError = action.reason) }
                scheduler.enqueue(project.id, project.stage, action.atMs - System.currentTimeMillis())
                Result.success()
            }
            ErrorAction.Retry -> {
                projects.update(project.id) { it.copy(status = ProjectStatus.QUEUED, lastError = "Retrying: $message", attempts = attempt) }
                Result.retry()
            }
            is ErrorAction.Block -> {
                projects.update(project.id) { it.copy(status = ProjectStatus.BLOCKED, lastError = action.reason) }
                notifier.problem(project.id, "Needs attention: ${project.title}", action.reason)
                Result.success()
            }
            is ErrorAction.Fail -> {
                projects.update(project.id) { it.copy(status = ProjectStatus.FAILED, lastError = action.reason, attempts = attempt) }
                notifier.problem(project.id, "Failed: ${project.title}", "${project.stage.label}: ${action.reason}")
                Result.failure()
            }
        }
    }

    private fun foregroundInfo(id: Int, project: VideoProject, text: String, progress: Int): ForegroundInfo {
        val notification = notifier.workNotification(project.title, text, progress)
        return when {
            Build.VERSION.SDK_INT >= 35 && project.stage != PipelineStage.UPLOAD ->
                ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(id, notification)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val project = inputData.getString(KEY_PROJECT)?.let { projects.get(it) }
            ?: return ForegroundInfo(1, notifier.workNotification("TubePilot", "Working", -1))
        return foregroundInfo(project.id.hashCode(), project, project.stage.label, -1)
    }

    companion object {
        const val KEY_PROJECT = "projectId"
    }
}
