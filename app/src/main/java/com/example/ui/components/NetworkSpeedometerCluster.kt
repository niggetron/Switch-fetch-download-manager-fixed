package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.engine.LinkEndpointInfo
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoMetricStyle
import com.example.util.Formatters
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val DIAL_START_DEG = 150f
private const val DIAL_SWEEP_DEG = 240f

/**
 * Compact per-network live speedometer cluster embedded directly in the Transfers hero banner.
 * Renders animated radial speedometer dials for Wi-Fi, 5G/LTE, and USB-C Ethernet side-by-side.
 */
@Composable
fun PerNetworkSpeedometerCluster(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    useMBpsUnit: Boolean,
    onToggleUnit: () -> Unit,
    onResetPeaks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
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
                    text = "LIVE NETWORK SPEEDOMETERS",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.cyan,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = useMBpsUnit,
                    onClick = onToggleUnit,
                    label = { Text(if (useMBpsUnit) "MB/s" else "Mbps") },
                    modifier = Modifier.testTag("speed_unit_toggle_transfers"),
                )
                OutlinedButton(
                    onClick = onResetPeaks,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("reset_peaks_transfers"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reset speed peaks",
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        val showEthInline = networkMatrix.ethernetLink != null || telemetry.ethBps > 0L || telemetry.ethPeakBps > 0L

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetworkSpeedometerCard(
                title = "Wi-Fi",
                iface = networkMatrix.wifiLink?.interfaceName ?: "wlan0",
                icon = Icons.Filled.Wifi,
                speedBps = telemetry.wifiBps,
                peakBps = telemetry.wifiPeakBps,
                bytesTotal = telemetry.wifiBytesTotal,
                workers = telemetry.wifiWorkers,
                endpoint = networkMatrix.wifiLink,
                accent = ext.cyan,
                useMBpsUnit = useMBpsUnit,
                compact = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("speedometer_wifi"),
            )
            NetworkSpeedometerCard(
                title = "5G/LTE",
                iface = networkMatrix.cellularLink?.interfaceName ?: "rmnet0",
                icon = Icons.Filled.SignalCellularAlt,
                speedBps = telemetry.cellBps,
                peakBps = telemetry.cellPeakBps,
                bytesTotal = telemetry.cellBytesTotal,
                workers = telemetry.cellularWorkers,
                endpoint = networkMatrix.cellularLink,
                accent = ext.violet,
                useMBpsUnit = useMBpsUnit,
                compact = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("speedometer_cellular"),
            )
        }

        if (showEthInline) {
            NetworkSpeedometerCard(
                title = "USB-C Ethernet",
                iface = networkMatrix.ethernetLink?.interfaceName ?: "eth0",
                icon = Icons.Filled.Cable,
                speedBps = telemetry.ethBps,
                peakBps = telemetry.ethPeakBps,
                bytesTotal = telemetry.ethBytesTotal,
                workers = telemetry.ethWorkers,
                endpoint = networkMatrix.ethernetLink,
                accent = ext.emerald,
                useMBpsUnit = useMBpsUnit,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("speedometer_ethernet"),
            )
        }

        if (telemetry.wifiBytesTotal + telemetry.cellBytesTotal + telemetry.ethBytesTotal > 0L) {
            SankeyLinkSplitBar(
                wifiBytes = telemetry.wifiBytesTotal,
                cellBytes = telemetry.cellBytesTotal,
                ethBytes = telemetry.ethBytesTotal,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Full Engine-tab Per-Network Speedometer Dashboard featuring:
 * - Hero Aggregate Multi-Link Radial Speedometer
 * - Dedicated Live Speedometer for EVERY network interface (Wi-Fi, 5G/LTE, USB-C Ethernet)
 * - Dual-Engine A/B Peak Throughput Comparison (Rust Native vs Kotlin OkHttp)
 */
@Composable
fun FullSpeedometerDashboardCard(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    useMBpsUnit: Boolean,
    onToggleUnit: () -> Unit,
    onResetPeaks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val totalCapacityMbps = networkMatrix.aggregateEstimatedMbps.toFloat().coerceAtLeast(100f)

    EngineSectionCard(
        title = "MULTI-NETWORK LIVE SPEEDOMETERS & A/B BENCHMARK",
        modifier = modifier.testTag("full_speedometer_dashboard"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(
                label = "${telemetry.activeEngineBadge} · ${telemetry.activeWorkers}W",
                color = ext.emerald,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
        }

        // Hero Aggregate Multi-Link Speedometer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(ext.heroTop, ext.heroBottom)))
                .border(1.dp, ext.cardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp)
                .testTag("speedometer_aggregate"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "AGGREGATE MULTI-LINK GOODPUT",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.cyan,
                )
                RadialSpeedometerGauge(
                    speedBps = telemetry.instantBps,
                    peakBps = telemetry.peakBps,
                    capacityHintMbps = totalCapacityMbps,
                    accentStart = ext.cyan,
                    accentEnd = ext.emerald,
                    peakMarkerColor = ext.amber,
                    useMBpsUnit = useMBpsUnit,
                    dialSize = 196.dp,
                    isOnline = networkMatrix.hasAnyInternet || telemetry.instantBps > 0L,
                    centerSubtitle = "PEAK ${Formatters.formatSpeed(telemetry.peakBps, useMBpsUnit)}",
                )
                Text(
                    text = "Secondary: ${Formatters.formatSpeed(telemetry.instantBps, !useMBpsUnit)} · TTFB ${telemetry.lastTtfbMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Individual Radial Speedometer for EVERY network interface
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetworkSpeedometerCard(
                title = "Wi-Fi",
                iface = networkMatrix.wifiLink?.interfaceName ?: "wlan0",
                icon = Icons.Filled.Wifi,
                speedBps = telemetry.wifiBps,
                peakBps = telemetry.wifiPeakBps,
                bytesTotal = telemetry.wifiBytesTotal,
                workers = telemetry.wifiWorkers,
                endpoint = networkMatrix.wifiLink,
                accent = ext.cyan,
                useMBpsUnit = useMBpsUnit,
                compact = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag("engine_speedometer_wifi"),
            )
            NetworkSpeedometerCard(
                title = "5G / LTE",
                iface = networkMatrix.cellularLink?.interfaceName ?: "rmnet0",
                icon = Icons.Filled.SignalCellularAlt,
                speedBps = telemetry.cellBps,
                peakBps = telemetry.cellPeakBps,
                bytesTotal = telemetry.cellBytesTotal,
                workers = telemetry.cellularWorkers,
                endpoint = networkMatrix.cellularLink,
                accent = ext.violet,
                useMBpsUnit = useMBpsUnit,
                compact = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag("engine_speedometer_cell"),
            )
        }

        NetworkSpeedometerCard(
            title = "USB-C Ethernet",
            iface = networkMatrix.ethernetLink?.interfaceName ?: "eth0",
            icon = Icons.Filled.Cable,
            speedBps = telemetry.ethBps,
            peakBps = telemetry.ethPeakBps,
            bytesTotal = telemetry.ethBytesTotal,
            workers = telemetry.ethWorkers,
            endpoint = networkMatrix.ethernetLink,
            accent = ext.emerald,
            useMBpsUnit = useMBpsUnit,
            compact = false,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("engine_speedometer_eth"),
        )

        // Dual-Engine A/B Benchmark Comparison Strip
        EngineAbBenchmarkStrip(
            rustPeakBps = telemetry.rustPeakBps,
            kotlinPeakBps = telemetry.kotlinPeakBps,
            useMBpsUnit = useMBpsUnit,
        )
    }
}

@Composable
private fun NetworkSpeedometerCard(
    title: String,
    iface: String,
    icon: ImageVector,
    speedBps: Long,
    peakBps: Long,
    bytesTotal: Long,
    workers: Int,
    endpoint: LinkEndpointInfo?,
    accent: Color,
    useMBpsUnit: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val isOnline = endpoint != null || speedBps > 0L
    val breakerDotColor = when (endpoint?.breakerState) {
        BreakerState.CLOSED -> ext.emerald
        BreakerState.HALF_OPEN -> ext.amber
        BreakerState.OPEN -> ext.rose
        null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    val capacityMbps = (endpoint?.downstreamKbps?.div(1000f) ?: 100f).coerceAtLeast(25f)
    val speedDesc = Formatters.formatSpeed(speedBps, useMBpsUnit)
    val peakDesc = Formatters.formatSpeed(peakBps, useMBpsUnit)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isOnline) 0.85f else 0.50f))
            .border(
                width = 1.dp,
                color = if (speedBps > 0L) accent.copy(alpha = 0.55f) else ext.cardBorder,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(if (compact) 10.dp else 12.dp)
            .semantics {
                contentDescription = "$title $iface speedometer: live $speedDesc, peak $peakDesc, $workers active workers, ${Formatters.formatBytes(bytesTotal)} transferred"
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
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isOnline) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "$title · $iface",
                    style = MaterialTheme.typography.labelSmall,
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
                    text = if (isOnline) "${workers}W" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOnline) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        RadialSpeedometerGauge(
            speedBps = speedBps,
            peakBps = peakBps,
            capacityHintMbps = capacityMbps,
            accentStart = accent,
            accentEnd = accent.copy(alpha = 0.72f),
            peakMarkerColor = ext.amber,
            useMBpsUnit = useMBpsUnit,
            dialSize = if (compact) 118.dp else 136.dp,
            isOnline = isOnline,
            centerSubtitle = if (peakBps > 0L) "PK $peakDesc" else if (isOnline) "READY" else "OFFLINE",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Pulled ${Formatters.formatBytes(bytesTotal)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (endpoint != null) {
                Text(
                    text = String.format(Locale.US, "%.0fM cap", endpoint.downstreamKbps / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Hardware-accelerated Compose Canvas 240° precision radial speedometer dial with:
 * - Spring-damped needle & active arc sweep
 * - Calibrated perimeter major/minor ticks
 * - Auto-ranging full-scale ceiling (25 -> 2500 Mbps)
 * - Amber peak-hold tick marker on the dial arc
 */
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
    modifier: Modifier = Modifier,
) {
    val speedMbps = ((speedBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
    val peakMbps = ((peakBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0).toFloat()
    val scaleMaxMbps = computeAutoScaleCeilingMbps(
        liveMbps = speedMbps,
        peakMbps = peakMbps,
        capacityHintMbps = capacityHintMbps,
    )

    val targetFraction = (speedMbps / scaleMaxMbps).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 210f),
        label = "speedometer_sweep",
    )
    val peakFraction = (peakMbps / scaleMaxMbps).coerceIn(0f, 1f)

    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    val tickDimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val hubColor = MaterialTheme.colorScheme.onSurface

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
            val strokePx = size.minDimension * 0.085f
            val inset = strokePx * 1.35f
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (arcSize.minDimension / 2f)

            // Background 240° track
            drawArc(
                color = trackColor,
                startAngle = DIAL_START_DEG,
                sweepAngle = DIAL_SWEEP_DEG,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            // Calibrated perimeter ticks (24 divisions -> 25 ticks)
            val totalTicks = 24
            for (i in 0..totalTicks) {
                val frac = i.toFloat() / totalTicks.toFloat()
                val angleDeg = DIAL_START_DEG + frac * DIAL_SWEEP_DEG
                val angleRad = angleDeg * (PI / 180.0)
                val isMajor = i % 6 == 0
                val outerR = radius + strokePx * 0.56f
                val innerR = radius - strokePx * (if (isMajor) 0.62f else 0.30f)
                val startPt = Offset(
                    center.x + (cos(angleRad) * innerR).toFloat(),
                    center.y + (sin(angleRad) * innerR).toFloat(),
                )
                val endPt = Offset(
                    center.x + (cos(angleRad) * outerR).toFloat(),
                    center.y + (sin(angleRad) * outerR).toFloat(),
                )
                val tickLit = isOnline && frac <= animatedFraction && animatedFraction > 0.005f
                drawLine(
                    color = if (tickLit) accentStart else tickDimColor,
                    start = startPt,
                    end = endPt,
                    strokeWidth = if (isMajor) 2.6f else 1.3f,
                    cap = StrokeCap.Round,
                )
            }

            // Active glowing progress sweep
            if (animatedFraction > 0.003f) {
                val sweep = DIAL_SWEEP_DEG * animatedFraction
                drawArc(
                    color = accentStart.copy(alpha = 0.22f),
                    startAngle = DIAL_START_DEG,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx * 1.65f, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.horizontalGradient(listOf(accentStart, accentEnd)),
                    startAngle = DIAL_START_DEG,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }

            // Peak-hold marker tick on arc
            if (peakFraction > 0.01f) {
                val pkAngleRad = (DIAL_START_DEG + peakFraction * DIAL_SWEEP_DEG) * (PI / 180.0)
                val pInner = Offset(
                    center.x + (cos(pkAngleRad) * (radius - strokePx * 0.85f)).toFloat(),
                    center.y + (sin(pkAngleRad) * (radius - strokePx * 0.85f)).toFloat(),
                )
                val pOuter = Offset(
                    center.x + (cos(pkAngleRad) * (radius + strokePx * 0.75f)).toFloat(),
                    center.y + (sin(pkAngleRad) * (radius + strokePx * 0.75f)).toFloat(),
                )
                drawLine(
                    color = peakMarkerColor,
                    start = pInner,
                    end = pOuter,
                    strokeWidth = 3.5f,
                    cap = StrokeCap.Round,
                )
            }

            // Animated speedometer needle
            val needleAngleRad = (DIAL_START_DEG + animatedFraction * DIAL_SWEEP_DEG) * (PI / 180.0)
            val needleOuterR = radius - strokePx * 0.45f
            val needleInnerR = radius * 0.46f
            val needleBase = Offset(
                center.x + (cos(needleAngleRad) * needleInnerR).toFloat(),
                center.y + (sin(needleAngleRad) * needleInnerR).toFloat(),
            )
            val needleTip = Offset(
                center.x + (cos(needleAngleRad) * needleOuterR).toFloat(),
                center.y + (sin(needleAngleRad) * needleOuterR).toFloat(),
            )
            drawLine(
                color = if (isOnline) accentStart else tickDimColor,
                start = needleBase,
                end = needleTip,
                strokeWidth = 3.4f,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = if (isOnline) hubColor else tickDimColor,
                radius = 3.5f,
                center = needleTip,
            )
        }

        // Center digital speed telemetry readout
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text(
                text = valueText,
                style = MonoMetricStyle.copy(
                    fontSize = if (dialSize >= 170.dp) 26.sp else if (dialSize >= 130.dp) 18.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (speedBps > 0L) accentStart else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = unitText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = centerSubtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (peakBps > 0L) peakMarkerColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Bottom scale endpoints (0 .. MAX)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.76f)
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "0",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = scaleLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
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
