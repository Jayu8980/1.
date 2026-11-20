package com.tradelens.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tradelens.app.work.Schedulers
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TradeLensApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_ALERTS, "Price & indicator alerts", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_FILLS, "Order fills & approvals", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_RISK, "Risk breaches & kill switch", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_REPORTS, "Market briefs & reports", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
        Schedulers.scheduleAll(this)
    }

    companion object {
        const val CH_ALERTS = "alerts"
        const val CH_FILLS = "fills"
        const val CH_RISK = "risk"
        const val CH_REPORTS = "reports"
    }
}
