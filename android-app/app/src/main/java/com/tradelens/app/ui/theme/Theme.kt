package com.tradelens.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bull = Color(0xFF26A69A)
val Bear = Color(0xFFEF5350)
val Warn = Color(0xFFFFB74D)
val Muted = Color(0xFF8B98A9)
val Accent = Color(0xFF2EC4B6)
val Surface1 = Color(0xFF121821)
val Surface2 = Color(0xFF1A2230)
val Grid = Color(0xFF243042)

private val Dark = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF00201D), secondary = Color(0xFF7AA2F7), tertiary = Warn,
    background = Color(0xFF0B0F14), onBackground = Color(0xFFE6EDF3), surface = Surface1, onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Surface2, onSurfaceVariant = Color(0xFFB0BCCB), surfaceContainer = Surface1, surfaceContainerHigh = Surface2,
    error = Bear, outline = Grid, outlineVariant = Grid,
)

@Composable
fun TradeLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = Typography(), content = content)
}
