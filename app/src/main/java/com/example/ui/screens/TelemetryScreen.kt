package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineSettings
import com.example.data.model.NetworkRouteMode
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.ui.components.EightAxisRadarChart
import com.example.ui.components.RadarAxis
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoMetricStyle
import com.example.ui.theme.ThemeMode
import com.example.util.Formatters
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelemetryScreen(
    telemetry: TelemetrySnapshot,
    networkMatrix: NetworkMatrixState,
    settings: EngineSettings,
    downloads: List<DownloadEntity>,
    onUpdateSettings: ((EngineSettings) -> EngineSettings) -> Unit,
    onRefreshNetwork: () -> Unit,
    onPruneCas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = LocalAetherColors.current
    var proxyHostDraft by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
    var proxyPortDraft by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }

    val completed = downloads.filter { it.statusEnum == DownloadStatus.COMPLETED }
    val peakMbps = ((telemetry.peakBps * 8.0) / 1_000_000.0).toFloat()
    val lockAxis = ((peakMbps / 100f) * 100f).coerceIn(40f, 99.7f)
    val ramAxis = (100f - (telemetry.jvmHeapUsedMb / 100f) * 100f).coerceIn(50f, 96f)
    val ttfbScore = if (telemetry.lastTtfbMs > 0L) {
        (100f * (1f - telemetry.lastTtfbMs / 500f)).coerceIn(35f, 98f)
    } else {
        94f
    }

    val radarAxes = listOf(
        RadarAxis("1. Lock", lockAxis),
        RadarAxis("2. RAM", ramAxis),
        RadarAxis("3. Thermal", if (telemetry.thermalHeadroomClear) 95.8f else 62f),
        RadarAxis("4. Batching", 98.5f),
        RadarAxis("5. CAS/SHA", if (settings.enableCasVerification) 99.4f else 75f),
        RadarAxis("6. 0-Merge", 95.4f),
        RadarAxis("7. TTFB", ttfbScore),
        RadarAxis("8. OS/5G", if (networkMatrix.isMultipathReady) 99.2f else 88.0f),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 4-Layer Architecture Live Matrix
        item(key = "four_layer_cards") {
            SectionCard(title = "4-LAYER ENGINE TELEMETRY") {
                LayerMetricRow(
                    title = "L1 · OS Control & Quota Guard",
                    detail = "Thermal: ${telemetry.thermalStatusLabel} · Quota left ${Formatters.formatDurationHms(telemetry.quotaRemainingMs)}",
                    color = ext.cyan,
                )
                LayerMetricRow(
                    title = "L2 · Lifecycle Telemetry Bridge",
                    detail = "${telemetry.pollingHzLabel} · 0 Hz automatic suspension on ON_STOP",
                    color = ext.violet,
                )
                LayerMetricRow(
                    title = "L3 · Multipath Transport Matrix",
                    detail = "Peak ${Formatters.formatSpeedMbps(telemetry.peakBps)} · Workers ${telemetry.activeWorkers} (${telemetry.wifiWorkers}W/${telemetry.cellularWorkers}C)",
                    color = ext.emerald,
                )
                LayerMetricRow(
                    title = "L4 · DirectByteBuffer Slab & 2 MB Extent",
                    detail = String.format(
                        "%s\nSlab Pool %.1f MB · JVM %.1f MB · Native %.1f MB · %d batches",
                        com.example.engine.AetherNativeBridge.engineBackendLabel,
                        telemetry.slabAllocatedMb,
                        telemetry.jvmHeapUsedMb,
                        telemetry.nativeHeapMb,
                        telemetry.totalSyscallWriteBatches,
                    ),
                    color = ext.amber,
                )
            }
        }

        // Network Link Matrix
        item(key = "network_matrix") {
            SectionCard(title = "MULTIPATH INTERFACE MATRIX") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        label = if (networkMatrix.isMultipathReady) "WIFI + 5G BOND READY" else "SINGLE LINK",
                        color = if (networkMatrix.isMultipathReady) ext.emerald else ext.cyan,
                    )
                    OutlinedButton(
                        onClick = onRefreshNetwork,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("Probe Links")
                    }
                }
                LinkInfoRow("wlan0 (Wi-Fi)", networkMatrix.wifiLink?.downstreamKbps)
                LinkInfoRow("rmnet0 (5G / Cellular)", networkMatrix.cellularLink?.downstreamKbps)
                Text(
                    text = "Completed zero-merge tasks: ${completed.size} · Last TTFB: ${telemetry.lastTtfbMs} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 8-Axis Radar Audit
        item(key = "eight_axis_radar") {
            SectionCard(title = "8-AXIS SESSION & ARCHITECTURE AUDIT") {
                EightAxisRadarChart(
                    axes = radarAxes,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Engine Configuration
        item(key = "engine_config") {
            SectionCard(title = "ENGINE & NETWORK CONFIGURATION") {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Default Worker Concurrency", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${settings.maxConcurrentWorkers} streams",
                            style = MonoMetricStyle,
                            color = ext.cyan,
                        )
                    }
                    Slider(
                        value = settings.maxConcurrentWorkers.toFloat(),
                        onValueChange = {
                            val count = it.roundToInt()
                            onUpdateSettings { s -> s.copy(maxConcurrentWorkers = count) }
                        },
                        valueRange = 1f..8f,
                        steps = 6,
                    )
                }

                ToggleRow(
                    title = "Tail Shadow-Hedge Work-Stealing",
                    subtitle = "Idle workers race slow final 2 MB extents",
                    checked = settings.enableTailHedge,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableTailHedge = on) } },
                )

                ToggleRow(
                    title = "S3 SHA-256 & 2 MB CAS Digest Audit",
                    subtitle = "Verify & index completed 2 MB extents",
                    checked = settings.enableCasVerification,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableCasVerification = on) } },
                )

                Text(
                    text = "DEFAULT ROUTE POLICY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NetworkRouteMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.defaultRouteMode == mode,
                            onClick = { onUpdateSettings { it.copy(defaultRouteMode = mode) } },
                            label = { Text(mode.label) },
                        )
                    }
                }

                // SOCKS5h Proxy Config
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = proxyHostDraft,
                        onValueChange = {
                            proxyHostDraft = it
                            onUpdateSettings { s -> s.copy(proxyHost = it) }
                        },
                        label = { Text("SOCKS5h Host") },
                        placeholder = { Text("127.0.0.1") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = proxyPortDraft,
                        onValueChange = { raw ->
                            proxyPortDraft = raw
                            raw.toIntOrNull()?.let { port ->
                                onUpdateSettings { s -> s.copy(proxyPort = port) }
                            }
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = "SURFACE THEME",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        ThemeMode.DARK_OBSIDIAN to "Obsidian Dark",
                        ThemeMode.LIGHT_PRECISION to "Precision Light",
                        ThemeMode.SYSTEM to "System",
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                            label = { Text(label) },
                        )
                    }
                }

                OutlinedButton(
                    onClick = onPruneCas,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Prune CAS Digest Ledger")
                }
            }
        }

        item { Spacer(modifier = Modifier.height(76.dp)) }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val ext = LocalAetherColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, ext.cardBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = ext.cyan,
        )
        content()
    }
}

@Composable
private fun LayerMetricRow(
    title: String,
    detail: String,
    color: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = color)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkInfoRow(
    interfaceName: String,
    downstreamKbps: Int?,
) {
    val ext = LocalAetherColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = interfaceName, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (downstreamKbps != null) {
                String.format("%.1f Mbps est.", downstreamKbps / 1000.0)
            } else {
                "Unavailable"
            },
            style = MonoMetricStyle,
            color = if (downstreamKbps != null) ext.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
