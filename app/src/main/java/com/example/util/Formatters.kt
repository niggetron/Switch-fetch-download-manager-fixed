package com.example.util

import java.util.Locale
import kotlin.math.roundToInt

object Formatters {
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Unknown"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatSpeedMbps(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0.0 Mbps"
        val mbps = (bytesPerSec * 8.0) / 1_000_000.0
        return if (mbps >= 100.0) {
            String.format(Locale.US, "%.0f Mbps", mbps)
        } else {
            String.format(Locale.US, "%.1f Mbps", mbps)
        }
    }

    fun formatSpeedMBps(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0.00 MB/s"
        val mbps = bytesPerSec / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f MB/s", mbps)
    }

    fun formatEta(remainingBytes: Long, speedBps: Long): String {
        if (remainingBytes <= 0L || speedBps <= 0L) return "—"
        val secs = (remainingBytes / speedBps).coerceAtLeast(1L)
        return when {
            secs < 60L -> "${secs}s"
            secs < 3600L -> "${secs / 60}m ${secs % 60}s"
            else -> "${secs / 3600}h ${(secs % 3600) / 60}m"
        }
    }

    fun formatDurationHms(ms: Long): String {
        val totalSecs = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSecs / 3600L
        val m = (totalSecs % 3600L) / 60L
        val s = totalSecs % 60L
        return String.format(Locale.US, "%dh %02dm %02ds", h, m, s)
    }

    fun formatPercent(fraction: Float): String =
        "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"
}
