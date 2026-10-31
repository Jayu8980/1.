package com.autotube.agents.core

import kotlinx.coroutines.CancellationException

/**
 * Routes messages between agents until the conversation settles.
 * Delivery is FIFO and sequential, which keeps the phone's resource use bounded
 * (one LLM call / one render at a time) and makes the transcript easy to follow.
 */
class MessageBus(private val agents: Map<String, Agent>, private val ctx: AgentContext) {
    private val queue = ArrayDeque<AgentMessage>()

    fun post(m: AgentMessage) {
        ctx.log.message(m)
        queue.addLast(m)
    }

    /** Runs until no messages remain or the safety step limit is hit. */
    suspend fun run(maxSteps: Int = 60) {
        var steps = 0
        while (queue.isNotEmpty()) {
            if (++steps > maxSteps) {
                ctx.log.error(Agents.SYSTEM, "Step limit reached; stopping cycle to stay safe.")
                queue.clear(); return
            }
            val msg = queue.removeFirst()
            val agent = agents[msg.to]
            if (agent == null) {
                ctx.log.error(Agents.SYSTEM, "No agent named ${msg.to}")
                continue
            }
            ctx.progress("${agent.name}: ${msg.type}")
            val replies = try {
                agent.handle(msg, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.log.error(agent.name, "${e.javaClass.simpleName}: ${e.message}")
                if (msg.to != Agents.CHIEF) {
                    listOf(AgentMessage(agent.name, Agents.CHIEF, MsgType.FAILED,
                        org.json.JSONObject().put("stage", msg.type).put("error", e.message ?: e.javaClass.simpleName),
                        note = "Failed while handling ${msg.type}: ${e.message}"))
                } else emptyList()
            }
            replies.forEach { post(it) }
        }
    }
}
