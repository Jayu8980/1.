package com.autotube.agents.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import com.autotube.agents.core.VideoScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns a [VideoScript] into an MP4 entirely on the phone:
 * on-device TTS narration → AAC, animated Canvas frames → H.264, MediaMuxer → .mp4.
 */
class VideoRenderer(private val context: Context) {

    data class Result(val video: File, val thumbnail: File, val durationSec: Double)

    private class SceneTrack(
        val scene: VideoScript.Scene,
        val startSec: Double,
        val endSec: Double,
        val color: Int,
        val image: Bitmap?,
        val words: List<String>,
    )

    suspend fun render(
        script: VideoScript,
        outDir: File,
        vertical: Boolean,
        languageTag: String,
        channelName: String,
        images: StockImages?,
        progress: (String) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        outDir.mkdirs()
        val width = if (vertical) 1080 else 1280
        val height = if (vertical) 1920 else 720

        // 1) Narration via on-device TTS, one clip per scene.
        progress("Recording narration")
        val tts = TtsEngine.create(context)
        val clips = try {
            tts.configure(languageTag)
            script.scenes.mapIndexed { i, s ->
                ensureActive()
                val wav = File(outDir, "scene_$i.wav")
                tts.synthesize(s.narration, wav)
                Pcm.readWav(wav).resample(SAMPLE_RATE).also { wav.delete() }
            }
        } finally { tts.shutdown() }

        // 2) Lay scenes out on a timeline and build one continuous PCM track.
        val lead = 0.35; val gap = 0.30; val tail = 0.8
        val tracks = ArrayList<SceneTrack>()
        val audio = ArrayList<ShortArray>()
        audio += Pcm.silence(lead, SAMPLE_RATE)
        var t = lead
        script.scenes.forEachIndexed { i, s ->
            ensureActive()
            progress("Finding visuals ${i + 1}/${script.scenes.size}")
            val img = images?.find(s.visual, vertical)
            val start = if (i == 0) 0.0 else t
            val end = t + clips[i].durationSec + gap
            tracks += SceneTrack(s, start, end, parseColor(s.color, i), img, s.narration.split(Regex("\\s+")).filter { it.isNotBlank() })
            audio += clips[i].samples
            audio += Pcm.silence(gap, SAMPLE_RATE)
            t = end
        }
        audio += Pcm.silence(tail, SAMPLE_RATE)
        val total = t + tail
        val pcm = ShortArray(audio.sumOf { it.size }).also { dst -> var o = 0; audio.forEach { a -> a.copyInto(dst, o); o += a.size } }

        // 3) Encode audio first (small), then stream video frames into the muxer.
        progress("Encoding audio")
        val audioEnc = encodeAudio(pcm)

        val out = File(outDir, "video.mp4")
        progress("Rendering ${"%.0f".format(total)}s of video")
        encodeVideo(out, width, height, total, audioEnc, tracks, script, channelName, progress)

        // 4) Thumbnail.
        val thumb = File(outDir, "thumbnail.jpg")
        Thumbnail.render(thumb, script.thumbnailText.ifBlank { script.title }, tracks.firstOrNull()?.image, tracks.firstOrNull()?.color ?: Color.DKGRAY)
        tracks.forEach { it.image?.recycle() }
        Result(out, thumb, total)
    }

    // ------------------------------------------------------------------ audio

    private class EncodedAudio(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    private fun encodeAudio(pcm: ShortArray): EncodedAudio {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> pcm.forEach { b.putShort(it) }; b.flip() }
        val totalBytes = bytes.limit()
        val samples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val pos = bytes.position()
                        val n = min(buf.remaining(), min(8192, bytes.remaining()))
                        val pts = (pos / 2).toLong() * 1_000_000L / SAMPLE_RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val slice = ByteArray(n); bytes.get(slice); buf.put(slice)
                            codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    outIdx >= 0 -> {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val data = ByteArray(info.size); ob.get(data)
                            samples += data to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally { codec.stop(); codec.release() }
        check(totalBytes > 0)
        return EncodedAudio(outFormat ?: error("AAC encoder produced no format"), samples)
    }

    // ------------------------------------------------------------------ video

    private suspend fun encodeVideo(
        out: File, w: Int, h: Int, totalSec: Double, audio: EncodedAudio,
        tracks: List<SceneTrack>, script: VideoScript, channel: String, progress: (String) -> Unit,
    ) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (w * h > 1_500_000) 8_000_000 else 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var started = false
        var framesOut = 0L
        val info = MediaCodec.BufferInfo()
        val painter = FramePainter(w, h, tracks, script, channel, totalSec)

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!endOfStream) return else continue }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    val audioTrack = muxer.addTrack(audio.format)
                    muxer.start(); started = true
                    audio.samples.forEach { (data, bi) -> muxer.writeSampleData(audioTrack, ByteBuffer.wrap(data), bi) }
                    continue
                }
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && started) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    // Canvas-on-surface frames carry wall-clock timestamps; rewrite to exact frame times.
                    info.presentationTimeUs = framesOut * 1_000_000L / FPS
                    framesOut++
                    muxer.writeSampleData(videoTrack, buf, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }

        try {
            val totalFrames = (totalSec * FPS).toInt()
            val frameMs = 1000.0 / FPS
            val t0 = System.nanoTime()
            for (f in 0 until totalFrames) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val canvas = surface.lockHardwareCanvas()
                try { painter.draw(canvas, f.toDouble() / FPS) } finally { surface.unlockCanvasAndPost(canvas) }
                drain(false)
                // Pace submission in real time so the encoder's rate control sees a steady 30 fps.
                val due = t0 + ((f + 1) * frameMs * 1_000_000).toLong()
                val waitMs = (due - System.nanoTime()) / 1_000_000
                if (waitMs > 0) delay(waitMs)
                if (f % (FPS * 5) == 0) progress("Rendering video ${(f * 100 / totalFrames)}%")
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release(); surface.release()
            try { if (started) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
        check(framesOut > 0) { "Video encoder produced no frames" }
    }

    /** Draws one frame of the animated explainer layout. */
    private class FramePainter(
        val w: Int, val h: Int, val tracks: List<SceneTrack>, val script: VideoScript,
        val channel: String, val totalSec: Double,
    ) {
        private val scale = min(w, h) / 1080f
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val shadePaint = Paint()
        private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 92f * scale; setShadowLayer(14f * scale, 0f, 4f * scale, 0xCC000000.toInt())
        }
        private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 62f * scale; setShadowLayer(10f * scale, 0f, 3f * scale, 0xE6000000.toInt())
        }
        private val subBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
        private val markPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); textSize = 34f * scale; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val barBg = Paint().apply { color = 0x55FFFFFF }
        private val barFg = Paint().apply { color = 0xFFFFD54F.toInt() }
        private val highlight = 0xFFFFD54F.toInt()

        fun draw(c: Canvas, t: Double) {
            val idx = tracks.indexOfLast { t >= it.startSec }.coerceAtLeast(0)
            val tr = tracks[idx]
            val local = ((t - tr.startSec) / max(0.01, tr.endSec - tr.startSec)).coerceIn(0.0, 1.0)
            drawBackground(c, tr, idx, t, local)

            // Caption with a pop-in.
            val pop = min(1.0, (t - tr.startSec) / 0.35).toFloat()
            val capScale = 0.8f + 0.2f * easeOut(pop)
            captionPaint.alpha = (255 * pop).toInt()
            val capWidth = (w * 0.86f).toInt()
            val capLayout = StaticLayout.Builder.obtain(tr.scene.caption.uppercase(), 0, tr.scene.caption.length, captionPaint, capWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).setMaxLines(3).build()
            c.save()
            val capY = h * if (h > w) 0.16f else 0.10f
            c.translate((w - capWidth) / 2f, capY)
            c.scale(capScale, capScale, capWidth / 2f, capLayout.height / 2f)
            capLayout.draw(c)
            c.restore()

            drawSubtitle(c, tr, local)

            // Watermark + progress bar.
            if (channel.isNotBlank()) c.drawText(channel, 40f * scale, 64f * scale, markPaint)
            val barH = 10f * scale
            c.drawRect(0f, h - barH, w.toFloat(), h.toFloat(), barBg)
            c.drawRect(0f, h - barH, (w * (t / totalSec)).toFloat(), h.toFloat(), barFg)
        }

        private fun drawBackground(c: Canvas, tr: SceneTrack, idx: Int, t: Double, local: Double) {
            val img = tr.image
            if (img != null) {
                // Ken Burns: slow zoom + drift.
                val zoom = 1.0f + 0.12f * local.toFloat()
                val cover = max(w / img.width.toFloat(), h / img.height.toFloat()) * zoom
                val dw = img.width * cover; val dh = img.height * cover
                val dx = (w - dw) / 2f + (if (idx % 2 == 0) 1 else -1) * 30f * scale * local.toFloat()
                val dy = (h - dh) / 2f
                c.drawBitmap(img, null, RectF(dx, dy, dx + dw, dy + dh), imgPaint)
                shadePaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    intArrayOf(0x99000000.toInt(), 0x22000000, 0xAA000000.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shadePaint)
            } else {
                val a = (t * 0.25).toFloat()
                val x0 = w / 2f + cos(a) * w; val y0 = h / 2f + sin(a) * h
                bgPaint.shader = LinearGradient(x0, y0, w - x0, h - y0, tr.color, darken(tr.color, 0.35f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
                for (k in 0 until 6) {
                    val phase = k * 1.7 + idx * 0.9
                    val bx = (w * (0.5 + 0.42 * sin(t * 0.21 + phase))).toFloat()
                    val by = (h * (0.5 + 0.42 * cos(t * 0.17 + phase * 1.3))).toFloat()
                    val r = (min(w, h) * (0.12 + 0.06 * k % 3)).toFloat()
                    blobPaint.color = if (k % 2 == 0) 0x22FFFFFF else 0x18000000
                    c.drawCircle(bx, by, r, blobPaint)
                }
            }
        }

        /** Karaoke-style subtitle: a window of ~6 words with the spoken word highlighted. */
        private fun drawSubtitle(c: Canvas, tr: SceneTrack, local: Double) {
            val words = tr.words
            if (words.isEmpty()) return
            // Narration ends slightly before the scene (gap), so stretch progress.
            val spoken = (local / 0.92).coerceIn(0.0, 0.999)
            val weights = words.map { it.length + 2 }
            val totalW = weights.sum().toDouble()
            var acc = 0.0; var cur = 0
            for (i in words.indices) { acc += weights[i]; if (acc / totalW > spoken) { cur = i; break } }
            val win = 6
            val start = (cur / win) * win
            val chunk = words.subList(start, min(words.size, start + win))
            val text = chunk.joinToString(" ")
            val span = SpannableString(text)
            var off = 0
            chunk.forEachIndexed { i, wd ->
                if (start + i == cur) span.setSpan(ForegroundColorSpan(highlight), off, off + wd.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                off += wd.length + 1
            }
            val boxW = (w * 0.88f).toInt()
            val layout = StaticLayout.Builder.obtain(span, 0, span.length, subPaint, boxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
            val y = h * if (h > w) 0.66f else 0.70f
            val pad = 24f * scale
            c.drawRoundRect(RectF((w - boxW) / 2f - pad, y - pad, (w + boxW) / 2f + pad, y + layout.height + pad), 28f * scale, 28f * scale, subBox)
            c.save(); c.translate((w - boxW) / 2f, y); layout.draw(c); c.restore()
        }

        private fun easeOut(x: Float) = 1 - (1 - x) * (1 - x)
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val FPS = 30

        fun parseColor(hex: String, i: Int): Int = try { Color.parseColor(hex) } catch (e: Exception) { PALETTE[i % PALETTE.size] }
        private val PALETTE = intArrayOf(0xFF3949AB.toInt(), 0xFF00897B.toInt(), 0xFF8E24AA.toInt(), 0xFFE53935.toInt(), 0xFFF4511E.toInt(), 0xFF1E88E5.toInt())

        fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }
}

/** 1280x720 click-worthy (but honest) thumbnail. */
object Thumbnail {
    fun render(out: File, text: String, image: Bitmap?, color: Int) {
        val w = 1280; val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (image != null) {
            val s = max(w / image.width.toFloat(), h / image.height.toFloat())
            val dw = image.width * s; val dh = image.height * s
            c.drawBitmap(image, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), 0f, 0xDD000000.toInt(), 0x22000000, Shader.TileMode.CLAMP)
            })
        } else {
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), color, VideoRenderer.darken(color, 0.3f), Shader.TileMode.CLAMP)
            })
        }
        val words = text.uppercase().split(Regex("\\s+")).take(6).joinToString(" ")
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 120f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); this.color = Color.WHITE
        }
        val stroke = TextPaint(fill).apply { style = Paint.Style.STROKE; strokeWidth = 14f; this.color = Color.BLACK }
        val boxW = (w * 0.62f).toInt()
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(words, 0, words.length, p, boxW).setMaxLines(4).setLineSpacing(0f, 0.95f).build()
        var lf = layout(fill)
        while (lf.height > h * 0.85 && fill.textSize > 60) { fill.textSize -= 8; stroke.textSize = fill.textSize; lf = layout(fill) }
        val ls = layout(stroke)
        c.save(); c.translate(60f, (h - lf.height) / 2f); ls.draw(c); lf.draw(c); c.restore()
        c.drawRect(0f, h - 18f, w.toFloat(), h.toFloat(), Paint().apply { this.color = 0xFFFFD54F.toInt() })
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
    }
}
