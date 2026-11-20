package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.LocalAetherNeonConfig
import com.example.ui.theme.MonoMetricStyle
import com.example.ui.theme.aetherNeonBorder
import com.example.ui.theme.contrastOnColor
import com.example.util.Formatters
import java.util.Locale

/**
 * Full-screen Motorsport Focused Gauge Cockpit that morphs directly out of any tapped
 * network speedometer (Wi-Fi 1, Mobile 5G, Wi-Fi 2, USB/Eth) using root Shared Element Transitions.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun FocusedGaugeOverlay(
    gaugeId: String,
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    useMBpsUnit: Boolean,
    isSimulating: Boolean,
    onPulseLinkBurst: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    BackHandler(onBack = onDismiss)

    val (title, iface, icon, accent, liveBps, peakBps, avgBps, bytesTotal, workers, history, endpoint) = when (gaugeId) {
        "cell" -> FocusedSpec(
            title = "Mobile 5G / LTE",
            iface = networkMatrix.cellularLink?.interfaceName ?: "rmnet0",
            icon = Icons.Filled.SignalCellularAlt,
            accent = ext.amber,
            liveBps = telemetry.cellBps,
            peakBps = telemetry.cellPeakBps,
            avgBps = telemetry.cellAvgBps,
            bytesTotal = telemetry.cellBytesTotal,
            workers = telemetry.cellularWorkers,
            history = telemetry.cellHistoryBps,
            endpoint = networkMatrix.cellularLink,
        )
        "wifi2" -> FocusedSpec(
            title = "Wi-Fi 2 Boost",
            iface = networkMatrix.wifi2Link?.interfaceName ?: "wlan1 / relay",
            icon = Icons.Filled.Router,
            accent = ext.violet,
            liveBps = telemetry.wifi2Bps,
            peakBps = telemetry.wifi2PeakBps,
            avgBps = telemetry.wifi2AvgBps,
            bytesTotal = telemetry.wifi2BytesTotal,
            workers = telemetry.wifi2Workers,
            history = telemetry.wifi2HistoryBps,
            endpoint = networkMatrix.wifi2Link,
        )
        "usb" -> FocusedSpec(
            title = "USB Tether / Eth",
            iface = networkMatrix.ethernetLink?.interfaceName ?: "usb0 / eth0",
            icon = Icons.Filled.Cable,
            accent = ext.emerald,
            liveBps = telemetry.ethBps,
            peakBps = telemetry.ethPeakBps,
            avgBps = telemetry.ethAvgBps,
            bytesTotal = telemetry.ethBytesTotal,
            workers = telemetry.ethWorkers,
            history = telemetry.ethHistoryBps,
            endpoint = networkMatrix.ethernetLink,
        )
        else -> FocusedSpec(
            title = "Wi-Fi 1 Primary",
            iface = networkMatrix.wifiLink?.interfaceName ?: "wlan0",
            icon = Icons.Filled.Wifi,
            accent = ext.cyan,
            liveBps = telemetry.wifiBps,
            peakBps = telemetry.wifiPeakBps,
            avgBps = telemetry.wifiAvgBps,
            bytesTotal = telemetry.wifiBytesTotal,
            workers = telemetry.wifiWorkers,
            history = telemetry.wifiHistoryBps,
            endpoint = networkMatrix.wifiLink,
        )
    }

    val isOnline = endpoint != null || liveBps > 0L
    val capMbps = (endpoint?.downstreamKbps?.div(1000f) ?: 250f).coerceAtLeast(50f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(16.dp)
            .testTag("focused_gauge_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .aetherSharedBounds(
                    key = AetherSharedKey(gaugeId, SharedPart.GAUGE_CARD),
                    clipShape = RoundedCornerShape(22.dp),
                    useCockpitSpring = true,
                    zIndexInOverlay = 3f,
                )
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(ext.heroTop, ext.heroBottom)))
                .aetherNeonBorder(
                    neon = neon,
                    accent = accent,
                    cornerRadius = 22.dp,
                    borderWidth = 1.5.dp,
                    fallbackBorderColor = accent.copy(alpha = 0.75f),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Interface $iface · ${if (isOnline) "$workers Active Workers" else "Standby"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_focused_gauge_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close focused gauge",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (isSimulating) {
                StatusPill(label = "SIMULATION MODE ACTIVE", color = ext.amber)
            }

            RadialSpeedometerGauge(
                speedBps = liveBps,
                peakBps = peakBps,
                avgBps = avgBps,
                capacityHintMbps = capMbps,
                accentStart = accent,
                accentEnd = ext.emerald,
                peakMarkerColor = ext.amber,
                avgMarkerColor = ext.violet,
                useMBpsUnit = useMBpsUnit,
                dialSize = 232.dp,
                isOnline = isOnline,
                centerSubtitle = "MAX ${Formatters.formatSpeed(peakBps, useMBpsUnit)}",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FocusedMetricCard(
                    label = "LIVE",
                    value = Formatters.formatSpeed(liveBps, useMBpsUnit),
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                FocusedMetricCard(
                    label = "MAX PEAK",
                    value = Formatters.formatSpeed(peakBps, useMBpsUnit),
                    color = ext.amber,
                    modifier = Modifier.weight(1f),
                )
                FocusedMetricCard(
                    label = "AVG ACTIVE",
                    value = Formatters.formatSpeed(avgBps, useMBpsUnit),
                    color = ext.violet,
                    modifier = Modifier.weight(1f),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
                    .border(1.dp, ext.cardBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "LIVE LINK WAVEFORM",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
                ThroughputSparkline(
                    historyBps = history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusPill(label = "Pulled ${Formatters.formatBytes(bytesTotal)}", color = ext.cyan)
                StatusPill(
                    label = endpoint?.let { String.format(Locale.US, "Cap %.0f Mbps", it.downstreamKbps / 1000.0) }
                        ?: "Standby Cap",
                    color = ext.emerald,
                )
                StatusPill(
                    label = "Secondary ${Formatters.formatSpeed(liveBps, !useMBpsUnit)}",
                    color = ext.violet,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onPulseLinkBurst(gaugeId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = contrastOnColor(accent),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pulse_gauge_burst_button"),
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Simulate Redline Burst")
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("Done", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

private data class FocusedSpec(
    val title: String,
    val iface: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: Color,
    val liveBps: Long,
    val peakBps: Long,
    val avgBps: Long,
    val bytesTotal: Long,
    val workers: Int,
    val history: List<Long>,
    val endpoint: com.example.engine.LinkEndpointInfo?,
)

@Composable
private fun FocusedMetricCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MonoMetricStyle,
            color = color,
            maxLines = 1,
        )
    }
}
