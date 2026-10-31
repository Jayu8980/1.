package com.autotube.agents.agents

import com.autotube.agents.core.Agent
import com.autotube.agents.core.AgentContext
import com.autotube.agents.core.AgentMessage
import com.autotube.agents.core.Agents
import com.autotube.agents.core.MsgType

/** Studies channel analytics and writes lessons into shared memory for the other agents. */
class AnalystAgent : Agent(Agents.ANALYST, "Performance analyst") {
    override suspend fun handle(msg: AgentMessage, ctx: AgentContext): List<AgentMessage> {
        if (msg.type != MsgType.ANALYZE) return emptyList()
        val yt = ctx.youtube
        if (yt == null || !ctx.settings.youtubeConnected) {
            return listOf(send(Agents.CHIEF, MsgType.INSIGHTS, note = "No YouTube access; using existing insights."))
        }
        val ch = yt.myChannel()
        ctx.settings.channelId = ch.id
        ctx.settings.channelTitle = ch.title
        val stats = yt.recentUploadStats(ch.uploadsPlaylist, 15)
        ctx.log.say("Channel \"${ch.title}\": ${ch.subscribers} subs, ${ch.views} views, ${ch.videoCount} videos.")
        if (stats.isEmpty()) {
            return listOf(send(Agents.CHIEF, MsgType.INSIGHTS, note = "No uploads yet — nothing to learn from."))
        }
        val table = stats.joinToString("\n") { "- \"${it.title}\" | published ${it.publishedAt} | ${it.views} views | ${it.likes} likes | ${it.comments} comments | ${it.durationIso}" }
        val insights = ctx.llm.ask(
            system = "You are a YouTube growth analyst. Be concrete and data-driven. Max 8 bullet points, under 180 words total.",
            user = """Channel: ${ch.title} (${ch.subscribers} subscribers), niche: ${ctx.settings.niche}.
Recent uploads (newest first):
$table

Previous insights:
${ctx.memory.insights}

Write updated insights: which topics/title styles/hooks work, which flop, what to do next. Account for video age when comparing views.""",
            maxTokens = 4000,
        )
        ctx.memory.insights = insights
        return listOf(send(Agents.CHIEF, MsgType.INSIGHTS, note = insights.lines().firstOrNull { it.isNotBlank() }?.take(140) ?: "Insights updated"))
    }
}
