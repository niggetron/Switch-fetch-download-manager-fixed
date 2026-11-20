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
import androidx.compose.ui.graphics.luminance
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
    val gold: Color,
    val cobalt: Color,
    val teal: Color,
    val neonGlow: Color,
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
    gold = Color(0xFFFACC15),
    cobalt = Color(0xFF60A5FA),
    teal = Color(0xFF2DD4BF),
    neonGlow = ElectricAqua,
    cardBorder = ObsidianBorder,
    gridLine = Color(0xFF172133),
    heroTop = Color(0xFF0C1729),
    heroBottom = Color(0xFF070B14),
    mutedText = Color(0xFF94A3B8),
)

private val LightExtendedColors = AetherExtendedColors(
    cyan = SkyPrimary,
    emerald = EmeraldPrimary,
    violet = VioletPrimary,
    amber = AmberPrimary,
    rose = RosePrimary,
    gold = Color(0xFFCA8A04),
    cobalt = Color(0xFF2563EB),
    teal = Color(0xFF0D9488),
    neonGlow = SkyPrimary,
    cardBorder = LightBorder,
    gridLine = Color(0xFFE2E8F0),
    heroTop = Color(0xFFE6F4FE),
    heroBottom = Color(0xFFF4F7FB),
    mutedText = LightTextSecondary,
)

val LocalAetherColors = staticCompositionLocalOf { DarkExtendedColors }

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

private fun blend(a: Color, b: Color, ratio: Float): Color {
    val t = ratio.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}

@Composable
fun AetherTheme(
    themeMode: ThemeMode = ThemeMode.DARK_OBSIDIAN,
    uiThemeConfig: UiThemeConfig = UiThemeConfig(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val preset = when {
        uiThemeConfig.presetId.isNotBlank() && uiThemeConfig.presetId != "obsidian_cyber" -> {
            uiThemeConfig.resolvedPreset()
        }
        themeMode == ThemeMode.LIGHT_PRECISION -> AetherThemePresets.byId("solar_daylight")
        themeMode == ThemeMode.SYSTEM && !systemDark -> AetherThemePresets.byId("solar_daylight")
        else -> uiThemeConfig.resolvedPreset()
    }

    val bg = Color(if (uiThemeConfig.customBackgroundArgb != 0L) uiThemeConfig.customBackgroundArgb else preset.background)
    val surface = Color(if (uiThemeConfig.customSurfaceArgb != 0L) uiThemeConfig.customSurfaceArgb else preset.surface)
    val isDark = bg.luminance() < 0.35f
    val surfaceVariant = if (uiThemeConfig.customSurfaceArgb != 0L) {
        blend(surface, if (isDark) Color.White else Color.Black, 0.08f)
    } else {
        Color(preset.surfaceVariant)
    }

    val primary = Color(if (uiThemeConfig.customPrimaryArgb != 0L) uiThemeConfig.customPrimaryArgb else preset.primary)
    val secondary = Color(if (uiThemeConfig.customSecondaryArgb != 0L) uiThemeConfig.customSecondaryArgb else preset.secondary)
    val tertiary = Color(preset.tertiary)
    val cardBorder = Color(if (uiThemeConfig.customCardBorderArgb != 0L) uiThemeConfig.customCardBorderArgb else preset.cardBorder)
    val neonGlow = Color(if (uiThemeConfig.customNeonGlowArgb != 0L) uiThemeConfig.customNeonGlowArgb else preset.neonGlow)

    val rawTextPrimary = Color(if (uiThemeConfig.customTextPrimaryArgb != 0L) uiThemeConfig.customTextPrimaryArgb else preset.textPrimary)
    val rawTextSecondary = Color(if (uiThemeConfig.customTextSecondaryArgb != 0L) uiThemeConfig.customTextSecondaryArgb else preset.textSecondary)

    val onBg = ensureReadableText(rawTextPrimary, bg, minRatio = 7.0)
    val onSurface = ensureReadableText(rawTextPrimary, surface, minRatio = 7.0)
    val onSurfaceVariant = ensureReadableText(rawTextSecondary, surface, minRatio = 4.5)

    val primaryContainer = blend(surface, primary, if (isDark) 0.24f else 0.16f)
    val secondaryContainer = blend(surface, secondary, if (isDark) 0.22f else 0.16f)
    val tertiaryContainer = blend(surface, tertiary, if (isDark) 0.22f else 0.16f)
    val errorColor = if (isDark) RoseAlert else RosePrimary
    val errorContainer = blend(surface, errorColor, if (isDark) 0.28f else 0.15f)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = contrastOnColor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = ensureReadableText(blend(primary, Color.White, 0.7f), primaryContainer, 4.5),
            secondary = secondary,
            onSecondary = contrastOnColor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = ensureReadableText(blend(secondary, Color.White, 0.7f), secondaryContainer, 4.5),
            tertiary = tertiary,
            onTertiary = contrastOnColor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = ensureReadableText(blend(tertiary, Color.White, 0.7f), tertiaryContainer, 4.5),
            background = bg,
            onBackground = onBg,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = cardBorder,
            outlineVariant = blend(surface, cardBorder, 0.5f),
            error = errorColor,
            onError = contrastOnColor(errorColor),
            errorContainer = errorContainer,
            onErrorContainer = ensureReadableText(Color(0xFFFECDD3), errorContainer, 4.5),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = contrastOnColor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = ensureReadableText(blend(primary, Color.Black, 0.55f), primaryContainer, 4.5),
            secondary = secondary,
            onSecondary = contrastOnColor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = ensureReadableText(blend(secondary, Color.Black, 0.55f), secondaryContainer, 4.5),
            tertiary = tertiary,
            onTertiary = contrastOnColor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = ensureReadableText(blend(tertiary, Color.Black, 0.55f), tertiaryContainer, 4.5),
            background = bg,
            onBackground = onBg,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = cardBorder,
            outlineVariant = blend(surface, cardBorder, 0.5f),
            error = errorColor,
            onError = contrastOnColor(errorColor),
            errorContainer = errorContainer,
            onErrorContainer = ensureReadableText(Color(0xFF881337), errorContainer, 4.5),
        )
    }

    val extendedColors = AetherExtendedColors(
        cyan = primary,
        emerald = secondary,
        violet = tertiary,
        amber = if (isDark) AmberGuard else AmberPrimary,
        rose = errorColor,
        gold = if (isDark) Color(0xFFFACC15) else Color(0xFFCA8A04),
        cobalt = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
        teal = if (isDark) Color(0xFF2DD4BF) else Color(0xFF0D9488),
        neonGlow = neonGlow,
        cardBorder = cardBorder,
        gridLine = blend(surface, cardBorder, 0.45f),
        heroTop = blend(bg, primary, if (isDark) 0.14f else 0.10f),
        heroBottom = bg,
        mutedText = ensureReadableText(onSurfaceVariant, surface, minRatio = 3.8),
    )

    val neonConfig = AetherNeonConfig(
        enabled = uiThemeConfig.neonGlowEnabled,
        intensity = uiThemeConfig.neonGlowIntensity.coerceIn(0f, 1f),
        glowColor = neonGlow,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalAetherColors provides extendedColors,
        LocalAetherNeonConfig provides neonConfig,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AetherTypography,
            shapes = AetherShapes,
            content = content,
        )
    }
}
