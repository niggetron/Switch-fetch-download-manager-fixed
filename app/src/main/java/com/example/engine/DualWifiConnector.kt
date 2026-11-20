package com.example.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import androidx.core.content.ContextCompat
import com.example.data.model.WifiBandPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class NearbyWifiNetwork(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val rssiDbm: Int,
    val bandLabel: String,
    val isSecured: Boolean,
    val isCurrentlyConnected: Boolean = false,
    val sourceTag: String = "Android",
)

data class DualWifiCapabilityReport(
    val dualStaMultiInternet: Boolean = false,
    val dualStaLocalOnly: Boolean = false,
    val supports5Ghz: Boolean = false,
    val supports6Ghz: Boolean = false,
    val recommendation: String = "Probing Wi-Fi chipset…",
)

/**
 * In-App Dual-Band Wi-Fi 2 Connector & Auto Wi-Fi Picker (v9.1):
 * 1. Auto-fetches active connected Wi-Fi 1 SSID + nearby Wi-Fi networks via Android `WifiManager`
 *    AND unthrottled Shizuku `cmd wifi list-scan-results` (when authorized) — zero manual SSID typing!
 * 2. Probes real chipset capabilities (`isStaConcurrencyForMultiInternetSupported` API 33+,
 *    `isStaConcurrencyForLocalOnlyConnectionsSupported` API 31+, 5 GHz / 6 GHz support).
 * 3. Connects directly to a selected 2nd Wi-Fi SSID/Band from inside the app via `WifiNetworkSpecifier`
 *    with Single-STA displacement detection & 1-tap rollback.
 * 4. Provides zero-root Companion Wi-Fi 2 Relay (SOCKS5h / HTTP proxy over a 2nd phone or dual-WAN router)
 *    for devices whose radio chip only supports Single-STA concurrency.
 */
class DualWifiConnector(
    private val context: Context,
    private val networkMonitor: NetworkMonitor,
    val shizukuHelper: ShizukuWifiHelper = ShizukuWifiHelper(context),
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _capabilities = MutableStateFlow(DualWifiCapabilityReport())
    val capabilities: StateFlow<DualWifiCapabilityReport> = _capabilities.asStateFlow()

    private val _nearbyNetworks = MutableStateFlow<List<NearbyWifiNetwork>>(emptyList())
    val nearbyNetworks: StateFlow<List<NearbyWifiNetwork>> = _nearbyNetworks.asStateFlow()

    private val _connectedWifi1Ssid = MutableStateFlow("")
    val connectedWifi1Ssid: StateFlow<String> = _connectedWifi1Ssid.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private var activeWifi2Callback: ConnectivityManager.NetworkCallback? = null

    fun probeCapabilities(): DualWifiCapabilityReport {
        shizukuHelper.refreshStatus()
        refreshConnectedWifi1Ssid()
        val wm = wifiManager
        val supports5 = runCatching { wm?.is5GHzBandSupported == true }.getOrDefault(false)
        val supports6 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm?.is6GHzBandSupported == true }.getOrDefault(false)
        } else {
            false
        }
        val dualLocal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { wm?.isStaConcurrencyForLocalOnlyConnectionsSupported == true }.getOrDefault(false)
        } else {
            false
        }
        val dualMulti = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val m = WifiManager::class.java.getMethod("isStaConcurrencyForMultiInternetSupported")
                (m.invoke(wm) as? Boolean) == true
            }.getOrDefault(false)
        } else {
            false
        }

        val recommendation = when {
            dualMulti -> "Hardware Dual-STA Multi-Internet supported! Tap any Wi-Fi below to bond Wi-Fi 1 + Wi-Fi 2 + 5G + USB simultaneously."
            dualLocal -> "Chipset supports Local-Only Dual-STA. Select a Wi-Fi below to test Direct Wi-Fi 2, or use Companion Relay / Shizuku boost."
            else -> "Single-STA Wi-Fi radio detected. You can still test Direct Wi-Fi 2 below (with 1-tap rollback) or use Companion Wi-Fi 2 Relay / USB Tether!"
        }

        val report = DualWifiCapabilityReport(
            dualStaMultiInternet = dualMulti,
            dualStaLocalOnly = dualLocal,
            supports5Ghz = supports5,
            supports6Ghz = supports6,
            recommendation = recommendation,
        )
        _capabilities.value = report
        networkMonitor.updateDualWifiCapabilities(
            dualStaMultiInternet = dualMulti,
            dualStaLocalOnly = dualLocal,
            supports5Ghz = supports5,
            supports6Ghz = supports6,
        )
        return report
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun refreshConnectedWifi1Ssid(): String {
        val fromMonitor = networkMonitor.state.value.wifiLink?.ssid.orEmpty()
        if (fromMonitor.isNotBlank() && fromMonitor != "<unknown ssid>") {
            _connectedWifi1Ssid.value = fromMonitor
            return fromMonitor
        }
        val raw = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull().orEmpty()
        val cleaned = raw.trim().removeSurrounding("\"")
        val resolved = if (cleaned.equals("<unknown ssid>", ignoreCase = true)) "" else cleaned
        _connectedWifi1Ssid.value = resolved
        return resolved
    }

    fun hasWifiScanPermission(): Boolean {
        val wifiState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_WIFI_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!wifiState) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun scanNearbyNetworks(): List<NearbyWifiNetwork> {
        val connectedSsid = refreshConnectedWifi1Ssid()
        val wm = wifiManager ?: return emptyList()
        if (!hasWifiScanPermission()) return emptyList()
        runCatching { wm.startScan() }
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()
        val parsed = results
            .filter { !it.SSID.isNullOrBlank() }
            .groupBy { it.SSID }
            .map { (ssid, entries) ->
                val strongest = entries.maxByOrNull { it.level } ?: entries.first()
                val freq = strongest.frequency
                val band = when {
                    freq >= 5925 -> "6 GHz"
                    freq >= 4900 -> "5 GHz"
                    freq in 2400..2500 -> "2.4 GHz"
                    else -> "Wi-Fi"
                }
                val caps = strongest.capabilities.orEmpty()
                NearbyWifiNetwork(
                    ssid = ssid,
                    bssid = strongest.BSSID.orEmpty(),
                    frequencyMhz = freq,
                    rssiDbm = strongest.level,
                    bandLabel = band,
                    isSecured = caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE"),
                    isCurrentlyConnected = connectedSsid.isNotBlank() && ssid.equals(connectedSsid, ignoreCase = true),
                    sourceTag = "Android",
                )
            }
            .sortedWith(
                compareByDescending<NearbyWifiNetwork> { it.isCurrentlyConnected }
                    .thenByDescending { it.rssiDbm },
            )
        _nearbyNetworks.value = parsed
        return parsed
    }

    /**
     * Async auto-scan merging standard Android `WifiManager.scanResults` + Shizuku unthrottled scan
     * (if authorized) while driving [isScanning] so the UI displays smooth skeleton shimmer rows.
     */
    suspend fun scanNearbyNetworksAsync(): List<NearbyWifiNetwork> = withContext(Dispatchers.IO) {
        _isScanning.value = true
        try {
            val connectedSsid = refreshConnectedWifi1Ssid()
            val androidList = if (hasWifiScanPermission()) scanNearbyNetworks() else emptyList()
            val shizukuList = shizukuHelper.scanWifiViaShizuku(connectedSsid)

            val merged = (shizukuList + androidList)
                .groupBy { it.ssid }
                .map { (_, items) ->
                    val best = items.maxByOrNull { it.rssiDbm } ?: items.first()
                    best.copy(
                        isCurrentlyConnected = items.any { it.isCurrentlyConnected } ||
                            (connectedSsid.isNotBlank() && best.ssid.equals(connectedSsid, ignoreCase = true)),
                    )
                }
                .sortedWith(
                    compareByDescending<NearbyWifiNetwork> { it.isCurrentlyConnected }
                        .thenByDescending { it.rssiDbm },
                )
            _nearbyNetworks.value = merged
            merged
        } finally {
            _isScanning.value = false
        }
    }

    /**
     * Attempts direct in-app Wi-Fi 2 join via Android's `WifiNetworkSpecifier` (API 29+).
     * Detects Single-STA displacement if Wi-Fi 1 drops when Wi-Fi 2 comes up and flags it for 1-tap rollback.
     */
    fun connectDirectWifi2(
        ssid: String,
        passphrase: String,
        bandPref: WifiBandPreference,
    ) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isBlank()) {
            networkMonitor.updateWifi2Status("Select a Wi-Fi network from the list to connect")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            networkMonitor.updateWifi2Status("Direct in-app Wi-Fi 2 requires Android 10+; use Companion Relay")
            return
        }

        disconnectDirectWifi2(silent = true)
        _isConnecting.value = true
        val hadPrimaryWifiBefore = networkMonitor.state.value.wifiLink != null
        val previousPrimaryHandle = networkMonitor.state.value.wifiLink?.netHandle

        val specBuilder = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(cleanSsid, PatternMatcher.PATTERN_LITERAL))

        if (passphrase.isNotBlank()) {
            specBuilder.setWpa2Passphrase(passphrase)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (bandPref) {
                WifiBandPreference.BAND_5GHZ -> runCatching { specBuilder.setBand(android.net.wifi.ScanResult.WIFI_BAND_5_GHZ) }
                WifiBandPreference.BAND_24GHZ -> runCatching { specBuilder.setBand(android.net.wifi.ScanResult.WIFI_BAND_24_GHZ) }
                WifiBandPreference.BAND_6GHZ -> runCatching { specBuilder.setBand(android.net.wifi.ScanResult.WIFI_BAND_6_GHZ) }
                WifiBandPreference.AUTO -> Unit
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isConnecting.value = false
                val currentPrimary = networkMonitor.state.value.wifiLink
                val displaced = hadPrimaryWifiBefore &&
                    (currentPrimary == null || (currentPrimary.netHandle == network.networkHandle &&
                        previousPrimaryHandle != null && previousPrimaryHandle != network.networkHandle))

                networkMonitor.registerDirectWifi2Network(network, cleanSsid)
                if (displaced) {
                    networkMonitor.updateWifi2Status(
                        message = "Single-STA radio displaced Wi-Fi 1 — tap Rollback & use Companion Relay for dual Wi-Fi!",
                        displacementDetected = true,
                    )
                }
            }

            override fun onUnavailable() {
                _isConnecting.value = false
                networkMonitor.updateWifi2Status(
                    message = "Wi-Fi 2 join cancelled or unavailable. Try Companion Relay for non-Dual-STA phones.",
                    displacementDetected = false,
                )
            }

            override fun onLost(network: Network) {
                _isConnecting.value = false
                networkMonitor.clearWifi2Links("Wi-Fi 2 ($cleanSsid) lost")
            }
        }

        activeWifi2Callback = callback
        networkMonitor.updateWifi2Status("Connecting to $cleanSsid… approve system Wi-Fi prompt")
        runCatching {
            cm.requestNetwork(request, callback, 25_000)
        }.onFailure { err ->
            _isConnecting.value = false
            networkMonitor.updateWifi2Status("Direct Wi-Fi 2 error: ${err.message}")
            DiagnosticsCenter.log("WIFI2_ERR", "Direct requestNetwork failed: ${err.message}")
        }
    }

    fun connectCompanionRelay(host: String, port: Int, isHttp: Boolean): Boolean {
        disconnectDirectWifi2(silent = true)
        return networkMonitor.registerCompanionWifi2Relay(host, port, isHttp)
    }

    fun disconnectDirectWifi2(silent: Boolean = false) {
        activeWifi2Callback?.let { cb ->
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        activeWifi2Callback = null
        _isConnecting.value = false
        if (!silent) {
            networkMonitor.clearWifi2Links("Wi-Fi 2 disconnected")
        }
    }
}
