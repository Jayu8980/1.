package com.autotube.agents.agents

import com.autotube.agents.core.Agent
import com.autotube.agents.core.AgentContext
import com.autotube.agents.core.AgentMessage
import com.autotube.agents.core.Agents
import com.autotube.agents.core.MsgType
import com.autotube.agents.llm.ClaudeClient.Companion.strings
import org.json.JSONArray
import org.json.JSONObject

/**
 * The channel manager. Starts each cycle, enforces the safety rails (daily limit,
 * spacing between videos), chooses which proposed topic to produce, and handles failures.
 */
class ChiefAgent : Agent(Agents.CHIEF, "Channel manager and decision maker") {

    private var backupTopics = ArrayList<JSONObject>()
    private var retriedTopic = false

    override suspend fun handle(msg: AgentMessage, ctx: AgentContext): List<AgentMessage> = when (msg.type) {
        MsgType.CYCLE_START -> startCycle(ctx)
        MsgType.INSIGHTS -> decideProduction(ctx)
        MsgType.TOPIC_PROPOSALS -> chooseTopic(msg, ctx)
        MsgType.SCRIPT_REJECTED -> onRejected(msg, ctx)
        MsgType.PUBLISHED -> {
            ctx.log.say("Cycle deliverable done: ${msg.note}"); emptyList()
        }
        MsgType.COMMUNITY_REPORT -> { ctx.log.say("Community update: ${msg.note}"); emptyList() }
        MsgType.FAILED -> { ctx.log.say("${msg.from} reported a failure at ${msg.payload.optString("stage")}: ${msg.payload.optString("error")}"); emptyList() }
        else -> emptyList()
    }

    private fun startCycle(ctx: AgentContext): List<AgentMessage> {
        val s = ctx.settings
        ctx.log.say("New cycle. Niche: \"${s.niche}\". YouTube ${if (s.youtubeConnected) "connected" else "NOT connected (videos are saved on the phone)"}.")
        val out = ArrayList<AgentMessage>()
        out += send(Agents.ANALYST, MsgType.ANALYZE, note = "Review channel performance before we plan.")
        if (s.youtubeConnected && s.autoReplyComments) {
            out += send(Agents.COMMUNITY, MsgType.MODERATE, note = "Handle new comments (max ${s.maxRepliesPerCycle} replies).")
        }
        return out
    }

    private fun decideProduction(ctx: AgentContext): List<AgentMessage> {
        val s = ctx.settings
        val produced = ctx.memory.producedToday()
        if (produced >= s.uploadsPerDay) {
            ctx.log.say("Daily limit reached ($produced/${s.uploadsPerDay}). No new video this cycle.")
            return emptyList()
        }
        val spacingMs = (24.0 / s.uploadsPerDay * 0.75 * 3_600_000).toLong()
        val since = System.currentTimeMillis() - ctx.memory.lastProducedTs()
        if (since < spacingMs) {
            ctx.log.say("Last video was ${since / 60_000} min ago; waiting to space out uploads.")
            return emptyList()
        }
        return listOf(send(Agents.TREND, MsgType.RESEARCH,
            JSONObject().put("niche", s.niche),
            note = "We have room for a video ($produced/${s.uploadsPerDay} today). Find what our audience wants now."))
    }

    private suspend fun chooseTopic(msg: AgentMessage, ctx: AgentContext): List<AgentMessage> {
        val proposals = msg.payload.optJSONArray("proposals") ?: JSONArray()
        if (proposals.length() == 0) { ctx.log.say("No topic proposals; skipping production."); return emptyList() }
        val decision = ctx.llm.askJson(
            system = """You are the channel manager of a YouTube channel about "${ctx.settings.niche}".
Persona/tone: ${ctx.settings.persona}
Pick the single proposal most likely to earn views AND deliver genuine value to viewers, given the performance insights.
Prefer topics that are accurate, evergreen or timely, and clearly different from past videos.""",
            user = """Performance insights:
${ctx.memory.insights}

Recent titles (do not repeat):
${ctx.memory.recentTitles().joinToString("\n") { "- $it" }}

Proposals:
${proposals.toString(2)}

Return {"index": <0-based index>, "reason": "...", "brief": "2-4 sentences telling the script writer the angle, key facts to cover and the hook"}""",
            maxTokens = 4000,
        )
        val i = decision.optInt("index", 0).coerceIn(0, proposals.length() - 1)
        val chosen = proposals.getJSONObject(i)
        backupTopics = ArrayList((0 until proposals.length()).filter { it != i }.map { proposals.getJSONObject(it) })
        ctx.log.say("Chose \"${chosen.optString("topic")}\" — ${decision.optString("reason")}")
        return listOf(send(Agents.WRITER, MsgType.WRITE_SCRIPT,
            JSONObject().put("topic", chosen).put("brief", decision.optString("brief")).put("revision", 0),
            note = "Write: ${chosen.optString("topic")}"))
    }

    private fun onRejected(msg: AgentMessage, ctx: AgentContext): List<AgentMessage> {
        val title = msg.payload.optString("title")
        if (title.isNotBlank()) ctx.memory.addRejected(title)
        ctx.log.say("Reviewer rejected the script after revisions: ${msg.payload.optJSONArray("issues")?.strings()?.joinToString("; ")}")
        if (!retriedTopic && backupTopics.isNotEmpty()) {
            retriedTopic = true
            val next = backupTopics.removeAt(0)
            ctx.log.say("Trying backup topic \"${next.optString("topic")}\".")
            return listOf(send(Agents.WRITER, MsgType.WRITE_SCRIPT,
                JSONObject().put("topic", next).put("brief", next.optString("angle")).put("revision", 0),
                note = "Backup topic: ${next.optString("topic")}"))
        }
        return emptyList()
    }
}
