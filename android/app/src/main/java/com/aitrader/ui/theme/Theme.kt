package com.aitrader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Profit = Color(0xFF22C55E)
val Loss = Color(0xFFEF4444)
val PaperBlue = Color(0xFF3B82F6)
val LiveRed = Color(0xFFDC2626)
val Nifty = Color(0xFF9CA3AF)
val Warn = Color(0xFFF59E0B)

private val Dark = darkColorScheme(
    primary = Color(0xFF6EA0FF), onPrimary = Color(0xFF00214F),
    secondary = Color(0xFF8BD3C7),
    background = Color(0xFF0E1116), surface = Color(0xFF0E1116),
    surfaceVariant = Color(0xFF1A1F27), surfaceContainer = Color(0xFF151A21),
    surfaceContainerHigh = Color(0xFF1C222B), onSurfaceVariant = Color(0xFFB4BCC8),
    error = Loss, outline = Color(0xFF2E3642), outlineVariant = Color(0xFF242B35),
)
private val Light = lightColorScheme(
    primary = Color(0xFF2F6FED), secondary = Color(0xFF0F766E),
    error = Color(0xFFB91C1C),
)

@Composable
fun AITraderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
}
