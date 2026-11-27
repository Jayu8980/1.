package com.aitrader

import android.app.Application
import com.aitrader.work.Notifier
import com.aitrader.work.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AiTraderApp : Application() {
    @Inject lateinit var notifier: Notifier

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        SyncWorker.schedule(this)
    }
}
