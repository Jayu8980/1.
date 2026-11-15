package com.tubepilot.pipeline

import com.tubepilot.agents.core.RetryPolicy
import com.tubepilot.core.error.AgentsPausedException
import com.tubepilot.core.error.AuthRequiredException
import com.tubepilot.core.error.BudgetExceededException
import com.tubepilot.core.error.ContractViolationException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.error.QuotaExhaustedException
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.model.ContentLanguage
import com.tubepilot.core.model.OperatingMode
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.core.model.ProjectArtifacts
import com.tubepilot.core.model.ProjectStatus
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoProject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineEngineTest {
    private fun project(stage: PipelineStage, status: ProjectStatus = ProjectStatus.RUNNING) =
        VideoProject("p", "t", VideoFormat.SHORT, ContentLanguage.ENGLISH, stage, status, ProjectArtifacts(), null, null, 0, 0)

    @Test
    fun `stages run in the documented order`() {
        val expected = listOf(
            "IDEA", "RESEARCH", "SCRIPT", "SCRIPT_APPROVAL", "VOICE", "VISUALS", "EDIT", "THUMBNAIL", "SEO", "COMPLIANCE", "QA",
            "PUBLISH_APPROVAL", "UPLOAD", "COMMENT_MONITORING", "ANALYTICS", "MEMORY_UPDATE", "COMPLETE",
        )
        assertEquals(expected, PipelineStage.entries.map { it.name })
        var stage = PipelineStage.IDEA
        val walked = mutableListOf(stage)
        while (stage != PipelineStage.COMPLETE) { stage = stage.next(); walked += stage }
        assertEquals(PipelineStage.entries.toList(), walked.toList())
    }

    @Test
    fun `advance moves to the next stage and queues it`() {
        val t = PipelineEngine.transition(project(PipelineStage.SCRIPT), StageOutcome.Advance())
        assertEquals(PipelineStage.SCRIPT_APPROVAL, t.stage)
        assertEquals(ProjectStatus.QUEUED, t.status)
        assertEquals(0L, t.nextRunDelayMs)
    }

    @Test
    fun `upload sets scheduled status and delays comment monitoring`() {
        val t = PipelineEngine.transition(project(PipelineStage.UPLOAD), StageOutcome.Advance(delayMs = 5_000, status = ProjectStatus.SCHEDULED))
        assertEquals(PipelineStage.COMMENT_MONITORING, t.stage)
        assertEquals(ProjectStatus.SCHEDULED, t.status)
        assertEquals(5_000L, t.nextRunDelayMs)
        // Post-upload stages keep their published status while running.
        assertEquals(ProjectStatus.PUBLISHED, PipelineEngine.runningStatus(project(PipelineStage.ANALYTICS, ProjectStatus.PUBLISHED)))
        assertEquals(ProjectStatus.RUNNING, PipelineEngine.runningStatus(project(PipelineStage.EDIT, ProjectStatus.QUEUED)))
    }

    @Test
    fun `last stage completes the project without scheduling more work`() {
        val t = PipelineEngine.transition(project(PipelineStage.MEMORY_UPDATE, ProjectStatus.PUBLISHED), StageOutcome.Advance())
        assertEquals(PipelineStage.COMPLETE, t.stage)
        assertEquals(ProjectStatus.DONE, t.status)
        assertNull(t.nextRunDelayMs)
    }

    @Test
    fun `approval gates wait for a human and blocks stop the pipeline`() {
        val wait = PipelineEngine.transition(project(PipelineStage.PUBLISH_APPROVAL), StageOutcome.AwaitApproval)
        assertEquals(PipelineStage.PUBLISH_APPROVAL, wait.stage)
        assertEquals(ProjectStatus.WAITING_APPROVAL, wait.status)
        assertNull(wait.nextRunDelayMs)

        val blocked = PipelineEngine.transition(project(PipelineStage.COMPLIANCE), StageOutcome.Blocked("copyright"))
        assertEquals(ProjectStatus.BLOCKED, blocked.status)
        assertEquals("copyright", blocked.error)
        assertNull(blocked.nextRunDelayMs)
    }

    @Test
    fun `revisit re-runs the same stage later`() {
        val t = PipelineEngine.transition(project(PipelineStage.ANALYTICS, ProjectStatus.PUBLISHED), StageOutcome.Revisit(10))
        assertEquals(PipelineStage.ANALYTICS, t.stage)
        assertEquals(ProjectStatus.PUBLISHED, t.status)
        assertEquals(60_000L, t.nextRunDelayMs) // never re-polls faster than once a minute
    }

    @Test
    fun `approval mode gates both stages by default, auto mode gates none`() {
        val approval = AppSettings()
        assertEquals(OperatingMode.APPROVAL, approval.mode)
        assertTrue(approval.requiresApproval(PipelineStage.SCRIPT_APPROVAL))
        assertTrue(approval.requiresApproval(PipelineStage.PUBLISH_APPROVAL))
        assertFalse(approval.copy(approveScripts = false).requiresApproval(PipelineStage.SCRIPT_APPROVAL))
        val auto = AppSettings(mode = OperatingMode.AUTO)
        assertFalse(auto.requiresApproval(PipelineStage.SCRIPT_APPROVAL))
        assertFalse(auto.requiresApproval(PipelineStage.PUBLISH_APPROVAL))
    }

    @Test
    fun `errors map to pause, wait, retry, block or fail`() {
        val policy = RetryPolicy(maxAttempts = 3)
        assertIs<ErrorAction.Pause>(PipelineEngine.onError(AgentsPausedException(), 1, policy))
        assertIs<ErrorAction.Pause>(PipelineEngine.onError(BudgetExceededException(30.0, 25.0), 1, policy))
        val wait = PipelineEngine.onError(QuotaExhaustedException("videos_insert", 1_000_000), 1, policy)
        assertIs<ErrorAction.WaitUntil>(wait)
        assertEquals(1_000_000 + 5 * 60_000L, wait.atMs)
        assertIs<ErrorAction.Block>(PipelineEngine.onError(AuthRequiredException(), 1, policy))
        assertIs<ErrorAction.Retry>(PipelineEngine.onError(ProviderException("x", "503", 503, retryable = true), 1, policy))
        assertIs<ErrorAction.Fail>(PipelineEngine.onError(ProviderException("x", "503", 503, retryable = true), 3, policy))
        assertIs<ErrorAction.Fail>(PipelineEngine.onError(ProviderException("x", "400", 400, retryable = false), 1, policy))
        assertIs<ErrorAction.Retry>(PipelineEngine.onError(ContractViolationException("bad json", ""), 1, policy))
        assertIs<ErrorAction.Retry>(PipelineEngine.onError(java.io.IOException("reset"), 2, policy))
        assertIs<ErrorAction.Fail>(PipelineEngine.onError(IllegalStateException("script missing"), 1, policy))
    }

    @Test
    fun `request changes rewinds to the right stage`() {
        assertEquals(PipelineStage.SCRIPT, PipelineEngine.rewindTarget(PipelineStage.SCRIPT_APPROVAL))
        assertEquals(PipelineStage.SEO, PipelineEngine.rewindTarget(PipelineStage.PUBLISH_APPROVAL))
    }

    @Test
    fun `only active projects are runnable and heavy stages are flagged`() {
        assertTrue(PipelineEngine.isRunnable(project(PipelineStage.VOICE, ProjectStatus.QUEUED)))
        assertFalse(PipelineEngine.isRunnable(project(PipelineStage.PUBLISH_APPROVAL, ProjectStatus.WAITING_APPROVAL)))
        assertFalse(PipelineEngine.isRunnable(project(PipelineStage.QA, ProjectStatus.BLOCKED)))
        assertFalse(PipelineEngine.isRunnable(project(PipelineStage.EDIT, ProjectStatus.PAUSED)))
        assertTrue(PipelineEngine.isHeavy(PipelineStage.EDIT))
        assertFalse(PipelineEngine.isHeavy(PipelineStage.SEO))
        assertTrue(PipelineEngine.needsUnmetered(PipelineStage.UPLOAD))
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val policy = RetryPolicy(initialBackoffMs = 1_000, maxBackoffMs = 10_000)
        assertEquals(1_000, PipelineEngine.backoffMs(policy, 0))
        assertEquals(4_000, PipelineEngine.backoffMs(policy, 2))
        assertEquals(10_000, PipelineEngine.backoffMs(policy, 10))
    }
}
