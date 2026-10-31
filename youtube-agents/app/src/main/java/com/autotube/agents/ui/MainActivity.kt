package com.autotube.agents.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.autotube.agents.R
import com.autotube.agents.core.ActivityLog
import com.autotube.agents.core.Blackboard
import com.autotube.agents.core.Orchestrator
import com.autotube.agents.core.Settings
import com.autotube.agents.work.Scheduler

/** Dashboard: status, autopilot switch, live activity log and the agents' conversation. */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var log: ActivityLog
    private lateinit var memory: Blackboard
    private val handler = Handler(Looper.getMainLooper())
    private var tab = 0

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this); log = ActivityLog(this); memory = Blackboard(this)

        val autopilot = findViewById<Switch>(R.id.autopilot)
        autopilot.isChecked = settings.autopilot
        autopilot.setOnCheckedChangeListener { _, on ->
            settings.autopilot = on
            Scheduler.apply(this)
            log.info("System", if (on) "Autopilot ON — cycles every ${settings.cycleHours}h." else "Autopilot OFF.")
            refresh()
        }
        findViewById<Button>(R.id.runNow).setOnClickListener {
            Scheduler.runNow(this)
            log.info("System", "Manual cycle requested.")
            refresh()
        }
        findViewById<Button>(R.id.openSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.tabActivity).setOnClickListener { tab = 0; refresh() }
        findViewById<Button>(R.id.tabBus).setOnClickListener { tab = 1; refresh() }
        findViewById<Button>(R.id.tabVideos).setOnClickListener { tab = 2; refresh() }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun refresh() {
        val ready = settings.aiConfigured
        findViewById<TextView>(R.id.statusTitle).text = when {
            !ready -> "Setup needed"
            Orchestrator.isRunning -> "Agents working…"
            settings.autopilot -> "Autopilot is running your channel"
            else -> "Agents ready"
        }
        val lines = mutableListOf<String>()
        lines += "AI brain: " + if (ready) "Claude (${settings.model})" else "add an Anthropic API key in Settings"
        lines += "YouTube: " + if (settings.youtubeConnected) "connected${settings.channelTitle.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}" else "not connected (videos saved on phone)"
        lines += "Today: ${memory.producedToday()}/${settings.uploadsPerDay} videos · every ${settings.cycleHours}h · ${settings.videoFormat} · ${settings.privacy}" + if (settings.dryRun) " · DRY RUN" else ""
        lines += "Last cycle: ${memory.getString("last_cycle", "never")}"
        if (Orchestrator.isRunning) lines += "Now: ${Orchestrator.currentStatus}"
        findViewById<TextView>(R.id.statusBody).text = lines.joinToString("\n")

        findViewById<TextView>(R.id.logText).text = when (tab) {
            0 -> log.readActivity().ifBlank { "No activity yet. Configure Settings, then tap \"Run cycle now\"." }
            1 -> log.readBus().ifBlank { "Agents haven't talked yet." }
            else -> memory.published().reversed().joinToString("\n\n") {
                val where = if (it.optBoolean("uploaded")) it.optString("url") else "saved: ${it.optString("local_path")}"
                "${it.optString("date")}  ${it.optString("title")}\n  $where"
            }.ifBlank { "No videos yet." }
        }
        val active = 0xFFE53935.toInt(); val idle = 0xFF5F6368.toInt()
        findViewById<Button>(R.id.tabActivity).setTextColor(if (tab == 0) active else idle)
        findViewById<Button>(R.id.tabBus).setTextColor(if (tab == 1) active else idle)
        findViewById<Button>(R.id.tabVideos).setTextColor(if (tab == 2) active else idle)
    }
}
