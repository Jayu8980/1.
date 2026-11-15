package com.tubepilot.media.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tubepilot.core.model.ThumbnailVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class ThumbnailLayout(val label: String) {
    LEFT_BOLD("Bold headline left, scrim"),
    BOTTOM_BANNER("Bottom banner"),
    RIGHT_OUTLINE("Outlined headline right"),
}

/**
 * Composes 1280x720 thumbnails (YouTube's recommended size) from a base image and a short
 * headline, and keeps the JPEG under YouTube's 2 MB limit.
 */
@Singleton
class ThumbnailCompositor @Inject constructor() {

    suspend fun compose(base: File?, headline: String, layout: ThumbnailLayout, brandColors: List<Int>, out: File): ThumbnailVariant =
        withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val accent = brandColors.getOrElse(0) { Color.parseColor("#FFD400") }
                val dark = brandColors.getOrElse(1) { Color.parseColor("#111111") }
                drawBase(canvas, base, dark)
                val text = headline.trim().uppercase().ifEmpty { "WATCH THIS" }
                when (layout) {
                    ThumbnailLayout.LEFT_BOLD -> {
                        scrim(canvas, horizontal = true)
                        drawText(canvas, text, RectF(56f, 60f, W * 0.62f, H - 60f), Color.WHITE, accent, Layout.Alignment.ALIGN_NORMAL)
                        canvas.drawRect(56f, H - 48f, 56f + W * 0.25f, H - 32f, Paint().apply { color = accent })
                    }
                    ThumbnailLayout.BOTTOM_BANNER -> {
                        val bannerTop = H * 0.66f
                        canvas.drawRect(0f, bannerTop, W.toFloat(), H.toFloat(), Paint().apply { color = accent })
                        drawText(canvas, text, RectF(40f, bannerTop + 14f, W - 40f, H - 14f), dark, null, Layout.Alignment.ALIGN_CENTER)
                    }
                    ThumbnailLayout.RIGHT_OUTLINE -> {
                        scrim(canvas, horizontal = true, reverse = true)
                        drawText(canvas, text, RectF(W * 0.40f, 60f, W - 56f, H - 60f), accent, Color.BLACK, Layout.Alignment.ALIGN_OPPOSITE)
                    }
                }
                out.parentFile?.mkdirs()
                var quality = 92
                do {
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                    quality -= 8
                } while (out.length() > MAX_BYTES && quality > 40)
                ThumbnailVariant(out.absolutePath, text, layout.label, out.length())
            } finally {
                bitmap.recycle()
            }
        }

    private fun drawBase(canvas: Canvas, base: File?, fallback: Int) {
        val src = base?.takeIf { it.isFile }?.let { decodeSampled(it) }
        if (src == null) {
            canvas.drawColor(fallback)
            return
        }
        try {
            // Centre-crop to 16:9.
            val targetRatio = W.toFloat() / H
            val srcRatio = src.width.toFloat() / src.height
            val crop = if (srcRatio > targetRatio) {
                val w = (src.height * targetRatio).toInt()
                Rect((src.width - w) / 2, 0, (src.width + w) / 2, src.height)
            } else {
                val h = (src.width / targetRatio).toInt()
                Rect(0, (src.height - h) / 3, src.width, (src.height - h) / 3 + h)
            }
            canvas.drawBitmap(src, crop, Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
        } finally {
            src.recycle()
        }
    }

    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= W && bounds.outHeight / (sample * 2) >= H) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scrim(canvas: Canvas, horizontal: Boolean, reverse: Boolean = false) {
        val paint = Paint()
        val dark = Color.argb(200, 0, 0, 0)
        paint.shader = if (horizontal) {
            if (reverse) LinearGradient(W.toFloat(), 0f, W * 0.3f, 0f, dark, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, W * 0.7f, 0f, dark, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, H.toFloat(), 0f, H * 0.4f, dark, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
    }

    /** Draws the largest text size that fits [box] (max 3 lines), with an optional outline. */
    private fun drawText(canvas: Canvas, text: String, box: RectF, fill: Int, outline: Int?, align: Layout.Alignment) {
        val typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        var size = 150f
        var fillLayout: StaticLayout
        while (true) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.typeface = typeface; color = fill }
            fillLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, box.width().toInt())
                .setAlignment(align).setLineSpacing(0f, 0.92f).setIncludePad(false).build()
            val fits = fillLayout.height <= box.height() && fillLayout.lineCount <= 3 &&
                (0 until fillLayout.lineCount).none { fillLayout.getLineWidth(it) > box.width() }
            if (fits || size <= 40f) break
            size -= 6f
        }
        val top = box.top + (box.height() - fillLayout.height) / 2
        canvas.save()
        canvas.translate(box.left, top)
        if (outline != null) {
            val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.typeface = typeface; color = outline
                style = Paint.Style.STROKE; strokeWidth = size * 0.12f; strokeJoin = Paint.Join.ROUND
            }
            StaticLayout.Builder.obtain(text, 0, text.length, strokePaint, box.width().toInt())
                .setAlignment(align).setLineSpacing(0f, 0.92f).setIncludePad(false).build().draw(canvas)
        }
        fillLayout.draw(canvas)
        canvas.restore()
    }

    companion object {
        const val W = 1280
        const val H = 720
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
