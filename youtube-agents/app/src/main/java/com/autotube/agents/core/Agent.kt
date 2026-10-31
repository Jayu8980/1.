package com.autotube.agents.core

import android.content.Context
import com.autotube.agents.llm.ClaudeClient
import com.autotube.agents.youtube.YouTubeApi

/** Everything an agent can use while handling a message. */
class AgentContext(
    val app: Context,
    val settings: Settings,
    val memory: Blackboard,
    val log: ActivityLog,
    val llm: ClaudeClient,
    val youtube: YouTubeApi?,
    val progress: (String) -> Unit,
)

/**
 * An autonomous agent. It reacts to messages addressed to it and replies with
 * zero or more messages to other agents. Agents never call each other directly:
 * all coordination flows through the [MessageBus], so every decision is logged.
 */
abstract class Agent(val name: String, val role: String) {
    abstract suspend fun handle(msg: AgentMessage, ctx: AgentContext): List<AgentMessage>

    protected fun send(to: String, type: String, payload: org.json.JSONObject = org.json.JSONObject(), note: String = "") =
        AgentMessage(from = name, to = to, type = type, payload = payload, note = note)

    protected fun ActivityLog.say(text: String) = info(name, text)
}
