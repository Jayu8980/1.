package com.tradelens.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object Schedulers {
    fun scheduleAll(ctx: Context, alertMinutes: Int = 15) {
        val wm = WorkManager.getInstance(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        // Android's minimum periodic interval is 15 minutes; crypto alerts therefore check every 15 min, 24/7.
        wm.enqueueUniquePeriodicWork("alerts", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AlertWorker>(alertMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).setConstraints(net).build())
        wm.enqueueUniquePeriodicWork("instrument-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<InstrumentSyncWorker>(1, TimeUnit.DAYS).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()).build())
        wm.enqueueUniquePeriodicWork("briefs", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BriefWorker>(1, TimeUnit.HOURS).setConstraints(net).build())
    }
}
