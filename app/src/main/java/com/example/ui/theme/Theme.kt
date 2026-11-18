package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Immutable
data class AetherExtendedColors(
    val cyan: Color,
    val emerald: Color,
    val violet: Color,
    val amber: Color,
    val rose: Color,
    val cardBorder: Color,
    val gridLine: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val mutedText: Color,
)

private val DarkExtendedColors = AetherExtendedColors(
    cyan = CyanPulse,
    emerald = EmeraldLock,
    violet = VioletCore,
    amber = AmberGuard,
    rose = RoseAlert,
    cardBorder = ObsidianBorder,
    gridLine = Color(0xFF172133),
    heroTop = Color(0xFF0C1729),
    heroBottom = Color(0xFF070B14),
    mutedText = DarkTextMuted,
)

private val LightExtendedColors = AetherExtendedColors(
    cyan = SkyPrimary,
    emerald = EmeraldPrimary,
    violet = VioletPrimary,
    amber = AmberPrimary,
    rose = RosePrimary,
    cardBorder = LightBorder,
    gridLine = Color(0xFFE2E8F0),
    heroTop = Color(0xFFE6F4FE),
    heroBottom = Color(0xFFF4F7FB),
    mutedText = LightTextMuted,
)

val LocalAetherColors = staticCompositionLocalOf { DarkExtendedColors }

private val DarkColorScheme = darkColorScheme(
    primary = CyanPulse,
    onPrimary = Color(0xFF04131E),
    primaryContainer = Color(0xFF0E2B42),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = EmeraldLock,
    onSecondary = Color(0xFF042117),
    secondaryContainer = Color(0xFF0D2E25),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = VioletCore,
    onTertiary = Color(0xFF1C1038),
    tertiaryContainer = Color(0xFF231942),
    onTertiaryContainer = Color(0xFFDDD6FE),
    background = VoidBackground,
    onBackground = DarkTextPrimary,
    surface = ObsidianSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = ObsidianBorder,
    outlineVariant = Color(0xFF172030),
    error = RoseAlert,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3B0E1A),
    onErrorContainer = Color(0xFFFECDD3),
)

private val LightColorScheme = lightColorScheme(
    primary = SkyPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = EmeraldPrimary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = VioletPrimary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF2E1065),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = RosePrimary,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
)

val AetherShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

enum class ThemeMode {
    DARK_OBSIDIAN,
    LIGHT_PRECISION,
    SYSTEM,
}

@Composable
fun AetherTheme(
    themeMode: ThemeMode = ThemeMode.DARK_OBSIDIAN,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.DARK_OBSIDIAN -> true
        ThemeMode.LIGHT_PRECISION -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme
    val extendedColors = if (useDark) DarkExtendedColors else LightExtendedColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !useDark
                insetsController.isAppearanceLightNavigationBars = !useDark
            }
        }
    }

    CompositionLocalProvider(LocalAetherColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AetherTypography,
            shapes = AetherShapes,
            content = content,
        )
    }
}
