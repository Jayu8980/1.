package com.tradelens.app.ui.chart

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.Zone
import com.tradelens.app.ui.components.fmtPrice
import com.tradelens.app.ui.components.fmtTime
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Bull
import com.tradelens.app.ui.theme.Grid
import com.tradelens.app.ui.theme.Muted
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DrawTool { NONE, TREND, HLINE, FIB }

data class Drawing(val tool: DrawTool, val i1: Double, val p1: Double, val i2: Double = i1, val p2: Double = p1)

data class Overlay(val values: DoubleArray, val color: Color, val dashed: Boolean = false)

sealed interface SubPane {
    data class Oscillator(val name: String, val values: DoubleArray, val lines: List<Double>, val min: Double? = null, val max: Double? = null) : SubPane
    data class Histogram(val name: String, val hist: DoubleArray, val a: DoubleArray, val b: DoubleArray) : SubPane
}

/**
 * Canvas candlestick chart: pinch-zoom & pan, long-press crosshair, drawing tools (trendline / horizontal
 * level / Fibonacci retracement), indicator overlays, S/R zones and one oscillator sub-pane.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    overlays: List<Overlay>,
    zones: List<Zone>,
    sub: SubPane?,
    tool: DrawTool,
    drawings: List<Drawing>,
    onAddDrawing: (Drawing) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(420.dp),
) {
    if (candles.isEmpty()) return
    var visible by remember(candles.size) { mutableFloatStateOf(min(120f, candles.size.toFloat())) }
    var rightOffset by remember(candles.size) { mutableFloatStateOf(0f) } // bars hidden on the right
    var cross by remember { mutableStateOf<Offset?>(null) }
    var pending by remember(tool) { mutableStateOf<Pair<Double, Double>?>(null) }
    val textPaint = remember { Paint().apply { color = android.graphics.Color.rgb(176, 188, 203); textSize = 26f; isAntiAlias = true } }
    val labelBg = remember { Paint().apply { color = android.graphics.Color.rgb(36, 48, 66) } }

    // Shared geometry for gestures and drawing.
    class Geo(val size: Size) {
        val axisW = 120f
        val plotW = size.width - axisW
        val mainH = if (sub != null) size.height * 0.72f else size.height - 30f
        val subTop = mainH + 24f
        val subH = size.height - subTop - 28f
        val vis = visible.roundToInt().coerceIn(10, candles.size)
        val end = (candles.size - rightOffset.roundToInt()).coerceIn(vis, candles.size)
        val start = end - vis
        val barW = plotW / vis
        val slice = candles.subList(start, end)
        val hi: Double; val lo: Double
        init {
            var h = slice.maxOf { it.high }; var l = slice.minOf { it.low }
            overlays.forEach { o -> for (k in start until end) { val v = o.values.getOrNull(k) ?: continue; if (!v.isNaN()) { h = max(h, v); l = min(l, v) } } }
            val pad = (h - l) * 0.05; hi = h + pad; lo = l - pad
        }
        fun x(i: Double) = ((i - start) * barW + barW / 2).toFloat()
        fun y(p: Double) = (mainH - (p - lo) / (hi - lo) * mainH).toFloat()
        fun idx(px: Float) = start + px / barW - 0.5
        fun price(py: Float) = lo + (mainH - py) / mainH * (hi - lo)
    }

    Canvas(
        modifier
            .pointerInput(candles.size, tool) {
                if (tool == DrawTool.NONE) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        visible = (visible / zoom).coerceIn(10f, candles.size.toFloat())
                        val barW = (size.width - 120f) / visible
                        rightOffset = (rightOffset + pan.x / barW).coerceIn(0f, (candles.size - visible).coerceAtLeast(0f))
                    }
                }
            }
            .pointerInput(candles.size, tool) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { cross = it }, onDragEnd = { cross = null }, onDragCancel = { cross = null },
                    onDrag = { ch, _ -> cross = ch.position },
                )
            }
            .pointerInput(candles.size, tool, visible, rightOffset) {
                detectTapGestures { pos ->
                    if (tool == DrawTool.NONE) { cross = if (cross == null) pos else null; return@detectTapGestures }
                    val g = Geo(Size(size.width.toFloat(), size.height.toFloat()))
                    if (pos.y > g.mainH) return@detectTapGestures
                    val pt = g.idx(pos.x) to g.price(pos.y)
                    when {
                        tool == DrawTool.HLINE -> onAddDrawing(Drawing(DrawTool.HLINE, pt.first, pt.second))
                        pending == null -> pending = pt
                        else -> { onAddDrawing(Drawing(tool, pending!!.first, pending!!.second, pt.first, pt.second)); pending = null }
                    }
                }
            },
    ) {
        val g = Geo(size)
        // grid + price axis
        for (k in 0..5) {
            val p = g.lo + (g.hi - g.lo) * k / 5
            val y = g.y(p)
            drawLine(Grid, Offset(0f, y), Offset(g.plotW, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(fmtPrice(p), g.plotW + 8f, y + 9f, textPaint)
        }
        // S/R zones
        zones.forEach { z ->
            val y1 = g.y(z.high); val y2 = g.y(z.low)
            if (y2 >= 0 && y1 <= g.mainH) drawRect((if (z.isSupport) Bull else Bear).copy(alpha = 0.10f), Offset(0f, y1), Size(g.plotW, max(2f, y2 - y1)))
        }
        // volume (bottom 18% of main pane)
        val maxVol = g.slice.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
        g.slice.forEachIndexed { k, c ->
            val i = g.start + k
            val h = (c.volume / maxVol * g.mainH * 0.18).toFloat()
            drawRect((if (c.close >= c.open) Bull else Bear).copy(alpha = 0.25f), Offset(g.x(i.toDouble()) - g.barW * 0.35f, g.mainH - h), Size(g.barW * 0.7f, h))
        }
        // candles
        g.slice.forEachIndexed { k, c ->
            val i = (g.start + k).toDouble()
            val col = if (c.close >= c.open) Bull else Bear
            val cx = g.x(i)
            drawLine(col, Offset(cx, g.y(c.high)), Offset(cx, g.y(c.low)), strokeWidth = max(1f, g.barW * 0.12f))
            val top = g.y(max(c.open, c.close)); val bot = g.y(min(c.open, c.close))
            drawRect(col, Offset(cx - g.barW * 0.35f, top), Size(max(1f, g.barW * 0.7f), max(1f, bot - top)))
        }
        // overlays
        overlays.forEach { o -> drawSeries(o.values, g.start, g.end, { g.x(it.toDouble()) }, { g.y(it) }, o.color, o.dashed) }
        // drawings
        drawings.forEach { d -> drawDrawing(d, g.plotW, { g.x(it) }, { g.y(it) }, textPaint) }
        pending?.let { (i, p) -> drawCircle(Color.White, 6f, Offset(g.x(i), g.y(p))) }
        // last price line
        val last = candles.last().close
        val ly = g.y(last)
        if (ly in 0f..g.mainH) {
            drawLine(Color(0xFF7AA2F7), Offset(0f, ly), Offset(g.plotW, ly), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawContext.canvas.nativeCanvas.drawRect(g.plotW, ly - 16f, size.width, ly + 14f, labelBg)
            drawContext.canvas.nativeCanvas.drawText(fmtPrice(last), g.plotW + 8f, ly + 9f, textPaint)
        }
        // sub pane
        if (sub != null) drawSub(sub, g.start, g.end, g.subTop, g.subH, g.plotW, { g.x(it.toDouble()) }, textPaint)
        // time labels
        val step = max(1, g.vis / 4)
        for (k in 0 until g.vis step step) {
            val c = g.slice[k]
            drawContext.canvas.nativeCanvas.drawText(fmtTime(c.time, if (candles.last().time - candles.first().time > 200L * 86400) "dd MMM yy" else "dd MMM HH:mm"),
                g.x((g.start + k).toDouble()), size.height - 4f, textPaint)
        }
        // crosshair
        cross?.let { pos ->
            val i = g.idx(pos.x).roundToInt().coerceIn(g.start, g.end - 1)
            val c = candles[i]
            val cx = g.x(i.toDouble())
            drawLine(Muted, Offset(cx, 0f), Offset(cx, size.height), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            if (pos.y < g.mainH) {
                drawLine(Muted, Offset(0f, pos.y), Offset(g.plotW, pos.y), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawContext.canvas.nativeCanvas.drawRect(g.plotW, pos.y - 16f, size.width, pos.y + 14f, labelBg)
                drawContext.canvas.nativeCanvas.drawText(fmtPrice(g.price(pos.y)), g.plotW + 8f, pos.y + 9f, textPaint)
            }
            val info = "${fmtTime(c.time)}  O ${fmtPrice(c.open)}  H ${fmtPrice(c.high)}  L ${fmtPrice(c.low)}  C ${fmtPrice(c.close)}  V ${"%.0f".format(c.volume)}"
            drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, 34f, labelBg)
            drawContext.canvas.nativeCanvas.drawText(info, 8f, 25f, textPaint)
        }
    }
}

private fun DrawScope.drawSeries(v: DoubleArray, start: Int, end: Int, x: (Int) -> Float, y: (Double) -> Float, color: Color, dashed: Boolean) {
    val p = Path(); var started = false
    for (i in start until min(end, v.size)) {
        val value = v[i]
        if (value.isNaN()) { started = false; continue }
        if (!started) { p.moveTo(x(i), y(value)); started = true } else p.lineTo(x(i), y(value))
    }
    drawPath(p, color, style = Stroke(1.6.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
}

private val FIB = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)

private fun DrawScope.drawDrawing(d: Drawing, plotW: Float, x: (Double) -> Float, y: (Double) -> Float, paint: Paint) {
    val c = Color(0xFFFFD166)
    when (d.tool) {
        DrawTool.HLINE -> { drawLine(c, Offset(0f, y(d.p1)), Offset(plotW, y(d.p1)), 2f); drawContext.canvas.nativeCanvas.drawText(fmtPrice(d.p1), 8f, y(d.p1) - 6f, paint) }
        DrawTool.TREND -> {
            // extend the line to the right edge
            val x1 = x(d.i1); val y1 = y(d.p1); val x2 = x(d.i2); val y2 = y(d.p2)
            val slope = if (x2 != x1) (y2 - y1) / (x2 - x1) else 0f
            drawLine(c, Offset(x1, y1), Offset(plotW, y1 + slope * (plotW - x1)), 2f)
        }
        DrawTool.FIB -> {
            val left = min(x(d.i1), x(d.i2))
            FIB.forEach { f ->
                val p = d.p2 + (d.p1 - d.p2) * f
                drawLine(c.copy(alpha = 0.7f), Offset(left, y(p)), Offset(plotW, y(p)), 1.5f)
                drawContext.canvas.nativeCanvas.drawText("${"%.1f".format(f * 100)}%  ${fmtPrice(p)}", left + 6f, y(p) - 4f, paint)
            }
        }
        DrawTool.NONE -> {}
    }
}

private fun DrawScope.drawSub(sub: SubPane, start: Int, end: Int, top: Float, h: Float, plotW: Float, x: (Int) -> Float, paint: Paint) {
    drawLine(Grid, Offset(0f, top - 8f), Offset(size.width, top - 8f))
    when (sub) {
        is SubPane.Oscillator -> {
            val vals = (start until min(end, sub.values.size)).map { sub.values[it] }.filter { !it.isNaN() }
            if (vals.isEmpty()) return
            val mn = sub.min ?: vals.min(); val mx = sub.max ?: vals.max(); val r = (mx - mn).takeIf { it > 0 } ?: 1.0
            val y = { v: Double -> (top + h - (v - mn) / r * h).toFloat() }
            sub.lines.forEach { l -> drawLine(Grid, Offset(0f, y(l)), Offset(plotW, y(l)), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
            drawSeries(sub.values, start, end, x, y, Color(0xFFBB9AF7), false)
            drawContext.canvas.nativeCanvas.drawText("${sub.name} ${"%.1f".format(vals.last())}", 8f, top + 22f, paint)
        }
        is SubPane.Histogram -> {
            val vals = (start until min(end, sub.hist.size)).flatMap { listOf(sub.hist[it], sub.a[it], sub.b[it]) }.filter { !it.isNaN() }
            if (vals.isEmpty()) return
            val mx = vals.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
            val mid = top + h / 2
            val y = { v: Double -> (mid - v / mx * h / 2).toFloat() }
            val bw = plotW / (end - start)
            for (i in start until min(end, sub.hist.size)) {
                val v = sub.hist[i]; if (v.isNaN()) continue
                val yy = y(v)
                drawRect(if (v >= 0) Bull.copy(alpha = 0.6f) else Bear.copy(alpha = 0.6f), Offset(x(i) - bw * 0.35f, min(yy, mid)), Size(bw * 0.7f, kotlin.math.abs(yy - mid)))
            }
            drawSeries(sub.a, start, end, x, y, Color(0xFF7AA2F7), false)
            drawSeries(sub.b, start, end, x, y, Color(0xFFFF9E64), false)
            drawContext.canvas.nativeCanvas.drawText(sub.name, 8f, top + 22f, paint)
        }
    }
}
