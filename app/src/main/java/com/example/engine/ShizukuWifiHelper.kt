package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

enum class ShizukuStatus(val label: String) {
    NOT_INSTALLED("Shizuku Not Installed"),
    INSTALLED_STOPPED("Shizuku Installed (Service Stopped)"),
    PERMISSION_REQUIRED("Shizuku Ready (Grant Permission)"),
    AUTHORIZED("Shizuku Authorized (ADB Privilege Active)"),
}

data class ShizukuWifiState(
    val status: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
    val versionLabel: String = "",
    val lowLatencyModeActive: Boolean = false,
    val detectedInterfaces: List<String> = emptyList(),
    val lastShellMessage: String = "Check Shizuku for unthrottled Wi-Fi scans & low-latency radio lock",
)

/**
 * Reflection-safe Shizuku Privilege Helper (Zero-Root ADB-level Wi-Fi Toolkit):
 * - Detects installed Shizuku package (`moe.shizuku.privileged.api`) cleanly without hard binary crashes.
 * - Uses Shizuku binder API via reflection if present on classpath, plus standard Android Wi-Fi fallback.
 * - Executes unthrottled `cmd wifi list-scan-results`, `cmd wifi status`, `ip -o link show`,
 *   and optional `cmd wifi force-low-latency-mode enabled` to reduce Wi-Fi jitter dips.
 */
class ShizukuWifiHelper(private val context: Context) {

    private val _state = MutableStateFlow(ShizukuWifiState())
    val state: StateFlow<ShizukuWifiState> = _state.asStateFlow()

    fun refreshStatus(): ShizukuWifiState {
        val installed = isPackageInstalled("moe.shizuku.privileged.api")
        if (!installed) {
            val s = ShizukuWifiState(
                status = ShizukuStatus.NOT_INSTALLED,
                lastShellMessage = "Install & start Shizuku to unlock unthrottled Wi-Fi scanning and low-latency Wi-Fi mode.",
            )
            _state.value = s
            return s
        }

        val shizukuClass = runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()
        val binderAlive = runCatching {
            shizukuClass?.getMethod("pingBinder")?.invoke(null) as? Boolean
        }.getOrNull() ?: false

        if (!binderAlive) {
            val s = ShizukuWifiState(
                status = ShizukuStatus.INSTALLED_STOPPED,
                lastShellMessage = "Shizuku app detected. Open Shizuku -> Start via Wireless Debugging, then tap Re-Check.",
            )
            _state.value = s
            return s
        }

        val granted = runCatching {
            val res = shizukuClass?.getMethod("checkSelfPermission")?.invoke(null) as? Int
            res == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val version = runCatching {
            val v = shizukuClass?.getMethod("getVersion")?.invoke(null) as? Int
            if (v != null) "v$v" else ""
        }.getOrDefault("")

        val s = if (granted) {
            ShizukuWifiState(
                status = ShizukuStatus.AUTHORIZED,
                versionLabel = version,
                lowLatencyModeActive = _state.value.lowLatencyModeActive,
                detectedInterfaces = _state.value.detectedInterfaces,
                lastShellMessage = "Shizuku $version authorized — unthrottled Wi-Fi scan & low-latency boost ready.",
            )
        } else {
            ShizukuWifiState(
                status = ShizukuStatus.PERMISSION_REQUIRED,
                versionLabel = version,
                lastShellMessage = "Shizuku $version running — tap Grant Permission to unlock Wi-Fi shell controls.",
            )
        }
        _state.value = s
        return s
    }

    fun requestPermission(requestCode: Int = 9101): Boolean {
        val current = refreshStatus()
        if (current.status == ShizukuStatus.AUTHORIZED) return true
        val shizukuClass = runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()
        if (shizukuClass != null && current.status == ShizukuStatus.PERMISSION_REQUIRED) {
            return runCatching {
                shizukuClass.getMethod("requestPermission", Int::class.javaPrimitiveType)
                    .invoke(null, requestCode)
                DiagnosticsCenter.log("SHIZUKU", "Requested Shizuku runtime permission")
                true
            }.getOrDefault(false)
        }
        openShizukuAppOrStore()
        return false
    }

    fun openShizukuAppOrStore() {
        val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        val intent = launch ?: Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://shizuku.rikka.app/"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    suspend fun toggleWifiLowLatencyBoost(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cmd = if (enable) {
            arrayOf("cmd", "wifi", "force-low-latency-mode", "enabled")
        } else {
            arrayOf("cmd", "wifi", "force-low-latency-mode", "disabled")
        }
        val output = executeShizukuCommand(cmd)
        val ok = output != null
        _state.value = _state.value.copy(
            lowLatencyModeActive = if (ok) enable else _state.value.lowLatencyModeActive,
            lastShellMessage = if (ok) {
                if (enable) "Wi-Fi low-latency mode ENABLED via Shizuku" else "Wi-Fi low-latency mode disabled"
            } else {
                "Shizuku authorization needed to toggle Wi-Fi low-latency mode"
            },
        )
        ok
    }

    suspend fun scanWifiViaShizuku(connectedSsid: String): List<NearbyWifiNetwork> = withContext(Dispatchers.IO) {
        if (refreshStatus().status != ShizukuStatus.AUTHORIZED) return@withContext emptyList()
        executeShizukuCommand(arrayOf("cmd", "wifi", "start-scan"))
        val raw = executeShizukuCommand(arrayOf("cmd", "wifi", "list-scan-results")) ?: return@withContext emptyList()
        parseCmdWifiScanOutput(raw, connectedSsid)
    }

    suspend fun probeActiveInterfacesViaShizuku(): List<String> = withContext(Dispatchers.IO) {
        if (refreshStatus().status != ShizukuStatus.AUTHORIZED) return@withContext emptyList()
        val raw = executeShizukuCommand(arrayOf("ip", "-o", "link", "show", "up")) ?: return@withContext emptyList()
        val ifaces = raw.lines()
            .mapNotNull { line ->
                val parts = line.split(":")
                parts.getOrNull(1)?.trim()?.substringBefore('@')?.takeIf { it.isNotBlank() && it != "lo" }
            }
            .distinct()
        _state.value = _state.value.copy(detectedInterfaces = ifaces)
        ifaces
    }

    private fun executeShizukuCommand(cmd: Array<String>): String? {
        return runCatching {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val method = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val proc = method.invoke(null, cmd, null, null) as? Process ?: return null
            val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor()
            out
        }.getOrNull()
    }

    private fun isPackageInstalled(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    companion object {
        internal fun parseCmdWifiScanOutput(raw: String, connectedSsid: String): List<NearbyWifiNetwork> {
            val result = mutableListOf<NearbyWifiNetwork>()
            for (line in raw.lines().drop(1)) {
                val trimmed = line.trim()
                if (trimmed.isBlank()) continue
                // Format: BSSID  Frequency  RSSI  Age  SSID  [Flags]
                val cols = trimmed.split(Regex("\\s+"), limit = 6)
                if (cols.size < 5) continue
                val bssid = cols[0]
                val freq = cols[1].toIntOrNull() ?: continue
                val rssi = cols[2].toIntOrNull() ?: -75
                val rest = trimmed.substringAfter(cols[3]).trim()
                val flagIdx = rest.indexOf('[')
                val ssid = (if (flagIdx > 0) rest.substring(0, flagIdx) else rest).trim()
                val flags = if (flagIdx >= 0) rest.substring(flagIdx) else ""
                if (ssid.isBlank()) continue
                val band = when {
                    freq >= 5925 -> "6 GHz"
                    freq >= 4900 -> "5 GHz"
                    freq in 2400..2500 -> "2.4 GHz"
                    else -> "Wi-Fi"
                }
                result += NearbyWifiNetwork(
                    ssid = ssid,
                    bssid = bssid,
                    frequencyMhz = freq,
                    rssiDbm = rssi,
                    bandLabel = band,
                    isSecured = flags.contains("WPA") || flags.contains("SAE") || flags.contains("WEP"),
                    isCurrentlyConnected = connectedSsid.isNotBlank() && ssid.equals(connectedSsid, ignoreCase = true),
                    sourceTag = "Shizuku",
                )
            }
            return result
                .groupBy { it.ssid }
                .map { (_, entries) -> entries.maxByOrNull { it.rssiDbm } ?: entries.first() }
                .sortedWith(compareByDescending<NearbyWifiNetwork> { it.isCurrentlyConnected }.thenByDescending { it.rssiDbm })
        }
    }
}
