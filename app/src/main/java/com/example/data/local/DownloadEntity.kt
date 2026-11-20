package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.DownloadStatus
import com.example.data.model.F2FS_SEGMENT_BYTES
import com.example.data.model.NetworkRouteMode
import com.example.data.model.SegmentState

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,
    val routeMode: String,
    val workerCount: Int,
    val destinationUri: String,
    val isSafDestination: Boolean,
    val supportsRange: Boolean,
    val segmentSizeBytes: Long = F2FS_SEGMENT_BYTES,
    val segmentBitfield: String = "",
    val eTag: String? = null,
    val expectedSha256: String? = null,
    val actualSha256: String? = null,
    val ttfbMs: Long = 0L,
    val avgSpeedBps: Long = 0L,
    val peakSpeedBps: Long = 0L,
    val syscallWriteBatches: Int = 0,
    val casDedupSkippedBytes: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    // v8.0 dual-engine, multi-mirror, and per-link telemetry attribution columns
    val mirrorUrls: String = "",
    val engineBackendUsed: String = "",
    val wifiBytes: Long = 0L,
    val cellBytes: Long = 0L,
    val maxCellularBytes: Long = 0L,
    val browserProfile: String = "chrome134",
    val protocolMode: String = "auto",
    // v9.1 deep telemetry, quad-link attribution & 1DM+ checksum columns
    val startedAt: Long = 0L,
    val activeDownloadMs: Long = 0L,
    val maxConnectionsUsed: Int = 0,
    val wifi2Bytes: Long = 0L,
    val usbBytes: Long = 0L,
    val actualMd5: String? = null,
    val streamType: String = "DIRECT",
) {
    val statusEnum: DownloadStatus
        get() = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED)

    val routeModeEnum: NetworkRouteMode
        get() = runCatching { NetworkRouteMode.valueOf(routeMode) }
            .getOrDefault(NetworkRouteMode.AUTO_MULTIPATH)

    val allUrls: List<String>
        get() = buildList {
            if (url.isNotBlank()) add(url.trim())
            mirrorUrls.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && it != url.trim() }
                .forEach { add(it) }
        }

    val remainingBytes: Long
        get() = if (totalBytes > 0L) (totalBytes - downloadedBytes).coerceAtLeast(0L) else 0L

    val progressFraction: Float
        get() = when {
            statusEnum == DownloadStatus.COMPLETED -> 1f
            totalBytes <= 0L -> 0f
            else -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }

    fun parsedSegments(): List<SegmentState> =
        segmentBitfield.map { SegmentState.fromCode(it) }
}
