package com.tubepilot.youtube.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Subset of YouTube Data API v3 resources. Every field has a default so partial responses decode.

@Serializable data class PageInfo(val totalResults: Long = 0, val resultsPerPage: Int = 0)
@Serializable data class Thumbnail(val url: String = "", val width: Int = 0, val height: Int = 0)
@Serializable data class Thumbnails(val default: Thumbnail? = null, val medium: Thumbnail? = null, val high: Thumbnail? = null)

@Serializable data class ChannelSnippet(val title: String = "", val description: String = "", val customUrl: String? = null, val thumbnails: Thumbnails? = null, val country: String? = null)
@Serializable data class ChannelStatistics(val viewCount: String = "0", val subscriberCount: String = "0", val videoCount: String = "0", val hiddenSubscriberCount: Boolean = false)
@Serializable data class RelatedPlaylists(val uploads: String = "")
@Serializable data class ChannelContentDetails(val relatedPlaylists: RelatedPlaylists = RelatedPlaylists())
@Serializable data class ChannelDto(
    val id: String = "",
    val snippet: ChannelSnippet = ChannelSnippet(),
    val statistics: ChannelStatistics? = null,
    val contentDetails: ChannelContentDetails? = null,
)
@Serializable data class ChannelListResponse(val items: List<ChannelDto> = emptyList())

@Serializable data class ResourceId(val kind: String = "", val videoId: String? = null, val channelId: String? = null, val playlistId: String? = null)
@Serializable data class SearchSnippet(val title: String = "", val description: String = "", val channelId: String = "", val channelTitle: String = "", val publishedAt: String = "")
@Serializable data class SearchResultDto(val id: ResourceId = ResourceId(), val snippet: SearchSnippet = SearchSnippet())
@Serializable data class SearchListResponse(val items: List<SearchResultDto> = emptyList(), val pageInfo: PageInfo = PageInfo(), val nextPageToken: String? = null)

@Serializable data class VideoSnippet(
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val categoryId: String = "22",
    val publishedAt: String? = null,
    val channelId: String? = null,
    val channelTitle: String? = null,
    val defaultLanguage: String? = null,
    val defaultAudioLanguage: String? = null,
)
@Serializable data class VideoStatistics(val viewCount: String = "0", val likeCount: String = "0", val commentCount: String = "0")
@Serializable data class VideoContentDetails(val duration: String = "")
@Serializable data class VideoStatus(
    val privacyStatus: String = "private",
    val publishAt: String? = null,
    val selfDeclaredMadeForKids: Boolean? = null,
    val containsSyntheticMedia: Boolean? = null,
    val uploadStatus: String? = null,
    val rejectionReason: String? = null,
    val failureReason: String? = null,
)
@Serializable data class VideoDto(
    val id: String = "",
    val snippet: VideoSnippet? = null,
    val statistics: VideoStatistics? = null,
    val contentDetails: VideoContentDetails? = null,
    val status: VideoStatus? = null,
)
@Serializable data class VideoListResponse(val items: List<VideoDto> = emptyList())

@Serializable data class PlaylistSnippet(val title: String = "", val description: String = "")
@Serializable data class PlaylistDto(val id: String = "", val snippet: PlaylistSnippet = PlaylistSnippet())
@Serializable data class PlaylistListResponse(val items: List<PlaylistDto> = emptyList(), val nextPageToken: String? = null)

@Serializable data class PlaylistItemSnippet(val playlistId: String = "", val resourceId: ResourceId = ResourceId(), val title: String = "", val publishedAt: String? = null)
@Serializable data class PlaylistItemContentDetails(val videoId: String = "", val videoPublishedAt: String? = null)
@Serializable data class PlaylistItemDto(val id: String = "", val snippet: PlaylistItemSnippet = PlaylistItemSnippet(), val contentDetails: PlaylistItemContentDetails? = null)
@Serializable data class PlaylistItemListResponse(val items: List<PlaylistItemDto> = emptyList(), val nextPageToken: String? = null)

@Serializable data class AuthorChannelId(val value: String = "")
@Serializable data class CommentSnippet(
    val authorDisplayName: String = "",
    val authorChannelId: AuthorChannelId? = null,
    val textDisplay: String = "",
    val textOriginal: String = "",
    val likeCount: Long = 0,
    val publishedAt: String = "",
    val videoId: String? = null,
    val parentId: String? = null,
    val moderationStatus: String? = null,
)
@Serializable data class CommentDto(val id: String = "", val snippet: CommentSnippet = CommentSnippet())
@Serializable data class CommentThreadSnippet(
    val videoId: String = "",
    val topLevelComment: CommentDto = CommentDto(),
    val totalReplyCount: Int = 0,
    val canReply: Boolean = true,
)
@Serializable data class CommentThreadDto(val id: String = "", val snippet: CommentThreadSnippet = CommentThreadSnippet())
@Serializable data class CommentThreadListResponse(val items: List<CommentThreadDto> = emptyList(), val nextPageToken: String? = null)

// Request bodies.
@Serializable data class CommentInsertSnippet(val parentId: String, val textOriginal: String)
@Serializable data class CommentInsert(val snippet: CommentInsertSnippet)
@Serializable data class TopLevelCommentSnippet(val textOriginal: String)
@Serializable data class TopLevelComment(val snippet: TopLevelCommentSnippet)
@Serializable data class ThreadInsertSnippet(val videoId: String, val topLevelComment: TopLevelComment)
@Serializable data class CommentThreadInsert(val snippet: ThreadInsertSnippet)
@Serializable data class PlaylistItemInsert(val snippet: PlaylistItemSnippet)
@Serializable data class VideoUpdate(val id: String, val snippet: VideoSnippet)

// YouTube Analytics API v2.
@Serializable data class ColumnHeader(val name: String = "", val columnType: String = "", val dataType: String = "")
@Serializable data class AnalyticsReport(val columnHeaders: List<ColumnHeader> = emptyList(), val rows: List<List<JsonElement>>? = null)
