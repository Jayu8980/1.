package com.autotube.agents.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.autotube.agents.core.Settings
import java.util.concurrent.TimeUnit

/** Autopilot: a periodic WorkManager job that wakes the agent team every N hours. */
object Scheduler {
    private const val PERIODIC = "autotube_autopilot"
    private const val NOW = "autotube_run_now"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    fun apply(context: Context) {
        val s = Settings(context)
        val wm = WorkManager.getInstance(context)
        if (!s.autopilot) { wm.cancelUniqueWork(PERIODIC); return }
        val req = PeriodicWorkRequestBuilder<AgentWorker>(s.cycleHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<AgentWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }
}
