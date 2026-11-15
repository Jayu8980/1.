package com.tubepilot.youtube

import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.error.AuthRequiredException
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.youtube.api.AnalyticsReport
import com.tubepilot.youtube.api.CommentDto
import com.tubepilot.youtube.api.CommentInsert
import com.tubepilot.youtube.api.CommentInsertSnippet
import com.tubepilot.youtube.api.CommentThreadInsert
import com.tubepilot.youtube.api.CommentThreadListResponse
import com.tubepilot.youtube.api.PlaylistDto
import com.tubepilot.youtube.api.PlaylistItemInsert
import com.tubepilot.youtube.api.PlaylistItemSnippet
import com.tubepilot.youtube.api.ResourceId
import com.tubepilot.youtube.api.ThreadInsertSnippet
import com.tubepilot.youtube.api.TopLevelComment
import com.tubepilot.youtube.api.TopLevelCommentSnippet
import com.tubepilot.youtube.api.VideoDto
import com.tubepilot.youtube.api.VideoSnippet
import com.tubepilot.youtube.api.VideoUpdate
import com.tubepilot.youtube.api.YouTubeAnalyticsService
import com.tubepilot.youtube.api.YouTubeDataService
import com.tubepilot.youtube.di.AuthIoException
import com.tubepilot.youtube.quota.QuotaManager
import com.tubepilot.youtube.upload.MediaUploader
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ChannelInfo(
    val id: String,
    val title: String,
    val description: String,
    val subscribers: Long,
    val videoCount: Long,
    val viewCount: Long,
    val uploadsPlaylistId: String,
    val thumbnailUrl: String?,
)

data class VideoSummary(
    val id: String,
    val title: String,
    val channelId: String,
    val channelTitle: String,
    val publishedAtMs: Long,
    val views: Long,
    val likes: Long,
    val comments: Long,
    val durationSec: Int,
    val tags: List<String>,
    val description: String,
    val privacyStatus: String?,
)

data class SearchSnapshot(val query: String, val totalResults: Long, val videos: List<VideoSummary>)

/**
 * Facade over the official YouTube Data API v3 and Analytics API v2. Every Data API call goes
 * through [QuotaManager.track]; HTTP failures are mapped onto retryable/terminal app errors.
 */
@Singleton
class YouTubeClient @Inject constructor(
    private val data: YouTubeDataService,
    private val analytics: YouTubeAnalyticsService,
    private val uploader: MediaUploader,
    private val quota: QuotaManager,
    private val audit: AuditLog,
) {
    private suspend fun <T> call(method: String, block: suspend () -> T): T = quota.track(method) { mapErrors(method, block) }

    private suspend fun <T> mapErrors(method: String, block: suspend () -> T): T = try {
        block()
    } catch (e: AuthIoException) {
        throw e.auth
    } catch (e: HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty().take(600)
        val code = e.code()
        audit.record("YouTubeAPI", "api.error", "$method HTTP $code $body", level = AuditLevel.WARNING)
        if (code == 401) throw AuthRequiredException("YouTube rejected the credentials. Reconnect your channel.")
        // 403 quotaExceeded means Google's counter disagrees with ours: treat as retryable later.
        val quotaExceeded = code == 403 && body.contains("quotaExceeded")
        throw ProviderException("YouTube $method", "HTTP $code: $body", code, retryable = quotaExceeded || code == 429 || code >= 500)
    } catch (e: IOException) {
        throw ProviderException("YouTube $method", e.message ?: "network error", null, retryable = true, cause = e)
    }

    suspend fun myChannel(): ChannelInfo {
        val response = call("channels.list") { data.channels(part = "snippet,statistics,contentDetails", mine = true) }
        val dto = response.items.firstOrNull() ?: throw ProviderException("YouTube", "This Google account has no YouTube channel", 404, retryable = false)
        return dto.toInfo()
    }

    suspend fun channel(id: String): ChannelInfo? =
        call("channels.list") { data.channels(part = "snippet,statistics,contentDetails", id = id) }.items.firstOrNull()?.toInfo()

    private fun com.tubepilot.youtube.api.ChannelDto.toInfo() = ChannelInfo(
        id = id,
        title = snippet.title,
        description = snippet.description,
        subscribers = statistics?.subscriberCount?.toLongOrNull() ?: 0,
        videoCount = statistics?.videoCount?.toLongOrNull() ?: 0,
        viewCount = statistics?.viewCount?.toLongOrNull() ?: 0,
        uploadsPlaylistId = contentDetails?.relatedPlaylists?.uploads.orEmpty(),
        thumbnailUrl = snippet.thumbnails?.high?.url ?: snippet.thumbnails?.default?.url,
    )

    /** search.list (own 100-calls/day bucket) followed by one videos.list for statistics. */
    suspend fun search(query: String, publishedAfterMs: Long?, maxResults: Int = 25, language: String? = null, shortsOnly: Boolean = false): SearchSnapshot {
        val result = call("search.list") {
            data.search(
                query = query,
                order = "viewCount",
                publishedAfter = publishedAfterMs?.let { TimeUtil.rfc3339(it) },
                maxResults = maxResults.coerceIn(1, 50),
                relevanceLanguage = language,
                videoDuration = if (shortsOnly) "short" else null,
            )
        }
        val ids = result.items.mapNotNull { it.id.videoId }
        return SearchSnapshot(query, result.pageInfo.totalResults, if (ids.isEmpty()) emptyList() else videos(ids))
    }

    suspend fun videos(ids: List<String>): List<VideoSummary> = ids.distinct().chunked(50).flatMap { chunk ->
        call("videos.list") { data.videos(part = "snippet,statistics,contentDetails,status", ids = chunk.joinToString(",")) }
            .items.map { it.toSummary() }
    }

    /** Cheap competitor scan: uploads playlist (1 unit) + videos.list (1 unit) instead of search (100/day). */
    suspend fun recentUploads(channelId: String, maxResults: Int = 10): List<VideoSummary> {
        val info = channel(channelId) ?: return emptyList()
        if (info.uploadsPlaylistId.isBlank()) return emptyList()
        val items = call("playlistItems.list") { data.playlistItems(playlistId = info.uploadsPlaylistId, maxResults = maxResults.coerceIn(1, 50)) }
        val ids = items.items.mapNotNull { it.contentDetails?.videoId ?: it.snippet.resourceId.videoId }
        return if (ids.isEmpty()) emptyList() else videos(ids)
    }

    suspend fun playlists(): List<PlaylistDto> = call("playlists.list") { data.playlists() }.items

    suspend fun addToPlaylist(playlistId: String, videoId: String) {
        call("playlistItems.insert") {
            data.insertPlaylistItem(body = PlaylistItemInsert(PlaylistItemSnippet(playlistId = playlistId, resourceId = ResourceId(kind = "youtube#video", videoId = videoId))))
        }
    }

    suspend fun commentThreads(channelId: String, pageToken: String? = null): CommentThreadListResponse =
        call("commentThreads.list") { data.commentThreads(channelId = channelId, pageToken = pageToken) }

    suspend fun reply(parentCommentId: String, text: String): CommentDto =
        call("comments.insert") { data.insertComment(body = CommentInsert(CommentInsertSnippet(parentCommentId, text))) }

    /** Posts a top-level comment as the channel. The Data API cannot pin comments; the user pins it in Studio. */
    suspend fun postComment(videoId: String, text: String) {
        call("commentThreads.insert") {
            data.insertCommentThread(body = CommentThreadInsert(ThreadInsertSnippet(videoId, TopLevelComment(TopLevelCommentSnippet(text)))))
        }
    }

    /** status: "heldForReview" (hide, reversible), "rejected" or "published". */
    suspend fun moderate(commentIds: List<String>, status: String) {
        commentIds.chunked(50).forEach { chunk ->
            call("comments.setModerationStatus") {
                val response = data.setModerationStatus(chunk.joinToString(","), status)
                if (!response.isSuccessful) throw HttpException(response)
            }
        }
    }

    suspend fun updateVideoSnippet(videoId: String, snippet: VideoSnippet): VideoDto =
        call("videos.update") { data.updateVideo("snippet", VideoUpdate(videoId, snippet)) }

    suspend fun startUploadSession(metadata: JsonObject, file: File, notifySubscribers: Boolean): String =
        call("videos.insert") { uploader.startSession(metadata, file, notifySubscribers) }

    /** Continues an existing session. Not charged again: the session was paid for at creation. */
    suspend fun continueUpload(sessionUri: String, file: File, offset: Long, onCheckpoint: suspend (Long) -> Unit): VideoDto =
        mapErrors("videos.insert") { uploader.upload(sessionUri, file, offset, onCheckpoint) }

    suspend fun queryUpload(sessionUri: String, total: Long): MediaUploader.UploadProgress =
        mapErrors("videos.insert") { uploader.queryProgress(sessionUri, total) }

    suspend fun setThumbnail(videoId: String, image: File) = call("thumbnails.set") { uploader.setThumbnail(videoId, image) }

    suspend fun insertCaption(videoId: String, language: String, name: String, srt: File) =
        call("captions.insert") { uploader.insertCaption(videoId, language, name, srt) }

    /** Analytics API queries are not part of the Data API quota. Rows are keyed by column name. */
    suspend fun analyticsReport(
        startDate: String,
        endDate: String,
        metrics: String,
        dimensions: String? = null,
        filters: String? = null,
        sort: String? = null,
        maxResults: Int? = null,
    ): List<Map<String, JsonElement>> {
        val report: AnalyticsReport = mapErrors("analytics.reports") {
            analytics.reports(startDate = startDate, endDate = endDate, metrics = metrics, dimensions = dimensions, filters = filters, sort = sort, maxResults = maxResults)
        }
        val names = report.columnHeaders.map { it.name }
        return report.rows.orEmpty().map { row -> names.zip(row).toMap() }
    }

    private fun VideoDto.toSummary() = VideoSummary(
        id = id,
        title = snippet?.title.orEmpty(),
        channelId = snippet?.channelId.orEmpty(),
        channelTitle = snippet?.channelTitle.orEmpty(),
        publishedAtMs = TimeUtil.parseRfc3339(snippet?.publishedAt) ?: 0,
        views = statistics?.viewCount?.toLongOrNull() ?: 0,
        likes = statistics?.likeCount?.toLongOrNull() ?: 0,
        comments = statistics?.commentCount?.toLongOrNull() ?: 0,
        durationSec = parseIsoDuration(contentDetails?.duration.orEmpty()),
        tags = snippet?.tags.orEmpty(),
        description = snippet?.description.orEmpty(),
        privacyStatus = status?.privacyStatus,
    )

    companion object {
        /** Parses ISO-8601 durations such as PT1H2M3S or P1DT5M. */
        fun parseIsoDuration(text: String): Int {
            val match = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matchEntire(text) ?: return 0
            val (d, h, m, s) = match.destructured
            return (d.toIntOrNull() ?: 0) * 86_400 + (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
        }
    }
}
