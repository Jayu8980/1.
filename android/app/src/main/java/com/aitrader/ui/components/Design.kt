package com.aitrader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitrader.ui.theme.*

/** Translucent, hairline-bordered surface: the base of every card. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, onClick: (() -> Unit)? = null,
              brush: Brush? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(t.radius)
    var m = modifier.clip(shape)
    m = if (brush != null) m.background(brush) else m.background(t.glass)
    m = m.border(1.dp, t.glassBorder, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun heroBrush(): Brush {
    val t = LocalTokens.current
    return Brush.linearGradient(listOf(t.heroStart, t.accent.second.copy(alpha = if (t.dark) .25f else .9f), t.heroEnd),
        start = Offset(0f, 0f), end = Offset(900f, 700f))
}

/** Money that rolls to its new value instead of jumping. */
@Composable
fun AnimatedMoney(value: Double, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified,
                  signed: Boolean = true, modifier: Modifier = Modifier, compact: Boolean = false) {
    val v by animateFloatAsState(value.toFloat(), tween(600, easing = FastOutSlowInEasing), label = "money")
    Text(if (compact) Fmt.compact(v.toDouble()) else if (signed) Fmt.signedMoney(v.toDouble()) else Fmt.money(v.toDouble()),
        style = style, color = color, modifier = modifier, maxLines = 1)
}

@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, fill: Boolean = true, stroke: Float = 3f) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min(); val hi = values.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pts = values.mapIndexed { i, v -> Offset(size.width * i / (values.size - 1), (size.height * (1 - (v - lo) / span)).toFloat()) }
        val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        if (fill) {
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = .35f), Color.Transparent)))
        }
        drawPath(line, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 4.5f, pts.last())
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified,
               sub: String? = null, icon: ImageVector? = null) {
    val t = LocalTokens.current
    GlassCard(modifier, padding = if (LocalAppearance.current.compact) 10.dp else 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(14.dp), tint = t.muted); Spacer(Modifier.width(6.dp)) }
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = t.muted, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = t.muted, maxLines = 1)
    }
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(modifier.clip(RoundedCornerShape(50)).background(t.glass).border(1.dp, t.glassBorder, RoundedCornerShape(50)).padding(3.dp)) {
        options.forEach { (v, label) ->
            val sel = v == selected
            val bg by animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, label = "seg")
            Box(Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onSelect(v) }
                .padding(horizontal = 12.dp, vertical = 6.dp).testTag("seg_$label"), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Pill with a (optionally pulsing) status dot. */
@Composable
fun StatusPill(text: String, color: Color, pulse: Boolean = false, modifier: Modifier = Modifier) {
    val alpha = if (pulse) rememberInfiniteTransition(label = "pulse").animateFloat(1f, .25f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value else 1f
    Row(modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = .16f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

data class TapeItem(val symbol: String, val price: String, val chgPct: Double)

/** Scrolling ticker tape. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TickerTape(items: List<TapeItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val t = LocalTokens.current
    Row(modifier.fillMaxWidth().background(t.glass).padding(vertical = 7.dp).basicMarquee(iterations = Int.MAX_VALUE,
        velocity = 40.dp)) {
        items.forEach { i ->
            Text(i.symbol.removeSuffix("-EQ"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(i.price, style = MaterialTheme.typography.labelMedium.merge(Numeric))
            Spacer(Modifier.width(4.dp))
            Text((if (i.chgPct >= 0) "▲" else "▼") + "%.2f%%".format(kotlin.math.abs(i.chgPct)),
                style = MaterialTheme.typography.labelMedium.merge(Numeric), color = if (i.chgPct >= 0) Profit else Loss)
            Spacer(Modifier.width(22.dp))
        }
    }
}

/** Heatmap tile: colour intensity follows the move. */
@Composable
fun HeatTile(symbol: String, price: String, chgPct: Double, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val c = if (chgPct >= 0) Profit else Loss
    val strength = (kotlin.math.abs(chgPct) / 2.0).coerceIn(0.12, 1.0).toFloat()
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = .14f + .5f * strength)).clickable(onClick = onClick)
        .padding(12.dp)) {
        Text(symbol.removeSuffix("-EQ"), fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text(price, style = MaterialTheme.typography.labelLarge.merge(Numeric), color = Color.White.copy(alpha = .9f))
        Text("%+.2f%%".format(chgPct), style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** 270° gauge for "how much of a limit is used". */
@Composable
fun Gauge(fraction: Float, label: String, value: String, modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(800), label = "gauge")
    val color = when { f > .8f -> Loss; f > .5f -> Warn; else -> Profit }
    val track = LocalTokens.current.glassBorder
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val st = Stroke(14f, cap = StrokeCap.Round)
            drawArc(track, 135f, 270f, false, style = st)
            drawArc(Brush.sweepGradient(listOf(Profit, Warn, Loss, Profit)), 135f, 270f * f, false, style = st)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = LocalTokens.current.muted)
        }
    }
}

/** Allocation donut. */
@Composable
fun Donut(slices: List<Pair<String, Double>>, modifier: Modifier = Modifier, size: Dp = 110.dp) {
    val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, Profit, Warn, Loss, Nifty)
    val total = slices.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(size)) {
            var start = -90f
            slices.forEachIndexed { i, (_, v) ->
                val sweep = (v / total * 360).toFloat()
                drawArc(palette[i % palette.size], start, sweep - 2f, false, style = Stroke(22f, cap = StrokeCap.Butt),
                    topLeft = Offset(11f, 11f), size = Size(this.size.width - 22f, this.size.height - 22f))
                start += sweep
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slices.forEachIndexed { i, (name, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(palette[i % palette.size]))
                    Spacer(Modifier.width(6.dp))
                    Text("$name ${"%.0f".format(v / total * 100)}%", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Skeleton shimmer used while data loads. */
@Composable
fun Shimmer(modifier: Modifier) {
    val x = rememberInfiniteTransition(label = "shimmer").animateFloat(-400f, 1200f,
        infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x").value
    val base = LocalTokens.current.glass
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(
        Brush.linearGradient(listOf(base, base.copy(alpha = base.alpha * 3), base), start = Offset(x, 0f), end = Offset(x + 400f, 0f))))
}

@Composable
fun SkeletonScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Shimmer(Modifier.fillMaxWidth().height(160.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { Shimmer(Modifier.weight(1f).height(70.dp)) }
        }
        Shimmer(Modifier.fillMaxWidth().height(200.dp))
        repeat(3) { Shimmer(Modifier.fillMaxWidth().height(56.dp)) }
    }
}

/** Big circular quick-action button. */
@Composable
fun QuickAction(icon: ImageVector, label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(color.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = color)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, maxLines = 1)
    }
}
