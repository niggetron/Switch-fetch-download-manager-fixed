package com.example.engine

import com.example.data.model.ActiveConnectionInfo
import org.json.JSONArray
import org.json.JSONObject

data class NativeTaskOutcome(
    val taskId: String,
    val state: String,
    val fileName: String,
    val mimeType: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val wifiBytes: Long,
    val wifi2Bytes: Long,
    val cellBytes: Long,
    val usbBytes: Long,
    val sha256Hex: String,
    val ttfbMs: Long,
    val errorMessage: String,
)

/**
 * Tier-2 JNI Control Bridge to `libaether_core.so` (Rust `aether-core` v9.0).
 */
object AetherNativeBridge {
    @Volatile
    var loadError: String? = null
        private set

    val isNativeLoaded: Boolean by lazy {
        try {
            System.loadLibrary("aether_core")
            loadError = null
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    val engineBackendLabel: String
        get() = if (isNativeLoaded) {
            runCatching { nativeEngineVersion() }
                .getOrDefault("libaether_core.so v9.0 (Rust JNI Active)")
        } else {
            "Kotlin OkHttp Turbo Engine (Install GitHub Releases arm64 APK for bundled Rust libaether_core.so)"
        }

    fun updateLinkHandleSafe(
        linkKey: String,
        netHandle: Long,
        downstreamKbps: Long,
        rttMs: Long,
        isCellular: Boolean,
        localIp: String,
        proxyHost: String,
        proxyPort: Int,
    ) {
        if (!isNativeLoaded) return
        runCatching {
            nativeUpdateLinkHandle(
                linkKey = linkKey,
                netHandle = netHandle,
                downstreamKbps = downstreamKbps,
                rttMs = rttMs,
                isCellular = isCellular,
                localIp = localIp,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
            )
        }
    }

    fun removeLinkSafe(linkKey: String) {
        if (!isNativeLoaded) return
        runCatching { nativeRemoveLink(linkKey) }
    }

    fun setFeatureFlagsSafe(mask: Int) {
        if (!isNativeLoaded) return
        runCatching { nativeSetFeatureFlags(mask) }
    }

    fun setThermalWorkerCapSafe(maxWorkers: Int) {
        if (!isNativeLoaded) return
        runCatching { nativeSetThermalWorkerCap(maxWorkers.coerceIn(1, 64)) }
    }

    fun startDownloadSafe(
        taskId: String,
        urls: List<String>,
        outputPath: String,
        ledgerPath: String,
        directFd: Int,
        maxWorkers: Int,
        maxCellularBytes: Long,
        browserProfile: String,
        protocolMode: String,
    ): Boolean {
        if (!isNativeLoaded) return false
        return runCatching {
            nativeStartDownload(
                taskId = taskId,
                urlsNewlineSep = urls.joinToString("\n"),
                outputPath = outputPath,
                ledgerPath = ledgerPath,
                directFd = directFd,
                maxWorkers = maxWorkers.coerceIn(1, 64),
                maxCellularBytes = maxCellularBytes,
                browserProfile = browserProfile,
                protocolMode = protocolMode,
            )
            true
        }.getOrDefault(false)
    }

    fun pauseDownloadSafe(taskId: String) {
        if (!isNativeLoaded) return
        runCatching { nativePauseDownload(taskId) }
    }

    fun cancelDownloadSafe(taskId: String) {
        if (!isNativeLoaded) return
        runCatching { nativeCancelDownload(taskId) }
    }

    fun updateTaskUrlsSafe(taskId: String, urls: List<String>) {
        if (!isNativeLoaded) return
        runCatching { nativeUpdateTaskUrls(taskId, urls.joinToString("\n")) }
    }

    fun taskOutcomeSafe(taskId: String): NativeTaskOutcome? {
        if (!isNativeLoaded) return null
        val raw = runCatching { nativeTaskOutcomeJson(taskId) }.getOrNull() ?: return null
        if (raw.isBlank() || raw == "{}") return null
        return runCatching {
            val obj = JSONObject(raw)
            if (!obj.has("state")) return null
            NativeTaskOutcome(
                taskId = obj.optString("task_id", taskId),
                state = obj.optString("state", ""),
                fileName = obj.optString("file_name", ""),
                mimeType = obj.optString("mime_type", "application/octet-stream"),
                downloadedBytes = obj.optLong("downloaded_bytes", 0L),
                totalBytes = obj.optLong("total_bytes", 0L),
                wifiBytes = obj.optLong("wifi_bytes", 0L),
                wifi2Bytes = obj.optLong("wifi2_bytes", 0L),
                cellBytes = obj.optLong("cell_bytes", 0L),
                usbBytes = obj.optLong("usb_bytes", 0L),
                sha256Hex = obj.optString("sha256_hex", ""),
                ttfbMs = obj.optLong("ttfb_ms", 0L),
                errorMessage = obj.optString("error_message", ""),
            )
        }.getOrNull()
    }

    fun connectionsListSafe(): List<ActiveConnectionInfo> {
        if (!isNativeLoaded) return emptyList()
        val raw = runCatching { nativeConnectionsJson() }.getOrNull() ?: return emptyList()
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        ActiveConnectionInfo(
                            connId = o.optString("conn_id", "conn-$i"),
                            taskId = o.optString("task_id", ""),
                            url = o.optString("url", ""),
                            linkKeyId = o.optString("link_key_id", "auto"),
                            protocol = o.optString("protocol", "H2/wreq"),
                            socketState = o.optString("socket_state", "Streaming"),
                            ttfbMs = o.optLong("ttfb_ms", 0L),
                            bytesReceived = o.optLong("bytes_received", 0L),
                            speedBps = o.optLong("speed_bps", 0L),
                            rangeStart = o.optLong("range_start", 0L),
                            rangeEnd = o.optLong("range_end", 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun diagnosticsLogSafe(): String {
        if (!isNativeLoaded) return ""
        return runCatching { nativeDiagnosticsLog() }.getOrDefault("")
    }

    fun startAria2RpcSafe(): String {
        if (!isNativeLoaded) return ""
        return runCatching { nativeStartAria2Rpc() }.getOrDefault("")
    }

    fun stopAria2RpcSafe() {
        if (!isNativeLoaded) return
        runCatching { nativeStopAria2Rpc() }
    }

    fun pauseQuotaGuardSafe() {
        if (!isNativeLoaded) return
        runCatching { nativePauseQuotaGuard() }
    }

    fun slabStatsSafe(): LongArray? {
        if (!isNativeLoaded) return null
        return runCatching { nativeSlabStats() }.getOrNull()
    }

    fun copyStagingToFdSafe(stagingPath: String, destFd: Int): Boolean {
        if (!isNativeLoaded) return false
        return runCatching { nativeCopyStagingToFd(stagingPath, destFd) }.getOrDefault(false)
    }

    fun createTelemetryMemfdSafe(): Int {
        if (!isNativeLoaded) return -1
        return runCatching { nativeCreateTelemetryMemfd() }.getOrDefault(-1)
    }

    fun warmup() {
        if (isNativeLoaded) runCatching { nativeWarmup() }
    }

    external fun nativeWarmup()
    external fun nativeEngineVersion(): String
    external fun nativeCreateTelemetryMemfd(): Int
    external fun nativeUpdateLinkHandle(
        linkKey: String,
        netHandle: Long,
        downstreamKbps: Long,
        rttMs: Long,
        isCellular: Boolean,
        localIp: String,
        proxyHost: String,
        proxyPort: Int,
    )
    external fun nativeRemoveLink(linkKey: String)
    external fun nativeSetFeatureFlags(mask: Int)
    external fun nativeSetThermalWorkerCap(maxWorkers: Int)
    external fun nativeStartDownload(
        taskId: String,
        urlsNewlineSep: String,
        outputPath: String,
        ledgerPath: String,
        directFd: Int,
        maxWorkers: Int,
        maxCellularBytes: Long,
        browserProfile: String,
        protocolMode: String,
    )
    external fun nativePauseDownload(taskId: String)
    external fun nativeCancelDownload(taskId: String)
    external fun nativeUpdateTaskUrls(taskId: String, urlsNewlineSep: String)
    external fun nativeTaskOutcomeJson(taskId: String): String
    external fun nativeConnectionsJson(): String
    external fun nativeDiagnosticsLog(): String
    external fun nativeStartAria2Rpc(): String
    external fun nativeStopAria2Rpc()
    external fun nativeSlabStats(): LongArray
    external fun nativeCopyStagingToFd(stagingPath: String, destFd: Int): Boolean
    external fun nativePauseQuotaGuard()
    external fun nativeBlake3Hex(data: ByteArray): String
    external fun nativeSha256Hex(data: ByteArray): String
}
