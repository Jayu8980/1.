package com.aitrader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aitrader.ui.theme.Loss
import com.aitrader.ui.theme.Profit

data class Series(val label: String, val values: List<Double>, val color: Color, val dashed: Boolean = false)

/** Multi-line chart, values rebased to % change from first point so equity and NIFTY share an axis. */
@Composable
fun LineChart(series: List<Series>, modifier: Modifier = Modifier, rebase: Boolean = true, description: String = "chart") {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val data = series.filter { it.values.size >= 2 }.map { s ->
        if (rebase) s.copy(values = s.values.map { (it / s.values.first() - 1) * 100 }) else s
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
            if (data.isEmpty()) return@Canvas
            val all = data.flatMap { it.values }
            val lo = all.min(); val hi = all.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
            fun y(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat()
            repeat(4) { i ->
                val yy = size.height * i / 3f
                drawLine(grid, Offset(0f, yy), Offset(size.width, yy), 1f)
            }
            if (rebase && lo < 0 && hi > 0) drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            data.forEach { s ->
                val p = Path()
                s.values.forEachIndexed { i, v ->
                    val x = size.width * i / (s.values.size - 1)
                    if (i == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v))
                }
                drawPath(p, s.color, style = Stroke(width = if (s.dashed) 3f else 5f, cap = StrokeCap.Round,
                    pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            data.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(s.color) }
                    Spacer(Modifier.width(6.dp))
                    val last = s.values.last()
                    Text(if (rebase) "${s.label} ${Fmt.pct(last, 1)}" else s.label,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Underwater (drawdown) area chart; values are negative percents. */
@Composable
fun DrawdownChart(dd: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(90.dp).semantics { contentDescription = "drawdown chart" }) {
        if (dd.size < 2) return@Canvas
        val lo = dd.min().takeIf { it < 0 } ?: -1.0
        val p = Path().apply {
            moveTo(0f, 0f)
            dd.forEachIndexed { i, v -> lineTo(size.width * i / (dd.size - 1), (size.height * (v / lo)).toFloat()) }
            lineTo(size.width, 0f); close()
        }
        drawPath(p, Loss.copy(alpha = 0.35f))
    }
}

/** Monthly return bars. */
@Composable
fun BarChart(values: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(90.dp).semantics { contentDescription = "monthly returns" }) {
        if (values.isEmpty()) return@Canvas
        val m = values.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        val w = size.width / values.size
        val mid = size.height / 2
        values.forEachIndexed { i, v ->
            val h = (mid * v / m).toFloat()
            drawRect(if (v >= 0) Profit else Loss, Offset(i * w + w * 0.15f, if (h > 0) mid - h else mid),
                Size(w * 0.7f, kotlin.math.abs(h)))
        }
    }
}
