package com.tubepilot.youtube.upload

import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.util.TubeJson
import com.tubepilot.youtube.api.VideoDto
import com.tubepilot.youtube.di.AuthorizedClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/** The upload session expired or was cancelled server side; start a new session. */
class UploadSessionExpiredException : IOException("Resumable upload session expired")

/**
 * YouTube resumable uploads (https://developers.google.com/youtube/v3/guides/using_resumable_upload_protocol).
 * The session URI is persisted by the caller after [startSession] so an interrupted upload, even
 * across a reboot, resumes from the last byte the server confirmed instead of starting over.
 */
@Singleton
class MediaUploader @Inject constructor(
    @AuthorizedClient private val http: OkHttpClient,
) {
    internal var uploadBase = "https://www.googleapis.com/upload/youtube/v3"

    /** Creates an upload session for [file] with the given video resource metadata. */
    suspend fun startSession(metadata: JsonObject, file: File, notifySubscribers: Boolean): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$uploadBase/videos?uploadType=resumable&part=snippet,status&notifySubscribers=$notifySubscribers")
            .header("X-Upload-Content-Length", file.length().toString())
            .header("X-Upload-Content-Type", "video/mp4")
            .post(metadata.toString().toRequestBody(JSON))
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw httpError(response.code, response.body?.string())
            response.header("Location") ?: throw ProviderException("YouTube upload", "No session URI returned", null, retryable = true)
        }
    }

    /** Asks the server how many bytes it has. Throws [UploadSessionExpiredException] if the session is gone. */
    suspend fun queryProgress(sessionUri: String, totalBytes: Long): UploadProgress = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(sessionUri)
            .header("Content-Range", "bytes */$totalBytes")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        execute(request).use { response -> interpret(response.code, response.header("Range"), response.body?.string()) }
    }

    /**
     * Uploads [file] in chunks starting at [startOffset]. [onCheckpoint] receives each byte count the
     * server confirmed so the caller can persist it. Returns the created video resource.
     */
    suspend fun upload(
        sessionUri: String,
        file: File,
        startOffset: Long,
        onCheckpoint: suspend (confirmedBytes: Long) -> Unit,
    ): VideoDto = withContext(Dispatchers.IO) {
        val total = file.length()
        var offset = startOffset
        var failures = 0
        val buffer = ByteArray(CHUNK_SIZE)
        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                currentCoroutineContext().ensureActive()
                if (offset >= total) {
                    // Everything was sent before an interruption; ask the server for the final resource.
                    when (val p = queryProgress(sessionUri, total)) {
                        is UploadProgress.Complete -> return@withContext p.video
                        is UploadProgress.Incomplete -> offset = p.confirmedBytes
                    }
                    continue
                }
                val length = minOf(CHUNK_SIZE.toLong(), total - offset).toInt()
                raf.seek(offset)
                raf.readFully(buffer, 0, length)
                val request = Request.Builder()
                    .url(sessionUri)
                    .header("Content-Range", "bytes $offset-${offset + length - 1}/$total")
                    .put(buffer.toRequestBody(VIDEO, 0, length))
                    .build()
                val progress = try {
                    execute(request).use { interpret(it.code, it.header("Range"), it.body?.string()) }
                } catch (e: UploadSessionExpiredException) {
                    throw e
                } catch (e: IOException) {
                    null
                } catch (e: ProviderException) {
                    if (!e.retryable) throw e
                    null
                }
                when (progress) {
                    is UploadProgress.Complete -> {
                        onCheckpoint(total)
                        return@withContext progress.video
                    }
                    is UploadProgress.Incomplete -> {
                        offset = progress.confirmedBytes
                        failures = 0
                        onCheckpoint(offset)
                    }
                    null -> {
                        failures++
                        if (failures > MAX_CHUNK_FAILURES) {
                            throw ProviderException("YouTube upload", "Chunk upload failed $failures times", null, retryable = true)
                        }
                        delay(1_000L * (1L shl failures.coerceAtMost(6)))
                        // Re-sync with the server: the failed chunk may have been partly stored.
                        offset = when (val p = queryProgress(sessionUri, total)) {
                            is UploadProgress.Complete -> return@withContext p.video
                            is UploadProgress.Incomplete -> p.confirmedBytes
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    /** thumbnails.set: JPEG/PNG up to 2 MB. Requires a verified channel for custom thumbnails. */
    suspend fun setThumbnail(videoId: String, image: File) = withContext(Dispatchers.IO) {
        val mime = if (image.extension.equals("png", true)) "image/png" else "image/jpeg"
        val request = Request.Builder()
            .url("$uploadBase/thumbnails/set?videoId=$videoId&uploadType=media")
            .post(image.asRequestBody(mime.toMediaType()))
            .build()
        execute(request).use { if (!it.isSuccessful) throw httpError(it.code, it.body?.string()) }
    }

    /** captions.insert with a multipart/related body (metadata + SRT file). */
    suspend fun insertCaption(videoId: String, language: String, name: String, srt: File) = withContext(Dispatchers.IO) {
        val metadata = """{"snippet":{"videoId":"$videoId","language":"$language","name":${JsonPrimitive(name)},"isDraft":false}}"""
        val body: RequestBody = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(srt.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$uploadBase/captions?part=snippet&uploadType=multipart")
            .post(body)
            .build()
        execute(request).use { if (!it.isSuccessful) throw httpError(it.code, it.body?.string()) }
    }

    private fun interpret(code: Int, range: String?, body: String?): UploadProgress = when (code) {
        200, 201 -> UploadProgress.Complete(TubeJson.decodeFromString(VideoDto.serializer(), body.orEmpty()))
        308 -> UploadProgress.Incomplete(range?.substringAfter("bytes=")?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: 0L)
        404, 410 -> throw UploadSessionExpiredException()
        else -> throw httpError(code, body)
    }

    private fun execute(request: Request) = http.newCall(request).execute()

    private fun httpError(code: Int, body: String?) = ProviderException(
        "YouTube upload", "HTTP $code: ${body.orEmpty().take(600)}", code,
        retryable = code == 408 || code == 429 || code >= 500,
    )

    sealed interface UploadProgress {
        data class Incomplete(val confirmedBytes: Long) : UploadProgress
        data class Complete(val video: VideoDto) : UploadProgress
    }

    private companion object {
        // Chunks must be multiples of 256 KiB. 8 MiB balances memory use and request overhead.
        const val CHUNK_SIZE = 32 * 256 * 1024
        const val MAX_CHUNK_FAILURES = 8
        val JSON = "application/json; charset=UTF-8".toMediaType()
        val VIDEO = "video/mp4".toMediaType()
    }
}
