package com.tubepilot.media.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.tubepilot.core.error.ProviderException
import com.tubepilot.core.model.VideoFormat
import com.tubepilot.core.model.VisualKind
import com.tubepilot.media.subtitle.Cue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SceneClip(
    val path: String,
    val kind: VisualKind,
    /** Position of the scene on the output timeline. */
    val startMs: Long,
    val durationMs: Long,
    val onScreenText: String = "",
    /** For video sources: where to start reading inside the clip. */
    val sourceOffsetMs: Long = 0,
)

data class RenderSpec(
    val format: VideoFormat,
    val scenes: List<SceneClip>,
    val audioPath: String,
    val audioStartMs: Long = 0,
    val audioEndMs: Long? = null,
    val cues: List<Cue>,
    val output: File,
    val burnSubtitles: Boolean = true,
    val fps: Int = 30,
    val accentColor: Int = Color.parseColor("#FFD400"),
)

/**
 * Renders a video entirely on the device with Media3 Transformer, using hardware encoders. Memory
 * stays low because frames stream through GPU textures; only the current caption bitmap is held.
 * Output goes to a temporary file that is renamed on success, so a killed job never leaves a
 * truncated video that a resumed job would mistake for a finished one.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Singleton
class VideoRenderer @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun render(spec: RenderSpec, onProgress: (Int) -> Unit = {}): File {
        require(spec.scenes.isNotEmpty()) { "Nothing to render" }
        val tmp = File(spec.output.parentFile, spec.output.nameWithoutExtension + ".part.mp4")
        tmp.delete()
        val composition = buildComposition(spec)
        export(composition, tmp, bitrateFor(spec.format), onProgress)
        spec.output.delete()
        if (!tmp.renameTo(spec.output)) throw java.io.IOException("Could not move render into place")
        return spec.output
    }

    private fun bitrateFor(format: VideoFormat) = if (format == VideoFormat.SHORT) 10_000_000 else 12_000_000

    internal fun buildComposition(spec: RenderSpec): Composition {
        val w = spec.format.width
        val h = spec.format.height
        val items = spec.scenes.mapIndexed { index, scene ->
            val startUs = scene.startMs * 1000
            val durUs = scene.durationMs * 1000
            val clock = LocalClock(startUs)
            val effects = mutableListOf<androidx.media3.common.Effect>(
                Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
            )
            if (scene.kind == VisualKind.IMAGE) effects += KenBurns(clock, durUs, zoomIn = index % 2 == 0)
            effects += Fade(clock, durUs, fadeInUs = if (index == 0) 0 else FADE_US, fadeOutUs = if (index == spec.scenes.lastIndex) FADE_US * 2 else FADE_US)
            val overlays = mutableListOf<TextureOverlay>()
            if (scene.onScreenText.isNotBlank()) overlays += HeadlineOverlay(clock, scene.onScreenText, w, h, spec.accentColor)
            if (spec.burnSubtitles) {
                val sceneCues = spec.cues.filter { it.endMs > scene.startMs && it.startMs < scene.startMs + scene.durationMs }
                    .map { Cue(it.startMs - scene.startMs, it.endMs - scene.startMs, it.text) }
                if (sceneCues.isNotEmpty()) overlays += CaptionOverlay(clock, sceneCues, w, h, spec.format)
            }
            if (overlays.isNotEmpty()) effects += OverlayEffect(overlays)

            val mediaItem = when (scene.kind) {
                VisualKind.IMAGE -> MediaItem.Builder()
                    .setUri(Uri.fromFile(File(scene.path)))
                    .setImageDurationMs(scene.durationMs)
                    .build()
                VisualKind.VIDEO -> MediaItem.Builder()
                    .setUri(Uri.fromFile(File(scene.path)))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(scene.sourceOffsetMs)
                            .setEndPositionMs(scene.sourceOffsetMs + scene.durationMs)
                            .build(),
                    )
                    .build()
            }
            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(true)
                .setFrameRate(spec.fps)
                .setEffects(Effects(emptyList(), effects))
                .build()
        }
        val videoSequence = EditedMediaItemSequence.Builder(items).build()

        val audioItem = MediaItem.Builder()
            .setUri(Uri.fromFile(File(spec.audioPath)))
            .apply {
                if (spec.audioStartMs > 0 || spec.audioEndMs != null) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(spec.audioStartMs)
                            .apply { spec.audioEndMs?.let { setEndPositionMs(it) } }
                            .build(),
                    )
                }
            }
            .build()
        val audioSequence = EditedMediaItemSequence.Builder(EditedMediaItem.Builder(audioItem).setRemoveVideo(true).build()).build()
        return Composition.Builder(videoSequence, audioSequence).build()
    }

    private suspend fun export(composition: Composition, out: File, bitrate: Int, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.Main) {
            coroutineScope {
                var transformer: Transformer? = null
                val poller = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        delay(500)
                        val t = transformer ?: continue
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    }
                }
                try {
                    suspendCancellableCoroutine<Unit> { cont ->
                        val t = Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(
                                DefaultEncoderFactory.Builder(context)
                                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                                    .setEnableFallback(true)
                                    .build(),
                            )
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (cont.isActive) cont.resume(Unit)
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (cont.isActive) {
                                        cont.resumeWithException(
                                            ProviderException(
                                                "Media3", "Render failed (${exportException.errorCodeName}): ${exportException.message}", exportException.errorCode,
                                                // Codec/IO hiccups are retryable; malformed input is not.
                                                retryable = exportException.errorCode !in NON_RETRYABLE, cause = exportException,
                                            ),
                                        )
                                    }
                                }
                            })
                            .build()
                        transformer = t
                        cont.invokeOnCancellation { t.cancel() }
                        t.start(composition, out.absolutePath)
                    }
                } finally {
                    poller.cancel()
                }
            }
        }

    private companion object {
        const val FADE_US = 250_000L
        val NON_RETRYABLE = setOf(
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
    }
}

/**
 * Converts the timestamps Media3 hands to effects into time since the start of this scene. Media3
 * versions differ on whether those timestamps are sequence-relative or item-relative, so the mode
 * is detected from the first frame the effect sees.
 */
internal class LocalClock(private val itemStartUs: Long) {
    @Volatile private var mode = 0 // 0 unknown, 1 global, 2 local

    fun local(presentationTimeUs: Long): Long {
        if (itemStartUs == 0L) return presentationTimeUs
        if (mode == 0) mode = if (presentationTimeUs >= itemStartUs - 50_000) 1 else 2
        return if (mode == 1) (presentationTimeUs - itemStartUs).coerceAtLeast(0) else presentationTimeUs
    }
}

/** Slow zoom (and slight drift) across a still image. */
@UnstableApi
internal class KenBurns(private val clock: LocalClock, private val durationUs: Long, private val zoomIn: Boolean) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val p = (clock.local(presentationTimeUs).toFloat() / durationUs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val eased = p * p * (3 - 2 * p)
        val scale = if (zoomIn) 1.0f + 0.10f * eased else 1.10f - 0.10f * eased
        val drift = (if (zoomIn) 0.02f else -0.02f) * eased
        return Matrix().apply {
            postScale(scale, scale)
            postTranslate(drift, 0f)
        }
    }
}

/** Fade-through-black transitions at scene boundaries. */
@UnstableApi
internal class Fade(private val clock: LocalClock, private val durationUs: Long, private val fadeInUs: Long, private val fadeOutUs: Long) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val t = clock.local(presentationTimeUs)
        val a = when {
            fadeInUs > 0 && t < fadeInUs -> t.toFloat() / fadeInUs
            fadeOutUs > 0 && t > durationUs - fadeOutUs -> ((durationUs - t).toFloat() / fadeOutUs).coerceAtLeast(0f)
            else -> 1f
        }
        return floatArrayOf(
            a, 0f, 0f, 0f,
            0f, a, 0f, 0f,
            0f, 0f, a, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

/** Burned-in captions: white heavy text with a dark outline, near the bottom (above Shorts UI). */
@UnstableApi
internal class CaptionOverlay(
    private val clock: LocalClock,
    private val cues: List<Cue>,
    private val frameWidth: Int,
    frameHeight: Int,
    format: VideoFormat,
) : BitmapOverlay() {
    private val textSize = if (format == VideoFormat.SHORT) frameWidth * 0.068f else frameHeight * 0.058f
    private val boxHeight = (textSize * 3.4f).toInt()
    private val empty: Bitmap = Bitmap.createBitmap(frameWidth, boxHeight, Bitmap.Config.ARGB_8888)
    private var currentIndex = -2
    private var current: Bitmap = empty
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, if (format == VideoFormat.SHORT) -0.42f else -0.72f)
        .setOverlayFrameAnchor(0f, 0f)
        .build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val ms = clock.local(presentationTimeUs) / 1000
        val index = cues.indexOfFirst { ms >= it.startMs && ms < it.endMs }
        if (index != currentIndex) {
            currentIndex = index
            current = if (index < 0) empty else draw(cues[index].text)
        }
        return current
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private fun draw(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(frameWidth, boxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = this@CaptionOverlay.textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val width = (frameWidth * 0.88f).toInt()
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).build()
        val stroke = TextPaint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = textSize * 0.16f
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val fill = TextPaint(paint).apply { color = Color.WHITE }
        val strokeLayout = layout(stroke)
        val fillLayout = layout(fill)
        canvas.save()
        canvas.translate((frameWidth - width) / 2f, (boxHeight - fillLayout.height) / 2f)
        strokeLayout.draw(canvas)
        fillLayout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}

/** On-screen headline for the first few seconds of a scene. */
@UnstableApi
internal class HeadlineOverlay(
    private val clock: LocalClock,
    text: String,
    frameWidth: Int,
    frameHeight: Int,
    accent: Int,
) : BitmapOverlay() {
    private val bitmap: Bitmap
    private val blank: Bitmap
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, 0.68f)
        .setOverlayFrameAnchor(0f, 0f)
        .build()

    init {
        val size = minOf(frameWidth, frameHeight) * 0.07f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = Color.BLACK
        }
        val label = text.uppercase().take(48)
        val maxWidth = (frameWidth * 0.86f).toInt()
        val layout = StaticLayout.Builder.obtain(label, 0, label.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).build()
        val padding = size * 0.45f
        val h = (layout.height + padding * 2).toInt()
        bitmap = Bitmap.createBitmap(frameWidth, h, Bitmap.Config.ARGB_8888)
        blank = Bitmap.createBitmap(frameWidth, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val lineWidth = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        val left = (frameWidth - lineWidth) / 2f - padding
        canvas.drawRoundRect(RectF(left, 0f, frameWidth - left, h.toFloat()), size * 0.3f, size * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        canvas.save()
        canvas.translate((frameWidth - maxWidth) / 2f, padding)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap =
        if (clock.local(presentationTimeUs) < VISIBLE_US) bitmap else blank

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private companion object {
        const val VISIBLE_US = 2_800_000L
    }
}
