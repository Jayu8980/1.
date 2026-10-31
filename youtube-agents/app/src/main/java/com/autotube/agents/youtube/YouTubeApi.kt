package com.autotube.agents.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Thin wrapper over the YouTube Data API v3 endpoints the agents need. */
class YouTubeApi(private val auth: GoogleAuth) {
    private val base = "https://www.googleapis.com/youtube/v3"

    private suspend fun get(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        Http.json("GET", "$base/$path?${Http.form(params)}", auth.token())
    }

    data class Channel(val id: String, val title: String, val uploadsPlaylist: String, val subscribers: Long, val views: Long, val videoCount: Long)

    suspend fun myChannel(): Channel {
        val r = get("channels", mapOf("part" to "id,snippet,contentDetails,statistics", "mine" to "true"))
        val c = r.optJSONArray("items")?.optJSONObject(0) ?: throw IllegalStateException("This Google account has no YouTube channel.")
        val st = c.optJSONObject("statistics") ?: JSONObject()
        return Channel(
            c.getString("id"),
            c.getJSONObject("snippet").optString("title"),
            c.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads") ?: "",
            st.optString("subscriberCount", "0").toLongOrNull() ?: 0,
            st.optString("viewCount", "0").toLongOrNull() ?: 0,
            st.optString("videoCount", "0").toLongOrNull() ?: 0,
        )
    }

    data class VideoStats(val id: String, val title: String, val publishedAt: String, val views: Long, val likes: Long, val comments: Long, val durationIso: String)

    /** Recent uploads with statistics (cheap: 1 + 1 quota units). */
    suspend fun recentUploadStats(uploadsPlaylist: String, max: Int = 15): List<VideoStats> {
        if (uploadsPlaylist.isBlank()) return emptyList()
        val items = get("playlistItems", mapOf("part" to "contentDetails", "playlistId" to uploadsPlaylist, "maxResults" to "$max"))
            .optJSONArray("items") ?: return emptyList()
        val ids = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("contentDetails")?.optString("videoId") }
        return videoStats(ids)
    }

    suspend fun videoStats(ids: List<String>): List<VideoStats> {
        if (ids.isEmpty()) return emptyList()
        val r = get("videos", mapOf("part" to "snippet,statistics,contentDetails", "id" to ids.joinToString(",")))
        val arr = r.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { v ->
            val s = v.optJSONObject("statistics") ?: JSONObject()
            VideoStats(
                v.optString("id"), v.optJSONObject("snippet")?.optString("title") ?: "",
                v.optJSONObject("snippet")?.optString("publishedAt") ?: "",
                s.optString("viewCount", "0").toLongOrNull() ?: 0,
                s.optString("likeCount", "0").toLongOrNull() ?: 0,
                s.optString("commentCount", "0").toLongOrNull() ?: 0,
                v.optJSONObject("contentDetails")?.optString("duration") ?: "",
            )
        }
    }

    /** Top recent videos for a query: the TrendScout's market research (100 quota units). */
    suspend fun searchTrending(query: String, regionCode: String, days: Int = 14, max: Int = 15): List<VideoStats> {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val after = fmt.format(Date(System.currentTimeMillis() - days * 86_400_000L))
        val r = get("search", mapOf(
            "part" to "id", "q" to query, "type" to "video", "order" to "viewCount",
            "publishedAfter" to after, "regionCode" to regionCode, "maxResults" to "$max",
        ))
        val arr = r.optJSONArray("items") ?: JSONArray()
        val ids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONObject("id")?.optString("videoId") }
        return videoStats(ids)
    }

    data class CommentThread(val threadId: String, val videoId: String, val author: String, val authorChannelId: String, val text: String, val replyCount: Int)

    suspend fun recentComments(channelId: String, max: Int = 30): List<CommentThread> {
        val r = get("commentThreads", mapOf(
            "part" to "snippet", "allThreadsRelatedToChannelId" to channelId,
            "maxResults" to "$max", "order" to "time", "textFormat" to "plainText",
        ))
        val arr = r.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { t ->
            val sn = t.getJSONObject("snippet")
            val top = sn.getJSONObject("topLevelComment").getJSONObject("snippet")
            CommentThread(
                t.getString("id"), sn.optString("videoId"), top.optString("authorDisplayName"),
                top.optJSONObject("authorChannelId")?.optString("value") ?: "",
                top.optString("textDisplay"), sn.optInt("totalReplyCount"),
            )
        }
    }

    suspend fun reply(parentId: String, text: String): Unit = withContext(Dispatchers.IO) {
        Http.json("POST", "$base/comments?part=snippet", auth.token(),
            JSONObject().put("snippet", JSONObject().put("parentId", parentId).put("textOriginal", text)))
    }

    /** Resumable upload. Returns the new video id. */
    suspend fun uploadVideo(
        file: File, title: String, description: String, tags: List<String>,
        privacy: String, language: String, categoryId: String = "27",
    ): String = withContext(Dispatchers.IO) {
        val meta = JSONObject()
            .put("snippet", JSONObject()
                .put("title", title.take(100))
                .put("description", description.take(4900))
                .put("tags", JSONArray(tags.take(25)))
                .put("categoryId", categoryId)
                .put("defaultLanguage", language.substringBefore("-"))
                .put("defaultAudioLanguage", language.substringBefore("-")))
            .put("status", JSONObject()
                .put("privacyStatus", privacy)
                .put("selfDeclaredMadeForKids", false)
                // Honest disclosure: narration is synthetic and the script is AI-written.
                .put("containsSyntheticMedia", true))
        val token = auth.token()
        val (conn, _) = Http.request(
            "POST",
            "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status",
            mapOf(
                "Authorization" to "Bearer $token",
                "X-Upload-Content-Type" to "video/mp4",
                "X-Upload-Content-Length" to file.length().toString(),
            ),
            meta.toString().toByteArray(), "application/json; charset=UTF-8",
        )
        val location = conn.getHeaderField("Location") ?: throw IllegalStateException("Upload session not created")
        val resp = Http.putFile(location, token, file, "video/mp4")
        JSONObject(resp).getString("id")
    }

    suspend fun setThumbnail(videoId: String, jpeg: File): Unit = withContext(Dispatchers.IO) {
        Http.request(
            "POST", "https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=${Http.enc(videoId)}",
            mapOf("Authorization" to "Bearer ${auth.token()}"), jpeg.readBytes(), "image/jpeg",
        )
    }
}
