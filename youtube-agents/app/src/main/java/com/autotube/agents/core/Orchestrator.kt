package com.autotube.agents.core

import android.content.Context
import com.autotube.agents.agents.AnalystAgent
import com.autotube.agents.agents.ChiefAgent
import com.autotube.agents.agents.CommunityAgent
import com.autotube.agents.agents.ProducerAgent
import com.autotube.agents.agents.PublisherAgent
import com.autotube.agents.agents.ReviewerAgent
import com.autotube.agents.agents.ScriptWriterAgent
import com.autotube.agents.agents.TrendScoutAgent
import com.autotube.agents.llm.ClaudeClient
import com.autotube.agents.youtube.GoogleAuth
import com.autotube.agents.youtube.YouTubeApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Assembles the agent team and runs one full autonomous cycle. */
object Orchestrator {
    private val mutex = Mutex()

    @Volatile var currentStatus: String = "Idle"; private set
    val isRunning: Boolean get() = mutex.isLocked

    private var authInstance: GoogleAuth? = null
    fun auth(context: Context): GoogleAuth =
        authInstance ?: GoogleAuth(Settings(context.applicationContext)).also { authInstance = it }

    suspend fun runCycle(context: Context, onProgress: (String) -> Unit = {}): Boolean {
        if (!mutex.tryLock()) return false
        try {
            val app = context.applicationContext
            val settings = Settings(app)
            val log = ActivityLog(app)
            val memory = Blackboard(app)
            if (!settings.aiConfigured) {
                log.error(Agents.SYSTEM, "Add your Anthropic API key in Settings to start the agents.")
                return true
            }
            val yt = if (settings.youtubeConnected) YouTubeApi(auth(app)) else null
            val ctx = AgentContext(app, settings, memory, log, ClaudeClient(settings), yt) { p ->
                currentStatus = p; onProgress(p)
            }
            val team: List<Agent> = listOf(
                ChiefAgent(), AnalystAgent(), TrendScoutAgent(), ScriptWriterAgent(),
                ReviewerAgent(), ProducerAgent(), PublisherAgent(), CommunityAgent(),
            )
            val bus = MessageBus(team.associateBy { it.name }, ctx)
            log.info(Agents.SYSTEM, "===== Cycle started =====")
            bus.post(AgentMessage(Agents.SYSTEM, Agents.CHIEF, MsgType.CYCLE_START, note = "Scheduled cycle"))
            bus.run()
            val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
            memory.putString("last_cycle", stamp)
            log.info(Agents.SYSTEM, "===== Cycle finished =====")
            return true
        } finally {
            currentStatus = "Idle"
            mutex.unlock()
        }
    }
}
