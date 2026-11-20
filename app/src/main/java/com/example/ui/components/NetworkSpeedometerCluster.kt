package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BreakerState
import com.example.data.model.SpeedometerStyle
import com.example.engine.LinkEndpointInfo
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.LocalMotionSpeedMultiplier
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.motion.aetherSpringSpec
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.LocalAetherNeonConfig
import com.example.ui.theme.MonoMetricStyle
import com.example.ui.theme.aetherNeonBorder
import com.example.util.Formatters
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val DIAL_START_DEG = 140f
private const val DIAL_SWEEP_DEG = 260f
private const val REDLINE_START_FRACTION = 0.84f

private data class NetworkGaugeSpec(
    val id: String,
    val title: String,
    val shortTitle: String,
    val iface: String,
    val icon: ImageVector,
    val liveBps: Long,
    val peakBps: Long,
    val avgBps: Long,
    val bytesTotal: Long,
    val workers: Int,
    val endpoint: LinkEndpointInfo?,
    val accent: Color,
    val testTag: String,
    val engineTestTag: String,
)

@Composable
private fun buildQuadGaugeSpecs(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
): List<NetworkGaugeSpec> {
    val ext = LocalAetherColors.current
    return listOf(
        NetworkGaugeSpec(
            id = "wifi1",
            title = "Wi-Fi 1",
            shortTitle = "WI-FI 1",
            iface = networkMatrix.wifiLink?.interfaceName ?: "wlan0",
            icon = Icons.Filled.Wifi,
            liveBps = telemetry.wifiBps,
            peakBps = telemetry.wifiPeakBps,
            avgBps = telemetry.wifiAvgBps,
            bytesTotal = telemetry.wifiBytesTotal,
            workers = telemetry.wifiWorkers,
            endpoint = networkMatrix.wifiLink,
            accent = ext.cyan,
            testTag = "speedometer_wifi",
            engineTestTag = "engine_speedometer_wifi",
        ),
        NetworkGaugeSpec(
            id = "cell",
            title = "Mobile 5G/LTE",
            shortTitle = "MOBILE 5G",
            iface = networkMatrix.cellularLink?.interfaceName ?: "rmnet0",
            icon = Icons.Filled.SignalCellularAlt,
            liveBps = telemetry.cellBps,
            peakBps = telemetry.cellPeakBps,
            avgBps = telemetry.cellAvgBps,
            bytesTotal = telemetry.cellBytesTotal,
            workers = telemetry.cellularWorkers,
            endpoint = networkMatrix.cellularLink,
            accent = ext.amber,
            testTag = "speedometer_cellular",
            engineTestTag = "engine_speedometer_cell",
        ),
        NetworkGaugeSpec(
            id = "wifi2",
            title = "Wi-Fi 2 Boost",
            shortTitle = "WI-FI 2",
            iface = networkMatrix.wifi2Link?.interfaceName ?: "wlan1 / relay",
            icon = Icons.Filled.Router,
            liveBps = telemetry.wifi2Bps,
            peakBps = telemetry.wifi2PeakBps,
            avgBps = telemetry.wifi2AvgBps,
            bytesTotal = telemetry.wifi2BytesTotal,
            workers = telemetry.wifi2Workers,
            endpoint = networkMatrix.wifi2Link,
            accent = ext.violet,
            testTag = "speedometer_wifi2",
            engineTestTag = "engine_speedometer_wifi2",
        ),
        NetworkGaugeSpec(
            id = "usb",
            title = "USB Tether / Eth",
            shortTitle = "USB / ETH",
            iface = networkMatrix.ethernetLink?.interfaceName ?: "usb0 / eth0",
            icon = Icons.Filled.Cable,
            liveBps = telemetry.ethBps,
            peakBps = telemetry.ethPeakBps,
            avgBps = telemetry.ethAvgBps,
            bytesTotal = telemetry.ethBytesTotal,
            workers = telemetry.ethWorkers,
            endpoint = networkMatrix.ethernetLink,
            accent = ext.emerald,
            testTag = "speedometer_ethernet",
            engineTestTag = "engine_speedometer_eth",
        ),
    )
}

/**
 * Quad-Link Speedometer Cockpit supporting all 6 visual styles:
 * - SUPERBIKE_GRID, AUTO_SPORT_DASH, COMPACT_TACH_STRIP
 * - F1_TELEMETRY_HUD, NEON_PLASMA_ARC, RAYBURST_MATRIX_BAR
 *
 * Uses a single root SharedTransitionLayout (no nested SharedTransitionLayout) so tapping
 * any gauge smoothly zooms into `FocusedGaugeOverlay` without blank frames or text overlap.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PerNetworkSpeedometerCluster(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    useMBpsUnit: Boolean,
    onToggleUnit: () -> Unit,
    onResetPeaks: () -> Unit,
    speedometerStyle: SpeedometerStyle = SpeedometerStyle.SUPERBIKE_GRID,
    onCycleSpeedometerStyle: () -> Unit = {},
    onOpenDualWifi: () -> Unit = {},
    onSelectGauge: (String) -> Unit = {},
    onOpenSimulator: () -> Unit = {},
    isSimulating: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val motionSpeed = LocalMotionSpeedMultiplier.current
    val specs = buildQuadGaugeSpecs(telemetry, networkMatrix)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(aetherSpringSpec(motionSpeed))
            .testTag("per_network_speedometer_cluster"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Speed,
                    contentDescription = null,
                    tint = ext.cyan,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "QUAD-LINK SPEEDOMETERS",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.cyan,
                    fontWeight = FontWeight.Bold,
                )
            }
            StatusPill(
                label = if (isSimulating) "SIM · ${speedometerStyle.shortLabel}" else speedometerStyle.shortLabel,
                color = if (isSimulating) ext.amber else ext.violet,
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = true,
                onClick = onCycleSpeedometerStyle,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.DashboardCustomize,
                        contentDescription = "Cycle speedometer layout",
                        modifier = Modifier.size(14.dp),
                    )
                },
                label = { Text(speedometerStyle.shortLabel, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("speedometer_style_cycle"),
            )
            FilterChip(
                selected = networkMatrix.wifi2Link != null,
                onClick = onOpenDualWifi,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.AddLink,
                        contentDescription = "Configure Wi-Fi 2 and USB boost",
                        modifier = Modifier.size(14.dp),
                    )
                },
                label = {
                    Text(
                        text = if (networkMatrix.wifi2Link != null) "Wi-Fi 2 ON" else "+ Wi-Fi 2",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier
                    .aetherSharedBounds(
                        key = AetherSharedKey("wifi2_connector", SharedPart.WIFI2_CONNECTOR_PILL),
                        clipShape = RoundedCornerShape(10.dp),
                    )
                    .testTag("open_dual_wifi_button"),
            )
            FilterChip(
                selected = isSimulating,
                onClick = onOpenSimulator,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Open speed and animation simulator",
                        modifier = Modifier.size(14.dp),
                    )
                },
                label = {
                    Text(
                        text = if (isSimulating) "Sim ON" else "Sim",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.testTag("open_sim_from_cluster"),
            )
            FilterChip(
                selected = useMBpsUnit,
                onClick = onToggleUnit,
                label = { Text(if (useMBpsUnit) "MB/s" else "Mbps", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("speed_unit_toggle_transfers"),
            )
            OutlinedButton(
                onClick = onResetPeaks,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("reset_peaks_transfers"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Reset speed peaks and averages",
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reset", style = MaterialTheme.typography.labelSmall)
            }
        }

        SpeedometerStyleSwitchContainer(
            style = speedometerStyle,
            specs = specs,
            useMBpsUnit = useMBpsUnit,
            compact = true,
            useEngineTags = false,
            onSelectGauge = onSelectGauge,
        )

        val totalBytes = telemetry.wifiBytesTotal + telemetry.wifi2BytesTotal +
            telemetry.cellBytesTotal + telemetry.ethBytesTotal
        if (totalBytes > 0L) {
            SankeyLinkSplitBar(
                wifiBytes = telemetry.wifiBytesTotal,
                wifi2Bytes = telemetry.wifi2BytesTotal,
                cellBytes = telemetry.cellBytesTotal,
                ethBytes = telemetry.ethBytesTotal,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun FullSpeedometerDashboardCard(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    useMBpsUnit: Boolean,
    onToggleUnit: () -> Unit,
    onResetPeaks: () -> Unit,
    speedometerStyle: SpeedometerStyle = SpeedometerStyle.SUPERBIKE_GRID,
    onSelectGauge: (String) -> Unit = {},
    onOpenSimulator: () -> Unit = {},
    isSimulating: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val totalCapacityMbps = networkMatrix.aggregateEstimatedMbps.toFloat().coerceAtLeast(150f)
    val specs = buildQuadGaugeSpecs(telemetry, networkMatrix)

    EngineSectionCard(
        title = "QUAD-LINK MOTORSPORT SPEEDOMETERS & A/B BENCHMARK",
        modifier = modifier.testTag("full_speedometer_dashboard"),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusPill(
                label = "${telemetry.activeEngineBadge} · ${telemetry.activeWorkers}W",
                color = ext.emerald,
            )
            FilterChip(
                selected = isSimulating,
                onClick = onOpenSimulator,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Open speed simulator",
                        modifier = Modifier.size(15.dp),
                    )
                },
                label = { Text(if (isSimulating) "Sim Active" else "Simulate") },
                modifier = Modifier.testTag("open_sim_from_engine"),
            )
            FilterChip(
                selected = useMBpsUnit,
                onClick = onToggleUnit,
                label = { Text(if (useMBpsUnit) "Unit: MB/s" else "Unit: Mbps") },
                modifier = Modifier.testTag("speed_unit_toggle_engine"),
            )
            OutlinedButton(
                onClick = onResetPeaks,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("reset_peaks_engine"),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reset session peaks", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reset Peaks")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aetherSharedBounds(
                    key = AetherSharedKey("hero", SharedPart.HERO_CONTAINER),
                    clipShape = RoundedCornerShape(14.dp),
                    useCockpitSpring = true,
                )
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(ext.heroTop, ext.heroBottom)))
                .aetherNeonBorder(neon = neon, accent = ext.cyan, cornerRadius = 14.dp, fallbackBorderColor = ext.cardBorder)
                .padding(14.dp)
                .testTag("speedometer_aggregate"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (isSimulating) "SIMULATION · AGGREGATE QUAD-LINK GOODPUT" else "AGGREGATE QUAD-LINK GOODPUT",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSimulating) ext.amber else ext.cyan,
                )
                RadialSpeedometerGauge(
                    speedBps = telemetry.instantBps,
                    peakBps = telemetry.peakBps,
                    avgBps = telemetry.avgBps,
                    capacityHintMbps = totalCapacityMbps,
                    accentStart = ext.cyan,
                    accentEnd = ext.emerald,
                    peakMarkerColor = ext.amber,
                    avgMarkerColor = ext.violet,
                    useMBpsUnit = useMBpsUnit,
                    dialSize = 196.dp,
                    isOnline = networkMatrix.hasAnyInternet || telemetry.instantBps > 0L,
                    centerSubtitle = "MAX ${Formatters.formatSpeed(telemetry.peakBps, useMBpsUnit)}",
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusPill(
                        label = "AVG ${Formatters.formatSpeed(telemetry.avgBps, useMBpsUnit)}",
                        color = ext.violet,
                    )
                    StatusPill(
                        label = "MAX ${Formatters.formatSpeed(telemetry.peakBps, useMBpsUnit)}",
                        color = ext.amber,
                    )
                    StatusPill(
                        label = "STAB ${telemetry.stabilityPercent}%",
                        color = ext.emerald,
                    )
                    if (telemetry.antiDipRescues > 0) {
                        StatusPill(
                            label = "RESCUES ${telemetry.antiDipRescues}",
                            color = ext.cyan,
                        )
                    }
                }
                Text(
                    text = "Secondary: ${Formatters.formatSpeed(telemetry.instantBps, !useMBpsUnit)} · TTFB ${telemetry.lastTtfbMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SpeedometerStyleSwitchContainer(
            style = speedometerStyle,
            specs = specs,
            useMBpsUnit = useMBpsUnit,
            compact = false,
            useEngineTags = true,
            onSelectGauge = onSelectGauge,
        )

        EngineAbBenchmarkStrip(
            rustPeakBps = telemetry.rustPeakBps,
            kotlinPeakBps = telemetry.kotlinPeakBps,
            useMBpsUnit = useMBpsUnit,
        )
    }
}

@Composable
private fun SpeedometerStyleSwitchContainer(
    style: SpeedometerStyle,
    specs: List<NetworkGaugeSpec>,
    useMBpsUnit: Boolean,
    compact: Boolean,
    useEngineTags: Boolean,
    onSelectGauge: (String) -> Unit,
) {
    val motionSpeed = LocalMotionSpeedMultiplier.current
    val durMs = (240f / motionSpeed.coerceIn(0.25f, 2f)).toInt().coerceIn(120, 900)
    AnimatedContent(
        targetState = style,
        transitionSpec = {
            (fadeIn(tween(durMs)) + scaleIn(tween(durMs), initialScale = 0.96f)) togetherWith
                (fadeOut(tween(durMs / 2)) + scaleOut(tween(durMs / 2), targetScale = 0.97f))
        },
        label = "speedometer_style_animated_content",
    ) { activeStyle ->
        when (activeStyle) {
            SpeedometerStyle.SUPERBIKE_GRID -> SuperbikeQuadGrid(
                specs = specs,
                useMBpsUnit = useMBpsUnit,
                compact = compact,
                useEngineTags = useEngineTags,
                onSelectGauge = onSelectGauge,
            )
            SpeedometerStyle.AUTO_SPORT_DASH -> AutoSportDashStack(
                specs = specs,
                useMBpsUnit = useMBpsUnit,
                useEngineTags = useEngineTags,
                onSelectGauge = onSelectGauge,
            )
            SpeedometerStyle.COMPACT_TACH_STRIP -> CompactTachStripStack(
                specs = specs,
                useMBpsUnit = useMBpsUnit,
                useEngineTags = useEngineTags,
                onSelectGauge = onSelectGauge,
            )
            SpeedometerStyle.F1_TELEMETRY_HUD -> F1TelemetryHudStack(
                specs = specs,
                useMBpsUnit = useMBpsUnit,
                useEngineTags = useEngineTags,
                onSelectGauge = onSelectGauge,
            )
            SpeedometerStyle.NEON_PLASMA_ARC -> NeonPlasmaArcGrid(
                specs = specs,
                useMBpsUnit = useMBpsUnit,
                useEngineTags = useEngineTags,
                onSelectGauge = onSelectGauge,
            )
            SpeedometerStyle.RAYBURST_MATRIX_BAR -> RayburstMatrixBarStack(
                specs = specs,
                useMBpsUnit = useMBpsUnit,
                useEngineTags = useEngineTags,
                onSelectGauge = onSelectGauge,
            )
        }
    }
}

@Composable
private fun SuperbikeQuadGrid(
    specs: List<NetworkGaugeSpec>,
    useMBpsUnit: Boolean,
    compact: Boolean,
    useEngineTags: Boolean,
    onSelectGauge: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        specs.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { spec ->
                    SuperbikeDialCard(
                        spec = spec,
                        useMBpsUnit = useMBpsUnit,
                        compact = compact,
                        onSelect = { onSelectGauge(spec.id) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(if (useEngineTags) spec.engineTestTag else spec.testTag),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SuperbikeDialCard(
    spec: NetworkGaugeSpec,
    useMBpsUnit: Boolean,
    compact: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val isOnline = spec.endpoint != null || spec.liveBps > 0L
    val breakerDotColor = when (spec.endpoint?.breakerState) {
        BreakerState.CLOSED -> ext.emerald
        BreakerState.HALF_OPEN -> ext.amber
        BreakerState.OPEN -> ext.rose
        null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    val capacityMbps = (spec.endpoint?.downstreamKbps?.div(1000f) ?: 100f).coerceAtLeast(25f)
    val speedDesc = Formatters.formatSpeed(spec.liveBps, useMBpsUnit)
    val peakDesc = Formatters.formatSpeed(spec.peakBps, useMBpsUnit)
    val avgDesc = Formatters.formatSpeed(spec.avgBps, useMBpsUnit)

    Column(
        modifier = modifier
            .aetherSharedBounds(
                key = AetherSharedKey(spec.id, SharedPart.GAUGE_CARD),
                clipShape = RoundedCornerShape(14.dp),
                useCockpitSpring = true,
            )
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isOnline) 0.94f else 0.62f))
            .aetherNeonBorder(
                neon = neon,
                accent = if (spec.liveBps > 0L) spec.accent else ext.cardBorder,
                cornerRadius = 14.dp,
                fallbackBorderColor = if (spec.liveBps > 0L) spec.accent.copy(alpha = 0.65f) else ext.cardBorder,
            )
            .clickable(onClick = onSelect)
            .padding(if (compact) 9.dp else 11.dp)
            .semantics {
                contentDescription = "${spec.title} ${spec.iface} superbike speedometer: live $speedDesc, max $peakDesc, avg $avgDesc, ${spec.workers} active workers. Tap to zoom."
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    tint = if (isOnline) spec.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = spec.shortTitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isOnline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(breakerDotColor),
                )
                Text(
                    text = if (isOnline) "${spec.workers}W" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOnline) spec.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        RadialSpeedometerGauge(
            speedBps = spec.liveBps,
            peakBps = spec.peakBps,
            avgBps = spec.avgBps,
            capacityHintMbps = capacityMbps,
            accentStart = spec.accent,
            accentEnd = spec.accent.copy(alpha = 0.72f),
            peakMarkerColor = ext.amber,
            avgMarkerColor = ext.cyan,
            useMBpsUnit = useMBpsUnit,
            dialSize = if (compact) 114.dp else 132.dp,
            isOnline = isOnline,
            centerSubtitle = if (spec.endpoint != null) spec.iface else "STANDBY",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GaugeStatChip(
                label = "MAX",
                value = compactMetric(spec.peakBps, useMBpsUnit),
                color = ext.amber,
                modifier = Modifier.weight(1f),
            )
            GaugeStatChip(
                label = "AVG",
                value = compactMetric(spec.avgBps, useMBpsUnit),
                color = spec.accent,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = Formatters.formatBytes(spec.bytesTotal),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = spec.endpoint?.let { String.format(Locale.US, "%.0fM", it.downstreamKbps / 1000.0) } ?: "Offline",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (isOnline) spec.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AutoSportDashStack(
    specs: List<NetworkGaugeSpec>,
    useMBpsUnit: Boolean,
    useEngineTags: Boolean,
    onSelectGauge: (String) -> Unit,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        specs.forEach { spec ->
            val isOnline = spec.endpoint != null || spec.liveBps > 0L
            val capacityMbps = (spec.endpoint?.downstreamKbps?.div(1000f) ?: 100f).coerceAtLeast(25f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .aetherSharedBounds(
                        key = AetherSharedKey(spec.id, SharedPart.GAUGE_CARD),
                        clipShape = RoundedCornerShape(14.dp),
                        useCockpitSpring = true,
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isOnline) 0.94f else 0.62f))
                    .aetherNeonBorder(
                        neon = neon,
                        accent = if (spec.liveBps > 0L) spec.accent else ext.cardBorder,
                        cornerRadius = 14.dp,
                        fallbackBorderColor = if (spec.liveBps > 0L) spec.accent.copy(alpha = 0.6f) else ext.cardBorder,
                    )
                    .clickable { onSelectGauge(spec.id) }
                    .padding(10.dp)
                    .testTag(if (useEngineTags) spec.engineTestTag else spec.testTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadialSpeedometerGauge(
                    speedBps = spec.liveBps,
                    peakBps = spec.peakBps,
                    avgBps = spec.avgBps,
                    capacityHintMbps = capacityMbps,
                    accentStart = spec.accent,
                    accentEnd = ext.emerald,
                    peakMarkerColor = ext.amber,
                    avgMarkerColor = ext.cyan,
                    useMBpsUnit = useMBpsUnit,
                    dialSize = 94.dp,
                    isOnline = isOnline,
                    centerSubtitle = if (isOnline) "${spec.workers}W" else "OFF",
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = spec.icon,
                                contentDescription = null,
                                tint = if (isOnline) spec.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "${spec.title} · ${spec.iface}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isOnline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = if (isOnline) "${spec.workers}W" else "OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOnline) spec.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GaugeStatChip("LIVE", compactMetric(spec.liveBps, useMBpsUnit), spec.accent, Modifier.weight(1f))
                        GaugeStatChip("MAX", compactMetric(spec.peakBps, useMBpsUnit), ext.amber, Modifier.weight(1f))
                        GaugeStatChip("AVG", compactMetric(spec.avgBps, useMBpsUnit), ext.emerald, Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Pulled ${Formatters.formatBytes(spec.bytesTotal)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = spec.endpoint?.let {
                                String.format(Locale.US, "Cap %.0f Mbps", it.downstreamKbps / 1000.0)
                            } ?: "Standby",
                            style = MaterialTheme.typography.labelSmall,
                            color = spec.accent,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CompactTachStripStack(
    specs: List<NetworkGaugeSpec>,
    useMBpsUnit: Boolean,
    useEngineTags: Boolean,
    onSelectGauge: (String) -> Unit,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val motionSpeed = LocalMotionSpeedMultiplier.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        specs.forEach { spec ->
            val isOnline = spec.endpoint != null || spec.liveBps > 0L
            val speedMbps = ((spec.liveBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
            val peakMbps = ((spec.peakBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
            val capMbps = (spec.endpoint?.downstreamKbps?.div(1000f) ?: 100f).coerceAtLeast(25f)
            val scaleMax = computeAutoScaleCeilingMbps(speedMbps, peakMbps, capMbps)
            val liveFrac by animateFloatAsState(
                targetValue = (speedMbps / scaleMax).coerceIn(0f, 1f),
                animationSpec = aetherSpringSpec(motionSpeed, dampingRatio = 0.78f, stiffness = 240f),
                label = "tach_strip_${spec.id}",
            )
            val peakFrac = (peakMbps / scaleMax).coerceIn(0f, 1f)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aetherSharedBounds(
                        key = AetherSharedKey(spec.id, SharedPart.GAUGE_CARD),
                        clipShape = RoundedCornerShape(10.dp),
                        useCockpitSpring = true,
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isOnline) 0.92f else 0.56f))
                    .aetherNeonBorder(
                        neon = neon,
                        accent = if (spec.liveBps > 0L) spec.accent else ext.cardBorder,
                        cornerRadius = 10.dp,
                        fallbackBorderColor = if (spec.liveBps > 0L) spec.accent.copy(alpha = 0.55f) else ext.cardBorder,
                    )
                    .clickable { onSelectGauge(spec.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag(if (useEngineTags) spec.engineTestTag else spec.testTag),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null,
                            tint = if (isOnline) spec.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "${spec.shortTitle} (${spec.iface})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isOnline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${Formatters.formatSpeed(spec.liveBps, useMBpsUnit)} · ${if (isOnline) "${spec.workers}W" else "OFF"}",
                        style = MonoMetricStyle.copy(fontSize = 12.sp),
                        color = if (spec.liveBps > 0L) spec.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val ledInactive = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                ) {
                    val segments = 20
                    val gap = 2.5.dp.toPx()
                    val segW = ((size.width - gap * (segments - 1)) / segments).coerceAtLeast(2f)
                    val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    for (i in 0 until segments) {
                        val frac = (i + 1).toFloat() / segments.toFloat()
                        val lit = isOnline && frac <= liveFrac + 0.02f && liveFrac > 0.005f
                        val isPeakCell = peakFrac > 0.02f && abs(frac - peakFrac) <= (1f / segments)
                        val color = when {
                            lit && frac >= REDLINE_START_FRACTION -> ext.rose
                            lit && frac >= 0.65f -> ext.amber
                            lit -> spec.accent
                            isPeakCell -> ext.amber.copy(alpha = 0.7f)
                            else -> ledInactive
                        }
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(i * (segW + gap), 0f),
                            size = Size(segW, size.height),
                            cornerRadius = radius,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "MAX ${Formatters.formatSpeed(spec.peakBps, useMBpsUnit)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = ext.amber,
                    )
                    Text(
                        text = "AVG ${Formatters.formatSpeed(spec.avgBps, useMBpsUnit)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = ext.cyan,
                    )
                    Text(
                        text = Formatters.formatBytes(spec.bytesTotal),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Style 4: Formula 1 Halo Telemetry HUD with 15 shift-LED rev lights, gear-style worker badge,
 * and big monospace pit-wall speed readout.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun F1TelemetryHudStack(
    specs: List<NetworkGaugeSpec>,
    useMBpsUnit: Boolean,
    useEngineTags: Boolean,
    onSelectGauge: (String) -> Unit,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val motionSpeed = LocalMotionSpeedMultiplier.current
    val inactiveLed = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        specs.forEach { spec ->
            val isOnline = spec.endpoint != null || spec.liveBps > 0L
            val speedMbps = ((spec.liveBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
            val peakMbps = ((spec.peakBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
            val capMbps = (spec.endpoint?.downstreamKbps?.div(1000f) ?: 120f).coerceAtLeast(25f)
            val scaleMax = computeAutoScaleCeilingMbps(speedMbps, peakMbps, capMbps)
            val liveFrac by animateFloatAsState(
                targetValue = (speedMbps / scaleMax).coerceIn(0f, 1f),
                animationSpec = aetherSpringSpec(motionSpeed, dampingRatio = 0.72f, stiffness = 280f),
                label = "f1_hud_${spec.id}",
            )
            val (valText, unitText) = formatValueAndUnit(spec.liveBps, useMBpsUnit)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aetherSharedBounds(
                        key = AetherSharedKey(spec.id, SharedPart.GAUGE_CARD),
                        clipShape = RoundedCornerShape(12.dp),
                        useCockpitSpring = true,
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isOnline) 0.95f else 0.58f))
                    .aetherNeonBorder(
                        neon = neon,
                        accent = if (spec.liveBps > 0L) spec.accent else ext.cardBorder,
                        cornerRadius = 12.dp,
                        fallbackBorderColor = ext.cardBorder,
                    )
                    .clickable { onSelectGauge(spec.id) }
                    .padding(10.dp)
                    .testTag(if (useEngineTags) spec.engineTestTag else spec.testTag),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // F1 15-LED Halo Shift Lights (5 Emerald -> 5 Amber -> 5 Crimson/Violet)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                ) {
                    val leds = 15
                    val gap = 4.dp.toPx()
                    val diameter = minOf(size.height, (size.width - gap * (leds - 1)) / leds)
                    val radius = diameter / 2f
                    val totalW = leds * diameter + (leds - 1) * gap
                    val startX = (size.width - totalW) / 2f
                    for (i in 0 until leds) {
                        val frac = (i + 1).toFloat() / leds.toFloat()
                        val lit = isOnline && frac <= liveFrac + 0.03f && liveFrac > 0.005f
                        val ledColor = when {
                            !lit -> inactiveLed
                            i < 5 -> ext.emerald
                            i < 10 -> ext.amber
                            else -> ext.rose
                        }
                        val cx = startX + i * (diameter + gap) + radius
                        if (lit && neon.enabled) {
                            drawCircle(
                                color = ledColor.copy(alpha = 0.35f * neon.intensity),
                                radius = radius * 1.65f,
                                center = Offset(cx, size.height / 2f),
                            )
                        }
                        drawCircle(color = ledColor, radius = radius, center = Offset(cx, size.height / 2f))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Gear-style Stream Indicator Box
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(spec.accent.copy(alpha = 0.14f))
                            .border(1.dp, spec.accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (isOnline) "W${spec.workers}" else "N",
                            style = MonoMetricStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Black),
                            color = spec.accent,
                        )
                        Text(
                            text = spec.shortTitle,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$valText $unitText",
                            style = MonoMetricStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Black),
                            color = if (spec.liveBps > 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${spec.iface} · MAX ${compactMetric(spec.peakBps, useMBpsUnit)} · AVG ${compactMetric(spec.avgBps, useMBpsUnit)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    StatusPill(
                        label = "${(liveFrac * 100).toInt()}%",
                        color = if (liveFrac >= REDLINE_START_FRACTION) ext.rose else spec.accent,
                    )
                }
            }
        }
    }
}

/**
 * Style 5: Neon Plasma Arc — Dual concentric glowing 300° cyber arcs (outer = live, inner = AVG)
 * with luminous peak dot in a 2×2 high-contrast grid.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NeonPlasmaArcGrid(
    specs: List<NetworkGaugeSpec>,
    useMBpsUnit: Boolean,
    useEngineTags: Boolean,
    onSelectGauge: (String) -> Unit,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val motionSpeed = LocalMotionSpeedMultiplier.current
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        specs.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { spec ->
                    val isOnline = spec.endpoint != null || spec.liveBps > 0L
                    val speedMbps = ((spec.liveBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
                    val peakMbps = ((spec.peakBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
                    val avgMbps = ((spec.avgBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
                    val capMbps = (spec.endpoint?.downstreamKbps?.div(1000f) ?: 100f).coerceAtLeast(25f)
                    val scaleMax = computeAutoScaleCeilingMbps(speedMbps, peakMbps, capMbps)
                    val animLive by animateFloatAsState(
                        targetValue = (speedMbps / scaleMax).coerceIn(0f, 1f),
                        animationSpec = aetherSpringSpec(motionSpeed, dampingRatio = 0.74f, stiffness = 210f),
                        label = "plasma_arc_${spec.id}",
                    )
                    val avgFrac = (avgMbps / scaleMax).coerceIn(0f, 1f)
                    val (valText, unitText) = formatValueAndUnit(spec.liveBps, useMBpsUnit)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .aetherSharedBounds(
                                key = AetherSharedKey(spec.id, SharedPart.GAUGE_CARD),
                                clipShape = RoundedCornerShape(16.dp),
                                useCockpitSpring = true,
                            )
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isOnline) 0.94f else 0.58f))
                            .aetherNeonBorder(
                                neon = neon,
                                accent = spec.accent,
                                cornerRadius = 16.dp,
                                fallbackBorderColor = spec.accent.copy(alpha = 0.45f),
                            )
                            .clickable { onSelectGauge(spec.id) }
                            .padding(10.dp)
                            .testTag(if (useEngineTags) spec.engineTestTag else spec.testTag),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = spec.shortTitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = spec.accent,
                            )
                            Text(
                                text = if (isOnline) "${spec.workers}W" else "OFF",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            modifier = Modifier.size(108.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val outerStroke = 8.dp.toPx()
                                val innerStroke = 4.dp.toPx()
                                val pad = outerStroke
                                val outerSize = Size(size.width - pad * 2f, size.height - pad * 2f)
                                val innerPad = pad + outerStroke * 1.15f
                                val innerSize = Size(size.width - innerPad * 2f, size.height - innerPad * 2f)

                                drawArc(
                                    color = trackColor,
                                    startAngle = 120f,
                                    sweepAngle = 300f,
                                    useCenter = false,
                                    topLeft = Offset(pad, pad),
                                    size = outerSize,
                                    style = Stroke(outerStroke, cap = StrokeCap.Round),
                                )
                                if (animLive > 0.005f) {
                                    if (neon.enabled) {
                                        drawArc(
                                            color = spec.accent.copy(alpha = 0.28f * neon.intensity),
                                            startAngle = 120f,
                                            sweepAngle = 300f * animLive,
                                            useCenter = false,
                                            topLeft = Offset(pad, pad),
                                            size = outerSize,
                                            style = Stroke(outerStroke * 1.9f, cap = StrokeCap.Round),
                                        )
                                    }
                                    drawArc(
                                        brush = Brush.sweepGradient(listOf(spec.accent, ext.neonGlow, ext.emerald, spec.accent)),
                                        startAngle = 120f,
                                        sweepAngle = 300f * animLive,
                                        useCenter = false,
                                        topLeft = Offset(pad, pad),
                                        size = outerSize,
                                        style = Stroke(outerStroke, cap = StrokeCap.Round),
                                    )
                                }
                                // Inner AVG plasma ring
                                drawArc(
                                    color = trackColor.copy(alpha = 0.5f),
                                    startAngle = 120f,
                                    sweepAngle = 300f,
                                    useCenter = false,
                                    topLeft = Offset(innerPad, innerPad),
                                    size = innerSize,
                                    style = Stroke(innerStroke, cap = StrokeCap.Round),
                                )
                                if (avgFrac > 0.005f) {
                                    drawArc(
                                        color = ext.amber,
                                        startAngle = 120f,
                                        sweepAngle = 300f * avgFrac,
                                        useCenter = false,
                                        topLeft = Offset(innerPad, innerPad),
                                        size = innerSize,
                                        style = Stroke(innerStroke, cap = StrokeCap.Round),
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = valText,
                                    style = MonoMetricStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                                    color = if (spec.liveBps > 0L) spec.accent else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = unitText,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = "MAX ${compactMetric(spec.peakBps, useMBpsUnit)} · AVG ${compactMetric(spec.avgBps, useMBpsUnit)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Style 6: Rayburst Matrix Bar — 32-cell glowing micro-bar per link with live worker pulse
 * inspired by the Rayburst recording.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun RayburstMatrixBarStack(
    specs: List<NetworkGaugeSpec>,
    useMBpsUnit: Boolean,
    useEngineTags: Boolean,
    onSelectGauge: (String) -> Unit,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val motionSpeed = LocalMotionSpeedMultiplier.current
    val offCell = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        specs.forEach { spec ->
            val isOnline = spec.endpoint != null || spec.liveBps > 0L
            val speedMbps = ((spec.liveBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
            val peakMbps = ((spec.peakBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
            val capMbps = (spec.endpoint?.downstreamKbps?.div(1000f) ?: 100f).coerceAtLeast(25f)
            val scaleMax = computeAutoScaleCeilingMbps(speedMbps, peakMbps, capMbps)
            val liveFrac by animateFloatAsState(
                targetValue = (speedMbps / scaleMax).coerceIn(0f, 1f),
                animationSpec = aetherSpringSpec(motionSpeed, dampingRatio = 0.80f, stiffness = 260f),
                label = "rayburst_bar_${spec.id}",
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aetherSharedBounds(
                        key = AetherSharedKey(spec.id, SharedPart.GAUGE_CARD),
                        clipShape = RoundedCornerShape(12.dp),
                        useCockpitSpring = true,
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isOnline) 0.94f else 0.56f))
                    .aetherNeonBorder(
                        neon = neon,
                        accent = if (spec.liveBps > 0L) spec.accent else ext.cardBorder,
                        cornerRadius = 12.dp,
                        fallbackBorderColor = ext.cardBorder,
                    )
                    .clickable { onSelectGauge(spec.id) }
                    .padding(horizontal = 10.dp, vertical = 9.dp)
                    .testTag(if (useEngineTags) spec.engineTestTag else spec.testTag),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(spec.icon, contentDescription = null, tint = spec.accent, modifier = Modifier.size(15.dp))
                        Text(
                            text = "${spec.shortTitle} · ${spec.iface}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = "${Formatters.formatSpeed(spec.liveBps, useMBpsUnit)} · ${spec.workers}W",
                        style = MonoMetricStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = if (spec.liveBps > 0L) spec.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Dual-row 32-column Rayburst LED dot matrix
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp),
                ) {
                    val cols = 32
                    val rows = 2
                    val gapX = 2.dp.toPx()
                    val gapY = 2.5.dp.toPx()
                    val cellW = ((size.width - gapX * (cols - 1)) / cols).coerceAtLeast(2f)
                    val cellH = ((size.height - gapY * (rows - 1)) / rows).coerceAtLeast(3f)
                    val corner = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                    for (c in 0 until cols) {
                        val frac = (c + 1).toFloat() / cols.toFloat()
                        val lit = isOnline && frac <= liveFrac + 0.015f && liveFrac > 0.005f
                        val baseCol = when {
                            !lit -> offCell
                            frac >= REDLINE_START_FRACTION -> ext.rose
                            frac >= 0.62f -> ext.amber
                            else -> spec.accent
                        }
                        for (r in 0 until rows) {
                            val topLeft = Offset(c * (cellW + gapX), r * (cellH + gapY))
                            drawRoundRect(
                                color = if (lit && r == 1) baseCol.copy(alpha = 0.72f) else baseCol,
                                topLeft = topLeft,
                                size = Size(cellW, cellH),
                                cornerRadius = corner,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "MAX ${Formatters.formatSpeed(spec.peakBps, useMBpsUnit)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = ext.amber,
                    )
                    Text(
                        text = "AVG ${Formatters.formatSpeed(spec.avgBps, useMBpsUnit)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = ext.emerald,
                    )
                    Text(
                        text = Formatters.formatBytes(spec.bytesTotal),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GaugeStatChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(7.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MonoMetricStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RadialSpeedometerGauge(
    speedBps: Long,
    peakBps: Long,
    capacityHintMbps: Float,
    accentStart: Color,
    accentEnd: Color,
    peakMarkerColor: Color,
    useMBpsUnit: Boolean,
    dialSize: Dp,
    isOnline: Boolean,
    centerSubtitle: String,
    avgBps: Long = 0L,
    avgMarkerColor: Color = accentStart,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val motionSpeed = LocalMotionSpeedMultiplier.current
    val speedMbps = ((speedBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
    val peakMbps = ((peakBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
    val avgMbps = ((avgBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
    val scaleMaxMbps = computeAutoScaleCeilingMbps(
        liveMbps = speedMbps,
        peakMbps = peakMbps,
        capacityHintMbps = capacityHintMbps,
    )

    val targetFraction = (speedMbps / scaleMaxMbps).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = aetherSpringSpec(motionSpeed, dampingRatio = 0.76f, stiffness = 220f),
        label = "speedometer_sweep",
    )
    val peakFraction = (peakMbps / scaleMaxMbps).coerceIn(0f, 1f)
    val avgFraction = (avgMbps / scaleMaxMbps).coerceIn(0f, 1f)

    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    val tickDimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    val hubColor = MaterialTheme.colorScheme.onSurface
    val redlineColor = ext.rose

    val (valueText, unitText) = formatValueAndUnit(speedBps, useMBpsUnit)
    val scaleLabel = if (useMBpsUnit) {
        String.format(Locale.US, "%.0f MB/s", scaleMaxMbps / 8f)
    } else {
        "${scaleMaxMbps.toInt()}M"
    }

    Box(
        modifier = modifier
            .size(dialSize)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = size.minDimension * 0.082f
            val inset = strokePx * 1.38f
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (arcSize.minDimension / 2f)

            drawArc(
                color = trackColor,
                startAngle = DIAL_START_DEG,
                sweepAngle = DIAL_SWEEP_DEG,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            val redlineStartDeg = DIAL_START_DEG + DIAL_SWEEP_DEG * REDLINE_START_FRACTION
            val redlineSweepDeg = DIAL_SWEEP_DEG * (1f - REDLINE_START_FRACTION)
            drawArc(
                color = redlineColor.copy(alpha = 0.28f),
                startAngle = redlineStartDeg,
                sweepAngle = redlineSweepDeg,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            val totalTicks = 26
            for (i in 0..totalTicks) {
                val frac = i.toFloat() / totalTicks.toFloat()
                val angleDeg = DIAL_START_DEG + frac * DIAL_SWEEP_DEG
                val angleRad = angleDeg * (PI / 180.0)
                val isMajor = i % 5 == 0
                val isRedline = frac >= REDLINE_START_FRACTION
                val outerR = radius + strokePx * 0.56f
                val innerR = radius - strokePx * (if (isMajor) 0.64f else 0.30f)
                val startPt = Offset(
                    center.x + (cos(angleRad) * innerR).toFloat(),
                    center.y + (sin(angleRad) * innerR).toFloat(),
                )
                val endPt = Offset(
                    center.x + (cos(angleRad) * outerR).toFloat(),
                    center.y + (sin(angleRad) * outerR).toFloat(),
                )
                val tickLit = isOnline && frac <= animatedFraction && animatedFraction > 0.005f
                val tickColor = when {
                    tickLit && isRedline -> redlineColor
                    tickLit -> accentStart
                    isRedline -> redlineColor.copy(alpha = 0.55f)
                    else -> tickDimColor
                }
                drawLine(
                    color = tickColor,
                    start = startPt,
                    end = endPt,
                    strokeWidth = if (isMajor) 2.6f else 1.3f,
                    cap = StrokeCap.Round,
                )
            }

            if (animatedFraction > 0.003f) {
                val sweep = DIAL_SWEEP_DEG * animatedFraction
                val sweepAccent = if (animatedFraction >= REDLINE_START_FRACTION) redlineColor else accentStart
                val haloAlpha = if (neon.enabled) (0.20f + 0.24f * neon.intensity) else 0.14f
                drawArc(
                    color = sweepAccent.copy(alpha = haloAlpha),
                    startAngle = DIAL_START_DEG,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx * (if (neon.enabled) 1.85f else 1.45f), cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.horizontalGradient(listOf(accentStart, if (animatedFraction >= REDLINE_START_FRACTION) redlineColor else accentEnd)),
                    startAngle = DIAL_START_DEG,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }

            if (avgFraction > 0.01f) {
                val avgRad = (DIAL_START_DEG + avgFraction * DIAL_SWEEP_DEG) * (PI / 180.0)
                drawLine(
                    color = avgMarkerColor,
                    start = Offset(
                        center.x + (cos(avgRad) * (radius - strokePx * 0.78f)).toFloat(),
                        center.y + (sin(avgRad) * (radius - strokePx * 0.78f)).toFloat(),
                    ),
                    end = Offset(
                        center.x + (cos(avgRad) * (radius + strokePx * 0.40f)).toFloat(),
                        center.y + (sin(avgRad) * (radius + strokePx * 0.40f)).toFloat(),
                    ),
                    strokeWidth = 2.8f,
                    cap = StrokeCap.Round,
                )
            }

            if (peakFraction > 0.01f) {
                val pkAngleRad = (DIAL_START_DEG + peakFraction * DIAL_SWEEP_DEG) * (PI / 180.0)
                drawLine(
                    color = peakMarkerColor,
                    start = Offset(
                        center.x + (cos(pkAngleRad) * (radius - strokePx * 0.85f)).toFloat(),
                        center.y + (sin(pkAngleRad) * (radius - strokePx * 0.85f)).toFloat(),
                    ),
                    end = Offset(
                        center.x + (cos(pkAngleRad) * (radius + strokePx * 0.75f)).toFloat(),
                        center.y + (sin(pkAngleRad) * (radius + strokePx * 0.75f)).toFloat(),
                    ),
                    strokeWidth = 3.5f,
                    cap = StrokeCap.Round,
                )
            }

            val needleAngleRad = (DIAL_START_DEG + animatedFraction * DIAL_SWEEP_DEG) * (PI / 180.0)
            val perpRad = needleAngleRad + PI / 2.0
            val needleOuterR = radius - strokePx * 0.40f
            val needleInnerR = radius * 0.48f
            val halfWidth = 2.4f
            val baseCenter = Offset(
                center.x + (cos(needleAngleRad) * needleInnerR).toFloat(),
                center.y + (sin(needleAngleRad) * needleInnerR).toFloat(),
            )
            val needleTip = Offset(
                center.x + (cos(needleAngleRad) * needleOuterR).toFloat(),
                center.y + (sin(needleAngleRad) * needleOuterR).toFloat(),
            )
            val needlePath = Path().apply {
                moveTo(needleTip.x, needleTip.y)
                lineTo(
                    baseCenter.x + (cos(perpRad) * halfWidth).toFloat(),
                    baseCenter.y + (sin(perpRad) * halfWidth).toFloat(),
                )
                lineTo(
                    baseCenter.x - (cos(perpRad) * halfWidth).toFloat(),
                    baseCenter.y - (sin(perpRad) * halfWidth).toFloat(),
                )
                close()
            }
            drawPath(
                path = needlePath,
                color = if (isOnline) {
                    if (animatedFraction >= REDLINE_START_FRACTION) redlineColor else accentStart
                } else {
                    tickDimColor
                },
            )
            drawCircle(
                color = if (isOnline) hubColor else tickDimColor,
                radius = 3.2f,
                center = needleTip,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = valueText,
                style = MonoMetricStyle.copy(
                    fontSize = when {
                        dialSize >= 170.dp -> 26.sp
                        dialSize >= 125.dp -> 17.sp
                        dialSize >= 105.dp -> 15.sp
                        else -> 13.sp
                    },
                    fontWeight = FontWeight.Bold,
                ),
                color = if (speedBps > 0L) accentStart else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = unitText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = if (dialSize < 105.dp) 9.sp else 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = centerSubtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = if (peakBps > 0L) peakMarkerColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.76f)
                .padding(bottom = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "0",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = scaleLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EngineAbBenchmarkStrip(
    rustPeakBps: Long,
    kotlinPeakBps: Long,
    useMBpsUnit: Boolean,
) {
    val ext = LocalAetherColors.current
    val rustFaster = rustPeakBps > 0L && kotlinPeakBps > 0L && rustPeakBps > kotlinPeakBps
    val kotlinFaster = rustPeakBps > 0L && kotlinPeakBps > 0L && kotlinPeakBps > rustPeakBps

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, ext.cardBorder, RoundedCornerShape(12.dp))
            .padding(10.dp)
            .testTag("engine_ab_benchmark_strip"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "DUAL-ENGINE ON-DEVICE PEAK BENCHMARK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BenchmarkCell(
                engineName = "Rust Native",
                peakBps = rustPeakBps,
                useMBpsUnit = useMBpsUnit,
                accent = ext.emerald,
                isWinner = rustFaster,
                modifier = Modifier.weight(1f),
            )
            BenchmarkCell(
                engineName = "Kotlin OkHttp",
                peakBps = kotlinPeakBps,
                useMBpsUnit = useMBpsUnit,
                accent = ext.cyan,
                isWinner = kotlinFaster,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BenchmarkCell(
    engineName: String,
    peakBps: Long,
    useMBpsUnit: Boolean,
    accent: Color,
    isWinner: Boolean,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .border(
                width = 1.dp,
                color = if (isWinner) accent.copy(alpha = 0.7f) else ext.cardBorder,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = engineName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isWinner) {
                StatusPill(label = "FASTER", color = accent)
            }
        }
        Text(
            text = if (peakBps > 0L) Formatters.formatSpeed(peakBps, useMBpsUnit) else "Not tested yet",
            style = MonoMetricStyle,
            color = if (peakBps > 0L) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun computeAutoScaleCeilingMbps(
    liveMbps: Float,
    peakMbps: Float,
    capacityHintMbps: Float,
): Float {
    val demand = maxOf(liveMbps * 1.25f, peakMbps * 1.15f, minOf(capacityHintMbps, 100f))
    return when {
        demand <= 25f -> 25f
        demand <= 50f -> 50f
        demand <= 100f -> 100f
        demand <= 250f -> 250f
        demand <= 500f -> 500f
        demand <= 1000f -> 1000f
        else -> 2500f
    }
}

private fun compactMetric(bytesPerSec: Long, useMBpsUnit: Boolean): String {
    if (bytesPerSec <= 0L) return "--"
    val (v, u) = formatValueAndUnit(bytesPerSec, useMBpsUnit)
    return "$v ${if (u == "Mbps") "M" else "MB/s"}"
}

private fun formatValueAndUnit(bytesPerSec: Long, useMBpsUnit: Boolean): Pair<String, String> {
    if (bytesPerSec <= 0L) {
        return if (useMBpsUnit) "0.00" to "MB/s" else "0.0" to "Mbps"
    }
    return if (useMBpsUnit) {
        val mbs = bytesPerSec / (1024.0 * 1024.0)
        val formatted = if (mbs >= 100.0) {
            String.format(Locale.US, "%.1f", mbs)
        } else {
            String.format(Locale.US, "%.2f", mbs)
        }
        formatted to "MB/s"
    } else {
        val mbps = (bytesPerSec * 8.0) / 1_000_000.0
        val formatted = if (mbps >= 100.0) {
            String.format(Locale.US, "%.0f", mbps)
        } else {
            String.format(Locale.US, "%.1f", mbps)
        }
        formatted to "Mbps"
    }
}
