package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import com.example.data.model.BreakerState
import com.example.data.model.EngineSettings
import com.example.data.model.LinkKey
import com.example.data.model.NetworkRouteMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class LinkEndpointInfo(
    val linkKey: LinkKey,
    val network: Network,
    val label: String,
    val interfaceName: String,
    val localIp: String,
    val netHandle: Long,
    val downstreamKbps: Int,
    val upstreamKbps: Int,
    val isMetered: Boolean,
    val isValidated: Boolean,
    val pacFileUrl: String? = null,
    val breakerState: BreakerState = BreakerState.CLOSED,
    val ewmaMbps: Double = downstreamKbps / 1000.0,
    val bandLabel: String = "",
    val ssid: String = "",
    val rssiDbm: Int? = null,
    val isRelay: Boolean = false,
    val relayProxyHost: String = "",
    val relayProxyPort: Int = 0,
    val relayIsHttp: Boolean = false,
)

data class NetworkMatrixState(
    val wifiLink: LinkEndpointInfo? = null,
    val wifi2Link: LinkEndpointInfo? = null,
    val cellularLink: LinkEndpointInfo? = null,
    val ethernetLink: LinkEndpointInfo? = null,
    val hasAnyInternet: Boolean = false,
    val cellularLeaseRequested: Boolean = false,
    val pacWarningUrl: String? = null,
    val restrictBackgroundStatus: Int = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
    val wifi2StatusMessage: String = "Tap + Wi-Fi 2 to connect 2nd band or Companion Relay",
    val dualStaHardwareCapable: Boolean = false,
    val dualStaLocalOnlyCapable: Boolean = false,
    val supports5Ghz: Boolean = false,
    val supports6Ghz: Boolean = false,
    val singleStaDisplacementDetected: Boolean = false,
) {
    val activeValidatedLinks: List<LinkEndpointInfo>
        get() = listOfNotNull(ethernetLink, wifiLink, wifi2Link, cellularLink).filter { it.isValidated }

    val isMultipathReady: Boolean
        get() = activeValidatedLinks.size >= 2

    val activeLinkCount: Int
        get() = activeValidatedLinks.size

    val aggregateEstimatedMbps: Double
        get() = activeValidatedLinks.sumOf { it.downstreamKbps } / 1000.0

    val dataSaverLabel: String
        get() = when (restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "Data Saver RESTRICTED (Background Cell Blocked)"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "Data Saver Whitelisted"
            else -> "Unrestricted"
        }
}

/**
 * Quad-Link Multi-Network Monitor (v9.0):
 * - Supports 4 simultaneous active paths: Wi-Fi 1 (`wifi:`), Wi-Fi 2 (`wifi2:`), Mobile 5G/LTE (`cell:`),
 *   and USB Tethering / USB-C Ethernet (`eth:`).
 * - Fixes OEM validation lag where requested secondary Cellular or USB networks have INTERNET + IP
 *   before Android sets NET_CAPABILITY_VALIDATED, while strictly excluding CAPTIVE_PORTAL networks.
 * - Supports both hardware Dual-STA Wi-Fi 2 (`WifiNetworkSpecifier`) and Companion Wi-Fi 2 Relay
 *   (SOCKS5h / HTTP proxy over a 2nd phone/router) so non-Dual-STA phones still achieve dual-Wi-Fi bonding.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val started = AtomicBoolean(false)

    private val discoveredByKey = ConcurrentHashMap<String, LinkEndpointInfo>()
    private val keyByNetworkHandle = ConcurrentHashMap<Long, String>()

    @Volatile
    private var cellularLeaseActive = false

    @Volatile
    private var primaryWifiHandle: Long? = null

    @Volatile
    private var wifi2RequestedHandle: Long? = null

    @Volatile
    private var wifi2StatusMsg: String = "Ready — connect 2nd Wi-Fi band or Companion Relay"

    @Volatile
    private var dualStaHw: Boolean = false

    @Volatile
    private var dualStaLocal: Boolean = false

    @Volatile
    private var band5Ghz: Boolean = false

    @Volatile
    private var band6Ghz: Boolean = false

    @Volatile
    private var singleStaDisplaced: Boolean = false

    @Volatile
    private var latestSettings: EngineSettings = EngineSettings()

    private val _state = MutableStateFlow(NetworkMatrixState())
    val state: StateFlow<NetworkMatrixState> = _state.asStateFlow()

    val breakerRegistry = ConcurrentHashMap<String, LinkCircuitBreaker>()

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork(network, fromRequestedLease = false)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetwork(network, caps = caps, fromRequestedLease = false)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetwork(network, lp = linkProperties, fromRequestedLease = false)
        override fun onLost(network: Network) = removeNetwork(network)
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork(network, fromRequestedLease = false)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetwork(network, caps = caps, fromRequestedLease = false)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetwork(network, lp = linkProperties, fromRequestedLease = false)
        override fun onLost(network: Network) = removeNetwork(network)
    }

    private val ethernetCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork(network, fromRequestedLease = true)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetwork(network, caps = caps, fromRequestedLease = true)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetwork(network, lp = linkProperties, fromRequestedLease = true)
        override fun onLost(network: Network) = removeNetwork(network)
    }

    private val usbCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork(network, fromRequestedLease = true)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetwork(network, caps = caps, fromRequestedLease = true)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetwork(network, lp = linkProperties, fromRequestedLease = true)
        override fun onLost(network: Network) = removeNetwork(network)
    }

    private val cellularRequestCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork(network, fromRequestedLease = true)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetwork(network, caps = caps, fromRequestedLease = true)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetwork(network, lp = linkProperties, fromRequestedLease = true)
        override fun onLost(network: Network) = removeNetwork(network)
    }

    fun start(initialSettings: EngineSettings = EngineSettings()) {
        latestSettings = initialSettings
        if (started.compareAndSet(false, true)) {
            runCatching { cm.registerDefaultNetworkCallback(defaultCallback) }
            runCatching {
                val wifiReq = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(wifiReq, wifiCallback)
            }
            runCatching {
                val ethReq = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(ethReq, ethernetCallback)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    val usbReq = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_USB)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.registerNetworkCallback(usbReq, usbCallback)
                }
            }
        }
        applyPolicy(initialSettings)
    }

    fun updateDualWifiCapabilities(
        dualStaMultiInternet: Boolean,
        dualStaLocalOnly: Boolean,
        supports5Ghz: Boolean,
        supports6Ghz: Boolean,
    ) {
        dualStaHw = dualStaMultiInternet
        dualStaLocal = dualStaLocalOnly
        band5Ghz = supports5Ghz
        band6Ghz = supports6Ghz
        publishState()
    }

    fun updateWifi2Status(message: String, displacementDetected: Boolean = false) {
        wifi2StatusMsg = message
        singleStaDisplaced = displacementDetected
        publishState()
    }

    /**
     * Registers a live hardware Wi-Fi 2 Network returned by `DualWifiConnector`.
     */
    fun registerDirectWifi2Network(network: Network, requestedSsid: String) {
        val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) network.networkHandle else 0L
        wifi2RequestedHandle = handle
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        val lp = runCatching { cm.getLinkProperties(network) }.getOrNull()
        val iface = lp?.interfaceName?.takeIf { it.isNotBlank() } ?: "wlan1"
        val ip = extractPrimaryIp(lp)
        val down = (caps?.linkDownstreamBandwidthKbps ?: 120_000).coerceAtLeast(10_000)
        val up = (caps?.linkUpstreamBandwidthKbps ?: 40_000).coerceAtLeast(5_000)
        val band = extractWifiBand(caps)
        val linkKey = LinkKey.WifiSecondary(iface)

        val info = LinkEndpointInfo(
            linkKey = linkKey,
            network = network,
            label = if (requestedSsid.isNotBlank()) "$requestedSsid (Wi-Fi 2)" else "$iface (Wi-Fi 2)",
            interfaceName = iface,
            localIp = ip,
            netHandle = handle,
            downstreamKbps = down,
            upstreamKbps = up,
            isMetered = false,
            isValidated = true,
            breakerState = breakerRegistry.computeIfAbsent(linkKey.id) { LinkCircuitBreaker() }.currentState(),
            ewmaMbps = down / 1000.0,
            bandLabel = band.ifBlank { "Dual-STA" },
            ssid = requestedSsid,
            rssiDbm = extractWifiRssi(caps),
            isRelay = false,
        )
        keyByNetworkHandle[handle] = linkKey.id
        discoveredByKey[linkKey.id] = info
        AetherNativeBridge.updateLinkHandleSafe(
            linkKey = linkKey.id,
            netHandle = handle,
            downstreamKbps = down.toLong(),
            rttMs = 16L,
            isCellular = false,
            localIp = ip,
            proxyHost = "",
            proxyPort = 0,
        )
        wifi2StatusMsg = "Connected Dual-STA: ${info.label} ($ip)"
        DiagnosticsCenter.log("WIFI2_STA", "Direct Wi-Fi 2 active: ${linkKey.id} ssid=$requestedSsid ip=$ip")
        publishState()
    }

    /**
     * Registers a Companion Wi-Fi 2 Relay endpoint (SOCKS5h or HTTP proxy on a 2nd phone or dual-WAN router)
     * so even single-STA Android phones get genuine simultaneous Wi-Fi 1 + Wi-Fi 2 + 5G + USB bonding!
     */
    fun registerCompanionWifi2Relay(host: String, port: Int, isHttp: Boolean): Boolean {
        val cleanHost = host.trim()
        if (cleanHost.isBlank() || port !in 1..65535) return false
        val baseNetwork = _state.value.wifiLink?.network
            ?: _state.value.ethernetLink?.network
            ?: _state.value.cellularLink?.network
            ?: cm.activeNetwork
            ?: return false

        val linkKey = LinkKey.WifiSecondary("relay")
        val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) baseNetwork.networkHandle else 0L
        val protoTag = if (isHttp) "HTTP" else "SOCKS5h"
        val info = LinkEndpointInfo(
            linkKey = linkKey,
            network = baseNetwork,
            label = "Wi-Fi 2 Relay ($cleanHost:$port)",
            interfaceName = "relay0",
            localIp = cleanHost,
            netHandle = handle,
            downstreamKbps = 150_000,
            upstreamKbps = 50_000,
            isMetered = false,
            isValidated = true,
            breakerState = breakerRegistry.computeIfAbsent(linkKey.id) { LinkCircuitBreaker() }.currentState(),
            ewmaMbps = 80.0,
            bandLabel = "$protoTag Relay",
            ssid = "$cleanHost:$port",
            isRelay = true,
            relayProxyHost = cleanHost,
            relayProxyPort = port,
            relayIsHttp = isHttp,
        )
        discoveredByKey[linkKey.id] = info
        val nativeProxyHost = if (isHttp) "http://$cleanHost" else "socks5h://$cleanHost"
        AetherNativeBridge.updateLinkHandleSafe(
            linkKey = linkKey.id,
            netHandle = handle,
            downstreamKbps = 150_000L,
            rttMs = 18L,
            isCellular = false,
            localIp = "",
            proxyHost = nativeProxyHost,
            proxyPort = port,
        )
        wifi2StatusMsg = "Active $protoTag Companion Relay -> $cleanHost:$port"
        singleStaDisplaced = false
        DiagnosticsCenter.log("WIFI2_RELAY", "Companion Wi-Fi 2 Relay enabled: $nativeProxyHost:$port")
        publishState()
        return true
    }

    fun clearWifi2Links(reason: String = "Wi-Fi 2 disconnected") {
        wifi2RequestedHandle = null
        discoveredByKey.keys.filter { it.startsWith("wifi2:") }.forEach { key ->
            discoveredByKey.remove(key)
            AetherNativeBridge.removeLinkSafe(key)
        }
        wifi2StatusMsg = reason
        singleStaDisplaced = false
        publishState()
    }

    fun applyPolicy(settings: EngineSettings) {
        latestSettings = settings
        AetherNativeBridge.setFeatureFlagsSafe(settings.toNativeFeatureMask())

        val wantsCellular = (settings.retainCellularOnWifi || settings.forceAllLinks) &&
            settings.defaultRouteMode != NetworkRouteMode.WIFI_ONLY
        if (wantsCellular && !cellularLeaseActive) {
            runCatching {
                val cellReq = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.requestNetwork(cellReq, cellularRequestCallback)
                cellularLeaseActive = true
                DiagnosticsCenter.log("CELL_LEASE", "Requested persistent Cellular lease (forceAllLinks=${settings.forceAllLinks})")
            }
        } else if (!wantsCellular && cellularLeaseActive) {
            runCatching { cm.unregisterNetworkCallback(cellularRequestCallback) }
            cellularLeaseActive = false
            discoveredByKey.keys.filter { it.startsWith("cell:") }.forEach { key ->
                discoveredByKey.remove(key)
                AetherNativeBridge.removeLinkSafe(key)
            }
        }

        // Auto-hydrate saved Companion Wi-Fi 2 Relay when Dual-Wi-Fi is enabled
        if (settings.dualWifiEnabled && settings.wifi2RelayHost.isNotBlank() &&
            discoveredByKey.keys.none { it.startsWith("wifi2:") }
        ) {
            registerCompanionWifi2Relay(
                host = settings.wifi2RelayHost,
                port = settings.wifi2RelayPort,
                isHttp = settings.wifi2RelayIsHttp,
            )
        } else if (!settings.dualWifiEnabled) {
            discoveredByKey.keys.filter { it.startsWith("wifi2:") }.forEach { key ->
                discoveredByKey.remove(key)
                AetherNativeBridge.removeLinkSafe(key)
            }
        }

        if (!settings.usbBondingEnabled) {
            discoveredByKey.keys.filter { it.startsWith("eth:") }.forEach { key ->
                discoveredByKey.remove(key)
                AetherNativeBridge.removeLinkSafe(key)
            }
        }
        publishState()
    }

    fun releaseCellularLease() {
        if (cellularLeaseActive) {
            runCatching { cm.unregisterNetworkCallback(cellularRequestCallback) }
            cellularLeaseActive = false
            publishState()
        }
    }

    /** Re-push every discovered link to the Rust core (handles rotate on reconnect). */
    fun syncAllToNative() {
        discoveredByKey.values.forEach { link ->
            val isCell = link.linkKey is LinkKey.Cellular
            val relayHost = link.relayProxyHost
            AetherNativeBridge.updateLinkHandleSafe(
                linkKey = link.linkKey.id,
                netHandle = link.netHandle,
                downstreamKbps = link.downstreamKbps.toLong(),
                rttMs = when {
                    isCell -> 26L
                    link.isRelay -> 18L
                    else -> 14L
                },
                isCellular = isCell,
                localIp = link.localIp,
                proxyHost = relayHost.ifBlank {
                    if (isCell) latestSettings.cellularProxyHost else latestSettings.proxyHost
                },
                proxyPort = if (link.relayProxyPort > 0) {
                    link.relayProxyPort
                } else if (isCell) {
                    latestSettings.cellularProxyPort
                } else {
                    latestSettings.proxyPort
                },
            )
        }
    }

    fun refreshState() {
        val active = cm.activeNetwork
        if (active != null) {
            updateNetwork(active, fromRequestedLease = false)
        } else {
            publishState()
        }
        syncAllToNative()
    }

    private fun updateNetwork(
        network: Network,
        caps: NetworkCapabilities? = null,
        lp: LinkProperties? = null,
        fromRequestedLease: Boolean = false,
    ) {
        val resolvedCaps = caps ?: runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return
        if (!resolvedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        // Reject captive portals strictly
        if (resolvedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            removeNetwork(network)
            return
        }

        val resolvedLp = lp ?: runCatching { cm.getLinkProperties(network) }.getOrNull()
        val localIp = extractPrimaryIp(resolvedLp)

        // Fix OEM validation lag: accept NET_CAPABILITY_VALIDATED, OR a requested secondary Cellular/USB
        // network that already has INTERNET + a real non-loopback IP (and is not a captive portal).
        val hasValidated = resolvedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isEthOrUsb = resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_USB))
        val isWifi = resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCell = resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (!isEthOrUsb && !isWifi && !isCell) return

        // OEM ROMs (notably Xiaomi/HyperOS) often never set NET_CAPABILITY_VALIDATED
        // on the secondary Wi-Fi network even though it has INTERNET + a usable IP.
        // Requiring VALIDATED made Wi-Fi invisible and left downloads cellular-only.
        // Captive portals are still rejected above, so accepting INTERNET + real IP
        // here is safe and restores true Wi-Fi + mobile bonding.
        val usableLease = localIp.isNotBlank() &&
            (fromRequestedLease || isWifi || isCell || isEthOrUsb)
        if (!hasValidated && !usableLease) {
            return
        }

        if (isEthOrUsb && !latestSettings.usbBondingEnabled) return

        val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) network.networkHandle else 0L
        val iface = resolvedLp?.interfaceName?.takeIf { it.isNotBlank() }

        // Detect simultaneous Dual-STA Wi-Fi (2 different Wi-Fi handles or interfaces!)
        val linkKey: LinkKey = when {
            isEthOrUsb -> LinkKey.Ethernet(iface ?: "usb0")
            isCell -> LinkKey.Cellular(iface ?: "rmnet0")
            else -> {
                val existingPrimary = discoveredByKey.values.firstOrNull { it.linkKey is LinkKey.Wifi }
                val isSecondWifiHandle = latestSettings.dualWifiEnabled && (
                    handle == wifi2RequestedHandle ||
                        (existingPrimary != null &&
                            existingPrimary.netHandle != handle &&
                            existingPrimary.interfaceName != (iface ?: "wlan0"))
                    )
                if (isSecondWifiHandle) {
                    LinkKey.WifiSecondary(iface ?: "wlan1")
                } else {
                    primaryWifiHandle = handle
                    LinkKey.Wifi(iface ?: "wlan0")
                }
            }
        }

        val pacUrl = resolvedLp?.httpProxy?.pacFileUrl
            ?.takeIf { it != Uri.EMPTY && it.toString().isNotBlank() }
            ?.toString()

        val down = resolvedCaps.linkDownstreamBandwidthKbps.coerceAtLeast(10_000)
        val up = resolvedCaps.linkUpstreamBandwidthKbps.coerceAtLeast(2_000)
        val metered = !resolvedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val breaker = breakerRegistry.computeIfAbsent(linkKey.id) { LinkCircuitBreaker() }
        // Cap initial unmeasured bootstrap EWMA to 80 Mbps so Android's fake 866 Mbps Wi-Fi PHY
        // never starves Mobile 5G/LTE or Wi-Fi 2 before real bytes flow!
        val bootstrapEwma = (down / 1000.0).coerceIn(15.0, 80.0)
        val existingEwma = discoveredByKey[linkKey.id]?.ewmaMbps ?: bootstrapEwma
        val band = if (isWifi) extractWifiBand(resolvedCaps) else if (isEthOrUsb) "USB/Eth" else "5G/LTE"
        val rssi = if (isWifi) extractWifiRssi(resolvedCaps) else null
        val wifiSsid = if (isWifi) extractWifiSsid(resolvedCaps) else ""

        val label = when (linkKey) {
            is LinkKey.Ethernet -> "${linkKey.iface} (USB/Eth)"
            is LinkKey.Wifi -> when {
                wifiSsid.isNotBlank() && band.isNotBlank() -> "$wifiSsid ($band)"
                band.isNotBlank() -> "${linkKey.iface} ($band)"
                else -> "${linkKey.iface} (Wi-Fi 1)"
            }
            is LinkKey.WifiSecondary -> if (band.isNotBlank()) "${linkKey.iface} ($band W2)" else "${linkKey.iface} (Wi-Fi 2)"
            is LinkKey.Cellular -> "${linkKey.iface} (5G/LTE)"
        }

        val info = LinkEndpointInfo(
            linkKey = linkKey,
            network = network,
            label = label,
            interfaceName = iface ?: when {
                isEthOrUsb -> "usb0"
                isWifi -> "wlan0"
                else -> "rmnet0"
            },
            localIp = localIp,
            netHandle = handle,
            downstreamKbps = down,
            upstreamKbps = up,
            isMetered = metered,
            isValidated = true,
            pacFileUrl = pacUrl,
            breakerState = breaker.currentState(),
            ewmaMbps = existingEwma,
            bandLabel = band,
            ssid = wifiSsid,
            rssiDbm = rssi,
            isRelay = false,
        )

        keyByNetworkHandle[handle] = linkKey.id
        val isNew = discoveredByKey.put(linkKey.id, info) == null
        if (isNew) {
            DiagnosticsCenter.log("HOTPLUG", "Interface validated: ${linkKey.id} ip=$localIp down=${down}kbps band=$band ssid=$wifiSsid")
        }

        val (pxHost, pxPort) = if (isCell) {
            latestSettings.cellularProxyHost to latestSettings.cellularProxyPort
        } else {
            latestSettings.proxyHost to latestSettings.proxyPort
        }
        AetherNativeBridge.updateLinkHandleSafe(
            linkKey = linkKey.id,
            netHandle = handle,
            downstreamKbps = down.toLong(),
            rttMs = when {
                isEthOrUsb -> 8L
                isCell -> 26L
                linkKey is LinkKey.WifiSecondary -> 16L
                else -> 14L
            },
            isCellular = isCell,
            localIp = localIp,
            proxyHost = pxHost,
            proxyPort = pxPort,
        )

        publishState()
    }

    private fun extractWifiBand(caps: NetworkCapabilities?): String {
        if (caps == null) return ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps.transportInfo as? WifiInfo ?: return ""
            val freq = info.frequency
            return when {
                freq >= 5925 -> "6 GHz"
                freq >= 4900 -> "5 GHz"
                freq in 2400..2500 -> "2.4 GHz"
                else -> ""
            }
        }
        return ""
    }

    private fun extractWifiSsid(caps: NetworkCapabilities?): String {
        if (caps == null) return ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps.transportInfo as? WifiInfo ?: return ""
            val raw = runCatching { info.ssid }.getOrNull().orEmpty().trim().removeSurrounding("\"")
            if (raw.isNotBlank() && !raw.equals("<unknown ssid>", ignoreCase = true)) {
                return raw
            }
        }
        return ""
    }

    private fun extractWifiRssi(caps: NetworkCapabilities?): Int? {
        if (caps == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sig = caps.signalStrength
            if (sig != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) return sig
            val info = caps.transportInfo as? WifiInfo
            if (info != null && info.rssi != -127) return info.rssi
        }
        return null
    }

    private fun extractPrimaryIp(lp: LinkProperties?): String {
        val addrs = lp?.linkAddresses ?: return ""
        val ipv4 = addrs.firstOrNull { !it.address.isLoopbackAddress && it.address is Inet4Address }
        val chosen = ipv4 ?: addrs.firstOrNull { !it.address.isLoopbackAddress }
        return chosen?.address?.hostAddress?.substringBefore('%').orEmpty()
    }

    private fun removeNetwork(network: Network) {
        val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) network.networkHandle else 0L
        val key = keyByNetworkHandle.remove(handle)
        if (key != null) {
            val current = discoveredByKey[key]
            if (current != null && current.netHandle == handle && !current.isRelay) {
                discoveredByKey.remove(key)
                AetherNativeBridge.removeLinkSafe(key)
                DiagnosticsCenter.log("NET_LOST", "Removed link $key")
            }
        }
        publishState()
    }

    fun updateLinkEwma(linkKeyId: String, measuredMbps: Double) {
        val cur = discoveredByKey[linkKeyId] ?: return
        val blended = (cur.ewmaMbps * 0.65) + (measuredMbps * 0.35)
        discoveredByKey[linkKeyId] = cur.copy(ewmaMbps = blended.coerceAtLeast(0.5))
    }

    fun getNetworkByKey(linkKeyId: String): LinkEndpointInfo? = discoveredByKey[linkKeyId]

    fun publishState() {
        val all = discoveredByKey.values.toList()
        val eth = all.firstOrNull { it.linkKey is LinkKey.Ethernet }?.let {
            val br = breakerRegistry[it.linkKey.id]?.currentState() ?: BreakerState.CLOSED
            it.copy(breakerState = br)
        }
        val wifi = all.firstOrNull { it.linkKey is LinkKey.Wifi }?.let {
            val br = breakerRegistry[it.linkKey.id]?.currentState() ?: BreakerState.CLOSED
            it.copy(breakerState = br)
        }
        val wifi2 = all.firstOrNull { it.linkKey is LinkKey.WifiSecondary }?.let {
            val br = breakerRegistry[it.linkKey.id]?.currentState() ?: BreakerState.CLOSED
            it.copy(breakerState = br)
        }
        val cell = all.firstOrNull { it.linkKey is LinkKey.Cellular }?.let {
            val br = breakerRegistry[it.linkKey.id]?.currentState() ?: BreakerState.CLOSED
            it.copy(breakerState = br)
        }
        val pac = listOfNotNull(eth?.pacFileUrl, wifi?.pacFileUrl, wifi2?.pacFileUrl, cell?.pacFileUrl)
            .firstOrNull()
        val restrictStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { cm.restrictBackgroundStatus }
                .getOrDefault(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
        } else {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
        }

        _state.value = NetworkMatrixState(
            wifiLink = wifi,
            wifi2Link = wifi2,
            cellularLink = cell,
            ethernetLink = eth,
            hasAnyInternet = eth != null || wifi != null || wifi2 != null || cell != null,
            cellularLeaseRequested = cellularLeaseActive,
            pacWarningUrl = pac,
            restrictBackgroundStatus = restrictStatus,
            wifi2StatusMessage = wifi2StatusMsg,
            dualStaHardwareCapable = dualStaHw,
            dualStaLocalOnlyCapable = dualStaLocal,
            supports5Ghz = band5Ghz,
            supports6Ghz = band6Ghz,
            singleStaDisplacementDetected = singleStaDisplaced,
        )
    }
}
