package com.example.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Telegram-style Color Studio preset definition.
 * Every preset provides calibrated dark/light palettes that are also validated
 * through WCAG 2.1 contrast enforcement inside [AetherTheme].
 */
@Immutable
data class AetherThemePreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val isDark: Boolean,
    val background: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val primary: Long,
    val secondary: Long,
    val tertiary: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val neonGlow: Long,
    val cardBorder: Long,
)

/**
 * Persisted user color & neon glow customization state.
 * Custom overrides (`0L` = inherit preset value) let users fine-tune individual UI slots
 * in the Telegram-style live device mockup studio.
 */
@Immutable
data class UiThemeConfig(
    val presetId: String = "obsidian_cyber",
    val neonGlowEnabled: Boolean = true,
    val neonGlowIntensity: Float = 0.72f,
    val customBackgroundArgb: Long = 0L,
    val customSurfaceArgb: Long = 0L,
    val customPrimaryArgb: Long = 0L,
    val customSecondaryArgb: Long = 0L,
    val customTextPrimaryArgb: Long = 0L,
    val customTextSecondaryArgb: Long = 0L,
    val customNeonGlowArgb: Long = 0L,
    val customCardBorderArgb: Long = 0L,
) {
    val hasCustomOverrides: Boolean
        get() = customBackgroundArgb != 0L ||
            customSurfaceArgb != 0L ||
            customPrimaryArgb != 0L ||
            customSecondaryArgb != 0L ||
            customTextPrimaryArgb != 0L ||
            customTextSecondaryArgb != 0L ||
            customNeonGlowArgb != 0L ||
            customCardBorderArgb != 0L

    fun resolvedPreset(): AetherThemePreset =
        AetherThemePresets.byId(presetId)

    fun effectiveBackground(): Color =
        Color(if (customBackgroundArgb != 0L) customBackgroundArgb else resolvedPreset().background)

    fun effectiveSurface(): Color =
        Color(if (customSurfaceArgb != 0L) customSurfaceArgb else resolvedPreset().surface)

    fun effectivePrimary(): Color =
        Color(if (customPrimaryArgb != 0L) customPrimaryArgb else resolvedPreset().primary)

    fun effectiveSecondary(): Color =
        Color(if (customSecondaryArgb != 0L) customSecondaryArgb else resolvedPreset().secondary)

    fun effectiveTextPrimary(): Color {
        val bg = effectiveSurface()
        val raw = Color(if (customTextPrimaryArgb != 0L) customTextPrimaryArgb else resolvedPreset().textPrimary)
        return ensureReadableText(raw, bg, minRatio = 4.5)
    }

    fun effectiveTextSecondary(): Color {
        val bg = effectiveSurface()
        val raw = Color(if (customTextSecondaryArgb != 0L) customTextSecondaryArgb else resolvedPreset().textSecondary)
        return ensureReadableText(raw, bg, minRatio = 3.5)
    }

    fun effectiveNeonGlow(): Color =
        Color(if (customNeonGlowArgb != 0L) customNeonGlowArgb else resolvedPreset().neonGlow)

    fun effectiveCardBorder(): Color =
        Color(if (customCardBorderArgb != 0L) customCardBorderArgb else resolvedPreset().cardBorder)

    fun clearCustomOverrides(): UiThemeConfig = copy(
        customBackgroundArgb = 0L,
        customSurfaceArgb = 0L,
        customPrimaryArgb = 0L,
        customSecondaryArgb = 0L,
        customTextPrimaryArgb = 0L,
        customTextSecondaryArgb = 0L,
        customNeonGlowArgb = 0L,
        customCardBorderArgb = 0L,
    )
}

@Immutable
data class AetherNeonConfig(
    val enabled: Boolean = true,
    val intensity: Float = 0.72f,
    val glowColor: Color = CyanPulse,
)

val LocalAetherNeonConfig = staticCompositionLocalOf { AetherNeonConfig() }

/**
 * 18 curated Telegram-style Color Studio themes spanning high-contrast dark cockpits,
 * OLED stealth skins, cyberpunk neons, motorsport clusters, and daylight editorial themes.
 */
object AetherThemePresets {
    val all: List<AetherThemePreset> = listOf(
        AetherThemePreset(
            id = "obsidian_cyber",
            name = "Obsidian Cyber",
            subtitle = "Signature deep cockpit + cyan telemetry",
            isDark = true,
            background = 0xFF060A12,
            surface = 0xFF0D1422,
            surfaceVariant = 0xFF131C2E,
            primary = 0xFF38BDF8,
            secondary = 0xFF34D399,
            tertiary = 0xFFA78BFA,
            textPrimary = 0xFFF1F5F9,
            textSecondary = 0xFFA3B4CC,
            neonGlow = 0xFF00E5FF,
            cardBorder = 0xFF1E293B,
        ),
        AetherThemePreset(
            id = "hyperion_neon",
            name = "Hyperion Neon",
            subtitle = "Electric cyan + plasma violet overdrive",
            isDark = true,
            background = 0xFF050410,
            surface = 0xFF0E0B22,
            surfaceVariant = 0xFF171336,
            primary = 0xFF00F0FF,
            secondary = 0xFFFF2E93,
            tertiary = 0xFF9D4EDD,
            textPrimary = 0xFFF8FAFC,
            textSecondary = 0xFFB8B5E0,
            neonGlow = 0xFF00F0FF,
            cardBorder = 0xFF2D255C,
        ),
        AetherThemePreset(
            id = "emerald_matrix",
            name = "Emerald Matrix",
            subtitle = "Terminal phosphor green on obsidian",
            isDark = true,
            background = 0xFF030B07,
            surface = 0xFF081710,
            surfaceVariant = 0xFF0F241A,
            primary = 0xFF10F58A,
            secondary = 0xFF2DD4BF,
            tertiary = 0xFF38BDF8,
            textPrimary = 0xFFECFDF5,
            textSecondary = 0xFF8CC4A8,
            neonGlow = 0xFF10F58A,
            cardBorder = 0xFF173A2A,
        ),
        AetherThemePreset(
            id = "crimson_gt",
            name = "Crimson GT",
            subtitle = "Superbike redline + carbon cockpit",
            isDark = true,
            background = 0xFF0D0608,
            surface = 0xFF190C10,
            surfaceVariant = 0xFF261218,
            primary = 0xFFFF3358,
            secondary = 0xFFFFB020,
            tertiary = 0xFF38BDF8,
            textPrimary = 0xFFFFF1F2,
            textSecondary = 0xFFD4A3AD,
            neonGlow = 0xFFFF3358,
            cardBorder = 0xFF3E1C26,
        ),
        AetherThemePreset(
            id = "pure_oled_pitch",
            name = "Pure OLED Pitch",
            subtitle = "True #000000 black for AMOLED battery",
            isDark = true,
            background = 0xFF000000,
            surface = 0xFF090B10,
            surfaceVariant = 0xFF121620,
            primary = 0xFF38BDF8,
            secondary = 0xFF34D399,
            tertiary = 0xFFC084FC,
            textPrimary = 0xFFFFFFFF,
            textSecondary = 0xFFA1ACBD,
            neonGlow = 0xFF38BDF8,
            cardBorder = 0xFF1F2636,
        ),
        AetherThemePreset(
            id = "solar_daylight",
            name = "Solar Daylight",
            subtitle = "Crisp studio white + cobalt accents",
            isDark = false,
            background = 0xFFF4F7FB,
            surface = 0xFFFFFFFF,
            surfaceVariant = 0xFFE8EFF8,
            primary = 0xFF0284C7,
            secondary = 0xFF059669,
            tertiary = 0xFF7C3AED,
            textPrimary = 0xFF0F172A,
            textSecondary = 0xFF475569,
            neonGlow = 0xFF0284C7,
            cardBorder = 0xFFCBD5E1,
        ),
        AetherThemePreset(
            id = "synthwave_miami",
            name = "Synthwave Miami",
            subtitle = "Hot magenta + sunset turquoise",
            isDark = true,
            background = 0xFF12061E,
            surface = 0xFF1C0B2E,
            surfaceVariant = 0xFF281242,
            primary = 0xFFFF4FD8,
            secondary = 0xFF00F5D4,
            tertiary = 0xFFFFBE0B,
            textPrimary = 0xFFFDF4FF,
            textSecondary = 0xFFD4B2E8,
            neonGlow = 0xFFFF4FD8,
            cardBorder = 0xFF421E66,
        ),
        AetherThemePreset(
            id = "midnight_sakura",
            name = "Midnight Sakura",
            subtitle = "Soft cherry blossom glow on plum dark",
            isDark = true,
            background = 0xFF110912,
            surface = 0xFF1D101F,
            surfaceVariant = 0xFF29182C,
            primary = 0xFFFF7EB6,
            secondary = 0xFF8BE9FD,
            tertiary = 0xFFD8B4FE,
            textPrimary = 0xFFFFF5FA,
            textSecondary = 0xFFD6B5C8,
            neonGlow = 0xFFFF7EB6,
            cardBorder = 0xFF452749,
        ),
        AetherThemePreset(
            id = "golden_mirage",
            name = "Golden Mirage",
            subtitle = "Luxury champagne gold + matte onyx",
            isDark = true,
            background = 0xFF0B0905,
            surface = 0xFF16120A,
            surfaceVariant = 0xFF231D11,
            primary = 0xFFF5B727,
            secondary = 0xFF34D399,
            tertiary = 0xFFFB923C,
            textPrimary = 0xFFFFFBEB,
            textSecondary = 0xFFD1C19A,
            neonGlow = 0xFFF5B727,
            cardBorder = 0xFF3B3019,
        ),
        AetherThemePreset(
            id = "arctic_frost",
            name = "Arctic Frost",
            subtitle = "Glacial ice daylight + sapphire telemetry",
            isDark = false,
            background = 0xFFEBF5FF,
            surface = 0xFFF9FCFF,
            surfaceVariant = 0xFFDCECFB,
            primary = 0xFF0369A1,
            secondary = 0xFF0D9488,
            tertiary = 0xFF4F46E5,
            textPrimary = 0xFF0C1E33,
            textSecondary = 0xFF3B5570,
            neonGlow = 0xFF0EA5E9,
            cardBorder = 0xFFBCD9F0,
        ),
        AetherThemePreset(
            id = "cobalt_stealth",
            name = "Cobalt Stealth",
            subtitle = "Aviation navy + tactical ice blue",
            isDark = true,
            background = 0xFF040B1A,
            surface = 0xFF0A1630,
            surfaceVariant = 0xFF112247,
            primary = 0xFF4DA3FF,
            secondary = 0xFF22D3EE,
            tertiary = 0xFF818CF8,
            textPrimary = 0xFFF0F7FF,
            textSecondary = 0xFF9AB4DA,
            neonGlow = 0xFF4DA3FF,
            cardBorder = 0xFF1E376B,
        ),
        AetherThemePreset(
            id = "toxic_volt",
            name = "Toxic Volt",
            subtitle = "High-voltage acid lime + dark titanium",
            isDark = true,
            background = 0xFF080B06,
            surface = 0xFF11170D,
            surfaceVariant = 0xFF1A2414,
            primary = 0xFFB8FF1F,
            secondary = 0xFF00E5FF,
            tertiary = 0xFFFACC15,
            textPrimary = 0xFFF7FEE7,
            textSecondary = 0xFFB5C79B,
            neonGlow = 0xFFB8FF1F,
            cardBorder = 0xFF2E3F22,
        ),
        AetherThemePreset(
            id = "vulcan_magma",
            name = "Vulcan Magma",
            subtitle = "Molten orange + basalt charcoal",
            isDark = true,
            background = 0xFF100704,
            surface = 0xFF1C0E08,
            surfaceVariant = 0xFF2A160D,
            primary = 0xFFFF6B2B,
            secondary = 0xFFFACC15,
            tertiary = 0xFFFF3D71,
            textPrimary = 0xFFFFF7ED,
            textSecondary = 0xFFD9B29E,
            neonGlow = 0xFFFF6B2B,
            cardBorder = 0xFF4A2516,
        ),
        AetherThemePreset(
            id = "royal_amethyst",
            name = "Royal Amethyst",
            subtitle = "Deep ultraviolet + luminous lavender",
            isDark = true,
            background = 0xFF0A0614,
            surface = 0xFF140D26,
            surfaceVariant = 0xFF20153B,
            primary = 0xFFC084FC,
            secondary = 0xFF38BDF8,
            tertiary = 0xFFF472B6,
            textPrimary = 0xFFFAF5FF,
            textSecondary = 0xFFC2B2DC,
            neonGlow = 0xFFC084FC,
            cardBorder = 0xFF352461,
        ),
        AetherThemePreset(
            id = "ocean_abyss",
            name = "Ocean Abyss",
            subtitle = "Bioluminescent teal + deep trench slate",
            isDark = true,
            background = 0xFF030E12,
            surface = 0xFF081B22,
            surfaceVariant = 0xFF0E2933,
            primary = 0xFF14F1D9,
            secondary = 0xFF38BDF8,
            tertiary = 0xFF60A5FA,
            textPrimary = 0xFFECFEFF,
            textSecondary = 0xFF8FC2CC,
            neonGlow = 0xFF14F1D9,
            cardBorder = 0xFF164252,
        ),
        AetherThemePreset(
            id = "paper_editorial",
            name = "Paper Editorial",
            subtitle = "Warm archival paper + ink charcoal",
            isDark = false,
            background = 0xFFF7F3EB,
            surface = 0xFFFFFDF9,
            surfaceVariant = 0xFFEDE6D8,
            primary = 0xFFB45309,
            secondary = 0xFF0F766E,
            tertiary = 0xFF6D28D9,
            textPrimary = 0xFF1C1917,
            textSecondary = 0xFF57534E,
            neonGlow = 0xFFD97706,
            cardBorder = 0xFFD6CEC0,
        ),
        AetherThemePreset(
            id = "desert_dune",
            name = "Desert Dune",
            subtitle = "Terracotta sunset + warm espresso",
            isDark = true,
            background = 0xFF120C08,
            surface = 0xFF1E150F,
            surfaceVariant = 0xFF2C1F17,
            primary = 0xFFF48C42,
            secondary = 0xFF34D399,
            tertiary = 0xFFFBBF24,
            textPrimary = 0xFFFFF8F1,
            textSecondary = 0xFFD1B7A5,
            neonGlow = 0xFFF48C42,
            cardBorder = 0xFF473326,
        ),
        AetherThemePreset(
            id = "nordic_mint",
            name = "Nordic Mint",
            subtitle = "Scandinavian frost + crisp emerald",
            isDark = false,
            background = 0xFFEEF7F4,
            surface = 0xFFFFFFFF,
            surfaceVariant = 0xFFDDEEE8,
            primary = 0xFF047857,
            secondary = 0xFF0284C7,
            tertiary = 0xFF6366F1,
            textPrimary = 0xFF0B241E,
            textSecondary = 0xFF3E6359,
            neonGlow = 0xFF10B981,
            cardBorder = 0xFFB7D9CD,
        ),
    )

    fun byId(id: String): AetherThemePreset =
        all.firstOrNull { it.id == id } ?: all.first()
}

/**
 * Computes WCAG 2.1 contrast ratio (1.0 .. 21.0) between [foreground] and [background].
 */
fun wcagContrastRatio(foreground: Color, background: Color): Double {
    val l1 = foreground.luminance().toDouble()
    val l2 = background.luminance().toDouble()
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Returns a guaranteed high-contrast foreground color (pure crisp white or deep slate)
 * for any button, badge, pill, or container color.
 */
fun contrastOnColor(background: Color): Color {
    val whiteRatio = wcagContrastRatio(Color.White, background)
    val darkInk = Color(0xFF071019)
    val darkRatio = wcagContrastRatio(darkInk, background)
    return if (whiteRatio >= darkRatio) Color.White else darkInk
}

/**
 * Guarantees [preferred] meets [minRatio] contrast against [background].
 * If the user chooses a low-contrast custom combination (e.g. dark text on dark surface),
 * automatically lifts or darkens to a readable tone so text is never invisible.
 */
fun ensureReadableText(
    preferred: Color,
    background: Color,
    minRatio: Double = 4.5,
): Color {
    if (wcagContrastRatio(preferred, background) >= minRatio) return preferred
    val target = contrastOnColor(background)
    // Blend progressively toward guaranteed high-contrast ink until WCAG passes.
    for (step in 1..10) {
        val t = step / 10f
        val candidate = Color(
            red = preferred.red + (target.red - preferred.red) * t,
            green = preferred.green + (target.green - preferred.green) * t,
            blue = preferred.blue + (target.blue - preferred.blue) * t,
            alpha = 1f,
        )
        if (wcagContrastRatio(candidate, background) >= minRatio) return candidate
    }
    return target
}

/**
 * App-wide Neon Glow modifier controlled by [AetherNeonConfig].
 * Renders layered luminous outer halo + crisp neon inner edge when enabled,
 * and falls back cleanly to a subtle standard border when toggled off.
 */
fun Modifier.aetherNeonBorder(
    neon: AetherNeonConfig,
    accent: Color = neon.glowColor,
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.dp,
    fallbackBorderColor: Color = Color(0xFF1E293B),
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
): Modifier {
    if (!neon.enabled || neon.intensity <= 0.02f) {
        return this.border(borderWidth, fallbackBorderColor, shape)
    }
    val strength = neon.intensity.coerceIn(0f, 1f)
    return this
        .drawBehind {
            val radiusPx = cornerRadius.toPx()
            val outerStroke = (4.dp.toPx() * strength).coerceAtLeast(1.5f)
            val midStroke = (2.dp.toPx() * strength).coerceAtLeast(1f)
            drawRoundRect(
                color = accent.copy(alpha = 0.16f * strength),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(width = outerStroke * 2.2f),
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.34f * strength),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(width = midStroke * 1.4f),
            )
        }
        .border(
            width = borderWidth + (0.4f * strength).dp,
            brush = Brush.linearGradient(
                listOf(
                    accent.copy(alpha = (0.45f + 0.50f * strength).coerceAtMost(0.95f)),
                    fallbackBorderColor.copy(alpha = 0.7f),
                    accent.copy(alpha = (0.35f + 0.45f * strength).coerceAtMost(0.85f)),
                ),
            ),
            shape = shape,
        )
}

fun Color.toUnsignedArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
