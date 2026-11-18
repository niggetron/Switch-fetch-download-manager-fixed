package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
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
)

data class NetworkMatrixState(
    val wifiLink: LinkEndpointInfo? = null,
    val cellularLink: LinkEndpointInfo? = null,
    val ethernetLink: LinkEndpointInfo? = null,
    val hasAnyInternet: Boolean = false,
    val cellularLeaseRequested: Boolean = false,
    val pacWarningUrl: String? = null,
    val restrictBackgroundStatus: Int = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
) {
    val activeValidatedLinks: List<LinkEndpointInfo>
        get() = listOfNotNull(ethernetLink, wifiLink, cellularLink).filter { it.isValidated }

    val isMultipathReady: Boolean
        get() = activeValidatedLinks.size >= 2

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
 * Multi-network monitor fixing F1–F10 + v8.0 hot-plugging:
 * - F1: Holds requestNetwork(TRANSPORT_CELLULAR + NET_CAPABILITY_INTERNET) when enabled.
 * - F2: Reactive StateFlow from NetworkCallbacks requiring VALIDATED (excludes captive portals).
 * - F3: Keys endpoints by logical LinkKey (Wifi/Cellular/Ethernet), auto-rebinding rotated handles & local IP.
 * - Dynamic Mid-Download Hot-Plugging: registers USB-C Ethernet and late links dynamically.
 * - F9: Detects LinkProperties.httpProxy PAC URLs and propagates per-LinkKey SOCKS5h overrides + localIp to Rust.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val started = AtomicBoolean(false)

    private val discoveredByKey = ConcurrentHashMap<String, LinkEndpointInfo>()
    private val keyByNetworkHandle = ConcurrentHashMap<Long, String>()

    @Volatile
    private var cellularLeaseActive = false

    @Volatile
    private var latestSettings: EngineSettings = EngineSettings()

    private val _state = MutableStateFlow(NetworkMatrixState())
    val state: StateFlow<NetworkMatrixState> = _state.asStateFlow()

    val breakerRegistry = ConcurrentHashMap<String, LinkCircuitBreaker>()

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetwork(network, caps = caps)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetwork(network, lp = linkProperties)
        override fun onLost(network: Network) = removeNetwork(network)
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetwork(network, caps = caps)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetwork(network, lp = linkProperties)
        override fun onLost(network: Network) = removeNetwork(network)
    }

    private val ethernetCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetwork(network, caps = caps)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetwork(network, lp = linkProperties)
        override fun onLost(network: Network) = removeNetwork(network)
    }

    private val cellularRequestCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetwork(network, caps = caps)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetwork(network, lp = linkProperties)
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
        }
        applyPolicy(initialSettings)
    }

    /**
     * F1 / F4: Restores or updates active cellular retention policy & runtime feature mask.
     */
    fun applyPolicy(settings: EngineSettings) {
        latestSettings = settings
        AetherNativeBridge.setFeatureFlagsSafe(settings.toNativeFeatureMask())

        val wantsCellular = settings.retainCellularOnWifi &&
            settings.defaultRouteMode != NetworkRouteMode.WIFI_ONLY
        if (wantsCellular && !cellularLeaseActive) {
            runCatching {
                val cellReq = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.requestNetwork(cellReq, cellularRequestCallback)
                cellularLeaseActive = true
            }
        } else if (!wantsCellular && cellularLeaseActive) {
            runCatching { cm.unregisterNetworkCallback(cellularRequestCallback) }
            cellularLeaseActive = false
            discoveredByKey.keys.filter { it.startsWith("cell:") }.forEach { key ->
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

    fun refreshState() {
        val active = cm.activeNetwork
        if (active != null) {
            updateNetwork(active)
        } else {
            publishState()
        }
    }

    private fun updateNetwork(
        network: Network,
        caps: NetworkCapabilities? = null,
        lp: LinkProperties? = null,
    ) {
        val resolvedCaps = caps ?: runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return
        if (!resolvedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return

        // F2: Require NET_CAPABILITY_VALIDATED to exclude captive portals / offline routers
        val isValidated = resolvedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!isValidated) {
            removeNetwork(network)
            return
        }

        val resolvedLp = lp ?: runCatching { cm.getLinkProperties(network) }.getOrNull()
        val iface = resolvedLp?.interfaceName?.takeIf { it.isNotBlank() }

        val isEth = resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isWifi = resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCell = resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (!isEth && !isWifi && !isCell) return

        val linkKey: LinkKey = when {
            isEth -> LinkKey.Ethernet(iface ?: "eth0")
            isWifi -> LinkKey.Wifi(iface ?: "wlan0")
            else -> LinkKey.Cellular(iface ?: "rmnet0")
        }

        val localIp = extractPrimaryIp(resolvedLp)
        val pacUrl = resolvedLp?.httpProxy?.pacFileUrl
            ?.takeIf { it != Uri.EMPTY && it.toString().isNotBlank() }
            ?.toString()

        val down = resolvedCaps.linkDownstreamBandwidthKbps.coerceAtLeast(1000)
        val up = resolvedCaps.linkUpstreamBandwidthKbps.coerceAtLeast(500)
        val metered = !resolvedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) network.networkHandle else 0L

        val breaker = breakerRegistry.computeIfAbsent(linkKey.id) { LinkCircuitBreaker() }
        val existingEwma = discoveredByKey[linkKey.id]?.ewmaMbps ?: (down / 1000.0)

        val label = when (linkKey) {
            is LinkKey.Ethernet -> "${linkKey.iface} (Ethernet)"
            is LinkKey.Wifi -> "${linkKey.iface} (Wi-Fi)"
            is LinkKey.Cellular -> "${linkKey.iface} (5G/LTE)"
        }

        val info = LinkEndpointInfo(
            linkKey = linkKey,
            network = network,
            label = label,
            interfaceName = iface ?: when {
                isEth -> "eth0"
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
        )

        keyByNetworkHandle[handle] = linkKey.id
        val isNew = discoveredByKey.put(linkKey.id, info) == null
        if (isNew) {
            DiagnosticsCenter.log("HOTPLUG", "Interface validated: ${linkKey.id} ip=$localIp down=${down}kbps")
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
                isEth -> 10L
                isCell -> 28L
                else -> 18L
            },
            isCellular = isCell,
            localIp = localIp,
            proxyHost = pxHost,
            proxyPort = pxPort,
        )

        publishState()
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
            if (current != null && current.netHandle == handle) {
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
        val cell = all.firstOrNull { it.linkKey is LinkKey.Cellular }?.let {
            val br = breakerRegistry[it.linkKey.id]?.currentState() ?: BreakerState.CLOSED
            it.copy(breakerState = br)
        }
        val pac = listOfNotNull(eth?.pacFileUrl, wifi?.pacFileUrl, cell?.pacFileUrl).firstOrNull()
        val restrictStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { cm.restrictBackgroundStatus }
                .getOrDefault(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
        } else {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
        }

        _state.value = NetworkMatrixState(
            wifiLink = wifi,
            cellularLink = cell,
            ethernetLink = eth,
            hasAnyInternet = eth != null || wifi != null || cell != null,
            cellularLeaseRequested = cellularLeaseActive,
            pacWarningUrl = pac,
            restrictBackgroundStatus = restrictStatus,
        )
    }
}
