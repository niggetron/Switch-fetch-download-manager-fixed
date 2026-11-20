package com.example.data.model

import com.example.ui.theme.ThemeMode
import com.example.ui.theme.UiThemeConfig

const val F2FS_SEGMENT_BYTES: Long = 4L * 1024L * 1024L // 4 MiB default adaptive BDP base
const val MEDIASTORE_DIRECT_FD_THRESHOLD_BYTES: Long = 1L * 1024L * 1024L * 1024L // 1 GiB FUSE threshold
const val MAX_CONCURRENT_WORKERS_CAP: Int = 128

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
    AUTO_MULTIPATH("Quad-Link Bond"),
    WIFI_ONLY("Wi-Fi Only"),
    CELLULAR_ONLY("5G / Cellular"),
    PROXY_SOCKS5("SOCKS5h Proxy"),
}

enum class EngineBackendPreference(val label: String, val badge: String) {
    AUTO_PREFER_RUST("Auto (Prefer Rust)", "AUTO"),
    FORCE_RUST_NATIVE("Rust libaether_core.so", "RUST"),
    FORCE_KOTLIN_OKHTTP("Kotlin OkHttp Engine", "OKHTTP"),
    FORCE_ARIA2_NEXT("aria2-next (Rayburst engine)", "ARIA2"),
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

enum class SpeedometerStyle(val label: String, val shortLabel: String, val subtitle: String) {
    SUPERBIKE_GRID(
        label = "Superbike 2×2 Cockpit",
        shortLabel = "Superbike",
        subtitle = "4 high-contrast radial dials (Wi-Fi 1, 5G, Wi-Fi 2, USB) with redline needles",
    ),
    AUTO_SPORT_DASH(
        label = "Sport Car Instrument Dash",
        shortLabel = "Sport Dash",
        subtitle = "Horizontal motorsport cluster with live RPM arc, MAX peak-hold & AVG readout",
    ),
    COMPACT_TACH_STRIP(
        label = "Racing Tachometer Strip",
        shortLabel = "Tach Strip",
        subtitle = "Slim horizontal RPM shift-light bars for maximum download list density",
    ),
    F1_TELEMETRY_HUD(
        label = "F1 Telemetry Shift HUD",
        shortLabel = "F1 HUD",
        subtitle = "Formula shift-light LED array, active-stream gear readout & live peak-hold rail",
    ),
    NEON_PLASMA_ARC(
        label = "Neon Plasma Orbit",
        shortLabel = "Plasma Arc",
        subtitle = "Concentric luminous plasma rings with pulsing neon aura & digital telemetry core",
    ),
    RAYBURST_MATRIX_BAR(
        label = "Rayburst 64-Lane Matrix",
        shortLabel = "Rayburst",
        subtitle = "64-connection striped surge bars with live emerald completion flashes",
    );

    fun next(): SpeedometerStyle = entries[(ordinal + 1) % entries.size]
}

enum class ThroughputProfile(val label: String, val subtitle: String) {
    TURBO_ANTI_DIP(
        label = "Turbo + Anti-Dip (Both)",
        subtitle = "Up to 64 sharded multi-socket lanes + live range bisection + decoupled async I/O",
    ),
    SUSTAINED_ANTI_DIP(
        label = "Sustained Anti-Dip",
        subtitle = "Prioritizes rock-steady flatline speed with aggressive stall rescue & low jitter",
    ),
    MAX_MULTI_SOCKET(
        label = "Max Multi-Socket Burst",
        subtitle = "Maximum concurrent independent sockets per link to saturate Gigabit Wi-Fi & 5G",
    ),
}

enum class WifiBandPreference(val label: String) {
    AUTO("Auto (Best Band)"),
    BAND_5GHZ("5 GHz High-Speed"),
    BAND_24GHZ("2.4 GHz Long-Range"),
    BAND_6GHZ("6 GHz Wi-Fi 6E"),
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
 * Logical Android network identity (Quad-Link v9.1):
 * Keyed by transport + interface rather than rotating raw networkHandle.
 */
sealed class LinkKey(
    val id: String,
    val isCellular: Boolean,
    val isEthernet: Boolean = false,
    val isWifiSecondary: Boolean = false,
) {
    data class Wifi(val iface: String) :
        LinkKey("wifi:$iface", isCellular = false, isEthernet = false, isWifiSecondary = false)

    data class WifiSecondary(val iface: String) :
        LinkKey("wifi2:$iface", isCellular = false, isEthernet = false, isWifiSecondary = true)

    data class Cellular(val iface: String) :
        LinkKey("cell:$iface", isCellular = true, isEthernet = false, isWifiSecondary = false)

    data class Ethernet(val iface: String) :
        LinkKey("eth:$iface", isCellular = false, isEthernet = true, isWifiSecondary = false)

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

/**
 * Remote `.zip` EOCD Central Directory entry parsed from a 64 KB HTTP Range suffix.
 */
data class RemoteZipEntry(
    val fileName: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
    val compressionMethod: Int,
    val crc32: Long,
) {
    val isStored: Boolean get() = compressionMethod == 0
    val isDeflated: Boolean get() = compressionMethod == 8
}

data class EngineSettings(
    val maxConcurrentWorkers: Int = 32,
    val segmentSizeBytes: Long = F2FS_SEGMENT_BYTES,
    val enableTailHedge: Boolean = true,
    val enableAutoSpeedUp: Boolean = true,
    val enableCasVerification: Boolean = true,
    val stagedSafPlacement: Boolean = true,
    val retainCellularOnWifi: Boolean = true,
    val forceAllLinks: Boolean = true,
    val defaultRouteMode: NetworkRouteMode = NetworkRouteMode.AUTO_MULTIPATH,
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val cellularProxyHost: String = "",
    val cellularProxyPort: Int = 1080,
    val themeMode: ThemeMode = ThemeMode.DARK_OBSIDIAN,
    val uiThemeConfig: UiThemeConfig = UiThemeConfig(),
    // Dual-engine A/B toggles & v8.0/v9.1 feature flags
    val enableRustEngine: Boolean = true,
    val enableKotlinOkHttpEngine: Boolean = true,
    val enableAria2NextEngine: Boolean = false,
    val preferredEngineBackend: EngineBackendPreference = EngineBackendPreference.AUTO_PREFER_RUST,
    val enableHttp3Quic: Boolean = true,
    val enableWreqEmulation: Boolean = true,
    val enableIoUringProbe: Boolean = false,
    val enableAria2Rpc: Boolean = false,
    val maxCellularBytesPerSession: Long = 0L, // 0 = unlimited
    val defaultBrowserProfile: BrowserProfile = BrowserProfile.CHROME_134,
    val defaultProtocolMode: ProtocolMode = ProtocolMode.AUTO_HYBRID,
    // v9.1 Quad-Link Superbike, Anti-Dip & Shizuku settings
    val speedometerStyle: SpeedometerStyle = SpeedometerStyle.SUPERBIKE_GRID,
    val throughputProfile: ThroughputProfile = ThroughputProfile.TURBO_ANTI_DIP,
    val dualWifiEnabled: Boolean = true,
    val usbBondingEnabled: Boolean = true,
    val enableShizukuWifiBoost: Boolean = true,
    val wifi2Ssid: String = "",
    val wifi2BandPreference: WifiBandPreference = WifiBandPreference.BAND_5GHZ,
    val wifi2RelayHost: String = "",
    val wifi2RelayPort: Int = 1080,
    val wifi2RelayIsHttp: Boolean = false,
) {
    fun toNativeFeatureMask(): Int {
        var mask = 0
        if ((retainCellularOnWifi || forceAllLinks) && defaultRouteMode != NetworkRouteMode.WIFI_ONLY) {
            mask = mask or (1 shl 0)
        }
        if (enableHttp3Quic) mask = mask or (1 shl 1)
        if (enableWreqEmulation) mask = mask or (1 shl 2)
        if (enableIoUringProbe) mask = mask or (1 shl 3)
        if (enableAria2Rpc) mask = mask or (1 shl 4)
        if (enableTailHedge) mask = mask or (1 shl 5)
        if (enableAutoSpeedUp) mask = mask or (1 shl 6)
        if (throughputProfile == ThroughputProfile.TURBO_ANTI_DIP ||
            throughputProfile == ThroughputProfile.MAX_MULTI_SOCKET
        ) {
            mask = mask or (1 shl 7)
        }
        if (throughputProfile == ThroughputProfile.TURBO_ANTI_DIP ||
            throughputProfile == ThroughputProfile.SUSTAINED_ANTI_DIP
        ) {
            mask = mask or (1 shl 8)
        }
        if (forceAllLinks) mask = mask or (1 shl 9)
        return mask
    }
}

data class NewDownloadRequest(
    val url: String,
    val mirrorUrls: List<String> = emptyList(),
    val customFileName: String? = null,
    val expectedSha256: String? = null,
    val routeMode: NetworkRouteMode = NetworkRouteMode.AUTO_MULTIPATH,
    val workers: Int = 32,
    val safUriString: String? = null,
    val backendOverride: EngineBackendPreference? = null,
    val protocolMode: ProtocolMode = ProtocolMode.AUTO_HYBRID,
    val browserProfile: BrowserProfile = BrowserProfile.CHROME_134,
    val maxCellularBytes: Long = 0L,
    val selectedZipEntry: RemoteZipEntry? = null,
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
            title = "Cloudflare 25 MB Multi-Extent",
            subtitle = "Adaptive BDP extents · Ideal for Quad-Link Wi-Fi + 5G + USB bonding",
            url = "https://speed.cloudflare.com/__down?bytes=26214400",
            fileName = "cloudflare_25mb_extent.bin",
            approximateSizeLabel = "25.0 MB",
        ),
        PublicTestEndpoint(
            title = "Cloudflare 100 MB High-Speed Test",
            subtitle = "Sustained throughput stress test for 64-stream Anti-Dip & 4-link speedometers",
            url = "https://speed.cloudflare.com/__down?bytes=104857600",
            fileName = "cloudflare_100mb_turbo.bin",
            approximateSizeLabel = "100.0 MB",
        ),
        PublicTestEndpoint(
            title = "Cloudflare 10 MB Stream",
            subtitle = "Range-capable edge CDN binary stream",
            url = "https://speed.cloudflare.com/__down?bytes=10485760",
            fileName = "cloudflare_10mb_probe.bin",
            approximateSizeLabel = "10.0 MB",
        ),
        PublicTestEndpoint(
            title = "IETF RFC 9000 (QUIC Spec)",
            subtitle = "Official QUIC transport protocol specification TXT",
            url = "https://www.rfc-editor.org/rfc/rfc9000.txt",
            fileName = "rfc9000_quic_protocol.txt",
            approximateSizeLabel = "~500 KB",
        ),
    )
}
