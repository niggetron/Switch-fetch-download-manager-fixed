package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.SegmentState
import com.example.data.model.SpeedometerStyle
import com.example.ui.components.RadialSpeedometerGauge
import com.example.ui.components.SegmentBitfieldMatrix
import com.example.ui.components.StatusPill
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.motion.aetherSpatialBoundsTransform
import com.example.ui.motion.aetherSpringSpec
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoMetricStyle
import com.example.util.Formatters
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

enum class SpeedSimulationPreset(val label: String, val subtitle: String) {
    QUAD_GIGABIT_REDLINE(
        label = "Quad-Link 1.1G Redline",
        subtitle = "Revs Wi-Fi 1 + Wi-Fi 2 + 5G + USB into the redline zone with live needles & waves",
    ),
    ANTI_DIP_RESCUE(
        label = "Anti-Dip Rescue Demo",
        subtitle = "Drops Wi-Fi 1 briefly then triggers live range bisection & Anti-Dip Rescue surge",
    ),
    DUAL_LINK_RACE(
        label = "Wi-Fi vs 5G Race",
        subtitle = "Asymmetric sweep comparing Wi-Fi 1 vs Mobile 5G + Rust vs Kotlin A/B benchmark",
    ),
    CUSTOM_SLIDERS(
        label = "Manual Sliders",
        subtitle = "Drag live Mbps sliders for all 4 networks to inspect needles, LEDs & MAX/AVG",
    ),
}

data class AnimationSimulationState(
    val isRunning: Boolean = false,
    val preset: SpeedSimulationPreset = SpeedSimulationPreset.QUAD_GIGABIT_REDLINE,
    val autoCycleSpeedometerLayouts: Boolean = false,
    val motionSpeedMultiplier: Float = 1.0f,
    val customWifi1Mbps: Float = 320f,
    val customCellMbps: Float = 380f,
    val customWifi2Mbps: Float = 240f,
    val customUsbMbps: Float = 180f,
    val focusedBurstLinkId: String? = null,
)

/**
 * Interactive Speed & Motion Simulation Lab:
 * - Morphs directly out of the TopAppBar "Sim Lab" pill via Compose Shared Element Transition.
 * - Lets users simulate live quad-link speeds, test Anti-Dip rescues, scrub motion speed (0.25x-1.5x),
 *   auto-cycle all 3 motorsport speedometer layouts, and preview every shared-element morph in the app!
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun AnimationSimulatorSheet(
    simState: AnimationSimulationState,
    currentStyle: SpeedometerStyle,
    useMBpsUnit: Boolean,
    liveAggregateBps: Long,
    livePeakBps: Long,
    onStartSimulation: (SpeedSimulationPreset) -> Unit,
    onStopSimulation: () -> Unit,
    onUpdateCustomSpeeds: (wifi1: Float, cell: Float, wifi2: Float, usb: Float) -> Unit,
    onToggleAutoCycleLayouts: (Boolean) -> Unit,
    onSetMotionSpeed: (Float) -> Unit,
    onCycleSpeedometerStyle: () -> Unit,
    onTriggerNewDownloadMorph: () -> Unit,
    onTriggerWifi2Morph: () -> Unit,
    onTriggerFocusedGaugeMorph: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = LocalAetherColors.current
    BackHandler(onBack = onDismiss)

    var showcaseStage by remember { mutableIntStateOf(0) } // 0 = Compact Card, 1 = Expanded Pipeline, 2 = Integrity Audit
    var autoPlayShowcase by remember { mutableStateOf(false) }
    var waveTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(autoPlayShowcase, simState.motionSpeedMultiplier) {
        if (!autoPlayShowcase) return@LaunchedEffect
        val waitMs = (1400f / simState.motionSpeedMultiplier.coerceIn(0.25f, 1.5f)).toLong()
        while (true) {
            delay(waitMs)
            showcaseStage = (showcaseStage + 1) % 3
        }
    }

    LaunchedEffect(simState.isRunning, autoPlayShowcase) {
        if (!simState.isRunning && !autoPlayShowcase) return@LaunchedEffect
        while (true) {
            delay(180L)
            waveTick = (waveTick + 1) % 24
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = if (simState.isRunning || autoPlayShowcase) ((waveTick + 1) / 24f) else 0.62f,
        animationSpec = aetherSpringSpec(simState.motionSpeedMultiplier),
        label = "sim_progress_wave",
    )

    val simulatedSegments = remember(waveTick) {
        List(24) { idx ->
            when {
                idx < waveTick - 3 -> SegmentState.VERIFIED
                idx < waveTick - 1 -> SegmentState.COMMITTED
                idx == waveTick - 1 -> SegmentState.STOLEN_HEDGE
                idx == waveTick -> SegmentState.ACTIVE_WIFI
                idx == (waveTick + 1) % 24 -> SegmentState.ACTIVE_5G
                else -> SegmentState.PENDING
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.64f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 14.dp, vertical = 20.dp)
            .testTag("animation_simulator_sheet"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .aetherSharedBounds(
                    key = AetherSharedKey("sim_lab", SharedPart.SIM_LAB_PILL),
                    clipShape = RoundedCornerShape(22.dp),
                    zIndexInOverlay = 3f,
                )
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, ext.cyan.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = ext.cyan, modifier = Modifier.size(22.dp))
                    Column {
                        Text("Speed & Animation Simulator", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Test live speedometer needles, shared-element morphs & slow-mo springs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_sim_lab_button"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close Simulator")
                }
            }

            // Live Speedometer Preview + Start/Stop Master Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(ext.heroTop, ext.heroBottom)))
                    .border(1.dp, if (simState.isRunning) ext.emerald.copy(alpha = 0.65f) else ext.cardBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadialSpeedometerGauge(
                    speedBps = liveAggregateBps,
                    peakBps = livePeakBps,
                    capacityHintMbps = 1000f,
                    accentStart = ext.cyan,
                    accentEnd = ext.emerald,
                    peakMarkerColor = ext.amber,
                    useMBpsUnit = useMBpsUnit,
                    dialSize = 112.dp,
                    isOnline = true,
                    centerSubtitle = if (simState.isRunning) "SIM LIVE" else "READY",
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill(
                        label = if (simState.isRunning) "SIMULATION RUNNING" else "SIMULATION STANDBY",
                        color = if (simState.isRunning) ext.emerald else ext.amber,
                    )
                    Text(
                        text = Formatters.formatSpeed(liveAggregateBps, useMBpsUnit),
                        style = MonoMetricStyle,
                        color = ext.cyan,
                    )
                    if (simState.isRunning) {
                        Button(
                            onClick = onStopSimulation,
                            colors = ButtonDefaults.buttonColors(containerColor = ext.rose),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sim_stop_button"),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Simulation")
                        }
                    } else {
                        Button(
                            onClick = { onStartSimulation(simState.preset) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sim_start_button"),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Launch Speed Simulation")
                        }
                    }
                }
            }

            // Speed Simulation Presets
            Text("LIVE SPEED SIMULATION PRESETS", style = MaterialTheme.typography.labelSmall, color = ext.cyan)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpeedSimulationPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = simState.preset == preset,
                        onClick = { onStartSimulation(preset) },
                        label = { Text(preset.label) },
                        modifier = Modifier.testTag("sim_preset_${preset.name.lowercase()}"),
                    )
                }
            }
            Text(
                text = simState.preset.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Manual Per-Network Speed Sliders
            if (simState.preset == SpeedSimulationPreset.CUSTOM_SLIDERS) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SpeedSliderRow("Wi-Fi 1", simState.customWifi1Mbps, 600f, ext.cyan) {
                        onUpdateCustomSpeeds(it, simState.customCellMbps, simState.customWifi2Mbps, simState.customUsbMbps)
                    }
                    SpeedSliderRow("Mobile 5G", simState.customCellMbps, 600f, ext.amber) {
                        onUpdateCustomSpeeds(simState.customWifi1Mbps, it, simState.customWifi2Mbps, simState.customUsbMbps)
                    }
                    SpeedSliderRow("Wi-Fi 2", simState.customWifi2Mbps, 400f, ext.violet) {
                        onUpdateCustomSpeeds(simState.customWifi1Mbps, simState.customCellMbps, it, simState.customUsbMbps)
                    }
                    SpeedSliderRow("USB / Eth", simState.customUsbMbps, 500f, ext.emerald) {
                        onUpdateCustomSpeeds(simState.customWifi1Mbps, simState.customCellMbps, simState.customWifi2Mbps, it)
                    }
                }
            }

            // Global Shared-Element Transition Speed Control (Slow-Mo to Snappy)
            Text("SHARED-ELEMENT MOTION SPEED (GLOBAL)", style = MaterialTheme.typography.labelSmall, color = ext.violet)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    0.25f to "0.25× Slow-Mo",
                    0.5f to "0.5× Half",
                    1.0f to "1.0× Normal",
                    1.5f to "1.5× Snappy",
                ).forEach { (mult, label) ->
                    FilterChip(
                        selected = kotlin.math.abs(simState.motionSpeedMultiplier - mult) < 0.05f,
                        onClick = { onSetMotionSpeed(mult) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("motion_speed_${label.substringBefore('×')}"),
                    )
                }
            }

            // Auto-Cycle Speedometer Morphing Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Morph Speedometer Layouts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Currently: ${currentStyle.label} (cycles all 3 styles live)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = simState.autoCycleSpeedometerLayouts,
                    onCheckedChange = onToggleAutoCycleLayouts,
                    modifier = Modifier.testTag("sim_auto_morph_layouts_switch"),
                )
            }

            // Interactive Shared-Element Card Morphing Sandbox (Test Card / Icon / Title / Bitfield Morph)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp)
                    .animateContentSize(aetherSpringSpec(simState.motionSpeedMultiplier)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Animation, contentDescription = null, tint = ext.emerald, modifier = Modifier.size(16.dp))
                        Text("SHARED-ELEMENT MORPH SANDBOX", style = MaterialTheme.typography.labelSmall, color = ext.emerald)
                    }
                    FilterChip(
                        selected = autoPlayShowcase,
                        onClick = { autoPlayShowcase = !autoPlayShowcase },
                        label = { Text(if (autoPlayShowcase) "Auto-Playing" else "Auto-Play") },
                        modifier = Modifier.testTag("sim_sandbox_autoplay_chip"),
                    )
                }

                // Local SharedTransitionLayout demonstrating official sharedBounds + sharedElement + skipToLookaheadSize
                SharedTransitionLayout(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showcaseStage = (showcaseStage + 1) % 3 }
                        .testTag("sim_morph_sandbox_card"),
                ) {
                    val bTransform = aetherSpatialBoundsTransform(simState.motionSpeedMultiplier)
                    AnimatedContent(
                        targetState = showcaseStage,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "sim_shared_morph_demo",
                    ) { stage ->
                        when (stage) {
                            0 -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sharedBounds(
                                            sharedContentState = rememberSharedContentState("demo_card"),
                                            animatedVisibilityScope = this@AnimatedContent,
                                            boundsTransform = bTransform,
                                        )
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(1.dp, ext.cyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .sharedElement(
                                                state = rememberSharedContentState("demo_icon"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                boundsTransform = bTransform,
                                            )
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(ext.cyan.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Outlined.FolderZip, contentDescription = null, tint = ext.cyan)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "linux-6.12-arm64.tar.zst",
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier
                                                .sharedElement(
                                                    state = rememberSharedContentState("demo_title"),
                                                    animatedVisibilityScope = this@AnimatedContent,
                                                    boundsTransform = bTransform,
                                                )
                                                .skipToLookaheadSize(),
                                        )
                                        Text("Tap card to morph → Expanded Pipeline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    StatusPill(
                                        label = "COMPACT",
                                        color = ext.cyan,
                                        modifier = Modifier.sharedElement(
                                            state = rememberSharedContentState("demo_badge"),
                                            animatedVisibilityScope = this@AnimatedContent,
                                            boundsTransform = bTransform,
                                        ),
                                    )
                                }
                            }
                            1 -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sharedBounds(
                                            sharedContentState = rememberSharedContentState("demo_card"),
                                            animatedVisibilityScope = this@AnimatedContent,
                                            boundsTransform = bTransform,
                                        )
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(1.5.dp, ext.violet.copy(alpha = 0.60f), RoundedCornerShape(16.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .sharedElement(
                                                        state = rememberSharedContentState("demo_icon"),
                                                        animatedVisibilityScope = this@AnimatedContent,
                                                        boundsTransform = bTransform,
                                                    )
                                                    .size(46.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(ext.violet.copy(alpha = 0.18f)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(Icons.Outlined.FolderZip, contentDescription = null, tint = ext.violet)
                                            }
                                            Text(
                                                text = "linux-6.12-arm64.tar.zst",
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier
                                                    .sharedElement(
                                                        state = rememberSharedContentState("demo_title"),
                                                        animatedVisibilityScope = this@AnimatedContent,
                                                        boundsTransform = bTransform,
                                                    )
                                                    .skipToLookaheadSize(),
                                            )
                                        }
                                        StatusPill(
                                            label = "16W QUAD",
                                            color = ext.violet,
                                            modifier = Modifier.sharedElement(
                                                state = rememberSharedContentState("demo_badge"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                boundsTransform = bTransform,
                                            ),
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { animatedProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(7.dp),
                                        color = ext.cyan,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        strokeCap = StrokeCap.Round,
                                    )
                                    SegmentBitfieldMatrix(
                                        segments = simulatedSegments,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text("Tap again to morph → SHA-256 Integrity Audit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            else -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sharedBounds(
                                            sharedContentState = rememberSharedContentState("demo_card"),
                                            animatedVisibilityScope = this@AnimatedContent,
                                            boundsTransform = bTransform,
                                        )
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Brush.verticalGradient(listOf(ext.heroTop, ext.heroBottom)))
                                        .border(1.5.dp, ext.emerald.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                                        .padding(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .sharedElement(
                                                state = rememberSharedContentState("demo_icon"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                boundsTransform = bTransform,
                                            )
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(ext.emerald.copy(alpha = 0.20f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = ext.emerald, modifier = Modifier.size(28.dp))
                                    }
                                    Text(
                                        text = "linux-6.12-arm64.tar.zst",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .sharedElement(
                                                state = rememberSharedContentState("demo_title"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                boundsTransform = bTransform,
                                            )
                                            .skipToLookaheadSize(),
                                    )
                                    StatusPill(
                                        label = "SHA-256 VERIFIED",
                                        color = ext.emerald,
                                        modifier = Modifier.sharedElement(
                                            state = rememberSharedContentState("demo_badge"),
                                            animatedVisibilityScope = this@AnimatedContent,
                                            boundsTransform = bTransform,
                                        ),
                                    )
                                    Text("Zero-merge F2FS commit · 1.12 Gbps peak · Tap to loop", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // 1-Tap Full-App Container Transform Triggers
            Text("TRIGGER APP-WIDE SHARED-ELEMENT MORPHS", style = MaterialTheme.typography.labelSmall, color = ext.amber)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(onClick = { onCycleSpeedometerStyle() }) {
                    Icon(Icons.Filled.DashboardCustomize, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Morph Gauges")
                }
                OutlinedButton(onClick = { onDismiss(); onTriggerFocusedGaugeMorph() }) {
                    Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Gauge Zoom")
                }
                OutlinedButton(onClick = { onDismiss(); onTriggerNewDownloadMorph() }) {
                    Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("FAB Morph")
                }
                OutlinedButton(onClick = { onDismiss(); onTriggerWifi2Morph() }) {
                    Icon(Icons.Filled.Router, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Wi-Fi 2 Morph")
                }
            }
        }
    }
}

@Composable
private fun SpeedSliderRow(
    label: String,
    valueMbps: Float,
    maxMbps: Float,
    accent: Color,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
            Text("${valueMbps.roundToInt()} Mbps", style = MonoMetricStyle, color = accent)
        }
        Slider(
            value = valueMbps,
            onValueChange = onValueChange,
            valueRange = 0f..maxMbps,
        )
    }
}
