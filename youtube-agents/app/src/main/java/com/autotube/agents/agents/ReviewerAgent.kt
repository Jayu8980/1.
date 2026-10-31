package com.autotube.agents.agents

import com.autotube.agents.core.Agent
import com.autotube.agents.core.AgentContext
import com.autotube.agents.core.AgentMessage
import com.autotube.agents.core.Agents
import com.autotube.agents.core.MsgType
import com.autotube.agents.core.VideoScript
import com.autotube.agents.llm.ClaudeClient.Companion.strings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Quality and policy gate. Nothing reaches production without passing here.
 * Sends drafts back to the writer with concrete feedback (up to [MAX_REVISIONS] times).
 */
class ReviewerAgent : Agent(Agents.REVIEWER, "Editor, fact-checker and policy reviewer") {
    override suspend fun handle(msg: AgentMessage, ctx: AgentContext): List<AgentMessage> {
        if (msg.type != MsgType.SCRIPT_DRAFT) return emptyList()
        val s = ctx.settings
        val scriptJson = msg.payload.getJSONObject("script")
        val script = VideoScript.fromJson(scriptJson)
        val revision = msg.payload.optInt("revision", 0)

        // Deterministic checks first (cheap, objective).
        val hard = ArrayList<String>()
        val seconds = if (s.videoFormat == "shorts") s.targetSeconds.coerceIn(15, 58) else s.targetSeconds
        val maxWords = (seconds * 2.4 * 1.25).toInt()
        if (script.title.isBlank() || script.title.length > 100) hard += "Title must be 1-100 characters."
        if (script.scenes.size !in 3..14) hard += "Use between 4 and 10 scenes (has ${script.scenes.size})."
        if (script.narrationWordCount > maxWords) hard += "Too long: ${script.narrationWordCount} words, max $maxWords for ${seconds}s."
        if (script.narrationWordCount < seconds * 1.2) hard += "Too short: ${script.narrationWordCount} words for ${seconds}s."
        if (script.fullNarration().contains("http", ignoreCase = true)) hard += "No URLs in narration."

        val review = ctx.llm.askJson(
            system = """You are a strict senior editor and YouTube policy reviewer for a channel about "${s.niche}".
Check: factual accuracy (flag anything doubtful or unverifiable), YouTube Community Guidelines and monetization policies
(no misleading metadata, no harmful/dangerous advice, no medical/financial claims presented as fact, no hate or harassment,
no copyrighted lyrics or text, not mass-produced low-value content), originality and real value to the viewer,
hook strength, pacing, and whether title/thumbnail honestly match the content.""",
            user = """Script (language ${s.language}):
${scriptJson.toString(2)}

Return {"score": 1-10, "approved": true|false, "policy_risk": "none|low|high", "issues": ["..."], "feedback": "specific rewrite instructions"}
Approve only if score >= 7, policy_risk is not high and there are no factual errors.""",
            maxTokens = 6000,
        )
        val score = review.optInt("score", 0)
        val risk = review.optString("policy_risk", "low")
        val issues = (review.optJSONArray("issues") ?: JSONArray()).strings() + hard
        val approved = review.optBoolean("approved") && score >= 7 && risk != "high" && hard.isEmpty()
        ctx.log.say("Review of \"${script.title}\": score $score/10, policy risk $risk → ${if (approved) "APPROVED" else "changes needed"}.")

        if (approved) {
            return listOf(send(Agents.PRODUCER, MsgType.SCRIPT_APPROVED, JSONObject().put("script", scriptJson),
                note = "Approved \"${script.title}\" ($score/10). Produce it."))
        }
        if (revision < MAX_REVISIONS && risk != "high") {
            val feedback = (listOf(review.optString("feedback")) + issues.map { "- $it" }).joinToString("\n")
            return listOf(send(Agents.WRITER, MsgType.REVISE_SCRIPT,
                JSONObject().put("script", scriptJson).put("feedback", feedback)
                    .put("topic", msg.payload.optJSONObject("topic") ?: JSONObject())
                    .put("brief", msg.payload.optString("brief")).put("revision", revision + 1),
                note = "Revise: ${issues.take(3).joinToString("; ")}"))
        }
        return listOf(send(Agents.CHIEF, MsgType.SCRIPT_REJECTED,
            JSONObject().put("title", script.title).put("issues", JSONArray(issues)),
            note = "Rejected \"${script.title}\" (score $score, risk $risk)."))
    }

    companion object { const val MAX_REVISIONS = 2 }
}
