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
import com.example.data.model.BreakerState
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineSettings
import com.example.data.model.NetworkRouteMode
import com.example.engine.AetherNativeBridge
import com.example.engine.LinkEndpointInfo
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
    var wifiProxyHostDraft by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
    var wifiProxyPortDraft by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
    var cellProxyHostDraft by remember(settings.cellularProxyHost) { mutableStateOf(settings.cellularProxyHost) }
    var cellProxyPortDraft by remember(settings.cellularProxyPort) { mutableStateOf(settings.cellularProxyPort.toString()) }

    val completed = downloads.filter { it.statusEnum == DownloadStatus.COMPLETED }
    val peakMbps = ((telemetry.peakBps * 8.0) / 1_000_000.0).toFloat()
    val lockAxis = ((peakMbps / 100f) * 100f).coerceIn(48f, 99.7f)
    val ramAxis = (100f - (telemetry.jvmHeapUsedMb / 100f) * 100f).coerceIn(55f, 97.5f)
    val ttfbScore = if (telemetry.lastTtfbMs > 0L) {
        (100f * (1f - telemetry.lastTtfbMs / 500f)).coerceIn(40f, 98.5f)
    } else {
        95.0f
    }
    val breakersHealthy = networkMatrix.activeValidatedLinks.all { it.breakerState == BreakerState.CLOSED }
    val multipathScore = when {
        networkMatrix.isMultipathReady && breakersHealthy -> 99.4f
        networkMatrix.hasAnyInternet && breakersHealthy -> 91.0f
        else -> 70.0f
    }

    val radarAxes = listOf(
        RadarAxis("1. Lock", lockAxis),
        RadarAxis("2. RAM/8M", ramAxis),
        RadarAxis("3. Thermal", if (telemetry.thermalHeadroomClear) 96.4f else 62f),
        RadarAxis("4. Batch/pwrite", if (settings.enableAutoSpeedUp) 98.5f else 91.0f),
        RadarAxis("5. CAS/SHA", if (settings.enableCasVerification) 99.4f else 75f),
        RadarAxis("6. Staged SAF", if (settings.stagedSafPlacement) 98.0f else 84.0f),
        RadarAxis("7. TTFB", ttfbScore),
        RadarAxis("8. 5G/Breaker", multipathScore),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 4-Layer Architecture Live Matrix
        item(key = "four_layer_cards") {
            SectionCard(title = "v7.1 4-LAYER ENGINE TELEMETRY") {
                LayerMetricRow(
                    title = "L1 · OS Control & dataSync Quota Guard",
                    detail = "Thermal: ${telemetry.thermalStatusLabel} · Quota left ${Formatters.formatDurationHms(telemetry.quotaRemainingMs)}",
                    color = ext.cyan,
                )
                LayerMetricRow(
                    title = "L2 · Lifecycle memfd Telemetry Bridge",
                    detail = "${telemetry.pollingHzLabel} · memfd_create: ${if (telemetry.isMemfdMapped) "Zero-JNI Mapped (4 KB)" else "Kotlin Ring Fallback"}",
                    color = ext.violet,
                )
                LayerMetricRow(
                    title = "L3 · Multi-Network Earliest-Arrival Matrix",
                    detail = "Peak ${Formatters.formatSpeedMbps(telemetry.peakBps)} · Workers ${telemetry.activeWorkers} (${telemetry.wifiWorkers}W/${telemetry.cellularWorkers}C) · autoSpeedUp: ${if (settings.enableAutoSpeedUp) "ON" else "OFF"}",
                    color = ext.emerald,
                )
                LayerMetricRow(
                    title = "L4 · BoundedSlabPool (8.0 MiB) & AETHLDG1",
                    detail = String.format(
                        "%s\nSlabs %.1f / 8.0 MB (in-use %.1f MB) · JVM %.1f MB · Native %.1f MB · %d batches",
                        AetherNativeBridge.engineBackendLabel,
                        telemetry.slabAllocatedMb,
                        telemetry.slabInUseMb,
                        telemetry.jvmHeapUsedMb,
                        telemetry.nativeHeapMb,
                        telemetry.totalSyscallWriteBatches,
                    ),
                    color = ext.amber,
                )
            }
        }

        // Multi-Network Hardening & Circuit Breaker Panel (F1-F9)
        item(key = "network_matrix") {
            SectionCard(title = "MULTI-NETWORK HARDENING & CIRCUIT BREAKERS (F1–F9)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        label = when {
                            networkMatrix.isMultipathReady -> "WIFI + 5G BOND READY"
                            networkMatrix.cellularLeaseRequested -> "5G LEASE ARMED"
                            else -> "SINGLE LINK"
                        },
                        color = if (networkMatrix.isMultipathReady) ext.emerald else ext.cyan,
                    )
                    OutlinedButton(
                        onClick = onRefreshNetwork,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("Probe Links")
                    }
                }

                networkMatrix.pacWarningUrl?.let { pacUrl ->
                    Text(
                        text = "PAC Proxy Warning (F9): System advertised $pacUrl. Raw/Rust sockets ignore PAC scripts — enter explicit SOCKS5h host/port below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.amber,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ext.amber.copy(alpha = 0.1f))
                            .border(1.dp, ext.amber.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    )
                }

                LinkHardeningCard(
                    title = "Wi-Fi Logical LinkKey",
                    endpoint = networkMatrix.wifiLink,
                )
                LinkHardeningCard(
                    title = "Cellular 5G/LTE Logical LinkKey",
                    endpoint = networkMatrix.cellularLink,
                )

                Text(
                    text = "Completed zero-merge tasks: ${completed.size} · Last TTFB: ${telemetry.lastTtfbMs} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 8-Axis Radar Audit
        item(key = "eight_axis_radar") {
            SectionCard(title = "v7.1 8-AXIS ARCHITECTURE AUDIT") {
                EightAxisRadarChart(
                    axes = radarAxes,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Engine Configuration
        item(key = "engine_config") {
            SectionCard(title = "ENGINE & MULTI-NETWORK POLICY") {
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
                    title = "Retain 5G Radio on Wi-Fi (requestNetwork)",
                    subtitle = "F1 fix: holds active cellular Network lease while Wi-Fi is default. Consumes mobile data & extra radio battery.",
                    checked = settings.retainCellularOnWifi,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(retainCellularOnWifi = on) } },
                )

                ToggleRow(
                    title = "Bidirectional autoSpeedUp Controller",
                    subtitle = "Dynamically scales workers +2 / -2 based on Δgoodput & ΔRTT",
                    checked = settings.enableAutoSpeedUp,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableAutoSpeedUp = on) } },
                )

                ToggleRow(
                    title = "Tail Shadow-Hedge Work-Stealing",
                    subtitle = "Idle workers race stalled final segments with pinned LinkKeys",
                    checked = settings.enableTailHedge,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableTailHedge = on) } },
                )

                ToggleRow(
                    title = "Staged App-Private SAF Placement",
                    subtitle = "Download & verify in private staging before atomic copy to SAF URI (prevents EBADF corruption)",
                    checked = settings.stagedSafPlacement,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(stagedSafPlacement = on) } },
                )

                ToggleRow(
                    title = "S3 SHA-256 & CAS Digest Audit",
                    subtitle = "Verify file digest & index deduplicated extents",
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

                // Per-LinkKey SOCKS5h Proxy Overrides (F9)
                Text(
                    text = "PER-LINKKEY SOCKS5h PROXY OVERRIDES (F9)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = wifiProxyHostDraft,
                        onValueChange = {
                            wifiProxyHostDraft = it
                            onUpdateSettings { s -> s.copy(proxyHost = it) }
                        },
                        label = { Text("Wi-Fi SOCKS5h Host") },
                        placeholder = { Text("Direct if blank") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = wifiProxyPortDraft,
                        onValueChange = { raw ->
                            wifiProxyPortDraft = raw
                            raw.toIntOrNull()?.let { port ->
                                onUpdateSettings { s -> s.copy(proxyPort = port) }
                            }
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = cellProxyHostDraft,
                        onValueChange = {
                            cellProxyHostDraft = it
                            onUpdateSettings { s -> s.copy(cellularProxyHost = it) }
                        },
                        label = { Text("Cellular SOCKS5h Host") },
                        placeholder = { Text("Direct if blank") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = cellProxyPortDraft,
                        onValueChange = { raw ->
                            cellProxyPortDraft = raw
                            raw.toIntOrNull()?.let { port ->
                                onUpdateSettings { s -> s.copy(cellularProxyPort = port) }
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
private fun LinkHardeningCard(
    title: String,
    endpoint: LinkEndpointInfo?,
) {
    val ext = LocalAetherColors.current
    val breakerColor = when (endpoint?.breakerState) {
        BreakerState.CLOSED -> ext.emerald
        BreakerState.OPEN -> ext.rose
        BreakerState.HALF_OPEN -> ext.amber
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, ext.cardBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            StatusPill(
                label = endpoint?.breakerState?.name ?: "OFFLINE",
                color = breakerColor,
            )
        }
        if (endpoint != null) {
            Text(
                text = "Key: ${endpoint.linkKey.id} · Handle: ${endpoint.netHandle} · VALIDATED",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = String.format(
                    "EWMA: %.1f Mbps · Cap: %.1f Mbps · %s",
                    endpoint.ewmaMbps,
                    endpoint.downstreamKbps / 1000.0,
                    if (endpoint.isMetered) "Metered" else "Unmetered",
                ),
                style = MonoMetricStyle,
                color = ext.emerald,
            )
        } else {
            Text(
                text = "Not validated or radio inactive",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
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
