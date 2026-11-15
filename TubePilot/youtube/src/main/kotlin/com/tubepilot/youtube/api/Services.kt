package com.tubepilot.youtube.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

/** YouTube Data API v3 (https://www.googleapis.com/youtube/v3/). Only official endpoints. */
interface YouTubeDataService {
    @GET("channels")
    suspend fun channels(
        @Query("part") part: String,
        @Query("mine") mine: Boolean? = null,
        @Query("id") id: String? = null,
    ): ChannelListResponse

    @GET("search")
    suspend fun search(
        @Query("part") part: String = "snippet",
        @Query("q") query: String? = null,
        @Query("type") type: String = "video",
        @Query("order") order: String = "relevance",
        @Query("publishedAfter") publishedAfter: String? = null,
        @Query("maxResults") maxResults: Int = 25,
        @Query("regionCode") regionCode: String? = null,
        @Query("relevanceLanguage") relevanceLanguage: String? = null,
        @Query("videoDuration") videoDuration: String? = null,
    ): SearchListResponse

    @GET("videos")
    suspend fun videos(
        @Query("part") part: String,
        @Query("id") ids: String,
    ): VideoListResponse

    @PUT("videos")
    suspend fun updateVideo(@Query("part") part: String, @Body body: VideoUpdate): VideoDto

    @GET("playlists")
    suspend fun playlists(
        @Query("part") part: String = "snippet",
        @Query("mine") mine: Boolean = true,
        @Query("maxResults") maxResults: Int = 50,
        @Query("pageToken") pageToken: String? = null,
    ): PlaylistListResponse

    @GET("playlistItems")
    suspend fun playlistItems(
        @Query("part") part: String = "snippet,contentDetails",
        @Query("playlistId") playlistId: String,
        @Query("maxResults") maxResults: Int = 25,
        @Query("pageToken") pageToken: String? = null,
    ): PlaylistItemListResponse

    @POST("playlistItems")
    suspend fun insertPlaylistItem(@Query("part") part: String = "snippet", @Body body: PlaylistItemInsert): PlaylistItemDto

    @GET("commentThreads")
    suspend fun commentThreads(
        @Query("part") part: String = "snippet",
        @Query("allThreadsRelatedToChannelId") channelId: String? = null,
        @Query("videoId") videoId: String? = null,
        @Query("maxResults") maxResults: Int = 100,
        @Query("order") order: String = "time",
        @Query("pageToken") pageToken: String? = null,
        @Query("textFormat") textFormat: String = "plainText",
    ): CommentThreadListResponse

    @POST("commentThreads")
    suspend fun insertCommentThread(@Query("part") part: String = "snippet", @Body body: CommentThreadInsert): CommentThreadDto

    @POST("comments")
    suspend fun insertComment(@Query("part") part: String = "snippet", @Body body: CommentInsert): CommentDto

    @POST("comments/setModerationStatus")
    suspend fun setModerationStatus(
        @Query("id") ids: String,
        @Query("moderationStatus") status: String,
        @Query("banAuthor") banAuthor: Boolean = false,
    ): Response<Unit>
}

/** YouTube Analytics API v2 (https://youtubeanalytics.googleapis.com/v2/). */
interface YouTubeAnalyticsService {
    @GET("reports")
    suspend fun reports(
        @Query("ids") ids: String = "channel==MINE",
        @Query("startDate") startDate: String,
        @Query("endDate") endDate: String,
        @Query("metrics") metrics: String,
        @Query("dimensions") dimensions: String? = null,
        @Query("filters") filters: String? = null,
        @Query("sort") sort: String? = null,
        @Query("maxResults") maxResults: Int? = null,
    ): AnalyticsReport
}
