package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.model.EngineSettings
import com.example.data.model.WifiBandPreference
import com.example.engine.DualWifiCapabilityReport
import com.example.engine.NearbyWifiNetwork
import com.example.engine.NetworkMatrixState
import com.example.engine.ShizukuStatus
import com.example.engine.ShizukuWifiState
import com.example.ui.components.SkeletonWifiRow
import com.example.ui.components.StatusPill
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.LocalMotionSpeedMultiplier
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.motion.aetherSpringSpec
import com.example.ui.theme.LocalAetherColors
import com.example.ui.theme.LocalAetherNeonConfig
import com.example.ui.theme.aetherNeonBorder
import com.example.ui.theme.contrastOnColor

/**
 * In-App Auto Wi-Fi 2 Picker + Shizuku Privilege Helper + Companion Relay + USB Tether sheet.
 * Automatically fetches active Wi-Fi 1 + nearby Wi-Fi networks (no manual SSID typing),
 * displays shimmering skeleton rows while scanning, prompts inline for password upon tapping any SSID,
 * and integrates Shizuku for unthrottled scans & Wi-Fi low-latency mode.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DualWifiConnectorSheet(
    settings: EngineSettings,
    networkMatrix: NetworkMatrixState,
    capabilities: DualWifiCapabilityReport,
    nearbyNetworks: List<NearbyWifiNetwork>,
    connectedWifi1Ssid: String = "",
    isScanning: Boolean = false,
    shizukuState: ShizukuWifiState = ShizukuWifiState(),
    isConnecting: Boolean,
    onProbeCapabilities: () -> Unit,
    onScanNearbyWifi: () -> Unit,
    onRequestShizukuPermission: () -> Unit = {},
    onToggleShizukuLowLatency: (Boolean) -> Unit = {},
    onConnectDirectWifi2: (ssid: String, passphrase: String, band: WifiBandPreference) -> Unit,
    onConnectWifi2Relay: (host: String, port: Int, isHttp: Boolean) -> Unit,
    onDisconnectWifi2: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val ext = LocalAetherColors.current
    val neon = LocalAetherNeonConfig.current
    val motionSpeed = LocalMotionSpeedMultiplier.current

    BackHandler(onBack = onDismiss)

    // Always default to tab 0 (Auto Wi-Fi Picker) so user immediately sees their active & nearby Wi-Fi networks!
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedNetwork by remember { mutableStateOf<NearbyWifiNetwork?>(null) }
    var showManualHiddenSsid by remember { mutableStateOf(false) }
    var ssidDraft by remember(settings.wifi2Ssid) { mutableStateOf(settings.wifi2Ssid) }
    var passphraseDraft by remember { mutableStateOf("") }
    var bandPref by remember(settings.wifi2BandPreference) { mutableStateOf(settings.wifi2BandPreference) }
    var relayHostDraft by remember(settings.wifi2RelayHost) { mutableStateOf(settings.wifi2RelayHost) }
    var relayPortDraft by remember(settings.wifi2RelayPort) { mutableStateOf(settings.wifi2RelayPort.toString()) }
    var relayIsHttp by remember(settings.wifi2RelayIsHttp) { mutableStateOf(settings.wifi2RelayIsHttp) }

    val requiredScanPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var hasScanPerm by remember {
        mutableStateOf(
            requiredScanPermissions.any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val scanPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasScanPerm = results.values.any { it }
        if (hasScanPerm) onScanNearbyWifi()
    }

    LaunchedEffect(Unit) {
        onProbeCapabilities()
        onScanNearbyWifi()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 12.dp, vertical = 18.dp)
            .testTag("dual_wifi_connector_sheet"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .aetherSharedBounds(
                    key = AetherSharedKey("wifi2_connector", SharedPart.WIFI2_CONNECTOR_PILL),
                    clipShape = RoundedCornerShape(24.dp),
                    zIndexInOverlay = 3f,
                )
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .aetherNeonBorder(
                    neon = neon,
                    accent = ext.violet,
                    cornerRadius = 24.dp,
                    borderWidth = 1.2.dp,
                    fallbackBorderColor = ext.violet.copy(alpha = 0.55f),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .animateContentSize(aetherSpringSpec(motionSpeed))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
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
                    Icon(Icons.Filled.Router, contentDescription = null, tint = ext.violet, modifier = Modifier.size(22.dp))
                    Text(
                        text = "Quad-Link Wi-Fi 2 & Shizuku",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (networkMatrix.wifi2Link != null) {
                        StatusPill(label = "Wi-Fi 2 ON", color = ext.emerald)
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_dual_wifi_sheet_button"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close Wi-Fi 2 connector", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Active Wi-Fi 1 + Chipset Capability Summary
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .border(1.dp, ext.cardBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val wifi1Display = connectedWifi1Ssid.ifBlank { networkMatrix.wifiLink?.interfaceName ?: "None" }
                    StatusPill(
                        label = "WI-FI 1: $wifi1Display",
                        color = if (networkMatrix.wifiLink != null) ext.cyan else ext.amber,
                    )
                    StatusPill(
                        label = when {
                            capabilities.dualStaMultiInternet -> "Dual-STA Multi-Internet"
                            capabilities.dualStaLocalOnly -> "Dual-STA Local-Only"
                            else -> "Single-STA Radio"
                        },
                        color = if (capabilities.dualStaMultiInternet) ext.emerald else ext.amber,
                    )
                    if (capabilities.supports5Ghz) StatusPill(label = "5 GHz", color = ext.cyan)
                    if (capabilities.supports6Ghz) StatusPill(label = "6 GHz", color = ext.violet)
                }
                Text(
                    text = networkMatrix.wifi2StatusMessage.ifBlank { capabilities.recommendation },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Shizuku Privilege & Unthrottled Wi-Fi Boost Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ext.violet.copy(alpha = 0.10f))
                    .border(1.dp, ext.violet.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
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
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = ext.violet, modifier = Modifier.size(18.dp))
                        Text(
                            text = shizukuState.status.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (shizukuState.status == ShizukuStatus.AUTHORIZED) {
                                onProbeCapabilities()
                                onScanNearbyWifi()
                            } else {
                                onRequestShizukuPermission()
                            }
                        },
                        modifier = Modifier.testTag("shizuku_action_button"),
                    ) {
                        Text(
                            text = when (shizukuState.status) {
                                ShizukuStatus.AUTHORIZED -> "Re-Scan"
                                ShizukuStatus.PERMISSION_REQUIRED -> "Grant Shizuku"
                                ShizukuStatus.INSTALLED_STOPPED -> "Start Shizuku"
                                ShizukuStatus.NOT_INSTALLED -> "Get Shizuku"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Text(
                    text = shizukuState.lastShellMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (shizukuState.status == ShizukuStatus.AUTHORIZED) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Force Wi-Fi Low-Latency Mode (Shizuku)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(
                            checked = shizukuState.lowLatencyModeActive,
                            onCheckedChange = onToggleShizukuLowLatency,
                        )
                    }
                }
            }

            if (networkMatrix.singleStaDisplacementDetected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ext.amber.copy(alpha = 0.14f))
                        .border(1.dp, ext.amber.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = ext.amber, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Single-STA Displacement Detected",
                            style = MaterialTheme.typography.titleSmall,
                            color = ext.amber,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "Your phone's Wi-Fi chipset replaced Wi-Fi 1 when joining Wi-Fi 2. Tap below to restore Wi-Fi 1 and switch to Companion Wi-Fi 2 Relay or USB Tether for true simultaneous dual-Wi-Fi speed!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Button(
                        onClick = {
                            onDisconnectWifi2()
                            selectedTab = 1
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ext.amber,
                            contentColor = contrastOnColor(ext.amber),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Rollback Wi-Fi 2 & Switch to Relay", fontWeight = FontWeight.Bold)
                    }
                }
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Wi-Fi Picker") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Wi-Fi 2 Relay") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("USB Tether") })
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                },
                label = "dual_wifi_tab_morph",
            ) { tab ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (tab) {
                        0 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "TAP A WI-FI NETWORK TO BOND AS WI-FI 2",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ext.cyan,
                                    fontWeight = FontWeight.Bold,
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (hasScanPerm || shizukuState.status == ShizukuStatus.AUTHORIZED) {
                                            onScanNearbyWifi()
                                        } else {
                                            scanPermLauncher.launch(requiredScanPermissions)
                                        }
                                    },
                                    modifier = Modifier.testTag("scan_nearby_wifi_button"),
                                ) {
                                    Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when {
                                            isScanning -> "Scanning…"
                                            hasScanPerm || shizukuState.status == ShizukuStatus.AUTHORIZED -> "Refresh"
                                            else -> "Grant Scan Perm"
                                        },
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }

                            if (!hasScanPerm && shizukuState.status != ShizukuStatus.AUTHORIZED) {
                                Button(
                                    onClick = { scanPermLauncher.launch(requiredScanPermissions) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ext.cyan,
                                        contentColor = contrastOnColor(ext.cyan),
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Allow Nearby Wi-Fi Scan to Auto-Fetch Networks", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            if (isScanning && nearbyNetworks.isEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    repeat(4) { SkeletonWifiRow() }
                                }
                            } else if (nearbyNetworks.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    nearbyNetworks.take(10).forEach { net ->
                                        val isPicked = selectedNetwork?.ssid == net.ssid
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (isPicked) ext.violet.copy(alpha = 0.14f)
                                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                )
                                                .border(
                                                    width = if (isPicked) 1.5.dp else 1.dp,
                                                    color = when {
                                                        isPicked -> ext.violet
                                                        net.isCurrentlyConnected -> ext.cyan.copy(alpha = 0.65f)
                                                        else -> ext.cardBorder
                                                    },
                                                    shape = RoundedCornerShape(12.dp),
                                                )
                                                .clickable {
                                                    selectedNetwork = if (isPicked) null else net
                                                    ssidDraft = net.ssid
                                                    passphraseDraft = ""
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Wifi,
                                                        contentDescription = null,
                                                        tint = if (net.isCurrentlyConnected) ext.cyan else ext.emerald,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                    Column {
                                                        Text(
                                                            text = net.ssid,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        Text(
                                                            text = "${net.bandLabel} · ${net.rssiDbm} dBm · ${net.sourceTag}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    if (net.isCurrentlyConnected) {
                                                        StatusPill(label = "WI-FI 1", color = ext.cyan)
                                                    } else {
                                                        StatusPill(label = net.bandLabel, color = ext.emerald)
                                                    }
                                                    Icon(
                                                        imageVector = if (net.isSecured) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                                        contentDescription = if (net.isSecured) "Secured Wi-Fi" else "Open Wi-Fi",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(15.dp),
                                                    )
                                                }
                                            }

                                            // Inline Password + Connect Prompt for tapped SSID!
                                            AnimatedVisibility(visible = isPicked) {
                                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    if (net.isSecured) {
                                                        OutlinedTextField(
                                                            value = passphraseDraft,
                                                            onValueChange = { passphraseDraft = it },
                                                            label = { Text("Password for ${net.ssid}") },
                                                            singleLine = true,
                                                            visualTransformation = PasswordVisualTransformation(),
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .testTag("wifi2_passphrase_input"),
                                                        )
                                                    }
                                                    Button(
                                                        onClick = {
                                                            onConnectDirectWifi2(net.ssid, passphraseDraft, bandPref)
                                                        },
                                                        enabled = !isConnecting,
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = ext.violet,
                                                            contentColor = contrastOnColor(ext.violet),
                                                        ),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .testTag("connect_direct_wifi2_button"),
                                                    ) {
                                                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = if (isConnecting) "Connecting to ${net.ssid}…" else "Bond ${net.ssid} as Wi-Fi 2",
                                                            fontWeight = FontWeight.Bold,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "No nearby Wi-Fi networks returned yet. Ensure Location / Wi-Fi is ON and tap Refresh, or enter a hidden SSID below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { showManualHiddenSsid = !showManualHiddenSsid }) {
                                    Text(if (showManualHiddenSsid) "Hide Manual SSID" else "+ Hidden / Manual SSID")
                                }
                                if (networkMatrix.wifi2Link != null) {
                                    OutlinedButton(onClick = onDisconnectWifi2) {
                                        Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Drop Wi-Fi 2", color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }

                            AnimatedVisibility(visible = showManualHiddenSsid) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = ssidDraft,
                                        onValueChange = { ssidDraft = it },
                                        label = { Text("Hidden Wi-Fi SSID") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("wifi2_ssid_input"),
                                    )
                                    OutlinedTextField(
                                        value = passphraseDraft,
                                        onValueChange = { passphraseDraft = it },
                                        label = { Text("WPA2/WPA3 Passphrase") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        WifiBandPreference.entries.forEach { bp ->
                                            FilterChip(
                                                selected = bandPref == bp,
                                                onClick = { bandPref = bp },
                                                label = { Text(bp.label) },
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = { onConnectDirectWifi2(ssidDraft, passphraseDraft, bandPref) },
                                        enabled = ssidDraft.isNotBlank() && !isConnecting,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ext.violet,
                                            contentColor = contrastOnColor(ext.violet),
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(if (isConnecting) "Connecting…" else "Connect Hidden Wi-Fi 2")
                                    }
                                }
                            }
                        }

                        1 -> {
                            Text(
                                text = "Works on EVERY non-Dual-STA phone (zero root): run any local SOCKS5/HTTP relay app (Every Proxy, NetShare, or a 2nd phone on Wi-Fi 2), enter its LAN IP & Port below, and AetherIO bonds Wi-Fi 1 + Wi-Fi 2 Relay + Mobile 5G + USB simultaneously!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = relayHostDraft,
                                    onValueChange = { relayHostDraft = it },
                                    label = { Text("Relay LAN IP / Host") },
                                    placeholder = { Text("192.168.1.50") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(2f)
                                        .testTag("wifi2_relay_host_input"),
                                )
                                OutlinedTextField(
                                    value = relayPortDraft,
                                    onValueChange = { relayPortDraft = it },
                                    label = { Text("Port") },
                                    placeholder = { Text("1080") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("wifi2_relay_port_input"),
                                )
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !relayIsHttp,
                                    onClick = { relayIsHttp = false },
                                    label = { Text("SOCKS5h (Recommended)") },
                                )
                                FilterChip(
                                    selected = relayIsHttp,
                                    onClick = { relayIsHttp = true },
                                    label = { Text("HTTP CONNECT Proxy") },
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        val port = relayPortDraft.toIntOrNull() ?: 1080
                                        onConnectWifi2Relay(relayHostDraft.trim(), port, relayIsHttp)
                                    },
                                    enabled = relayHostDraft.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ext.violet,
                                        contentColor = contrastOnColor(ext.violet),
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("connect_wifi2_relay_button"),
                                ) {
                                    Icon(Icons.Filled.Router, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Arm Wi-Fi 2 Relay", fontWeight = FontWeight.Bold)
                                }
                                if (networkMatrix.wifi2Link != null) {
                                    OutlinedButton(onClick = onDisconnectWifi2) {
                                        Text("Disconnect", color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }

                        else -> {
                            val usbLink = networkMatrix.ethernetLink
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                                    .border(1.dp, ext.cardBorder, RoundedCornerShape(12.dp))
                                    .padding(14.dp),
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
                                        Icon(Icons.Filled.Cable, contentDescription = null, tint = ext.emerald)
                                        Text(
                                            text = "USB Tethering / USB-C Ethernet",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    StatusPill(
                                        label = if (usbLink != null) "CONNECTED (${usbLink.interfaceName})" else "WAITING",
                                        color = if (usbLink != null) ext.emerald else ext.amber,
                                    )
                                }
                                Text(
                                    text = if (usbLink != null) {
                                        "Active on ${usbLink.interfaceName} (${usbLink.localIp.ifBlank { "DHCP" }}) · Est. ${usbLink.downstreamKbps / 1000} Mbps. AetherIO automatically bonds this link with Wi-Fi 1, Wi-Fi 2, and Mobile 5G!"
                                    } else {
                                        "Plug in a USB-C Gigabit Ethernet adapter or tether a second phone over USB-C (enable USB Tethering on the second phone). AetherIO automatically detects TRANSPORT_USB and TRANSPORT_ETHERNET and adds it as the 4th live speedometer and download pipe!"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
