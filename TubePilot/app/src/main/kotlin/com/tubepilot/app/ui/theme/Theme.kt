package com.tubepilot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Brand = Color(0xFFFFD400)
val Ink = Color(0xFF16161D)
val Success = Color(0xFF2E7D32)
val Warning = Color(0xFFB26A00)
val Danger = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6B5900),
    onPrimary = Color.White,
    primaryContainer = Brand,
    onPrimaryContainer = Ink,
    secondary = Color(0xFF4A4659),
    secondaryContainer = Color(0xFFE7E0F5),
    tertiary = Color(0xFF00658E),
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Ink,
    primaryContainer = Color(0xFF524400),
    onPrimaryContainer = Color(0xFFFFE57A),
    secondary = Color(0xFFCAC4DA),
    secondaryContainer = Color(0xFF333042),
    tertiary = Color(0xFF7DD0FF),
    background = Color(0xFF121218),
    surface = Color(0xFF121218),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

@Composable
fun TubePilotTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
}
