package com.autotube.agents.core

import org.json.JSONObject
import java.util.UUID

/**
 * A message passed between agents on the [MessageBus].
 * `type` is the intent (see [MsgType]); `payload` is free-form JSON.
 */
data class AgentMessage(
    val from: String,
    val to: String,
    val type: String,
    val payload: JSONObject = JSONObject(),
    val note: String = "",
    val id: String = UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("from", from).put("to", to).put("type", type)
        .put("note", note).put("ts", timestamp).put("payload", payload)

    companion object {
        fun fromJson(o: JSONObject) = AgentMessage(
            from = o.optString("from"), to = o.optString("to"), type = o.optString("type"),
            payload = o.optJSONObject("payload") ?: JSONObject(), note = o.optString("note"),
            id = o.optString("id"), timestamp = o.optLong("ts"),
        )
    }
}

/** Agent names (addresses on the bus). */
object Agents {
    const val CHIEF = "Chief"
    const val ANALYST = "Analyst"
    const val TREND = "TrendScout"
    const val WRITER = "ScriptWriter"
    const val REVIEWER = "Reviewer"
    const val PRODUCER = "Producer"
    const val PUBLISHER = "Publisher"
    const val COMMUNITY = "Community"
    const val SYSTEM = "System"
}

/** Message intents. */
object MsgType {
    const val CYCLE_START = "CYCLE_START"
    const val ANALYZE = "ANALYZE"
    const val INSIGHTS = "INSIGHTS"
    const val RESEARCH = "RESEARCH"
    const val TOPIC_PROPOSALS = "TOPIC_PROPOSALS"
    const val WRITE_SCRIPT = "WRITE_SCRIPT"
    const val SCRIPT_DRAFT = "SCRIPT_DRAFT"
    const val REVISE_SCRIPT = "REVISE_SCRIPT"
    const val SCRIPT_APPROVED = "SCRIPT_APPROVED"
    const val SCRIPT_REJECTED = "SCRIPT_REJECTED"
    const val VIDEO_READY = "VIDEO_READY"
    const val PUBLISHED = "PUBLISHED"
    const val MODERATE = "MODERATE"
    const val COMMUNITY_REPORT = "COMMUNITY_REPORT"
    const val FAILED = "FAILED"
}
