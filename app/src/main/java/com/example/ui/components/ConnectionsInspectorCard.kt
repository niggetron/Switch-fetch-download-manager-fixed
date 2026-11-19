package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ActiveConnectionInfo
import com.example.engine.LinkEndpointInfo
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.ui.motion.LocalMotionSpeedMultiplier
import com.example.ui.motion.aetherSpringSpec
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoMetricStyle
import com.example.util.Formatters

/**
 * Live Per-Network Active Speed Cards showing exact throughput on Wi-Fi, 5G/LTE,
 * and hot-plugged USB-C Ethernet simultaneously (`wifi 40 Mbps`, `mobile 100 Mbps`).
 */
@Composable
fun PerNetworkLiveSpeedPanel(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val maxLinkBps = maxOf(telemetry.wifiBps, telemetry.cellBps, telemetry.ethBps, 128L * 1024L)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("per_network_live_speed_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetworkSpeedTile(
                title = "Wi-Fi (${networkMatrix.wifiLink?.interfaceName ?: "wlan0"})",
                speedBps = telemetry.wifiBps,
                bytesTotal = telemetry.wifiBytesTotal,
                workers = telemetry.wifiWorkers,
                endpoint = networkMatrix.wifiLink,
                maxRefBps = maxLinkBps,
                accent = ext.cyan,
                modifier = Modifier.weight(1f),
            )
            NetworkSpeedTile(
                title = "5G/LTE (${networkMatrix.cellularLink?.interfaceName ?: "rmnet0"})",
                speedBps = telemetry.cellBps,
                bytesTotal = telemetry.cellBytesTotal,
                workers = telemetry.cellularWorkers,
                endpoint = networkMatrix.cellularLink,
                maxRefBps = maxLinkBps,
                accent = ext.violet,
                modifier = Modifier.weight(1f),
            )
        }

        if (networkMatrix.ethernetLink != null || telemetry.ethBps > 0L) {
            NetworkSpeedTile(
                title = "USB-C Eth (${networkMatrix.ethernetLink?.interfaceName ?: "eth0"})",
                speedBps = telemetry.ethBps,
                bytesTotal = telemetry.ethBytesTotal,
                workers = telemetry.ethWorkers,
                endpoint = networkMatrix.ethernetLink,
                maxRefBps = maxLinkBps,
                accent = ext.emerald,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (telemetry.wifiBytesTotal + telemetry.wifi2BytesTotal + telemetry.cellBytesTotal + telemetry.ethBytesTotal > 0L) {
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

@Composable
private fun NetworkSpeedTile(
    title: String,
    speedBps: Long,
    bytesTotal: Long,
    workers: Int,
    endpoint: LinkEndpointInfo?,
    maxRefBps: Long,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val speedLabel = Formatters.formatSpeedMbps(speedBps)
    val frac = (speedBps.toFloat() / maxRefBps.toFloat()).coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .border(1.dp, LocalAetherColors.current.cardBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
            .semantics {
                contentDescription = "$title active speed $speedLabel, $workers workers, ${Formatters.formatBytes(bytesTotal)} transferred"
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (endpoint != null) "${workers}W" else "OFF",
                style = MaterialTheme.typography.labelSmall,
                color = if (endpoint != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = speedLabel,
            style = MonoMetricStyle,
            color = if (speedBps > 0L) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        Text(
            text = "Pulled ${Formatters.formatBytes(bytesTotal)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Ketch-Style Live Connections Inspector Dashboard:
 * Lists every active socket stream with its mirror URL, bound LinkKey, protocol badge
 * (H3 QUIC / wreq H2 / OkHttp), RTT/TTFB, byte range, and individual live throughput.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KetchConnectionsInspectorCard(
    connections: List<ActiveConnectionInfo>,
    activeEngineBadge: String,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    val motionSpeed = LocalMotionSpeedMultiplier.current
    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, ext.cardBorder, RoundedCornerShape(16.dp))
            .animateContentSize(aetherSpringSpec(motionSpeed))
            .padding(14.dp)
            .testTag("ketch_connections_inspector"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Cable,
                    contentDescription = null,
                    tint = ext.cyan,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "LIVE CONNECTIONS (${connections.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = ext.cyan,
                )
                StatusPill(label = activeEngineBadge, color = ext.emerald)
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse connections" else "Expand connections",
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                connections.forEach { conn ->
                    val linkAccent = when {
                        conn.linkKeyId.startsWith("wifi2:") -> ext.violet
                        conn.linkKeyId.startsWith("cell:") -> ext.amber
                        conn.linkKeyId.startsWith("eth:") || conn.linkKeyId.startsWith("usb:") -> ext.emerald
                        else -> ext.cyan
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = conn.url.substringAfter("://").substringBefore('/').ifBlank { conn.url },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = Formatters.formatSpeedMbps(conn.speedBps),
                                style = MonoMetricStyle,
                                color = linkAccent,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            StatusPill(label = conn.linkKeyId, color = linkAccent)
                            StatusPill(label = conn.protocol, color = ext.amber)
                            StatusPill(label = "${conn.socketState} · ${conn.ttfbMs}ms", color = ext.emerald)
                        }
                        val rangeLabel = if (conn.rangeEnd > 0L) {
                            "[${Formatters.formatBytes(conn.rangeStart)}..${Formatters.formatBytes(conn.rangeEnd)}]"
                        } else {
                            "Stream"
                        }
                        Text(
                            text = "Range $rangeLabel · Pulled ${Formatters.formatBytes(conn.bytesReceived)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
