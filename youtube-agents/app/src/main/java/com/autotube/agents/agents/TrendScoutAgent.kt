package com.autotube.agents.agents

import com.autotube.agents.core.Agent
import com.autotube.agents.core.AgentContext
import com.autotube.agents.core.AgentMessage
import com.autotube.agents.core.Agents
import com.autotube.agents.core.MsgType
import org.json.JSONArray
import org.json.JSONObject

/** Researches what is performing in the niche right now and proposes video topics. */
class TrendScoutAgent : Agent(Agents.TREND, "Trend researcher") {
    override suspend fun handle(msg: AgentMessage, ctx: AgentContext): List<AgentMessage> {
        if (msg.type != MsgType.RESEARCH) return emptyList()
        val s = ctx.settings
        var market = "No live YouTube data available; rely on your knowledge of evergreen interest in this niche."
        val yt = ctx.youtube
        if (yt != null && s.youtubeConnected) {
            try {
                val top = yt.searchTrending(s.niche, s.regionCode)
                if (top.isNotEmpty()) {
                    market = "Top videos in this niche from the last 14 days:\n" +
                        top.joinToString("\n") { "- \"${it.title}\" — ${it.views} views" }
                    ctx.log.say("Scanned ${top.size} trending videos in the niche.")
                }
            } catch (e: Exception) {
                ctx.log.say("Trend search unavailable (${e.message?.take(80)}); brainstorming instead.")
            }
        }
        val format = if (s.videoFormat == "shorts") "YouTube Shorts (vertical, under 60 seconds)" else "landscape videos of about ${s.targetSeconds} seconds"
        val r = ctx.llm.askJson(
            system = """You are a YouTube trend researcher for a channel about "${s.niche}" producing $format.
Audience language: ${s.language}. Propose ORIGINAL ideas: learn from what trends, never copy titles or other creators' content.""",
            user = """$market

Our performance insights:
${ctx.memory.insights}

Already covered (avoid):
${ctx.memory.recentTitles().joinToString("\n") { "- $it" }}

Propose 5 video ideas. Return {"proposals":[{"topic":"...","angle":"what makes ours different/better","hook":"first sentence","why":"evidence it will perform"}]}""",
            maxTokens = 6000,
        )
        val proposals = r.optJSONArray("proposals") ?: JSONArray()
        return listOf(send(Agents.CHIEF, MsgType.TOPIC_PROPOSALS, JSONObject().put("proposals", proposals),
            note = (0 until proposals.length()).joinToString(" | ") { proposals.optJSONObject(it)?.optString("topic") ?: "" }))
    }
}
