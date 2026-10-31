package com.autotube.agents.agents

import android.os.Environment
import com.autotube.agents.core.Agent
import com.autotube.agents.core.AgentContext
import com.autotube.agents.core.AgentMessage
import com.autotube.agents.core.Agents
import com.autotube.agents.core.MsgType
import com.autotube.agents.core.VideoScript
import com.autotube.agents.media.StockImages
import com.autotube.agents.media.VideoRenderer
import org.json.JSONObject
import java.io.File

/** Renders the approved script into an MP4 + thumbnail on the phone. */
class ProducerAgent : Agent(Agents.PRODUCER, "Video producer") {
    override suspend fun handle(msg: AgentMessage, ctx: AgentContext): List<AgentMessage> {
        if (msg.type != MsgType.SCRIPT_APPROVED) return emptyList()
        val s = ctx.settings
        val scriptJson = msg.payload.getJSONObject("script")
        val script = VideoScript.fromJson(scriptJson)
        val root = File(ctx.app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.app.filesDir, "AutoTube")
        cleanup(root, keep = 6)
        val dir = File(root, System.currentTimeMillis().toString())
        ctx.log.say("Producing \"${script.title}\"…")
        val result = VideoRenderer(ctx.app).render(
            script = script,
            outDir = dir,
            vertical = s.videoFormat == "shorts",
            languageTag = s.language,
            channelName = s.channelTitle,
            images = s.pexelsApiKey.takeIf { it.isNotBlank() }?.let { StockImages(it) },
            progress = { ctx.progress("Producer: $it") },
        )
        File(dir, "script.json").writeText(scriptJson.toString(2))
        val mb = result.video.length() / 1_048_576.0
        ctx.log.say("Rendered ${"%.1f".format(result.durationSec)}s video (${"%.1f".format(mb)} MB): ${result.video.absolutePath}")
        return listOf(send(Agents.PUBLISHER, MsgType.VIDEO_READY,
            JSONObject().put("script", scriptJson).put("video", result.video.absolutePath)
                .put("thumbnail", result.thumbnail.absolutePath).put("duration", result.durationSec),
            note = "Video ready: ${script.title}"))
    }

    private fun cleanup(root: File, keep: Int) {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return
        dirs.drop(keep).forEach { it.deleteRecursively() }
    }
}
