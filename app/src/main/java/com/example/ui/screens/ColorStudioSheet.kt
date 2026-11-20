package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.StatusPill
import com.example.ui.theme.AetherNeonConfig
import com.example.ui.theme.AetherThemePresets
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoMetricStyle
import com.example.ui.theme.UiThemeConfig
import com.example.ui.theme.aetherNeonBorder
import com.example.ui.theme.contrastOnColor
import com.example.ui.theme.ensureReadableText
import com.example.ui.theme.toUnsignedArgbLong
import com.example.ui.theme.wcagContrastRatio
import java.util.Locale

private enum class ThemeColorSlot(val label: String) {
    BACKGROUND("Background"),
    SURFACE("Card Surface"),
    PRIMARY("Primary Accent"),
    SECONDARY("Secondary Accent"),
    TEXT_PRIMARY("Text Primary"),
    TEXT_SECONDARY("Text Secondary"),
    NEON_GLOW("Neon Glow"),
    CARD_BORDER("Card Border"),
}

private val STUDIO_SWATCHES = listOf(
    Color(0xFF060A12), Color(0xFF000000), Color(0xFF0D1422), Color(0xFF140D26),
    Color(0xFF081710), Color(0xFF190C10), Color(0xFFF4F7FB), Color(0xFFFFFFFF),
    Color(0xFF38BDF8), Color(0xFF00F0FF), Color(0xFF34D399), Color(0xFF10F58A),
    Color(0xFFA78BFA), Color(0xFFFF4FD8), Color(0xFFFF3358), Color(0xFFFF6B2B),
    Color(0xFFF5B727), Color(0xFFB8FF1F), Color(0xFFF1F5F9), Color(0xFF0F172A),
)

/**
 * Telegram-style Interactive Color Studio & Neon Glow Customizer featuring:
 * - Live miniature phone mockup updating in real time
 * - 18 curated theme presets (Obsidian Cyber, Hyperion Neon, Emerald Matrix, Crimson GT,
 *   Pure OLED Pitch, Solar Daylight, Synthwave Miami, Midnight Sakura, Golden Mirage,
 *   Arctic Frost, Cobalt Stealth, Toxic Volt, Vulcan Magma, Royal Amethyst, Ocean Abyss,
 *   Paper Editorial, Desert Dune, Nordic Mint)
 * - Global App-Wide Neon Glow ON/OFF toggle + Intensity Slider
 * - 8 per-element color pickers with swatches + HSV Hue/Saturation/Lightness sliders
 * - Live WCAG 2.1 contrast score readout
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorStudioSheet(
    currentConfig: UiThemeConfig,
    onApplyConfig: (UiThemeConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = LocalAetherColors.current
    BackHandler(onBack = onDismiss)

    var draft by remember(currentConfig) { mutableStateOf(currentConfig) }
    var activeSlot by remember { mutableStateOf(ThemeColorSlot.PRIMARY) }

    val selectedColor = remember(draft, activeSlot) {
        when (activeSlot) {
            ThemeColorSlot.BACKGROUND -> draft.effectiveBackground()
            ThemeColorSlot.SURFACE -> draft.effectiveSurface()
            ThemeColorSlot.PRIMARY -> draft.effectivePrimary()
            ThemeColorSlot.SECONDARY -> draft.effectiveSecondary()
            ThemeColorSlot.TEXT_PRIMARY -> draft.effectiveTextPrimary()
            ThemeColorSlot.TEXT_SECONDARY -> draft.effectiveTextSecondary()
            ThemeColorSlot.NEON_GLOW -> draft.effectiveNeonGlow()
            ThemeColorSlot.CARD_BORDER -> draft.effectiveCardBorder()
        }
    }

    var hue by remember(activeSlot, draft.presetId) { mutableFloatStateOf(colorToHsl(selectedColor)[0]) }
    var sat by remember(activeSlot, draft.presetId) { mutableFloatStateOf(colorToHsl(selectedColor)[1]) }
    var light by remember(activeSlot, draft.presetId) { mutableFloatStateOf(colorToHsl(selectedColor)[2]) }

    fun applyColorToSlot(color: Color) {
        val argb = color.toUnsignedArgbLong()
        draft = when (activeSlot) {
            ThemeColorSlot.BACKGROUND -> draft.copy(customBackgroundArgb = argb)
            ThemeColorSlot.SURFACE -> draft.copy(customSurfaceArgb = argb)
            ThemeColorSlot.PRIMARY -> draft.copy(customPrimaryArgb = argb)
            ThemeColorSlot.SECONDARY -> draft.copy(customSecondaryArgb = argb)
            ThemeColorSlot.TEXT_PRIMARY -> draft.copy(customTextPrimaryArgb = argb)
            ThemeColorSlot.TEXT_SECONDARY -> draft.copy(customTextSecondaryArgb = argb)
            ThemeColorSlot.NEON_GLOW -> draft.copy(customNeonGlowArgb = argb)
            ThemeColorSlot.CARD_BORDER -> draft.copy(customCardBorderArgb = argb)
        }
        onApplyConfig(draft)
    }

    val previewBg = draft.effectiveBackground()
    val previewSurface = draft.effectiveSurface()
    val previewPrimary = draft.effectivePrimary()
    val previewSecondary = draft.effectiveSecondary()
    val previewTextPrimary = ensureReadableText(draft.effectiveTextPrimary(), previewSurface, 4.5)
    val previewTextSecondary = ensureReadableText(draft.effectiveTextSecondary(), previewSurface, 3.5)
    val previewNeonColor = draft.effectiveNeonGlow()
    val previewBorder = draft.effectiveCardBorder()
    val contrastScore = wcagContrastRatio(previewTextPrimary, previewSurface)
    val previewNeon = AetherNeonConfig(
        enabled = draft.neonGlowEnabled,
        intensity = draft.neonGlowIntensity,
        glowColor = previewNeonColor,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 12.dp, vertical = 16.dp)
            .testTag("color_studio_sheet"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .aetherNeonBorder(
                    neon = previewNeon,
                    accent = previewNeonColor,
                    cornerRadius = 24.dp,
                    borderWidth = 1.4.dp,
                    fallbackBorderColor = ext.cardBorder,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.ColorLens, contentDescription = null, tint = previewPrimary, modifier = Modifier.size(22.dp))
                    Column {
                        Text(
                            text = "Color & Neon Studio",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${AetherThemePresets.all.size} Curated Themes · Live Device Preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_color_studio_button"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close Color Studio", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            // Telegram-style Live Phone Device Mockup Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF05070B))
                    .border(2.dp, previewBorder, RoundedCornerShape(22.dp))
                    .padding(10.dp)
                    .testTag("color_studio_device_mockup"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(previewBg)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Phone Notch / Status Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "AETHERIO OMEGA v9.1",
                            style = MaterialTheme.typography.labelSmall,
                            color = previewPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(previewSecondary.copy(alpha = 0.22f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = String.format(Locale.US, "WCAG %.1f:1", contrastScore),
                                style = MonoMetricStyle.copy(fontSize = 9.sp),
                                color = previewSecondary,
                            )
                        }
                    }

                    // Mockup Throughput Card with live Neon Glow
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(previewSurface)
                            .aetherNeonBorder(
                                neon = previewNeon,
                                accent = previewNeonColor,
                                cornerRadius = 12.dp,
                                fallbackBorderColor = previewBorder,
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "428.6 Mbps",
                                    style = MonoMetricStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Black),
                                    color = previewTextPrimary,
                                )
                                Text(
                                    text = "Wi-Fi 1 + Wi-Fi 2 + 5G · 64 Streams",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = previewTextSecondary,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(previewPrimary)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "BOOST",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                    color = contrastOnColor(previewPrimary),
                                )
                            }
                        }

                        // Mini 16-lane Rayburst progress preview
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            repeat(16) { idx ->
                                val barColor = when {
                                    idx < 10 -> if (idx % 2 == 0) previewPrimary else previewSecondary
                                    idx == 10 -> previewNeonColor
                                    else -> previewBorder
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(barColor),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "4K_Master_Release.mkv",
                                style = MaterialTheme.typography.labelSmall,
                                color = previewTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "68% · ETA 14s",
                                style = MonoMetricStyle.copy(fontSize = 10.sp),
                                color = previewSecondary,
                            )
                        }
                    }
                }
            }

            // Global App-Wide Neon Glow Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                    .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = previewNeonColor, modifier = Modifier.size(18.dp))
                        Column {
                            Text(
                                text = "App-Wide Neon Glow Effect",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Adds luminous edge glow to cards, gauges, and Rayburst bars",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = draft.neonGlowEnabled,
                        onCheckedChange = { enabled ->
                            draft = draft.copy(neonGlowEnabled = enabled)
                            onApplyConfig(draft)
                        },
                        modifier = Modifier.testTag("studio_neon_toggle"),
                    )
                }
                if (draft.neonGlowEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Neon Intensity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${(draft.neonGlowIntensity * 100).toInt()}%", style = MonoMetricStyle, color = previewNeonColor)
                    }
                    Slider(
                        value = draft.neonGlowIntensity,
                        onValueChange = { intensity ->
                            draft = draft.copy(neonGlowIntensity = intensity)
                            onApplyConfig(draft)
                        },
                        valueRange = 0.10f..1.0f,
                        modifier = Modifier.testTag("studio_neon_intensity_slider"),
                    )
                }
            }

            // 18 Curated Theme Presets Gallery (Telegram-style horizontal swatch cards)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "THEME PRESETS (${AetherThemePresets.all.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.cyan,
                    fontWeight = FontWeight.Bold,
                )
                StatusPill(label = draft.resolvedPreset().name, color = previewPrimary)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("theme_presets_row"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AetherThemePresets.all.forEach { preset ->
                    val isSelected = draft.presetId == preset.id && !draft.hasCustomOverrides
                    val pBg = Color(preset.background)
                    val pSurface = Color(preset.surface)
                    val pPrimary = Color(preset.primary)
                    val pSecondary = Color(preset.secondary)
                    val pText = Color(preset.textPrimary)

                    Column(
                        modifier = Modifier
                            .width(126.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(pBg)
                            .border(
                                width = if (isSelected) 2.2.dp else 1.dp,
                                color = if (isSelected) pPrimary else Color(preset.cardBorder),
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clickable {
                                draft = draft.clearCustomOverrides().copy(presetId = preset.id)
                                onApplyConfig(draft)
                            }
                            .padding(10.dp)
                            .testTag("theme_preset_${preset.id}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Mini card preview inside preset tile
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(pSurface)
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(11.dp).clip(CircleShape).background(pPrimary))
                                Box(Modifier.size(11.dp).clip(CircleShape).background(pSecondary))
                                Box(Modifier.size(11.dp).clip(CircleShape).background(Color(preset.neonGlow)))
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected preset",
                                    tint = pPrimary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = pText,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (preset.isDark) "Dark" else "Light",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = Color(preset.textSecondary),
                        )
                    }
                }
            }

            // Per-Element Color Customizer
            Text(
                text = "CUSTOMIZE INDIVIDUAL UI ELEMENTS",
                style = MaterialTheme.typography.labelSmall,
                color = ext.violet,
                fontWeight = FontWeight.Bold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ThemeColorSlot.entries.forEach { slot ->
                    FilterChip(
                        selected = activeSlot == slot,
                        onClick = {
                            activeSlot = slot
                            val c = when (slot) {
                                ThemeColorSlot.BACKGROUND -> draft.effectiveBackground()
                                ThemeColorSlot.SURFACE -> draft.effectiveSurface()
                                ThemeColorSlot.PRIMARY -> draft.effectivePrimary()
                                ThemeColorSlot.SECONDARY -> draft.effectiveSecondary()
                                ThemeColorSlot.TEXT_PRIMARY -> draft.effectiveTextPrimary()
                                ThemeColorSlot.TEXT_SECONDARY -> draft.effectiveTextSecondary()
                                ThemeColorSlot.NEON_GLOW -> draft.effectiveNeonGlow()
                                ThemeColorSlot.CARD_BORDER -> draft.effectiveCardBorder()
                            }
                            val hsl = colorToHsl(c)
                            hue = hsl[0]
                            sat = hsl[1]
                            light = hsl[2]
                        },
                        label = { Text(slot.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            // Quick Swatch Strip
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                STUDIO_SWATCHES.forEach { swatch ->
                    val isCurrent = swatch.toUnsignedArgbLong() == selectedColor.toUnsignedArgbLong()
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(
                                width = if (isCurrent) 2.5.dp else 1.dp,
                                color = if (isCurrent) Color.White else ext.cardBorder,
                                shape = CircleShape,
                            )
                            .clickable {
                                val hsl = colorToHsl(swatch)
                                hue = hsl[0]
                                sat = hsl[1]
                                light = hsl[2]
                                applyColorToSlot(swatch)
                            },
                    )
                }
            }

            // Fine-tune HSL Sliders
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .border(1.dp, ext.cardBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Fine-Tune ${activeSlot.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(selectedColor)
                            .border(1.dp, Color.White, CircleShape),
                    )
                }
                Text("Hue (${hue.toInt()}°)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                            ),
                        ),
                )
                Slider(
                    value = hue,
                    onValueChange = {
                        hue = it
                        applyColorToSlot(hslToColor(hue, sat, light))
                    },
                    valueRange = 0f..360f,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Saturation ${(sat * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = sat,
                            onValueChange = {
                                sat = it
                                applyColorToSlot(hslToColor(hue, sat, light))
                            },
                            valueRange = 0f..1f,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lightness ${(light * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = light,
                            onValueChange = {
                                light = it
                                applyColorToSlot(hslToColor(hue, sat, light))
                            },
                            valueRange = 0.02f..0.98f,
                        )
                    }
                }
            }

            // Footer Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        draft = UiThemeConfig()
                        onApplyConfig(draft)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("reset_color_studio_button"),
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset Defaults", color = MaterialTheme.colorScheme.onSurface)
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = previewPrimary,
                        contentColor = contrastOnColor(previewPrimary),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("done_color_studio_button"),
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun colorToHsl(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    val s = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f,
    )
}
