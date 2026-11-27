package com.aitrader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitrader.data.local.SettingsStore
import com.aitrader.ui.components.*
import com.aitrader.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val store: SettingsStore) : ViewModel() {
    fun set(a: Appearance) = viewModelScope.launch { store.setAppearance(a.layout.name, a.theme.name, a.accent.name, a.compact) }
}

@Composable
fun AppearanceScreen(vm: AppearanceViewModel = hiltViewModel()) {
    AppearancePicker(LocalAppearance.current, vm::set)
}

@Composable
fun AppearancePicker(cur: Appearance, onChange: (Appearance) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Layout")
        LayoutStyle.entries.forEach { l ->
            GlassCard(Modifier.fillMaxWidth().testTag("layout_${l.name}"), onClick = { onChange(cur.copy(layout = l)) },
                brush = if (l == cur.layout) Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, Color.Transparent)) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LayoutThumb(l, Modifier.size(64.dp, 92.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(l.title, fontWeight = FontWeight.Bold)
                        Text(l.blurb, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.muted)
                    }
                    if (l == cur.layout) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        SectionHeader("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ThemeStyle.entries.forEach { t ->
                val (bg, fg) = when (t) {
                    ThemeStyle.MIDNIGHT -> Color(0xFF0A0F1C) to Color(0xFF1A2234)
                    ThemeStyle.AMOLED -> Color.Black to Color(0xFF141418)
                    ThemeStyle.GRAPHITE -> Color(0xFF16181C) to Color(0xFF262A31)
                    ThemeStyle.LIGHT -> Color(0xFFF5F7FB) to Color.White
                    ThemeStyle.SYSTEM -> Color(0xFF0A0F1C) to Color(0xFFF5F7FB)
                }
                Column(Modifier.weight(1f).clickable { onChange(cur.copy(theme = t)) }.testTag("theme_${t.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(bg, fg)))
                        .border(if (t == cur.theme) 2.dp else 1.dp,
                            if (t == cur.theme) MaterialTheme.colorScheme.primary else LocalTokens.current.glassBorder, RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(4.dp))
                    Text(t.title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        SectionHeader("Accent")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Accent.entries.forEach { a ->
                Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a.color, a.second)))
                    .clickable { onChange(cur.copy(accent = a)) }.testTag("accent_${a.name}"), contentAlignment = Alignment.Center) {
                    if (a == cur.accent) Icon(Icons.Filled.Check, a.title, tint = Color.White)
                }
            }
        }
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compact density", fontWeight = FontWeight.SemiBold)
                    Text("Tighter spacing, more data per screen", style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.muted)
                }
                Switch(cur.compact, { onChange(cur.copy(compact = it)) })
            }
        }
        SectionHeader("Preview")
        GlassCard(brush = heroBrush(), padding = 18.dp) {
            Text("Today's P&L", color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelLarge)
            Text("+₹12,480", color = Color.White, style = MaterialTheme.typography.displaySmall)
            Sparkline(listOf(1.0, 1.2, 1.1, 1.4, 1.35, 1.6, 1.7, 1.65, 1.9), Color.White, Modifier.fillMaxWidth().height(40.dp))
        }
    }
}

/** Tiny wireframe of each layout. */
@Composable
private fun LayoutThumb(l: LayoutStyle, modifier: Modifier) {
    val p = MaterialTheme.colorScheme.primary; val g = LocalTokens.current.glassBorder
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable fun bar(h: Int, c: Color = g, w: Float = 1f) =
            Box(Modifier.fillMaxWidth(w).height(h.dp).clip(RoundedCornerShape(3.dp)).background(c))
        when (l) {
            LayoutStyle.PRO -> {
                bar(4, p.copy(alpha = .6f))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(3) { Box(Modifier.weight(1f).height(12.dp).background(g)) } }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(3) { Box(Modifier.weight(1f).height(12.dp).background(g)) } }
                bar(22, p.copy(alpha = .35f)); bar(4); bar(4); bar(4)
            }
            LayoutStyle.CARDS -> { bar(30, p.copy(alpha = .7f)); Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                repeat(4) { Box(Modifier.size(8.dp).clip(CircleShape).background(g)) } }; bar(18); bar(14) }
            LayoutStyle.MINIMAL -> { Spacer(Modifier.height(10.dp)); bar(14, p.copy(alpha = .8f), .7f); bar(4, g, .4f)
                Spacer(Modifier.height(6.dp)); bar(10, p.copy(alpha = .3f)); Spacer(Modifier.height(6.dp)); bar(8, g, .8f) }
        }
    }
}
