package com.example.data.model

import com.example.ui.theme.ThemeMode

const val F2FS_SEGMENT_BYTES: Long = 4L * 1024L * 1024L // 4 MiB default adaptive BDP base
const val MEDIASTORE_DIRECT_FD_THRESHOLD_BYTES: Long = 1L * 1024L * 1024L * 1024L // 1 GiB FUSE threshold

enum class DownloadStatus {
    QUEUED,
    PROBING_0RTT,
    STREAMING,
    WORK_STEALING,
    VERIFYING_BAO,
    PAUSED,
    COMPLETED,
    LINK_MIGRATE_R1,
    TOKEN_EXPIRED_R2,
    QUOTA_GUARD_R3,
    ENOSPC_R4,
    SAF_EBADF_R5,
    FAILED,
    CANCELLED;

    val isActive: Boolean
        get() = this == QUEUED || this == PROBING_0RTT || this == STREAMING ||
            this == WORK_STEALING || this == VERIFYING_BAO || this == LINK_MIGRATE_R1

    val isIssue: Boolean
        get() = this == TOKEN_EXPIRED_R2 || this == QUOTA_GUARD_R3 ||
            this == ENOSPC_R4 || this == SAF_EBADF_R5 || this == FAILED
}

enum class NetworkRouteMode(val label: String) {
    AUTO_MULTIPATH("Wi-Fi + 5G Bond"),
    WIFI_ONLY("Wi-Fi Only"),
    CELLULAR_ONLY("5G / Cellular"),
    PROXY_SOCKS5("SOCKS5h Proxy"),
}

enum class EngineBackendPreference(val label: String, val badge: String) {
    AUTO_PREFER_RUST("Auto (Prefer Rust)", "AUTO"),
    FORCE_RUST_NATIVE("Rust libaether_core.so", "RUST"),
    FORCE_KOTLIN_OKHTTP("Kotlin OkHttp Engine", "OKHTTP"),
}

enum class BrowserProfile(val wireId: String, val label: String) {
    CHROME_134("chrome134", "Chrome 134"),
    CHROME_131("chrome131", "Chrome 131"),
    OKHTTP_5("okhttp5", "OkHttp 5"),
}

enum class ProtocolMode(val wireId: String, val label: String) {
    AUTO_HYBRID("auto", "Auto H3 + wreq H2"),
    WREQ_H2("wreq_h2", "wreq BoringSSL H2"),
    H3_QUIC_0RTT("h3_quic", "HTTP/3 QUIC 0-RTT"),
}

enum class SegmentState(val code: Char) {
    PENDING('0'),
    COMMITTED('1'),
    VERIFIED('2'),
    ACTIVE_WIFI('3'),
    ACTIVE_5G('4'),
    STOLEN_HEDGE('5'),
    FAILED('6');

    companion object {
        fun fromCode(ch: Char): SegmentState =
            entries.firstOrNull { it.code == ch } ?: PENDING
    }
}

enum class FileCategory(val label: String) {
    ALL("All"),
    DOCUMENTS("Docs"),
    ARCHIVES("Archives"),
    MEDIA("Media"),
    PACKAGES("Binaries"),
}

enum class TransferFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    PAUSED("Paused"),
    COMPLETED("Done"),
    ISSUES("Recovery"),
}

/**
 * Logical Android network identity (Fix F3 + Hot-Plug USB-C Ethernet):
 * Keyed by transport + interface rather than rotating raw networkHandle.
 */
sealed class LinkKey(val id: String, val isCellular: Boolean, val isEthernet: Boolean = false) {
    data class Wifi(val iface: String) : LinkKey("wifi:$iface", isCellular = false, isEthernet = false)
    data class Cellular(val iface: String) : LinkKey("cell:$iface", isCellular = true, isEthernet = false)
    data class Ethernet(val iface: String) : LinkKey("eth:$iface", isCellular = false, isEthernet = true)

    override fun toString(): String = id
}

enum class BreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN,
}

data class ActiveConnectionInfo(
    val connId: String,
    val taskId: String,
    val url: String,
    val linkKeyId: String,
    val protocol: String,
    val socketState: String,
    val ttfbMs: Long,
    val bytesReceived: Long,
    val speedBps: Long,
    val rangeStart: Long,
    val rangeEnd: Long,
)

data class EngineSettings(
    val maxConcurrentWorkers: Int = 4,
    val segmentSizeBytes: Long = F2FS_SEGMENT_BYTES,
    val enableTailHedge: Boolean = true,
    val enableAutoSpeedUp: Boolean = true,
    val enableCasVerification: Boolean = true,
    val stagedSafPlacement: Boolean = true,
    val retainCellularOnWifi: Boolean = true,
    val defaultRouteMode: NetworkRouteMode = NetworkRouteMode.AUTO_MULTIPATH,
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val cellularProxyHost: String = "",
    val cellularProxyPort: Int = 1080,
    val themeMode: ThemeMode = ThemeMode.DARK_OBSIDIAN,
    // Dual-engine A/B toggles & v8.0 feature flags
    val enableRustEngine: Boolean = true,
    val enableKotlinOkHttpEngine: Boolean = true,
    val preferredEngineBackend: EngineBackendPreference = EngineBackendPreference.AUTO_PREFER_RUST,
    val enableHttp3Quic: Boolean = true,
    val enableWreqEmulation: Boolean = true,
    val enableIoUringProbe: Boolean = false,
    val enableAria2Rpc: Boolean = false,
    val maxCellularBytesPerSession: Long = 0L, // 0 = unlimited
    val defaultBrowserProfile: BrowserProfile = BrowserProfile.CHROME_134,
    val defaultProtocolMode: ProtocolMode = ProtocolMode.AUTO_HYBRID,
) {
    fun toNativeFeatureMask(): Int {
        var mask = 0
        if (retainCellularOnWifi && defaultRouteMode != NetworkRouteMode.WIFI_ONLY) mask = mask or (1 shl 0)
        if (enableHttp3Quic) mask = mask or (1 shl 1)
        if (enableWreqEmulation) mask = mask or (1 shl 2)
        if (enableIoUringProbe) mask = mask or (1 shl 3)
        if (enableAria2Rpc) mask = mask or (1 shl 4)
        if (enableTailHedge) mask = mask or (1 shl 5)
        if (enableAutoSpeedUp) mask = mask or (1 shl 6)
        return mask
    }
}

data class NewDownloadRequest(
    val url: String,
    val mirrorUrls: List<String> = emptyList(),
    val customFileName: String? = null,
    val expectedSha256: String? = null,
    val routeMode: NetworkRouteMode = NetworkRouteMode.AUTO_MULTIPATH,
    val workers: Int = 4,
    val safUriString: String? = null,
    val backendOverride: EngineBackendPreference? = null,
    val protocolMode: ProtocolMode = ProtocolMode.AUTO_HYBRID,
    val browserProfile: BrowserProfile = BrowserProfile.CHROME_134,
    val maxCellularBytes: Long = 0L,
)

data class PublicTestEndpoint(
    val title: String,
    val subtitle: String,
    val url: String,
    val fileName: String,
    val approximateSizeLabel: String,
)

object CuratedEndpoints {
    val samples = listOf(
        PublicTestEndpoint(
            title = "Cloudflare 10 MB Stream",
            subtitle = "Range-capable edge CDN binary stream",
            url = "https://speed.cloudflare.com/__down?bytes=10485760",
            fileName = "cloudflare_10mb_probe.bin",
            approximateSizeLabel = "10.0 MB",
        ),
        PublicTestEndpoint(
            title = "Cloudflare 25 MB Multi-Extent",
            subtitle = "Adaptive BDP extents · Ideal for Wi-Fi + 5G bonding",
            url = "https://speed.cloudflare.com/__down?bytes=26214400",
            fileName = "cloudflare_25mb_extent.bin",
            approximateSizeLabel = "25.0 MB",
        ),
        PublicTestEndpoint(
            title = "IETF RFC 9000 (QUIC Spec)",
            subtitle = "Official QUIC transport protocol specification TXT",
            url = "https://www.rfc-editor.org/rfc/rfc9000.txt",
            fileName = "rfc9000_quic_protocol.txt",
            approximateSizeLabel = "~500 KB",
        ),
        PublicTestEndpoint(
            title = "IETF RFC 9114 (HTTP/3)",
            subtitle = "Official HTTP/3 over QUIC specification TXT",
            url = "https://www.rfc-editor.org/rfc/rfc9114.txt",
            fileName = "rfc9114_http3.txt",
            approximateSizeLabel = "~230 KB",
        ),
    )
}
