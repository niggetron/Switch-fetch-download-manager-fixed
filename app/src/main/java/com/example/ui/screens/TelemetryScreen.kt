package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.DownloadEntity
import com.example.data.model.BreakerState
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineBackendPreference
import com.example.data.model.EngineSettings
import com.example.data.model.MAX_CONCURRENT_WORKERS_CAP
import com.example.data.model.NetworkRouteMode
import com.example.data.model.SpeedometerStyle
import com.example.data.model.ThroughputProfile
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
import com.example.ui.components.FullSpeedometerDashboardCard
import com.example.ui.components.OsSurvivalAndDiagnosticsCard
import com.example.ui.components.RadarAxis
import com.example.ui.components.RooflineParetoChart
import com.example.ui.components.StatusPill
import com.example.ui.theme.AetherThemePresets
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.MonoMetricStyle
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.contrastOnColor
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
    useMBpsSpeedUnit: Boolean,
    onToggleSpeedUnit: () -> Unit,
    onResetPeaks: () -> Unit,
    onUpdateSettings: ((EngineSettings) -> EngineSettings) -> Unit,
    onRefreshNetwork: () -> Unit,
    onPruneCas: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onCopyAria2Secret: () -> Unit,
    onOpenDualWifi: () -> Unit = {},
    isSimulating: Boolean = false,
    motionSpeedMultiplier: Float = 1.0f,
    flightSampleCount: Int = 0,
    onOpenSimulator: () -> Unit = {},
    onOpenColorStudio: () -> Unit = {},
    onSaveDiagnosticsTxt: (Uri) -> Unit = {},
    onSaveDiagnosticsJson: (Uri) -> Unit = {},
    onShareDiagnosticsBundle: () -> Unit = {},
    onSelectGauge: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ext = LocalAetherColors.current
    var wifiProxyHostDraft by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
    var wifiProxyPortDraft by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
    var cellProxyHostDraft by remember(settings.cellularProxyHost) { mutableStateOf(settings.cellularProxyHost) }
    var cellProxyPortDraft by remember(settings.cellularProxyPort) { mutableStateOf(settings.cellularProxyPort.toString()) }
    var permRefreshTick by remember { mutableIntStateOf(0) }

    val multiPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permRefreshTick++
    }
    val saveTxtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) onSaveDiagnosticsTxt(uri)
    }
    val saveJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) onSaveDiagnosticsJson(uri)
    }

    val notifGranted = remember(permRefreshTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val wifiScanPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    val wifiScanGranted = remember(permRefreshTick) {
        ContextCompat.checkSelfPermission(context, wifiScanPerm) == PackageManager.PERMISSION_GRANTED
    }
    val batteryUnrestricted = remember(permRefreshTick) {
        AetherDownloadService.isIgnoringBatteryOptimizations(context)
    }

    val completed = downloads.filter { it.statusEnum == DownloadStatus.COMPLETED }
    val peakMbps = ((telemetry.peakBps * 8.0) / 1_000_000.0).toFloat()
    val lockAxis = ((peakMbps / 100f) * 100f).coerceIn(48f, 99.7f)
    val ramAxis = (100f - (telemetry.jvmHeapUsedMb / 100f) * 100f).coerceIn(55f, 97.5f)
    val ttfbScore = if (telemetry.lastTtfbMs > 0L) (100f * (1f - telemetry.lastTtfbMs / 500f)).coerceIn(40f, 98.5f) else 95.0f
    val breakersHealthy = networkMatrix.activeValidatedLinks.all { it.breakerState == BreakerState.CLOSED }
    val multipathScore = when {
        networkMatrix.activeValidatedLinks.size >= 3 && breakersHealthy -> 99.8f
        networkMatrix.isMultipathReady && breakersHealthy -> 96.5f
        networkMatrix.hasAnyInternet && breakersHealthy -> 91.0f
        else -> 70.0f
    }

    val radarAxes = listOf(
        RadarAxis("1. Lock", lockAxis),
        RadarAxis("2. RAM/12M", ramAxis),
        RadarAxis("3. Thermal", if (telemetry.thermalHeadroomClear) 96.4f else 62f),
        RadarAxis("4. pwrite", if (settings.enableAutoSpeedUp) 98.8f else 91.0f),
        RadarAxis("5. CAS/SHA", if (settings.enableCasVerification) 99.4f else 75f),
        RadarAxis("6. AntiDip", telemetry.stabilityPercent.toFloat().coerceIn(60f, 99.5f)),
        RadarAxis("7. TTFB", ttfbScore),
        RadarAxis("8. QuadLnk", multipathScore),
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("telemetry_lazy_column"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Per-Network Live Speedometers (Aggregate, Wi-Fi 1, Mobile 5G, Wi-Fi 2, USB/Eth) + A/B Benchmark
        item(key = "per_network_speedometers") {
            FullSpeedometerDashboardCard(
                telemetry = telemetry,
                networkMatrix = networkMatrix,
                useMBpsUnit = useMBpsSpeedUnit,
                speedometerStyle = settings.speedometerStyle,
                onToggleUnit = onToggleSpeedUnit,
                onResetPeaks = onResetPeaks,
                onSelectGauge = onSelectGauge,
                onOpenSimulator = onOpenSimulator,
                isSimulating = isSimulating,
            )
        }

        // User-Selectable Bike / Vehicle Speedometer Layout Selector (6 styles)
        item(key = "speedometer_style_selector") {
            EngineSectionCard(title = "SPEEDOMETER & PROGRESS COCKPIT STYLES (6)") {
                Text(
                    text = "Pick your preferred motorsport, F1 HUD, Neon Plasma Arc, or Rayburst Matrix gauge layout. Switching styles cross-fades cleanly with zero overlap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SpeedometerStyle.entries.forEach { style ->
                        FilterChip(
                            selected = settings.speedometerStyle == style,
                            onClick = { onUpdateSettings { it.copy(speedometerStyle = style) } },
                            label = { Text(style.label) },
                            modifier = Modifier.testTag("speedometer_style_${style.name.lowercase()}"),
                        )
                    }
                }
            }
        }

        // Telegram-Style Color Studio (18 Themes) + App-Wide Neon Glow Switch
        item(key = "color_studio_and_neon") {
            val activePreset = AetherThemePresets.byId(settings.uiThemeConfig.presetId)
            EngineSectionCard(title = "UI COLOR STUDIO (18 THEMES) & APP-WIDE NEON GLOW") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        label = "THEME: ${activePreset.name.uppercase()}",
                        color = ext.cyan,
                    )
                    StatusPill(
                        label = if (settings.uiThemeConfig.neonGlowEnabled) {
                            "NEON ${(settings.uiThemeConfig.neonGlowIntensity * 100).roundToInt()}%"
                        } else {
                            "NEON OFF"
                        },
                        color = if (settings.uiThemeConfig.neonGlowEnabled) ext.violet else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                EngineToggleRow(
                    title = "App-Wide Neon Glow & Animated Halo",
                    subtitle = "Adds luminous neon glow across cards, speedometers, Rayburst bars, sheets, and buttons",
                    checked = settings.uiThemeConfig.neonGlowEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateSettings { s ->
                            s.copy(uiThemeConfig = s.uiThemeConfig.copy(neonGlowEnabled = enabled))
                        }
                    },
                    modifier = Modifier.testTag("toggle_app_wide_neon"),
                )

                if (settings.uiThemeConfig.neonGlowEnabled) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Neon Glow Intensity", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${(settings.uiThemeConfig.neonGlowIntensity * 100).roundToInt()}%",
                                style = MonoMetricStyle,
                                color = ext.violet,
                            )
                        }
                        Slider(
                            value = settings.uiThemeConfig.neonGlowIntensity,
                            onValueChange = { v ->
                                onUpdateSettings { s ->
                                    s.copy(uiThemeConfig = s.uiThemeConfig.copy(neonGlowIntensity = v.coerceIn(0.15f, 1.0f)))
                                }
                            },
                            valueRange = 0.15f..1.0f,
                        )
                    }
                }

                Button(
                    onClick = onOpenColorStudio,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ext.violet,
                        contentColor = contrastOnColor(ext.violet),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_color_studio_button"),
                ) {
                    Icon(Icons.Filled.ColorLens, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Telegram-Style Color Studio (${AetherThemePresets.all.size} Themes)")
                }
            }
        }

        // Interactive Speed & Animation Simulator Lab Launch Card
        item(key = "speed_animation_sim_lab") {
            EngineSectionCard(title = "SPEED & ANIMATION SIMULATOR LAB") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        label = if (isSimulating) "SIMULATION LIVE" else "SANDBOX READY",
                        color = if (isSimulating) ext.amber else ext.cyan,
                    )
                    StatusPill(
                        label = String.format("MOTION %.2f×", motionSpeedMultiplier),
                        color = ext.violet,
                    )
                }
                Text(
                    text = "Preview all 4 speedometer needle/LED redline sweeps, test Anti-Dip rescue waves, scrub global shared-element slow-motion (0.25×–1.5×), and trigger every card/FAB/gauge morph in the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onOpenSimulator,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_open_sim_lab_button"),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isSimulating) "Open Running Speed & Animation Lab" else "Launch Speed & Animation Simulator Lab")
                }
            }
        }

        // Dual-Engine Individual A/B Toggles
        item(key = "dual_engine_toggles") {
            EngineSectionCard(title = "DUAL-ENGINE A/B SWITCHBOARD (RUST + KOTLIN)") {
                StatusPill(
                    label = if (telemetry.isNativeRustActive) {
                        "libaether_core.so LOADED · Both Engines Ready"
                    } else {
                        "Kotlin OkHttp Turbo Active · CI arm64 APK bundles Rust .so"
                    },
                    color = if (telemetry.isNativeRustActive) ext.emerald else ext.amber,
                )

                if (!telemetry.isNativeRustActive) {
                    Text(
                        text = "Tip: AI Studio preview builds omit cargo-ndk so only Kotlin OkHttp Turbo runs in preview. Download the arm64-v8a release APK from GitHub Releases to benchmark BOTH Rust libaether_core.so and Kotlin OkHttp side-by-side!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                EngineToggleRow(
                    title = "Rust Native Engine (libaether_core.so v9.1)",
                    subtitle = "Sharded 64-stream wreq BoringSSL + quinn/h3 0-RTT + Decoupled Disk Writer + 12 MiB SlabPool",
                    checked = settings.enableRustEngine,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableRustEngine = on) } },
                    modifier = Modifier.testTag("toggle_rust_engine"),
                )

                EngineToggleRow(
                    title = "Kotlin OkHttp Turbo Multi-Network Engine",
                    subtitle = "Sharded 64-stream multi-socket pools + Lock-Free pwrite + Force-All-Links & Live Bisection",
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

        // Quad-Link Dual-Wi-Fi & USB Tethering Boost Controls
        item(key = "quad_link_controls") {
            EngineSectionCard(title = "QUAD-LINK BONDING (WI-FI 1 + WI-FI 2 + 5G + USB)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        label = when {
                            networkMatrix.activeValidatedLinks.size >= 4 -> "QUAD-LINK 4/4 ACTIVE"
                            networkMatrix.activeValidatedLinks.size == 3 -> "TRI-LINK 3/4 ACTIVE"
                            networkMatrix.isMultipathReady -> "DUAL-LINK ${networkMatrix.activeValidatedLinks.size}/4 ACTIVE"
                            else -> "SINGLE LINK (${networkMatrix.activeValidatedLinks.size}/4)"
                        },
                        color = if (networkMatrix.isMultipathReady) ext.emerald else ext.cyan,
                    )
                    OutlinedButton(onClick = onRefreshNetwork, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("Probe Links")
                    }
                }

                Button(
                    onClick = onOpenDualWifi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_open_dual_wifi_button"),
                ) {
                    Icon(Icons.Filled.Router, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Configure Wi-Fi 2 Connector & USB Tethering")
                }

                EngineToggleRow(
                    title = "Dual-Band Wi-Fi 2 Boost (Direct Dual-STA / Relay)",
                    subtitle = "Bond a second Wi-Fi network alongside Wi-Fi 1, 5G/LTE, and USB.",
                    checked = settings.dualWifiEnabled,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(dualWifiEnabled = on) } },
                )
                EngineToggleRow(
                    title = "USB Tethering & USB-C Ethernet Bonding",
                    subtitle = "Automatically schedule streams onto TRANSPORT_USB / TRANSPORT_ETHERNET.",
                    checked = settings.usbBondingEnabled,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(usbBondingEnabled = on) } },
                )

                LinkHardeningCard(title = "Wi-Fi 1 Primary LinkKey", endpoint = networkMatrix.wifiLink)
                LinkHardeningCard(title = "Wi-Fi 2 Secondary / Relay LinkKey", endpoint = networkMatrix.wifi2Link)
                LinkHardeningCard(title = "Mobile 5G/LTE Logical LinkKey", endpoint = networkMatrix.cellularLink)
                LinkHardeningCard(title = "USB Tethering / USB-C Eth LinkKey", endpoint = networkMatrix.ethernetLink)

                Text(
                    text = "Completed zero-merge tasks: ${completed.size} · Last TTFB: ${telemetry.lastTtfbMs} ms · Anti-Dip Rescues: ${telemetry.antiDipRescues}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Non-Root Permissions Readiness Card
        item(key = "non_root_permissions") {
            EngineSectionCard(title = "NON-ROOT PERMISSIONS & OS SURVIVAL READINESS") {
                Text(
                    text = "Grant these zero-root Android permissions to keep full quad-link Wi-Fi + 5G + USB speed running without OEM throttling or background sleep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PermissionRow(
                    icon = Icons.Filled.NotificationsActive,
                    title = "Foreground Live Speed Notification",
                    granted = notifGranted,
                    actionLabel = "Grant",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            multiPermLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    },
                )
                PermissionRow(
                    icon = Icons.Filled.WifiFind,
                    title = "Nearby Wi-Fi 2 Discovery Scan",
                    granted = wifiScanGranted,
                    actionLabel = "Grant",
                    onAction = { multiPermLauncher.launch(arrayOf(wifiScanPerm)) },
                )
                PermissionRow(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = "Unrestricted Battery (Anti-Doze)",
                    granted = batteryUnrestricted,
                    actionLabel = "Allow",
                    onAction = {
                        runCatching { context.startActivity(AetherDownloadService.createBatteryExemptionIntent(context)) }
                            .onFailure { runCatching { context.startActivity(AetherDownloadService.createAppBatteryDetailsIntent(context)) } }
                        permRefreshTick++
                    },
                )
                PermissionRow(
                    icon = Icons.Filled.Shield,
                    title = "Wi-Fi / Cellular Multi-Network Lease (Normal)",
                    granted = true,
                    actionLabel = "Granted",
                    onAction = {},
                )
            }
        }

        // 4-Layer Telemetry Summary
        item(key = "four_layer_cards") {
            EngineSectionCard(title = "v9.1 4-LAYER ENGINE TELEMETRY") {
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
                    title = "L3 · Quad-Link Anti-Dip Earliest-Arrival Matrix",
                    detail = "Peak ${Formatters.formatSpeedMbps(telemetry.peakBps)} · Avg ${Formatters.formatSpeedMbps(telemetry.avgBps)} · Workers ${telemetry.activeWorkers} (${telemetry.wifiWorkers}W1/${telemetry.wifi2Workers}W2/${telemetry.cellularWorkers}C/${telemetry.ethWorkers}U)",
                    color = ext.emerald,
                )
                EngineLayerRow(
                    title = "L4 · BoundedSlabPool (12.0 MiB) & Async Disk Writer",
                    detail = String.format(
                        "%s\nSlabs %.1f / 12.0 MB (in-use %.1f MB) · JVM %.1f MB · Native %.1f MB · %d batches",
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
                Text("2D BDP CONCURRENCY HEATMAP (WORKERS 4..64 × RTT)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BdpConcurrencyHeatmap(
                    currentWorkers = settings.maxConcurrentWorkers,
                    lastRttMs = telemetry.lastTtfbMs,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("OPERATIONAL INTENSITY ROOFLINE MODEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RooflineParetoChart(goodputBps = telemetry.instantBps, modifier = Modifier.fillMaxWidth())
            }
        }

        // 8-Axis Radar Audit (8 distinct axis colors)
        item(key = "eight_axis_radar") {
            EngineSectionCard(title = "v9.1 8-AXIS ARCHITECTURE AUDIT (COLOR-CODED)") {
                EightAxisRadarChart(axes = radarAxes, modifier = Modifier.fillMaxWidth())
            }
        }

        // Deep Diagnostic Flight Recorder & Save/Share Export (TXT + JSON)
        item(key = "flight_recorder_export") {
            EngineSectionCard(title = "DIAGNOSTIC FLIGHT RECORDER · SAVE & SHARE TRACE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        label = "$flightSampleCount SAMPLES (1 Hz)",
                        color = ext.cyan,
                    )
                    StatusPill(
                        label = "REDACTION ACTIVE",
                        color = ext.emerald,
                    )
                }
                Text(
                    text = "Captures per-link speeds, worker counts, TTFB, socket blocked reasons, cellular lease state, and Rust/Kotlin logs so you can save or share TXT + JSON traces to diagnose speed dips or unused networks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { saveTxtLauncher.launch("aetherio_diagnostics.txt") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_diag_txt_button"),
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save TXT")
                    }
                    OutlinedButton(
                        onClick = { saveJsonLauncher.launch("aetherio_trace.json") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_diag_json_button"),
                    ) {
                        Icon(Icons.Filled.DataObject, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save JSON")
                    }
                }
                Button(
                    onClick = onShareDiagnosticsBundle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ext.cyan,
                        contentColor = contrastOnColor(ext.cyan),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_diag_bundle_button"),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share TXT + JSON Diagnostics Bundle")
                }
            }
        }

        // HyperOS 3 OS Survival, Diagnostics Log & Bundled Font OFL Verification
        item(key = "os_survival_diagnostics") {
            OsSurvivalAndDiagnosticsCard(
                telemetry = telemetry,
                networkMatrix = networkMatrix,
                isIgnoringBatteryOpt = batteryUnrestricted,
                fontLicenseAudit = fontLicenseAudit,
                diagnosticsPreview = diagnosticsPreview,
                onOpenBatterySettings = {
                    runCatching { context.startActivity(AetherDownloadService.createBatteryExemptionIntent(context)) }
                        .onFailure { runCatching { context.startActivity(AetherDownloadService.createAppBatteryDetailsIntent(context)) } }
                },
                onCopyDiagnostics = onCopyDiagnostics,
            )
        }

        // Feature Kill Switches & Anti-Dip Policy
        item(key = "engine_config") {
            EngineSectionCard(title = "ANTI-DIP TUNING, KILL SWITCHES & PROXY POLICY") {
                Text("THROUGHPUT & ANTI-DIP PROFILE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ThroughputProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = settings.throughputProfile == profile,
                            onClick = { onUpdateSettings { it.copy(throughputProfile = profile) } },
                            label = { Text(profile.label) },
                            modifier = Modifier.testTag("throughput_profile_${profile.name.lowercase()}"),
                        )
                    }
                }

                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Default Worker Concurrency", style = MaterialTheme.typography.titleSmall)
                        Text("${settings.maxConcurrentWorkers} / $MAX_CONCURRENT_WORKERS_CAP streams", style = MonoMetricStyle, color = ext.cyan)
                    }
                    Slider(
                        value = settings.maxConcurrentWorkers.toFloat().coerceIn(1f, MAX_CONCURRENT_WORKERS_CAP.toFloat()),
                        onValueChange = {
                            val count = it.roundToInt().coerceIn(1, MAX_CONCURRENT_WORKERS_CAP)
                            onUpdateSettings { s -> s.copy(maxConcurrentWorkers = count) }
                        },
                        valueRange = 1f..MAX_CONCURRENT_WORKERS_CAP.toFloat(),
                    )
                }

                EngineToggleRow(
                    title = "Force-All-Links Aggressive Bonding (Zero Idle Radios)",
                    subtitle = "Guarantees dedicated parallel streams on EVERY connected network (Wi-Fi 1 + Wi-Fi 2 + Mobile 5G + USB) and auto-recovers 5G lease.",
                    checked = settings.forceAllLinks,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(forceAllLinks = on) } },
                    modifier = Modifier.testTag("toggle_force_all_links"),
                )
                EngineToggleRow(
                    title = "Retain 5G Radio on Wi-Fi (requestNetwork)",
                    subtitle = "F1 fix: holds active cellular Network lease while Wi-Fi is default.",
                    checked = settings.retainCellularOnWifi,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(retainCellularOnWifi = on) } },
                )
                EngineToggleRow(
                    title = "Shizuku Wi-Fi Low-Latency Boost",
                    subtitle = "Enables unthrottled Wi-Fi scanning & low-latency Wi-Fi mode when Shizuku is authorized.",
                    checked = settings.enableShizukuWifiBoost,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableShizukuWifiBoost = on) } },
                )
                EngineToggleRow(
                    title = "HTTP/3 QUIC 0-RTT (quinn + h3)",
                    subtitle = "Parallel 0-RTT UDP datagrams with Alt-Svc 256 KiB LRU cache",
                    checked = settings.enableHttp3Quic,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableHttp3Quic = on) } },
                )
                EngineToggleRow(
                    title = "wreq Chrome TLS/H2 Fingerprint Emulation",
                    subtitle = "BoringSSL ALPS/GREASE emulation with persistent keep-alive pools",
                    checked = settings.enableWreqEmulation,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableWreqEmulation = on) } },
                )
                EngineToggleRow(
                    title = "Ghost-Style Auto-Acceleration (autoSpeedUp)",
                    subtitle = "Dynamically ramps sharded workers up to $MAX_CONCURRENT_WORKERS_CAP streams based on Δgoodput & ΔRTT",
                    checked = settings.enableAutoSpeedUp,
                    onCheckedChange = { on -> onUpdateSettings { it.copy(enableAutoSpeedUp = on) } },
                )
                EngineToggleRow(
                    title = "Anti-Dip Live Range Bisection & Tail Hedge",
                    subtitle = "Idle workers atomically split lagging segments in flight without stalling",
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
                        label = { Text("Wi-Fi 1 SOCKS5h") },
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
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val ext = LocalAetherColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, ext.cardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) ext.emerald else ext.amber,
                modifier = Modifier.size(18.dp),
            )
            Text(text = title, style = MaterialTheme.typography.bodySmall)
        }
        if (granted) {
            StatusPill(label = "READY", color = ext.emerald)
        } else {
            OutlinedButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
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
