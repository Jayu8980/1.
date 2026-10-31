package com.autotube.agents.agents

import com.autotube.agents.core.Agent
import com.autotube.agents.core.AgentContext
import com.autotube.agents.core.AgentMessage
import com.autotube.agents.core.Agents
import com.autotube.agents.core.MsgType
import com.autotube.agents.core.VideoScript
import org.json.JSONObject

/** Writes the narration, on-screen captions, visuals, title, description and tags. */
class ScriptWriterAgent : Agent(Agents.WRITER, "Script writer and SEO copywriter") {
    override suspend fun handle(msg: AgentMessage, ctx: AgentContext): List<AgentMessage> {
        if (msg.type != MsgType.WRITE_SCRIPT && msg.type != MsgType.REVISE_SCRIPT) return emptyList()
        val s = ctx.settings
        val topic = msg.payload.optJSONObject("topic") ?: JSONObject()
        val brief = msg.payload.optString("brief")
        val revision = msg.payload.optInt("revision", 0)
        val seconds = if (s.videoFormat == "shorts") s.targetSeconds.coerceIn(15, 58) else s.targetSeconds.coerceAtLeast(30)
        val words = (seconds * 2.4).toInt()

        val revisionBlock = if (msg.type == MsgType.REVISE_SCRIPT) """
REVISION REQUEST from the Reviewer — fix every point:
${msg.payload.optString("feedback")}

Previous draft:
${msg.payload.optJSONObject("script")?.toString(2)}
""" else ""

        val json = ctx.llm.askJson(
            system = """You are the script writer for a faceless YouTube channel about "${s.niche}".
Persona/tone: ${s.persona}
Language for ALL viewer-facing text: ${s.language}.
The video is narrated by text-to-speech over animated captions and stock images, so write for the ear: short sentences, no stage directions, no emojis in narration, spell out symbols.
Accuracy matters more than hype: only state facts you are confident are true. Titles must be curiosity-driven but never misleading.""",
            user = """Topic: ${topic.optString("topic")}
Angle: ${topic.optString("angle")}
Suggested hook: ${topic.optString("hook")}
Manager's brief: $brief
Insights: ${ctx.memory.insights}
$revisionBlock
Target length: ~$seconds seconds ≈ $words spoken words in total (hard maximum ${(words * 1.15).toInt()}).
Structure: scene 1 is a strong hook (first 3 seconds decide retention), then fast, valuable beats, last scene a short payoff + call to subscribe.
Use 4-10 scenes.

Return:
{"title":"<= 70 chars","description":"2-4 sentence SEO description with keywords, then 3-5 hashtags","tags":["10-15 tags"],"thumbnail_text":"2-5 punchy words",
 "scenes":[{"narration":"what the voice says","caption":"2-6 word on-screen headline","visual":"2-4 word stock photo search query","color":"#RRGGBB background color"}]}""",
            maxTokens = 8000,
        )
        val script = VideoScript.fromJson(json)
        require(script.scenes.isNotEmpty()) { "Writer produced no scenes" }
        ctx.log.say("Draft ${revision + 1}: \"${script.title}\" (${script.scenes.size} scenes, ${script.narrationWordCount} words).")
        return listOf(send(Agents.REVIEWER, MsgType.SCRIPT_DRAFT,
            JSONObject().put("script", script.toJson()).put("topic", topic).put("brief", brief).put("revision", revision),
            note = "Draft ${revision + 1}: ${script.title}"))
    }
}
