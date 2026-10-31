package com.autotube.agents.agents

import com.autotube.agents.core.Agent
import com.autotube.agents.core.AgentContext
import com.autotube.agents.core.AgentMessage
import com.autotube.agents.core.Agents
import com.autotube.agents.core.MsgType
import com.autotube.agents.core.VideoScript
import org.json.JSONObject
import java.io.File

/** Uploads to YouTube with SEO metadata, sets the thumbnail and records the result. */
class PublisherAgent : Agent(Agents.PUBLISHER, "Publisher") {
    override suspend fun handle(msg: AgentMessage, ctx: AgentContext): List<AgentMessage> {
        if (msg.type != MsgType.VIDEO_READY) return emptyList()
        val s = ctx.settings
        val script = VideoScript.fromJson(msg.payload.getJSONObject("script"))
        val video = File(msg.payload.getString("video"))
        val thumb = File(msg.payload.getString("thumbnail"))
        val shorts = s.videoFormat == "shorts"

        val title = if (shorts && !script.title.contains("#shorts", true) && script.title.length <= 91) "${script.title} #Shorts" else script.title
        val description = buildString {
            append(script.description)
            append("\n\n")
            append("Narration is synthetic (text-to-speech) and this video was produced with AI assistance.")
        }
        val entry = JSONObject().put("title", script.title).put("local_path", video.absolutePath)

        val yt = ctx.youtube
        if (s.dryRun || yt == null || !s.youtubeConnected) {
            val why = if (s.dryRun) "dry-run mode" else "YouTube not connected"
            ctx.memory.addPublished(entry.put("uploaded", false))
            ctx.log.say("Saved locally ($why): ${video.absolutePath}")
            return listOf(send(Agents.CHIEF, MsgType.PUBLISHED, entry, note = "Saved locally ($why): ${script.title}"))
        }

        ctx.log.say("Uploading \"$title\" as ${s.privacy}…")
        val id = yt.uploadVideo(video, title, description, script.tags, s.privacy, s.language)
        val url = "https://youtu.be/$id"
        if (!shorts) {
            try { yt.setThumbnail(id, thumb) } catch (e: Exception) {
                ctx.log.say("Custom thumbnail not set (${e.message?.take(80)}). Verify your channel to enable custom thumbnails.")
            }
        }
        ctx.memory.addPublished(entry.put("uploaded", true).put("video_id", id).put("url", url))
        ctx.log.say("Published: $url")
        return listOf(send(Agents.CHIEF, MsgType.PUBLISHED, entry, note = "Published \"${script.title}\" → $url"))
    }
}
