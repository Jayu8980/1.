package com.tubepilot.pipeline

import com.tubepilot.agents.core.RetryPolicy
import com.tubepilot.core.error.AgentsPausedException
import com.tubepilot.core.error.AuthRequiredException
import com.tubepilot.core.error.BudgetExceededException
import com.tubepilot.core.error.MissingConfigurationException
import com.tubepilot.core.error.PolicyBlockedException
import com.tubepilot.core.error.QuotaExhaustedException
import com.tubepilot.core.error.TubePilotException
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.core.model.VideoProject

/** What a stage run produced. */
sealed interface StageOutcome {
    /** Move to the next stage, optionally after [delayMs], optionally setting [status]. */
    data class Advance(val delayMs: Long = 0, val status: ProjectStatus? = null) : StageOutcome
    /** Stop at an approval gate until the user decides. */
    data object AwaitApproval : StageOutcome
    /** A reviewer (compliance/QA) stopped the video; a human must intervene. */
    data class Blocked(val reason: String) : StageOutcome
    /** Run this same stage again later (e.g. a title test waiting for data). */
    data class Revisit(val delayMs: Long) : StageOutcome
}

data class Transition(
    val stage: PipelineStage,
    val status: ProjectStatus,
    /** Delay before the next run, or null when nothing should be scheduled. */
    val nextRunDelayMs: Long?,
    val error: String? = null,
)

/** How to react to an exception thrown by a stage. */
sealed interface ErrorAction {
    data object Pause : ErrorAction
    data class WaitUntil(val atMs: Long, val reason: String) : ErrorAction
    data object Retry : ErrorAction
    data class Block(val reason: String) : ErrorAction
    data class Fail(val reason: String) : ErrorAction
}

/**
 * The pipeline state machine, free of Android and WorkManager so it is fully unit-testable.
 * Idea → Research → Script → (approval) → Voice → Visuals → Edit → Thumbnail → SEO → Compliance →
 * QA → (approval) → Upload → Comment monitoring → Analytics → Memory update → Complete.
 */
object PipelineEngine {
    const val COMMENT_CHECK_AFTER_PUBLISH_MS = 24 * 60 * 60_000L
    const val ANALYTICS_AFTER_COMMENTS_MS = 6 * 24 * 60 * 60_000L

    fun transition(project: VideoProject, outcome: StageOutcome): Transition = when (outcome) {
        is StageOutcome.Advance -> {
            val next = project.stage.next()
            if (next == PipelineStage.COMPLETE) {
                Transition(PipelineStage.COMPLETE, ProjectStatus.DONE, null)
            } else {
                val status = outcome.status ?: when {
                    project.stage.ordinal >= PipelineStage.UPLOAD.ordinal -> project.status
                    else -> ProjectStatus.QUEUED
                }
                Transition(next, status, outcome.delayMs.coerceAtLeast(0))
            }
        }
        StageOutcome.AwaitApproval -> Transition(project.stage, ProjectStatus.WAITING_APPROVAL, null)
        is StageOutcome.Blocked -> Transition(project.stage, ProjectStatus.BLOCKED, null, outcome.reason)
        is StageOutcome.Revisit -> Transition(project.stage, project.status, outcome.delayMs.coerceAtLeast(60_000))
    }

    /** Status to show while a stage is executing. Post-upload stages keep SCHEDULED/PUBLISHED. */
    fun runningStatus(project: VideoProject): ProjectStatus =
        if (project.stage.ordinal > PipelineStage.UPLOAD.ordinal) project.status else ProjectStatus.RUNNING

    /** Projects in these states must not be executed by a worker that fires. */
    fun isRunnable(project: VideoProject): Boolean = project.status in setOf(
        ProjectStatus.QUEUED, ProjectStatus.RUNNING, ProjectStatus.SCHEDULED, ProjectStatus.PUBLISHED,
    ) && project.stage != PipelineStage.COMPLETE

    fun onError(error: Throwable, attempt: Int, policy: RetryPolicy): ErrorAction = when (error) {
        is AgentsPausedException, is BudgetExceededException -> ErrorAction.Pause
        is QuotaExhaustedException -> ErrorAction.WaitUntil(error.resetAtMs + 5 * 60_000, "YouTube quota '${error.bucket}' exhausted; continuing after reset")
        is AuthRequiredException -> ErrorAction.Block(error.message ?: "Reconnect YouTube")
        is MissingConfigurationException -> ErrorAction.Block(error.message ?: "Configuration missing")
        is PolicyBlockedException -> ErrorAction.Block(error.message ?: "Blocked by policy")
        is TubePilotException -> if (error.retryable && attempt < policy.maxAttempts) ErrorAction.Retry else ErrorAction.Fail(error.message ?: "failed")
        is java.io.IOException -> if (attempt < policy.maxAttempts) ErrorAction.Retry else ErrorAction.Fail(error.message ?: "I/O error")
        is IllegalArgumentException, is IllegalStateException -> ErrorAction.Fail(error.message ?: "invalid state")
        else -> if (attempt < policy.maxAttempts) ErrorAction.Retry else ErrorAction.Fail(error.message ?: error::class.java.simpleName)
    }

    /** Exponential backoff for display and for delayed re-enqueue. */
    fun backoffMs(policy: RetryPolicy, attempt: Int): Long =
        (policy.initialBackoffMs * (1L shl attempt.coerceIn(0, 16))).coerceAtMost(policy.maxBackoffMs)

    /** Where "request changes" at a gate sends the project back to. */
    fun rewindTarget(gate: PipelineStage): PipelineStage = when (gate) {
        PipelineStage.SCRIPT_APPROVAL -> PipelineStage.SCRIPT
        PipelineStage.PUBLISH_APPROVAL -> PipelineStage.SEO
        else -> gate
    }

    /** Stages that render or upload and should run as a foreground service. */
    fun isHeavy(stage: PipelineStage) = stage in setOf(PipelineStage.VOICE, PipelineStage.VISUALS, PipelineStage.EDIT, PipelineStage.UPLOAD)

    /** Stages that need an unmetered network unless the user allows mobile data. */
    fun needsUnmetered(stage: PipelineStage) = stage == PipelineStage.UPLOAD
}
