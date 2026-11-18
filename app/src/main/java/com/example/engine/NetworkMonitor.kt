package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.data.model.NetworkRouteMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class LinkEndpointInfo(
    val network: Network,
    val label: String,
    val downstreamKbps: Int,
    val upstreamKbps: Int,
    val isMetered: Boolean,
)

data class NetworkMatrixState(
    val wifiLink: LinkEndpointInfo? = null,
    val cellularLink: LinkEndpointInfo? = null,
    val hasAnyInternet: Boolean = false,
) {
    val isMultipathReady: Boolean
        get() = wifiLink != null && cellularLink != null

    val aggregateEstimatedMbps: Double
        get() = ((wifiLink?.downstreamKbps ?: 0) + (cellularLink?.downstreamKbps ?: 0)) / 1000.0
}

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val started = AtomicBoolean(false)

    private val _state = MutableStateFlow(NetworkMatrixState())
    val state: StateFlow<NetworkMatrixState> = _state.asStateFlow()

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshState()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshState()
        override fun onLost(network: Network) = refreshState()
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshState()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshState()
        override fun onLost(network: Network) = refreshState()
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshState()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshState()
        override fun onLost(network: Network) = refreshState()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        refreshState()
        runCatching {
            cm.registerDefaultNetworkCallback(defaultCallback)
        }
        runCatching {
            val wifiReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.requestNetwork(wifiReq, wifiCallback)
        }
        runCatching {
            val cellReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.requestNetwork(cellReq, cellCallback)
        }
    }

    fun refreshState() {
        var wifi: LinkEndpointInfo? = null
        var cell: LinkEndpointInfo? = null
        var anyInternet = false

        runCatching {
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                anyInternet = true
                val down = caps.linkDownstreamBandwidthKbps.coerceAtLeast(1000)
                val up = caps.linkUpstreamBandwidthKbps.coerceAtLeast(500)
                val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && wifi == null) {
                    wifi = LinkEndpointInfo(
                        network = net,
                        label = "wlan0 (Wi-Fi)",
                        downstreamKbps = down,
                        upstreamKbps = up,
                        isMetered = metered,
                    )
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && cell == null) {
                    cell = LinkEndpointInfo(
                        network = net,
                        label = "rmnet0 (5G/LTE)",
                        downstreamKbps = down,
                        upstreamKbps = up,
                        isMetered = metered,
                    )
                }
            }
        }

        if (!anyInternet) {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                anyInternet = true
                val down = caps.linkDownstreamBandwidthKbps.coerceAtLeast(1000)
                val up = caps.linkUpstreamBandwidthKbps.coerceAtLeast(500)
                val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        wifi = LinkEndpointInfo(active, "wlan0 (Wi-Fi)", down, up, metered)
                    }
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        cell = LinkEndpointInfo(active, "rmnet0 (5G/LTE)", down, up, metered)
                    }
                }
            }
        }

        _state.value = NetworkMatrixState(
            wifiLink = wifi,
            cellularLink = cell,
            hasAnyInternet = anyInternet,
        )
    }

    /**
     * Earliest-Arrival Weighted Scheduler: selects Wi-Fi or Cellular Network handle
     * proportional to estimated downstream bandwidth when Multipath is active.
     */
    fun selectNetworkForWorker(
        mode: NetworkRouteMode,
        workerIndex: Int,
    ): Pair<Network?, Boolean> { // Pair(network, isCellular)
        val snap = _state.value
        return when (mode) {
            NetworkRouteMode.WIFI_ONLY -> snap.wifiLink?.network to false
            NetworkRouteMode.CELLULAR_ONLY -> snap.cellularLink?.network to true
            NetworkRouteMode.PROXY_SOCKS5 -> null to false
            NetworkRouteMode.AUTO_MULTIPATH -> {
                val w = snap.wifiLink
                val c = snap.cellularLink
                when {
                    w != null && c != null -> {
                        val total = (w.downstreamKbps + c.downstreamKbps).coerceAtLeast(1)
                        val wifiShare = ((w.downstreamKbps.toFloat() / total) * 4f).toInt().coerceIn(1, 3)
                        if (workerIndex % 4 < wifiShare) w.network to false else c.network to true
                    }
                    w != null -> w.network to false
                    c != null -> c.network to true
                    else -> null to false
                }
            }
        }
    }
}
