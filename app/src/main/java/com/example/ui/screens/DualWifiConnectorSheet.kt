package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.model.EngineSettings
import com.example.data.model.WifiBandPreference
import com.example.engine.DualWifiCapabilityReport
import com.example.engine.NearbyWifiNetwork
import com.example.engine.NetworkMatrixState
import com.example.ui.components.StatusPill
import com.example.ui.motion.AetherSharedKey
import com.example.ui.motion.LocalMotionSpeedMultiplier
import com.example.ui.motion.SharedPart
import com.example.ui.motion.aetherSharedBounds
import com.example.ui.motion.aetherSpringSpec
import com.example.ui.theme.LocalAetherColors

/**
 * In-tree Container Transform Overlay for Quad-Link Wi-Fi 2 & USB Tethering.
 * Morphs directly from the `+ Wi-Fi 2` chip (`WIFI2_CONNECTOR_PILL`) via Compose `sharedBounds`,
 * and uses directional `AnimatedContent` across its 3 tabs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DualWifiConnectorSheet(
    settings: EngineSettings,
    networkMatrix: NetworkMatrixState,
    capabilities: DualWifiCapabilityReport,
    nearbyNetworks: List<NearbyWifiNetwork>,
    isConnecting: Boolean,
    onProbeCapabilities: () -> Unit,
    onScanNearbyWifi: () -> Unit,
    onConnectDirectWifi2: (ssid: String, passphrase: String, band: WifiBandPreference) -> Unit,
    onConnectWifi2Relay: (host: String, port: Int, isHttp: Boolean) -> Unit,
    onDisconnectWifi2: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val ext = LocalAetherColors.current
    val motionSpeed = LocalMotionSpeedMultiplier.current

    BackHandler(onBack = onDismiss)

    var selectedTab by remember {
        mutableIntStateOf(
            if (capabilities.dualStaMultiInternet) 0 else 1
        )
    }
    var ssidDraft by remember(settings.wifi2Ssid) { mutableStateOf(settings.wifi2Ssid) }
    var passphraseDraft by remember { mutableStateOf("") }
    var bandPref by remember(settings.wifi2BandPreference) { mutableStateOf(settings.wifi2BandPreference) }
    var relayHostDraft by remember(settings.wifi2RelayHost) { mutableStateOf(settings.wifi2RelayHost) }
    var relayPortDraft by remember(settings.wifi2RelayPort) { mutableStateOf(settings.wifi2RelayPort.toString()) }
    var relayIsHttp by remember(settings.wifi2RelayIsHttp) { mutableStateOf(settings.wifi2RelayIsHttp) }

    val requiredScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    var hasScanPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredScanPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val scanPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasScanPerm = granted
        if (granted) onScanNearbyWifi()
    }

    LaunchedEffect(Unit) {
        onProbeCapabilities()
        if (hasScanPerm) onScanNearbyWifi()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
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
                .border(1.2.dp, ext.violet.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
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
                    Text("Quad-Link Wi-Fi 2 & USB Boost", style = MaterialTheme.typography.titleLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (networkMatrix.wifi2Link != null) {
                        StatusPill(label = "Wi-Fi 2 ACTIVE", color = ext.emerald)
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_dual_wifi_sheet_button"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close Wi-Fi 2 connector")
                    }
                }
            }

            // Hardware Dual-STA Capability Banner
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
                    StatusPill(
                        label = if (capabilities.dualStaMultiInternet) {
                            "Dual-STA Multi-Internet READY"
                        } else if (capabilities.dualStaLocalOnly) {
                            "Dual-STA Local-Only"
                        } else {
                            "Single-STA Radio (Use Relay / USB)"
                        },
                        color = if (capabilities.dualStaMultiInternet) ext.emerald else ext.amber,
                    )
                    if (capabilities.supports5Ghz) StatusPill(label = "5 GHz", color = ext.cyan)
                    if (capabilities.supports6Ghz) StatusPill(label = "6 GHz Wi-Fi 6E/7", color = ext.violet)
                }
                Text(
                    text = networkMatrix.wifi2StatusMessage.ifBlank { capabilities.recommendation },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        text = "Your phone's Wi-Fi chipset replaced Wi-Fi 1 when joining Wi-Fi 2. Roll back to restore Wi-Fi 1 and use Companion Wi-Fi 2 Relay or USB Tethering for true simultaneous speed boost!",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            onDisconnectWifi2()
                            selectedTab = 1
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Rollback Wi-Fi 2 & Switch to Relay")
                    }
                }
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Direct Wi-Fi 2") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Wi-Fi 2 Relay") },
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("USB Tether") },
                )
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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (tab) {
                        0 -> {
                            Text(
                                text = "Connect directly to a second Wi-Fi SSID (e.g., 5 GHz + 2.4 GHz or a second router) from inside AetherIO via WifiNetworkSpecifier.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("NEARBY WI-FI NETWORKS", style = MaterialTheme.typography.labelSmall, color = ext.cyan)
                                OutlinedButton(
                                    onClick = {
                                        if (hasScanPerm) onScanNearbyWifi()
                                        else scanPermLauncher.launch(requiredScanPermission)
                                    },
                                    modifier = Modifier.testTag("scan_nearby_wifi_button"),
                                ) {
                                    Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (hasScanPerm) "Scan Wi-Fi" else "Grant Scan Perm")
                                }
                            }

                            if (nearbyNetworks.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    nearbyNetworks.take(6).forEach { net ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                                .border(1.dp, ext.cardBorder, RoundedCornerShape(10.dp))
                                                .clickable { ssidDraft = net.ssid }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Icon(Icons.Filled.Wifi, contentDescription = null, tint = ext.cyan, modifier = Modifier.size(16.dp))
                                                Text(net.ssid, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            }
                                            StatusPill(label = "${net.bandLabel} · ${net.rssiDbm} dBm", color = ext.emerald)
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = ssidDraft,
                                onValueChange = { ssidDraft = it },
                                label = { Text("Second Wi-Fi SSID") },
                                placeholder = { Text("MyRouter_5G or NeighborWiFi") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wifi2_ssid_input"),
                            )

                            OutlinedTextField(
                                value = passphraseDraft,
                                onValueChange = { passphraseDraft = it },
                                label = { Text("WPA2/WPA3 Passphrase (leave empty for open)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wifi2_passphrase_input"),
                            )

                            Text("BAND PREFERENCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { onConnectDirectWifi2(ssidDraft, passphraseDraft, bandPref) },
                                    enabled = ssidDraft.isNotBlank() && !isConnecting,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("connect_direct_wifi2_button"),
                                ) {
                                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isConnecting) "Connecting..." else "Connect Wi-Fi 2")
                                }
                                if (networkMatrix.wifi2Link != null) {
                                    OutlinedButton(onClick = onDisconnectWifi2) {
                                        Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Drop")
                                    }
                                }
                            }
                        }

                        1 -> {
                            Text(
                                text = "Works on EVERY Android phone (no Dual-STA hardware or root needed): run any local SOCKS5/HTTP relay app (e.g., Every Proxy, NetShare, or OpenWrt dual-WAN router) connected to Wi-Fi 2, then enter its LAN IP & Port below. AetherIO will bond Wi-Fi 1 + Wi-Fi 2 Relay + 5G + USB simultaneously!",
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
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("connect_wifi2_relay_button"),
                                ) {
                                    Icon(Icons.Filled.Router, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Arm Wi-Fi 2 Relay")
                                }
                                if (networkMatrix.wifi2Link != null) {
                                    OutlinedButton(onClick = onDisconnectWifi2) {
                                        Text("Disconnect")
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
                                        Text("USB Tethering / USB-C Ethernet", style = MaterialTheme.typography.titleSmall)
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
