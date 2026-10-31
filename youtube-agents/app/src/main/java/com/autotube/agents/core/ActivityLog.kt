package com.autotube.agents.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent, human-readable log of what the agents did and said to each other.
 * Two streams: "activity" (status lines) and "bus" (agent-to-agent messages).
 */
class ActivityLog(context: Context) {
    private val activityFile = File(context.filesDir, "activity.log")
    private val busFile = File(context.filesDir, "bus.log")
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun info(agent: String, text: String) = append(activityFile, "${fmt.format(Date())}  [$agent] $text")
    fun error(agent: String, text: String) = append(activityFile, "${fmt.format(Date())}  [$agent] ERROR: $text")

    fun message(m: AgentMessage) {
        val summary = m.note.ifBlank { m.payload.toString().take(160) }
        append(busFile, "${fmt.format(Date(m.timestamp))}  ${m.from} → ${m.to}  ${m.type}\n      $summary")
    }

    fun readActivity(maxLines: Int = 400) = tail(activityFile, maxLines)
    fun readBus(maxLines: Int = 400) = tail(busFile, maxLines)

    fun clear() = synchronized(lock) { activityFile.delete(); busFile.delete() }

    private fun append(f: File, line: String) = synchronized(lock) {
        f.appendText(line + "\n")
        if (f.length() > 512_000) {
            val lines = f.readLines()
            f.writeText(lines.takeLast(2000).joinToString("\n") + "\n")
        }
    }

    private fun tail(f: File, n: Int): String = synchronized(lock) {
        if (!f.exists()) "" else f.readLines().takeLast(n).reversed().joinToString("\n")
    }

    @Suppress("unused")
    fun debugJson(o: JSONObject) = o.toString(2)
}
