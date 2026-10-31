package com.autotube.agents.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared long-term memory that all agents read from and write to:
 * published videos, performance insights, replied comments, rejected ideas.
 */
class Blackboard(context: Context) {
    private val file = File(context.filesDir, "blackboard.json")
    private val lock = Any()
    private var root: JSONObject = load()

    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun arr(key: String): JSONArray = root.optJSONArray(key) ?: JSONArray().also { root.put(key, it) }

    // ---- Published videos ----
    fun addPublished(entry: JSONObject) = synchronized(lock) {
        arr("published").put(entry.put("date", today()).put("ts", System.currentTimeMillis()))
        trim("published", 300); save()
    }

    fun published(): List<JSONObject> = synchronized(lock) { arr("published").objects() }

    /** Videos produced today (uploaded or saved locally). */
    fun producedToday(): Int = published().count { it.optString("date") == today() }

    fun lastProducedTs(): Long = published().maxOfOrNull { it.optLong("ts") } ?: 0L

    fun recentTitles(n: Int = 40): List<String> =
        (published().map { it.optString("title") } + rejected()).takeLast(n)

    // ---- Rejected ideas (so we never retry them) ----
    fun addRejected(title: String) = synchronized(lock) { arr("rejected").put(title); trim("rejected", 100); save() }
    private fun rejected(): List<String> = synchronized(lock) { arr("rejected").let { a -> (0 until a.length()).map { a.optString(it) } } }

    // ---- Insights from the Analyst ----
    var insights: String
        get() = synchronized(lock) { root.optString("insights", "No performance data yet. This is a new channel.") }
        set(v) = synchronized(lock) { root.put("insights", v); save() }

    // ---- Comment threads already handled ----
    fun isCommentHandled(id: String): Boolean = synchronized(lock) {
        val a = arr("handled_comments"); (0 until a.length()).any { a.optString(it) == id }
    }
    fun markCommentHandled(id: String) = synchronized(lock) { arr("handled_comments").put(id); trim("handled_comments", 1000); save() }

    // ---- Generic key/value for agents ----
    fun getString(key: String, def: String = ""): String = synchronized(lock) { root.optJSONObject("kv")?.optString(key, def) ?: def }
    fun putString(key: String, v: String) = synchronized(lock) {
        val kv = root.optJSONObject("kv") ?: JSONObject().also { root.put("kv", it) }
        kv.put(key, v); save()
    }

    private fun trim(key: String, max: Int) {
        val a = arr(key)
        if (a.length() <= max) return
        val keep = JSONArray()
        for (i in a.length() - max until a.length()) keep.put(a.get(i))
        root.put(key, keep)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
