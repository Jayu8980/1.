package com.tradelens.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tradelens.app.domain.SourceBadge
import com.tradelens.app.ui.theme.Bear
import com.tradelens.app.ui.theme.Bull
import com.tradelens.app.ui.theme.Muted
import com.tradelens.app.ui.theme.Warn
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val inr: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2; minimumFractionDigits = 2 }
private val inr0: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }

fun fmtPrice(v: Double?): String = when {
    v == null || v.isNaN() -> "—"
    abs(v) >= 1 -> inr.format(v)
    else -> "%.5f".format(v)
}
fun fmtMoney(v: Double?, sym: String = "₹"): String = if (v == null || v.isNaN()) "—" else (if (v < 0) "-" else "") + sym + inr0.format(abs(v))
fun fmtPct(v: Double?): String = if (v == null || v.isNaN()) "—" else "%+.2f%%".format(v)
fun fmtTime(epochSec: Long, pattern: String = "dd MMM HH:mm"): String =
    if (epochSec <= 0) "—" else DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.of("Asia/Kolkata")).format(Instant.ofEpochSecond(epochSec))
fun pnlColor(v: Double) = if (v > 0) Bull else if (v < 0) Bear else Muted

@Composable
fun SourceChip(b: SourceBadge?) {
    if (b == null) return
    val c = if (b.realtime) Bull else Warn
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text("${b.source.label} · ${if (b.realtime) "LIVE" else "DELAYED"}", color = c, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier.width(64.dp).height(24.dp)) {
    if (values.size < 2) { Spacer(modifier); return }
    val color = if (values.last() >= values.first()) Bull else Bear
    Canvas(modifier) {
        val mn = values.min(); val mx = values.max(); val r = (mx - mn).takeIf { it > 0 } ?: 1.0
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = i * size.width / (values.size - 1); val y = size.height - ((v - mn) / r * size.height).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(width = 1.5.dp.toPx()))
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            if (title != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun KV(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
}

@Composable
fun ErrorText(msg: String?) {
    if (msg.isNullOrBlank()) return
    Text(msg, color = Bear, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
fun Disclaimer(modifier: Modifier = Modifier) {
    Text("Not financial advice. Analysis and strategy outputs are educational; markets carry risk of loss.",
        color = Muted, fontSize = 11.sp, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), secret: Boolean = false,
          number: Boolean = false, hint: String? = null, singleLine: Boolean = true) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, modifier = modifier.padding(vertical = 3.dp), singleLine = singleLine,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else if (secret) KeyboardType.Password else KeyboardType.Text),
        supportingText = hint?.let { { Text(it, fontSize = 11.sp) } },
    )
}

val ListPadding = PaddingValues(bottom = 24.dp)

@Composable
fun RowSpaced(content: @Composable () -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }

/** Simple line chart for equity curves (multiple series share a y-scale). */
@Composable
fun LineChart(series: List<Pair<List<Double>, Color>>, modifier: Modifier = Modifier.fillMaxWidth().height(180.dp), fillBelowZero: Boolean = false) {
    val all = series.flatMap { it.first }.filter { !it.isNaN() }
    if (all.size < 2) { Box(modifier, contentAlignment = Alignment.Center) { Text("No data", color = Muted) }; return }
    Canvas(modifier) {
        val mn = all.min(); val mx = all.max(); val r = (mx - mn).takeIf { it > 0 } ?: 1.0
        for (k in 0..3) { val y = size.height * k / 3f; drawLine(Color(0xFF243042), Offset(0f, y), Offset(size.width, y)) }
        series.forEach { (v, c) ->
            if (v.size < 2) return@forEach
            val p = Path()
            v.forEachIndexed { i, x ->
                val px = i * size.width / (v.size - 1); val py = size.height - ((x - mn) / r * size.height).toFloat()
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            drawPath(p, c, style = Stroke(1.6.dp.toPx()))
            if (fillBelowZero) {
                p.lineTo(size.width, size.height - ((0 - mn) / r * size.height).toFloat()); p.lineTo(0f, size.height - ((0 - mn) / r * size.height).toFloat()); p.close()
                drawPath(p, c.copy(alpha = 0.2f))
            }
        }
    }
}
