package com.autotube.agents.core

import org.json.JSONArray
import org.json.JSONObject

/** The production package passed Writer → Reviewer → Producer → Publisher. */
data class VideoScript(
    val title: String,
    val description: String,
    val tags: List<String>,
    val thumbnailText: String,
    val scenes: List<Scene>,
) {
    data class Scene(val narration: String, val caption: String, val visual: String, val color: String)

    val narrationWordCount: Int get() = scenes.sumOf { s -> s.narration.split(Regex("\\s+")).count { it.isNotBlank() } }

    fun fullNarration(): String = scenes.joinToString(" ") { it.narration }

    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("description", description).put("tags", JSONArray(tags))
        .put("thumbnail_text", thumbnailText)
        .put("scenes", JSONArray(scenes.map {
            JSONObject().put("narration", it.narration).put("caption", it.caption).put("visual", it.visual).put("color", it.color)
        }))

    companion object {
        fun fromJson(o: JSONObject): VideoScript {
            val sc = o.optJSONArray("scenes") ?: JSONArray()
            val scenes = (0 until sc.length()).mapNotNull { sc.optJSONObject(it) }.map {
                Scene(it.optString("narration").trim(), it.optString("caption").trim(), it.optString("visual").trim(), it.optString("color", "#3949AB"))
            }.filter { it.narration.isNotBlank() }
            val tagsArr = o.optJSONArray("tags") ?: JSONArray()
            return VideoScript(
                title = o.optString("title").trim(),
                description = o.optString("description").trim(),
                tags = (0 until tagsArr.length()).map { tagsArr.optString(it).trim() }.filter { it.isNotBlank() },
                thumbnailText = o.optString("thumbnail_text").trim(),
                scenes = scenes,
            )
        }
    }
}
