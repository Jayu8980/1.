package com.tubepilot.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.model.PipelineStage
import com.tubepilot.pipeline.work.AnalyticsWorker
import com.tubepilot.pipeline.work.CommentsWorker
import com.tubepilot.pipeline.work.OrchestratorWorker
import com.tubepilot.pipeline.work.StageWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All long-running and scheduled work goes through WorkManager, which persists requests in its own
 * database: pending stages survive process death and reboots and resume with their saved state.
 */
@Singleton
class PipelineScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val wm get() = WorkManager.getInstance(context)

    private suspend fun constraints(stage: PipelineStage): Constraints {
        val s = settings.current()
        val network = when {
            PipelineEngine.needsUnmetered(stage) && !s.allowMeteredUploads -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(network)
            .setRequiresStorageNotLow(stage == PipelineStage.EDIT || stage == PipelineStage.VISUALS || stage == PipelineStage.VOICE)
            .setRequiresBatteryNotLow(PipelineEngine.isHeavy(stage))
            .build()
    }

    /**
     * Queues the next run of [projectId] at [stage]. The project's work is a single unique chain;
     * [append] places it after the currently running worker (used from inside a worker).
     */
    suspend fun enqueue(projectId: String, stage: PipelineStage, delayMs: Long = 0, append: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<StageWorker>()
            .setInputData(workDataOf(StageWorker.KEY_PROJECT to projectId))
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(constraints(stage))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_PIPELINE)
            .addTag(projectTag(projectId))
            .build()
        wm.enqueueUniqueWork(uniqueName(projectId), if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }

    /** Re-queues a project only if no work is pending for it (used on app start and resume). */
    suspend fun ensureScheduled(projectId: String, stage: PipelineStage) {
        val request = OneTimeWorkRequestBuilder<StageWorker>()
            .setInputData(workDataOf(StageWorker.KEY_PROJECT to projectId))
            .setConstraints(constraints(stage))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_PIPELINE)
            .addTag(projectTag(projectId))
            .build()
        wm.enqueueUniqueWork(uniqueName(projectId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(projectId: String) {
        wm.cancelUniqueWork(uniqueName(projectId))
    }

    fun cancelAllPipelineWork() {
        wm.cancelAllWorkByTag(TAG_PIPELINE)
    }

    fun schedulePeriodic() {
        val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            OrchestratorWorker.NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OrchestratorWorker>(12, TimeUnit.HOURS).setConstraints(connected).addTag(TAG_PERIODIC).build(),
        )
        wm.enqueueUniquePeriodicWork(
            CommentsWorker.NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CommentsWorker>(6, TimeUnit.HOURS).setConstraints(connected).addTag(TAG_PERIODIC).build(),
        )
        wm.enqueueUniquePeriodicWork(
            AnalyticsWorker.NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AnalyticsWorker>(24, TimeUnit.HOURS).setConstraints(connected).addTag(TAG_PERIODIC).build(),
        )
    }

    /** "Run now" buttons on the dashboard. */
    fun runNow(kind: PeriodicKind) {
        val builder = when (kind) {
            PeriodicKind.PLAN -> OneTimeWorkRequestBuilder<OrchestratorWorker>()
            PeriodicKind.COMMENTS -> OneTimeWorkRequestBuilder<CommentsWorker>()
            PeriodicKind.ANALYTICS -> OneTimeWorkRequestBuilder<AnalyticsWorker>().setInputData(workDataOf(AnalyticsWorker.KEY_FORCE to true))
        }
        val request = builder
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_PERIODIC)
            .build()
        wm.enqueueUniqueWork("run-now-${kind.name}", ExistingWorkPolicy.KEEP, request)
    }

    /** Live running state per project, for the dashboard. */
    fun observeRunning(): Flow<Set<String>> = wm.getWorkInfosByTagFlow(TAG_PIPELINE).map { infos ->
        infos.filter { it.state == WorkInfo.State.RUNNING }.flatMap { info -> info.tags.filter { it.startsWith("project:") }.map { it.removePrefix("project:") } }.toSet()
    }

    enum class PeriodicKind { PLAN, COMMENTS, ANALYTICS }

    companion object {
        const val TAG_PIPELINE = "tubepilot-pipeline"
        const val TAG_PERIODIC = "tubepilot-periodic"
        fun uniqueName(projectId: String) = "project-$projectId"
        fun projectTag(projectId: String) = "project:$projectId"
    }
}
