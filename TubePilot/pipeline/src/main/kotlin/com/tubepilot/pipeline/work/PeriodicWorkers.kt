package com.tubepilot.pipeline.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tubepilot.agents.AgentTeam
import com.tubepilot.agents.contracts.PlanRequest
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ReportRepository
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.error.AuthRequiredException
import com.tubepilot.core.error.TubePilotException
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.pipeline.Notifier
import com.tubepilot.pipeline.PipelineScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant

/** Shared guard: periodic agents run only after onboarding and while not paused. */
private suspend fun SettingsRepository.canRunAgents(): Boolean = current().let { it.onboardingComplete && !it.killSwitch && !it.budgetPaused }

private suspend fun periodic(audit: AuditLog, name: String, block: suspend () -> Unit): androidx.work.ListenableWorker.Result = try {
    block()
    androidx.work.ListenableWorker.Result.success()
} catch (e: CancellationException) {
    throw e
} catch (e: AuthRequiredException) {
    audit.record(name, "periodic.auth", e.message.orEmpty(), level = AuditLevel.WARNING)
    androidx.work.ListenableWorker.Result.success()
} catch (e: TubePilotException) {
    audit.record(name, "periodic.error", e.message.orEmpty(), level = AuditLevel.WARNING)
    if (e.retryable) androidx.work.ListenableWorker.Result.retry() else androidx.work.ListenableWorker.Result.success()
} catch (e: Exception) {
    audit.record(name, "periodic.error", e.message ?: e::class.java.simpleName, level = AuditLevel.ERROR)
    androidx.work.ListenableWorker.Result.success()
}

/** Channel Manager heartbeat: keeps this week and next week planned and starts due productions. */
@HiltWorker
class OrchestratorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val team: AgentTeam,
    private val settings: SettingsRepository,
    private val memory: MemoryRepository,
    private val scheduler: PipelineScheduler,
    private val audit: AuditLog,
    private val time: TimeSource,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.canRunAgents()) return Result.success()
        return periodic(audit, "Channel Manager") {
            val zone = TimeUtil.zoneOrDefault(memory.profile().timezone)
            val now = time.now()
            val thisWeek = TimeUtil.startOfWeekMs(now, zone)
            if (!team.orchestrator.hasPlan(thisWeek)) team.orchestrator.planWeek(PlanRequest(thisWeek))
            // Plan next week from Thursday so productions with a 4-day lead time start on time.
            val dow = Instant.ofEpochMilli(now).atZone(zone).dayOfWeek
            val nextWeek = thisWeek + 7 * TimeUtil.DAY
            if (dow >= DayOfWeek.THURSDAY && !team.orchestrator.hasPlan(nextWeek)) team.orchestrator.planWeek(PlanRequest(nextWeek))
            team.orchestrator.startDueProductions().forEach { scheduler.enqueue(it.id, it.stage) }
        }
    }

    companion object {
        const val NAME = "orchestrator-periodic"
    }
}

/** Community Manager heartbeat: new comments every few hours. */
@HiltWorker
class CommentsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val team: AgentTeam,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val audit: AuditLog,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.canRunAgents()) return Result.success()
        return periodic(audit, "Community Manager") {
            val summary = team.community.processNewComments()
            if (summary.flagged > 0) notifier.flaggedComments(summary.flagged)
        }
    }

    companion object {
        const val NAME = "comments-periodic"
    }
}

/** Analytics heartbeat: weekly report and lessons once every 7 days (or on demand). */
@HiltWorker
class AnalyticsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val team: AgentTeam,
    private val settings: SettingsRepository,
    private val reports: ReportRepository,
    private val notifier: Notifier,
    private val audit: AuditLog,
    private val time: TimeSource,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.canRunAgents()) return Result.success()
        val force = inputData.getBoolean(KEY_FORCE, false)
        return periodic(audit, "Analytics") {
            val last = reports.observeLatest().first()
            if (force || last == null || time.now() - last.createdAt >= 6 * TimeUtil.DAY + 12 * TimeUtil.HOUR) {
                val report = team.analytics.weeklyReport()
                notifier.weeklyReport(report.headline)
            }
        }
    }

    companion object {
        const val NAME = "analytics-periodic"
        const val KEY_FORCE = "force"
    }
}
