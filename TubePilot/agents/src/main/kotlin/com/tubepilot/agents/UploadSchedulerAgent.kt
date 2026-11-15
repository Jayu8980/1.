package com.tubepilot.agents

import com.tubepilot.agents.core.Agent
import com.tubepilot.agents.core.AgentRuntime
import com.tubepilot.agents.core.RetryPolicy
import com.tubepilot.core.domain.MemoryRepository
import com.tubepilot.core.domain.ProjectRepository
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.error.QuotaExhaustedException
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.ChannelProfile
import com.tubepilot.core.model.RenderOutput
import com.tubepilot.core.model.TitleTest
import com.tubepilot.core.model.UploadResult
import com.tubepilot.core.model.UploadSession
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VideoProject
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TimeUtil
import com.tubepilot.youtube.YouTubeClient
import com.tubepilot.youtube.quota.QuotaManager
import com.tubepilot.youtube.upload.MediaUploader
import com.tubepilot.youtube.upload.UploadSessionExpiredException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Pure scheduling rules for publish times. */
object PublishTimePlanner {
    /** YouTube requires a scheduled publishAt to be in the future; keep a safety margin. */
    const val MIN_LEAD_MS = 60 * 60_000L

    /**
     * Returns the publish time: the calendar slot if it is far enough ahead, otherwise the next
     * preferred hour in the channel timezone. Returns null for "publish now" (no preferred hours).
     */
    fun publishAt(slot: Long?, now: Long, profile: ChannelProfile): Long? {
        if (slot != null && slot >= now + MIN_LEAD_MS) return slot
        val hours = profile.preferredPublishHours.filter { it in 0..23 }.sorted()
        if (hours.isEmpty()) return null
        val zone = TimeUtil.zoneOrDefault(profile.timezone)
        val start = Instant.ofEpochMilli(now + MIN_LEAD_MS).atZone(zone)
        for (day in 0..7) {
            val date = start.toLocalDate().plusDays(day.toLong())
            for (h in hours) {
                val candidate = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
                if (candidate >= now + MIN_LEAD_MS) return candidate
            }
        }
        return null
    }

    /** Derived Shorts go out a day apart after the main video. */
    fun derivedOffsetMs(index: Int) = (index + 1) * TimeUtil.DAY
}

/** Resumable uploads, scheduling, playlists, captions, thumbnail and pinned comment. */
@Singleton
class UploadSchedulerAgent @Inject constructor(
    runtime: AgentRuntime,
    private val youtube: YouTubeClient,
    private val quota: QuotaManager,
    private val projects: ProjectRepository,
    private val memory: MemoryRepository,
    private val time: TimeSource,
) : Agent(AgentRole.UPLOAD_SCHEDULER, runtime) {
    override val rolePrompt = "You are the Upload & Scheduler agent: reliable uploads at the right time, using the official YouTube Data API only."
    override val skillIds = listOf("subtitle_generator")
    override val retryPolicy = RetryPolicy(maxAttempts = 8, initialBackoffMs = 2 * 60_000)

    /** Quota needed to publish one video with all extras. */
    fun quotaPlan(withPlaylist: Boolean, withComment: Boolean) = buildMap {
        put("videos.insert", 1)
        put("thumbnails.set", 1)
        put("captions.insert", 1)
        if (withPlaylist) put("playlistItems.insert", 1)
        if (withComment) put("commentThreads.insert", 1)
    }

    /**
     * Uploads the primary render and any derived Shorts that are not uploaded yet. Safe to call
     * repeatedly: finished uploads are skipped and in-flight sessions are resumed.
     */
    suspend fun publish(project: VideoProject, onProgress: (Int) -> Unit = {}): List<UploadResult> = task("Upload '${project.title}'", project.id) {
        val edit = requireNotNull(project.artifacts.edit) { "edit missing" }
        val seo = requireNotNull(project.artifacts.seo) { "metadata missing" }
        val profile = memory.profile()
        val settings = runtime.settings.current()
        val now = time.now()
        val mainAt = PublishTimePlanner.publishAt(project.scheduledPublishAt, now, profile)
        val queue = listOf(edit.primary to mainAt) + edit.derivedShorts.mapIndexed { i, short ->
            short to (mainAt ?: now).plus(PublishTimePlanner.derivedOffsetMs(i))
        }
        val results = project.artifacts.uploads.toMutableList()
        for ((index, entry) in queue.withIndex()) {
            val (render, publishAt) = entry
            if (results.any { it.renderPath == render.videoPath }) continue
            val withPlaylist = profile.defaultPlaylistId != null
            val withComment = index == 0 && seo.pinnedComment.isNotBlank()
            val existingSession = projects.get(project.id)?.artifacts?.uploadSessions?.firstOrNull { it.renderPath == render.videoPath }
            if (existingSession == null && !quota.canAfford(quotaPlan(withPlaylist, withComment))) {
                throw QuotaExhaustedException("videos.insert", quota.nextResetMs())
            }
            val title = if (index == 0) seo.title else derivedTitle(seo.title, render)
            results += uploadOne(project, render, title, seo, publishAt, settings.defaultPrivacy, profile, withPlaylist, withComment, onProgress)
            projects.update(project.id) { p -> p.copy(artifacts = p.artifacts.copy(uploads = results.toList())) }
        }
        // Start the title A/B test on the main video when there are alternative titles.
        val main = results.firstOrNull { it.renderPath == edit.primary.videoPath }
        if (main != null && seo.titleVariants.size > 1 && project.artifacts.titleTest == null) {
            projects.update(project.id) { p ->
                p.copy(artifacts = p.artifacts.copy(titleTest = TitleTest(main.videoId, seo.titleVariants.take(3), 0, switchedAt = main.publishAtIso?.let { TimeUtil.parseRfc3339(it) } ?: now)))
            }
        }
        results
    }

    @Suppress("UNUSED_PARAMETER")
    private fun derivedTitle(mainTitle: String, render: RenderOutput): String = mainTitle.take(90).trimEnd() + " #Shorts"

    private suspend fun uploadOne(
        project: VideoProject,
        render: RenderOutput,
        title: String,
        seo: com.tubepilot.core.model.SeoPackage,
        publishAt: Long?,
        defaultPrivacy: String,
        profile: ChannelProfile,
        withPlaylist: Boolean,
        withComment: Boolean,
        onProgress: (Int) -> Unit,
    ): UploadResult {
        val file = File(render.videoPath)
        require(file.isFile) { "Render file missing: ${file.name}" }
        val compliance = project.artifacts.compliance
        // Scheduled publishing requires privacyStatus=private plus publishAt.
        val privacy = if (publishAt != null) "private" else defaultPrivacy
        val language = project.language.youtubeCode
        val metadata = buildJsonObject {
            put("snippet", buildJsonObject {
                put("title", title)
                put("description", seo.fullDescription())
                put("tags", buildJsonArray { seo.tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("categoryId", seo.categoryId.ifBlank { profile.defaultCategoryId })
                put("defaultLanguage", language)
                put("defaultAudioLanguage", language)
            })
            put("status", buildJsonObject {
                put("privacyStatus", privacy)
                publishAt?.let { put("publishAt", TimeUtil.rfc3339(it)) }
                put("selfDeclaredMadeForKids", compliance?.madeForKids ?: profile.madeForKids)
                // YouTube's altered or synthetic content disclosure.
                put("containsSyntheticMedia", compliance?.requiresAiDisclosure ?: false)
                put("embeddable", true)
            })
        }
        val video = uploadWithResume(project.id, render, file, metadata, onProgress)
        val videoId = video.id
        val actualPrivacy = video.status?.privacyStatus ?: privacy
        runtime.audit.record(role.displayName, "upload.done", "Uploaded '$title' as $videoId ($actualPrivacy${publishAt?.let { ", publishes ${TimeUtil.rfc3339(it)}" } ?: ""})", project.id, AuditLevel.INFO)
        if (publishAt == null && privacy == "public" && actualPrivacy == "private") {
            runtime.audit.record(role.displayName, "upload.locked_private",
                "YouTube kept the video private. Unverified Google Cloud projects are restricted to private uploads until they pass the API compliance audit.",
                project.id, AuditLevel.WARNING)
        }

        val thumbnailSet = project.artifacts.thumbnails?.selected?.let { thumb ->
            if (render.format == VideoFormat.SHORT && render.derivedFromSec != null) return@let false
            runCatching { youtube.setThumbnail(videoId, File(thumb.path)); true }.getOrElse {
                runtime.audit.record(role.displayName, "thumbnail.failed",
                    "Custom thumbnail not set (${it.message?.take(160)}). Custom thumbnails need a verified YouTube account.", project.id, AuditLevel.WARNING)
                false
            }
        } ?: false
        val captions = render.srtPath.takeIf { it.isNotBlank() && File(it).isFile }?.let { srt ->
            runCatching { youtube.insertCaption(videoId, language, project.language.displayName, File(srt)); true }.getOrElse {
                runtime.audit.record(role.displayName, "captions.failed", "Captions upload failed: ${it.message?.take(160)}", project.id, AuditLevel.WARNING)
                false
            }
        } ?: false
        val playlist = profile.defaultPlaylistId?.takeIf { withPlaylist }?.let { id ->
            runCatching { youtube.addToPlaylist(id, videoId); id }.getOrNull()
        }
        val commentPosted = withComment && runCatching { youtube.postComment(videoId, seo.pinnedComment); true }.getOrElse {
            runtime.audit.record(role.displayName, "comment.failed", "Pinned comment not posted: ${it.message?.take(160)}", project.id, AuditLevel.WARNING)
            false
        }
        return UploadResult(
            videoId = videoId,
            renderPath = render.videoPath,
            format = render.format,
            title = title,
            privacyStatus = actualPrivacy,
            publishAtIso = publishAt?.let { TimeUtil.rfc3339(it) },
            thumbnailSet = thumbnailSet,
            captionsUploaded = captions,
            playlistId = playlist,
            pinnedCommentPosted = commentPosted,
            uploadedAt = time.now(),
        )
    }

    /** Creates or resumes the resumable session, checkpointing confirmed bytes into the project. */
    private suspend fun uploadWithResume(projectId: String, render: RenderOutput, file: File, metadata: JsonObject, onProgress: (Int) -> Unit): com.tubepilot.youtube.api.VideoDto {
        var session = projects.get(projectId)?.artifacts?.uploadSessions?.firstOrNull { it.renderPath == render.videoPath }
        repeat(2) { attempt ->
            if (session == null || session!!.totalBytes != file.length()) {
                val uri = youtube.startUploadSession(metadata, file, notifySubscribers = true)
                session = UploadSession(render.videoPath, uri, file.length(), 0, time.now())
                saveSession(projectId, session!!)
            }
            val s = session!!
            try {
                val offset = when (val progress = youtube.queryUpload(s.sessionUri, s.totalBytes)) {
                    is MediaUploader.UploadProgress.Complete -> return progress.video.also { clearSession(projectId, render.videoPath) }
                    is MediaUploader.UploadProgress.Incomplete -> progress.confirmedBytes
                }
                val video = youtube.continueUpload(s.sessionUri, file, offset) { confirmed ->
                    onProgress(((confirmed * 100) / s.totalBytes.coerceAtLeast(1)).toInt())
                    saveSession(projectId, s.copy(confirmedBytes = confirmed))
                }
                clearSession(projectId, render.videoPath)
                return video
            } catch (e: UploadSessionExpiredException) {
                runtime.audit.record(role.displayName, "upload.session_expired", "Session expired; starting a new upload (attempt ${attempt + 1})", projectId, AuditLevel.WARNING)
                clearSession(projectId, render.videoPath)
                session = null
            }
        }
        throw ProviderException("YouTube upload", "Upload session kept expiring", null, retryable = true)
    }

    private suspend fun saveSession(projectId: String, session: UploadSession) {
        projects.update(projectId) { p ->
            p.copy(artifacts = p.artifacts.copy(uploadSessions = p.artifacts.uploadSessions.filter { it.renderPath != session.renderPath } + session))
        }
    }

    private suspend fun clearSession(projectId: String, renderPath: String) {
        projects.update(projectId) { p -> p.copy(artifacts = p.artifacts.copy(uploadSessions = p.artifacts.uploadSessions.filter { it.renderPath != renderPath })) }
    }
}
