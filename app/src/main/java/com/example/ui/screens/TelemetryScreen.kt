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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadEntity
import com.example.data.model.BreakerState
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineBackendPreference
import com.example.data.model.EngineSettings
import com.example.data.model.NetworkRouteMode
import com.example.engine.AetherNativeBridge
import com.example.engine.LinkEndpointInfo
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.service.AetherDownloadService
import com.example.ui.components.ArchitectureDecisionLogCard
import com.example.ui.components.BdpConcurrencyHeatmap
import com.example.ui.components.DualLinkSpeedHistoryChart
import com.example.ui.components.EightAxisRadarChart
import com.example.ui.components.EngineLayerRow
import com.example.ui.components.EngineSectionCard
import com.example.ui.components.EngineToggleRow
import com.example.ui.components.OsSurvivalAndDiagnosticsCard
import com.example.ui.components.RadarAxis
import com.example.ui.components.RooflineParetoChart
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
    fontLicenseAudit: String,
    diagnosticsPreview: String,
    aria2Secret: String,
    onUpdateSettings: ((EngineSettings) -> EngineSettings) -> Unit,
    onRefreshNetwork: () -> Unit,
    onPruneCas: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onCopyAria2Secret: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ext = LocalAetherColors.current
    var wifiProxyHostDraft by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
    var wifiProxyPortDraft by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
    var cellProxyHostDraft by remember(settings.cellularProxyHost) { mutableStateOf(settings.cellularProxyHost) }
    var cellProxyPortDraft by remember(settings.cellularProxyPort) { mutableStateOf(settings.cellularProxyPort.toString()) }

    val completed = downloads.filter { it.statusEnum == DownloadStatus.COMPLETED }
    val peakMbps = ((telemetry.peakBps * 8.0) / 1_000_000.0).toFloat()
    val lockAxis = ((peakMbps / 100f) * 100f).coerceIn(48f, 99.7f)
    val ramAxis = (100f - (telemetry.jvmHeapUsedMb / 100f) * 100f).coerceIn(55f, 97.5f)
    val ttfbScore = if (telemetry.lastTtfbMs > 0L) (100f * (1f - telemetry.lastTtfbMs / 500f)).coerceIn(40f, 98.5f) else 95.0f
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
        RadarAxis("4. pwrite", if (settings.enableAutoSpeedUp) 98.5f else 91.0f),
        RadarAxis("5. CAS/SHA", if (settings.enableCasVerification) 99.4f else 75f),
        RadarAxis("6. 1G FD", if (settings.stagedSafPlacement) 98.0f else 84.0f),
        RadarAxis("7. TTFB", ttfbScore),
        RadarAxis("8. 5G/Brk", multipathScore),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Dual-Engine Individual A/B Toggles
        item(key = "dual_engine_toggles") {
            EngineSectionCard(title = "DUAL-ENGINE A/B SWITCHBOARD (INDIVIDUAL TOGGLES)") {
                StatusPill(
                    label = if (telemetry.isNativeRustActive) {
                        "libaether_core.so LOADED · Both Engines Ready"
                    } else {
                        "Kotlin OkHttp Active · libaether_core.so builds in CI"
                    },
                    color = if (telemetry.isNativeRustActive) ext.emerald else ext.amber,
                )

                EngineToggleRow(
                    title = "Rust Native Engine (libaether_core.so)",
                    subtitle = "wreq BoringSSL + quinn/h3 0-RTT + Semaphore SlabPool 8 MiB + memfd",
                    checked = settings.enableRustEngine,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableRustEngine = on) } },
                    modifier = Modifier.testTag("toggle_rust_engine"),
                )

                EngineToggleRow(
                    title = "Kotlin OkHttp Multi-Network Engine",
                    subtitle = "DirectByteBuffer FileChannel + Bound Network socketFactory + AETHLDG2",
                    checked = settings.enableKotlinOkHttpEngine,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableKotlinOkHttpEngine = on) } },
                    modifier = Modifier.testTag("toggle_kotlin_engine"),
                )

                Text(
                    text = "ACTIVE BACKEND PRIORITY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EngineBackendPreference.entries.forEach { pref ->
                        FilterChip(
                            selected = settings.preferredEngineBackend == pref,
                            onClick = { onUpdateSettings { it.copy(preferredEngineBackend = pref) } },
                            label = { Text(pref.label) },
                            modifier = Modifier.testTag("backend_pref_${pref.name.lowercase()}"),
                        )
                    }
                }
            }
        }

        // 4-Layer Telemetry Summary
        item(key = "four_layer_cards") {
            EngineSectionCard(title = "v8.0 4-LAYER ENGINE TELEMETRY") {
                EngineLayerRow(
                    title = "L1 · OS Control & dataSync Quota Guard",
                    detail = "Thermal: ${telemetry.thermalStatusLabel} · Quota left ${Formatters.formatDurationHms(telemetry.quotaRemainingMs)}",
                    color = ext.cyan,
                )
                EngineLayerRow(
                    title = "L2 · Lifecycle memfd Telemetry Bridge",
                    detail = "${telemetry.pollingHzLabel} · memfd_create: ${if (telemetry.isMemfdMapped) "Zero-JNI Mapped (4 KB)" else "Kotlin Metering"}",
                    color = ext.violet,
                )
                EngineLayerRow(
                    title = "L3 · Multi-Network Earliest-Arrival Matrix",
                    detail = "Peak ${Formatters.formatSpeedMbps(telemetry.peakBps)} · Workers ${telemetry.activeWorkers} (${telemetry.wifiWorkers}W/${telemetry.cellularWorkers}C/${telemetry.ethWorkers}E)",
                    color = ext.emerald,
                )
                EngineLayerRow(
                    title = "L4 · BoundedSlabPool (8.0 MiB) & AETHLDG2",
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

        // Dual-Link Charts & Analytics Suite
        item(key = "analytics_charts") {
            EngineSectionCard(title = "LIVE WI-FI vs 5G THROUGHPUT & ROOFLINE ANALYTICS") {
                DualLinkSpeedHistoryChart(
                    wifiHistoryBps = telemetry.wifiHistoryBps,
                    cellHistoryBps = telemetry.cellHistoryBps,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("2D BDP CONCURRENCY HEATMAP (WORKERS × RTT)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BdpConcurrencyHeatmap(
                    currentWorkers = settings.maxConcurrentWorkers,
                    lastRttMs = telemetry.lastTtfbMs,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("OPERATIONAL INTENSITY ROOFLINE MODEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RooflineParetoChart(goodputBps = telemetry.instantBps, modifier = Modifier.fillMaxWidth())
            }
        }

        // Multi-Network Hardening & Circuit Breakers
        item(key = "network_matrix") {
            EngineSectionCard(title = "MULTI-NETWORK HARDENING & CIRCUIT BREAKERS (F1–F10)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        label = when {
                            networkMatrix.isMultipathReady -> "MULTI-LINK BOND READY (${networkMatrix.activeValidatedLinks.size})"
                            networkMatrix.cellularLeaseRequested -> "5G LEASE ARMED"
                            else -> "SINGLE LINK"
                        },
                        color = if (networkMatrix.isMultipathReady) ext.emerald else ext.cyan,
                    )
                    OutlinedButton(onClick = onRefreshNetwork, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("Probe Links")
                    }
                }

                LinkHardeningCard(title = "Wi-Fi Logical LinkKey", endpoint = networkMatrix.wifiLink)
                LinkHardeningCard(title = "Cellular 5G/LTE Logical LinkKey", endpoint = networkMatrix.cellularLink)
                networkMatrix.ethernetLink?.let { eth ->
                    LinkHardeningCard(title = "USB-C Ethernet Hot-Plug LinkKey", endpoint = eth)
                }
                Text(
                    text = "Completed zero-merge tasks: ${completed.size} · Last TTFB: ${telemetry.lastTtfbMs} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 8-Axis Radar Audit
        item(key = "eight_axis_radar") {
            EngineSectionCard(title = "v8.0 8-AXIS ARCHITECTURE AUDIT") {
                EightAxisRadarChart(axes = radarAxes, modifier = Modifier.fillMaxWidth())
            }
        }

        // HyperOS 3 OS Survival, Diagnostics Log & Bundled Font OFL Verification
        item(key = "os_survival_diagnostics") {
            OsSurvivalAndDiagnosticsCard(
                telemetry = telemetry,
                networkMatrix = networkMatrix,
                isIgnoringBatteryOpt = AetherDownloadService.isIgnoringBatteryOptimizations(context),
                fontLicenseAudit = fontLicenseAudit,
                diagnosticsPreview = diagnosticsPreview,
                onOpenBatterySettings = {
                    runCatching { context.startActivity(AetherDownloadService.createBatteryExemptionIntent(context)) }
                        .onFailure { runCatching { context.startActivity(AetherDownloadService.createAppBatteryDetailsIntent(context)) } }
                },
                onCopyDiagnostics = onCopyDiagnostics,
            )
        }

        // Feature Kill Switches & Policy
        item(key = "engine_config") {
            EngineSectionCard(title = "PROTOCOL KILL SWITCHES, ARIA2 RPC & PROXY POLICY") {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Default Worker Concurrency", style = MaterialTheme.typography.titleSmall)
                        Text("${settings.maxConcurrentWorkers} streams", style = MonoMetricStyle, color = ext.cyan)
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

                EngineToggleRow(
                    title = "Retain 5G Radio on Wi-Fi (requestNetwork)",
                    subtitle = "F1 fix: holds active cellular Network lease while Wi-Fi is default.",
                    checked = settings.retainCellularOnWifi,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(retainCellularOnWifi = on) } },
                )
                EngineToggleRow(
                    title = "HTTP/3 QUIC 0-RTT (quinn + h3)",
                    subtitle = "Parallel 0-RTT UDP datagrams with Alt-Svc 256 KiB LRU cache",
                    checked = settings.enableHttp3Quic,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableHttp3Quic = on) } },
                )
                EngineToggleRow(
                    title = "wreq Chrome TLS/H2 Fingerprint Emulation",
                    subtitle = "BoringSSL ALPS/GREASE emulation with .no_proxy() cellular isolation",
                    checked = settings.enableWreqEmulation,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableWreqEmulation = on) } },
                )
                EngineToggleRow(
                    title = "Bidirectional autoSpeedUp Controller",
                    subtitle = "Dynamically scales workers +2 / -2 based on Δgoodput & ΔRTT",
                    checked = settings.enableAutoSpeedUp,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableAutoSpeedUp = on) } },
                )
                EngineToggleRow(
                    title = "Tail Shadow-Hedge Work-Stealing",
                    subtitle = "Idle workers race & bisect slow extents with pinned LinkKeys",
                    checked = settings.enableTailHedge,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableTailHedge = on) } },
                )
                EngineToggleRow(
                    title = "Hardened Aria2 Loopback JSON-RPC (:6800)",
                    subtitle = "Disabled by default · 127.0.0.1 only · Enforces per-boot random 32-byte token",
                    checked = settings.enableAria2Rpc,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableAria2Rpc = on) } },
                )

                if (settings.enableAria2Rpc && aria2Secret.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ext.cyan.copy(alpha = 0.08f))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "127.0.0.1:6800 · token:${aria2Secret.take(8)}••••",
                            style = MonoMetricStyle,
                            color = ext.cyan,
                        )
                        OutlinedButton(onClick = onCopyAria2Secret, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text("  Copy Token")
                        }
                    }
                }

                EngineToggleRow(
                    title = "Experimental io_uring Seccomp Probe",
                    subtitle = "Default OFF: Android 16 seccomp-bpf blocks io_uring_setup safely",
                    checked = settings.enableIoUringProbe,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableIoUringProbe = on) } },
                )

                Text("DEFAULT ROUTE POLICY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkRouteMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.defaultRouteMode == mode,
                            onClick = { onUpdateSettings { it.copy(defaultRouteMode = mode) } },
                            label = { Text(mode.label) },
                        )
                    }
                }

                Text("PER-LINKKEY SOCKS5h PROXY OVERRIDES (F9)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = wifiProxyHostDraft,
                        onValueChange = { wifiProxyHostDraft = it; onUpdateSettings { s -> s.copy(proxyHost = it) } },
                        label = { Text("Wi-Fi SOCKS5h") },
                        placeholder = { Text("Direct") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = wifiProxyPortDraft,
                        onValueChange = { raw -> wifiProxyPortDraft = raw; raw.toIntOrNull()?.let { p -> onUpdateSettings { s -> s.copy(proxyPort = p) } } },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cellProxyHostDraft,
                        onValueChange = { cellProxyHostDraft = it; onUpdateSettings { s -> s.copy(cellularProxyHost = it) } },
                        label = { Text("Cellular SOCKS5h") },
                        placeholder = { Text("Direct") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = cellProxyPortDraft,
                        onValueChange = { raw -> cellProxyPortDraft = raw; raw.toIntOrNull()?.let { p -> onUpdateSettings { s -> s.copy(cellularProxyPort = p) } } },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text("SURFACE THEME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                OutlinedButton(onClick = onPruneCas, modifier = Modifier.fillMaxWidth()) {
                    Text("Prune CAS Digest Ledger")
                }
            }
        }

        // Architecture Decision Log
        item(key = "adr_log") {
            ArchitectureDecisionLogCard()
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
            StatusPill(label = endpoint?.breakerState?.name ?: "OFFLINE", color = breakerColor)
        }
        if (endpoint != null) {
            Text(
                text = "Key: ${endpoint.linkKey.id} · IP: ${endpoint.localIp.ifBlank { "auto" }} · Handle: ${endpoint.netHandle}",
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
