package com.tubepilot.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tubepilot.core.di.ApplicationScope
import com.tubepilot.pipeline.PipelineController
import com.tubepilot.skills.SkillRegistry
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TubePilotApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var controller: PipelineController
    @Inject lateinit var skills: SkillRegistry
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // Startup work must never crash the process: a bad skill or a transient DB error is logged instead.
            runCatching { skills.reload() }.onFailure { Log.e(TAG, "Skill registry failed to load", it) }
            // Re-arm periodic agents and make sure every active project has pending work.
            runCatching { controller.reconcile() }.onFailure { Log.e(TAG, "Pipeline reconcile failed", it) }
        }
    }

    private companion object {
        const val TAG = "TubePilot"
    }
}
