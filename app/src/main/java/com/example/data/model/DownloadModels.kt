package com.example.data.model

import com.example.ui.theme.ThemeMode

const val F2FS_SEGMENT_BYTES: Long = 2L * 1024L * 1024L // 2 MB = 0x200000

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
    AUTO_MULTIPATH("Multipath Auto"),
    WIFI_ONLY("Wi-Fi Only"),
    CELLULAR_ONLY("5G / Cellular"),
    PROXY_SOCKS5("SOCKS5h Proxy"),
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

data class EngineSettings(
    val maxConcurrentWorkers: Int = 4,
    val segmentSizeBytes: Long = F2FS_SEGMENT_BYTES,
    val enableTailHedge: Boolean = true,
    val enableCasVerification: Boolean = true,
    val defaultRouteMode: NetworkRouteMode = NetworkRouteMode.AUTO_MULTIPATH,
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val themeMode: ThemeMode = ThemeMode.DARK_OBSIDIAN,
)

data class NewDownloadRequest(
    val url: String,
    val customFileName: String? = null,
    val expectedSha256: String? = null,
    val routeMode: NetworkRouteMode = NetworkRouteMode.AUTO_MULTIPATH,
    val workers: Int = 4,
    val safUriString: String? = null,
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
            subtitle = "13 x 2 MB F2FS extents · Ideal for work-steal",
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
