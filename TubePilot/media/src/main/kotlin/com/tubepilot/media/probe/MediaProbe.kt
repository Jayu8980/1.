package com.tubepilot.media.probe

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class VideoProbe(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val bitrate: Int,
    val sizeBytes: Long,
) {
    /** Display size after rotation. */
    val displayWidth: Int get() = if (rotation % 180 == 0) width else height
    val displayHeight: Int get() = if (rotation % 180 == 0) height else width
}

data class ImageProbe(val width: Int, val height: Int, val sizeBytes: Long)

@Singleton
class MediaProbe @Inject constructor() {

    fun video(file: File): VideoProbe? {
        if (!file.isFile || file.length() == 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            VideoProbe(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
                bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
                sizeBytes = file.length(),
            )
        } catch (e: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun image(file: File): ImageProbe? {
        if (!file.isFile) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return if (o.outWidth <= 0) null else ImageProbe(o.outWidth, o.outHeight, file.length())
    }

    /** True if a render at [path] exists and plays for roughly [expectedMs]. Used to skip finished work on resume. */
    fun isCompleteRender(path: String?, expectedMs: Long, toleranceMs: Long = 1500): Boolean {
        val p = path?.let { video(File(it)) } ?: return false
        return p.hasVideo && p.hasAudio && kotlin.math.abs(p.durationMs - expectedMs) <= toleranceMs
    }
}
