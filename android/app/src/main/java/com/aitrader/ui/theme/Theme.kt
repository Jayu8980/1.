package com.aitrader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- semantic colours (theme-independent)
val Profit = Color(0xFF22C55E)
val Loss = Color(0xFFF43F5E)
val PaperBlue = Color(0xFF3B82F6)
val LiveRed = Color(0xFFE11D48)
val Nifty = Color(0xFF94A3B8)
val Warn = Color(0xFFF59E0B)

// ---------------------------------------------------------------- appearance model
enum class LayoutStyle(val title: String, val blurb: String) {
    PRO("Pro terminal", "Dense, data-first. Ticker tape, grids and tables."),
    CARDS("Cards", "Rich hero cards with gradients and breathing room."),
    MINIMAL("Focus", "One big number, essentials only. Calm."),
}

enum class ThemeStyle(val title: String) { MIDNIGHT("Midnight"), AMOLED("AMOLED black"), GRAPHITE("Graphite"), LIGHT("Light"), SYSTEM("System") }

enum class Accent(val title: String, val color: Color, val second: Color) {
    AZURE("Azure", Color(0xFF5B8CFF), Color(0xFF22D3EE)),
    EMERALD("Emerald", Color(0xFF10B981), Color(0xFF84CC16)),
    VIOLET("Violet", Color(0xFF8B5CF6), Color(0xFFEC4899)),
    AMBER("Amber", Color(0xFFF59E0B), Color(0xFFEF4444)),
    CYAN("Cyan", Color(0xFF06B6D4), Color(0xFF3B82F6)),
    ROSE("Rose", Color(0xFFF43F5E), Color(0xFFF59E0B)),
}

data class Appearance(
    val layout: LayoutStyle = LayoutStyle.PRO,
    val theme: ThemeStyle = ThemeStyle.MIDNIGHT,
    val accent: Accent = Accent.AZURE,
    val compact: Boolean = false,
)

/** Extra design tokens Material3 doesn't carry. */
data class Tokens(
    val dark: Boolean,
    val accent: Accent,
    val glass: Color,          // translucent card fill
    val glassBorder: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val muted: Color,
    val gap: androidx.compose.ui.unit.Dp,
    val radius: androidx.compose.ui.unit.Dp,
)

val LocalAppearance = staticCompositionLocalOf { Appearance() }
val LocalTokens = staticCompositionLocalOf {
    Tokens(true, Accent.AZURE, Color(0x14FFFFFF), Color(0x1FFFFFFF), Color(0xFF1E3A8A), Color(0xFF0E1116),
        Color(0xFF94A3B8), 12.dp, 20.dp)
}

/** Numbers that don't jitter as they tick: tabular figures everywhere digits change. */
val Numeric = TextStyle(fontFeatureSettings = "tnum, lnum")
val Mono = FontFamily.Monospace

private fun darkScheme(bg: Color, surface: Color, high: Color, accent: Accent, outline: Color) = darkColorScheme(
    primary = accent.color, onPrimary = Color.White, secondary = accent.second, tertiary = Profit,
    background = bg, surface = bg, surfaceVariant = high, surfaceContainerLowest = bg,
    surfaceContainerLow = surface, surfaceContainer = surface, surfaceContainerHigh = high,
    surfaceContainerHighest = high, onSurface = Color(0xFFE6EAF2), onSurfaceVariant = Color(0xFF9AA4B5),
    outline = outline, outlineVariant = outline.copy(alpha = 0.6f), error = Loss,
    primaryContainer = accent.color.copy(alpha = 0.22f), onPrimaryContainer = Color.White,
    secondaryContainer = accent.color.copy(alpha = 0.18f), onSecondaryContainer = Color.White,
)

@Composable
fun AITraderTheme(appearance: Appearance = Appearance(), dark: Boolean? = null, content: @Composable () -> Unit) {
    val sysDark = isSystemInDarkTheme()
    val theme = when {
        dark == true && appearance.theme == ThemeStyle.LIGHT -> ThemeStyle.MIDNIGHT
        dark == false -> ThemeStyle.LIGHT
        appearance.theme == ThemeStyle.SYSTEM -> if (sysDark) ThemeStyle.MIDNIGHT else ThemeStyle.LIGHT
        else -> appearance.theme
    }
    val a = appearance.accent
    val (scheme, tokens) = when (theme) {
        ThemeStyle.AMOLED -> darkScheme(Color.Black, Color(0xFF0A0A0C), Color(0xFF141418), a, Color(0xFF222228)) to
            Tokens(true, a, Color(0x0FFFFFFF), Color(0x1AFFFFFF), a.color.copy(alpha = .55f), Color.Black, Color(0xFF8B93A3), 12.dp, 20.dp)
        ThemeStyle.GRAPHITE -> darkScheme(Color(0xFF16181C), Color(0xFF1D2025), Color(0xFF262A31), a, Color(0xFF343944)) to
            Tokens(true, a, Color(0x12FFFFFF), Color(0x22FFFFFF), a.color.copy(alpha = .45f), Color(0xFF16181C), Color(0xFF9AA4B5), 12.dp, 18.dp)
        ThemeStyle.LIGHT -> lightColorScheme(
            primary = a.color, onPrimary = Color.White, secondary = a.second, background = Color(0xFFF5F7FB),
            surface = Color(0xFFF5F7FB), surfaceContainer = Color.White, surfaceContainerLow = Color.White,
            surfaceContainerHigh = Color(0xFFEEF1F6), surfaceVariant = Color(0xFFEEF1F6), onSurface = Color(0xFF0F172A),
            onSurfaceVariant = Color(0xFF5B6576), outline = Color(0xFFD9DEE7), outlineVariant = Color(0xFFE6EAF0),
            error = Loss, primaryContainer = a.color.copy(alpha = .14f), secondaryContainer = a.color.copy(alpha = .12f)) to
            Tokens(false, a, Color.White, Color(0xFFE3E8EF), a.color, a.second, Color(0xFF64748B), 12.dp, 20.dp)
        else -> darkScheme(Color(0xFF0A0F1C), Color(0xFF111827), Color(0xFF1A2234), a, Color(0xFF263045)) to
            Tokens(true, a, Color(0x12FFFFFF), Color(0x1FFFFFFF), a.color.copy(alpha = .55f), Color(0xFF0A0F1C), Color(0xFF94A3B8), 12.dp, 20.dp)
    }
    val t = tokens.copy(gap = if (appearance.compact) 8.dp else tokens.gap)
    val base = Typography()
    val type = base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp).merge(Numeric),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp).merge(Numeric),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp).merge(Numeric),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold).merge(Numeric),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold).merge(Numeric),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
        bodyMedium = base.bodyMedium.merge(Numeric),
    )
    val shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(t.radius))
    CompositionLocalProvider(LocalAppearance provides appearance, LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
    }
}
