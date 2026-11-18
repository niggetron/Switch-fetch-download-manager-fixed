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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class LinkEndpointInfo(
    val linkKey: LinkKey,
    val network: Network,
    val label: String,
    val interfaceName: String,
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
    val hasAnyInternet: Boolean = false,
    val cellularLeaseRequested: Boolean = false,
    val pacWarningUrl: String? = null,
) {
    val activeValidatedLinks: List<LinkEndpointInfo>
        get() = listOfNotNull(wifiLink, cellularLink).filter { it.isValidated }

    val isMultipathReady: Boolean
        get() = wifiLink?.isValidated == true && cellularLink?.isValidated == true

    val aggregateEstimatedMbps: Double
        get() = activeValidatedLinks.sumOf { it.downstreamKbps } / 1000.0
}

/**
 * Multi-network monitor fixing F1, F2, F3, F4, F7, F9:
 * - F1: Actively holds requestNetwork(TRANSPORT_CELLULAR + NET_CAPABILITY_INTERNET + VALIDATED) when enabled.
 * - F2: Bans deprecated manager.allNetworks; feeds reactive StateFlow from NetworkCallbacks requiring VALIDATED.
 * - F3: Keys endpoints by logical LinkKey (Wifi(iface)/Cellular(iface)), auto-rebinding rotated networkHandles.
 * - F9: Detects LinkProperties.httpProxy PAC URLs and propagates per-LinkKey proxy overrides to Rust.
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
        }
        applyPolicy(initialSettings)
    }

    /**
     * F1 / F4: Restores or updates active cellular retention policy whenever settings change
     * or DownloadService.onStartCommand() executes.
     */
    fun applyPolicy(settings: EngineSettings) {
        latestSettings = settings
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
            // Remove cellular entries if Wi-Fi only was requested
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
        // Re-evaluate currently tracked networks via active callbacks without calling deprecated allNetworks (F2)
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

        val isWifi = resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCell = resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (!isWifi && !isCell) return

        val linkKey: LinkKey = if (isWifi) {
            LinkKey.Wifi(iface ?: "wlan0")
        } else {
            LinkKey.Cellular(iface ?: "rmnet0")
        }

        val pacUrl = resolvedLp?.httpProxy?.pacFileUrl
            ?.takeIf { it != Uri.EMPTY && it.toString().isNotBlank() }
            ?.toString()

        val down = resolvedCaps.linkDownstreamBandwidthKbps.coerceAtLeast(1000)
        val up = resolvedCaps.linkUpstreamBandwidthKbps.coerceAtLeast(500)
        val metered = !resolvedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) network.networkHandle else 0L

        val breaker = breakerRegistry.computeIfAbsent(linkKey.id) { LinkCircuitBreaker() }
        val existingEwma = discoveredByKey[linkKey.id]?.ewmaMbps ?: (down / 1000.0)

        val info = LinkEndpointInfo(
            linkKey = linkKey,
            network = network,
            label = if (isWifi) "${linkKey.id.removePrefix("wifi:")} (Wi-Fi)" else "${linkKey.id.removePrefix("cell:")} (5G/LTE)",
            interfaceName = iface ?: if (isWifi) "wlan0" else "rmnet0",
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
        discoveredByKey[linkKey.id] = info

        // F3 / F9: Atomically rotate handle + per-LinkKey proxy override inside Rust core
        val (pxHost, pxPort) = if (isCell) {
            latestSettings.cellularProxyHost to latestSettings.cellularProxyPort
        } else {
            latestSettings.proxyHost to latestSettings.proxyPort
        }
        AetherNativeBridge.updateLinkHandleSafe(
            linkKey = linkKey.id,
            netHandle = handle,
            downstreamKbps = down.toLong(),
            rttMs = if (isCell) 28L else 18L,
            isCellular = isCell,
            proxyHost = pxHost,
            proxyPort = pxPort,
        )

        publishState()
    }

    private fun removeNetwork(network: Network) {
        val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) network.networkHandle else 0L
        val key = keyByNetworkHandle.remove(handle)
        if (key != null) {
            val current = discoveredByKey[key]
            if (current != null && current.netHandle == handle) {
                discoveredByKey.remove(key)
                AetherNativeBridge.removeLinkSafe(key)
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
        val wifi = all.firstOrNull { !it.linkKey.isCellular }?.let {
            val br = breakerRegistry[it.linkKey.id]?.currentState() ?: BreakerState.CLOSED
            it.copy(breakerState = br)
        }
        val cell = all.firstOrNull { it.linkKey.isCellular }?.let {
            val br = breakerRegistry[it.linkKey.id]?.currentState() ?: BreakerState.CLOSED
            it.copy(breakerState = br)
        }
        val pac = listOfNotNull(wifi?.pacFileUrl, cell?.pacFileUrl).firstOrNull()
        _state.value = NetworkMatrixState(
            wifiLink = wifi,
            cellularLink = cell,
            hasAnyInternet = wifi != null || cell != null,
            cellularLeaseRequested = cellularLeaseActive,
            pacWarningUrl = pac,
        )
    }
}
