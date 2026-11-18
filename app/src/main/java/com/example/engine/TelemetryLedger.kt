package com.example.engine

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

const val DATA_SYNC_QUOTA_MS = 6L * 60L * 60L * 1000L // Android 15/16 6h dataSync limit

data class TelemetrySnapshot(
    val instantBps: Long = 0L,
    val peakBps: Long = 0L,
    val speedHistoryBps: List<Long> = emptyList(),
    val activeWorkers: Int = 0,
    val wifiWorkers: Int = 0,
    val cellularWorkers: Int = 0,
    val slabAllocatedMb: Float = 0f,
    val slabInUseMb: Float = 0f,
    val jvmHeapUsedMb: Float = 0f,
    val nativeHeapMb: Float = 0f,
    val totalSyscallWriteBatches: Long = 0L,
    val lastTtfbMs: Long = 0L,
    val thermalStatusLabel: String = "Nominal",
    val thermalHeadroomClear: Boolean = true,
    val quotaRemainingMs: Long = DATA_SYNC_QUOTA_MS,
    val quotaActiveSession: Boolean = false,
    val pollingHzLabel: String = "4 Hz Active",
)

/**
 * Cache-line style atomic telemetry bridge.
 * Sampled by UI only when lifecycle is >= STARTED (4 Hz active, 0 Hz on ON_STOP).
 */
class TelemetryLedger(
    context: Context,
    private val slabPool: SlabBufferPool,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val bytesWindow = AtomicLong(0L)
    private val lastWindowCheckMs = AtomicLong(System.currentTimeMillis())
    private val lastInstantBps = AtomicLong(0L)
    private val peakBps = AtomicLong(0L)
    private val activeWorkers = AtomicInteger(0)
    private val wifiWorkers = AtomicInteger(0)
    private val cellularWorkers = AtomicInteger(0)
    private val totalSyscallWriteBatches = AtomicLong(0L)
    private val lastTtfbMs = AtomicLong(0L)
    private val quotaSessionStartMs = AtomicLong(0L)

    private val historyLock = Any()
    private val speedRing = ArrayDeque<Long>(60)

    fun recordBytesWritten(bytes: Long) {
        if (bytes > 0) bytesWindow.addAndGet(bytes)
    }

    fun recordSyscallWriteBatch() {
        totalSyscallWriteBatches.incrementAndGet()
    }

    fun recordTtfb(ms: Long) {
        if (ms > 0) lastTtfbMs.set(ms)
    }

    fun workerStarted(isCellular: Boolean) {
        activeWorkers.incrementAndGet()
        if (isCellular) cellularWorkers.incrementAndGet() else wifiWorkers.incrementAndGet()
    }

    fun workerStopped(isCellular: Boolean) {
        activeWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (isCellular) {
            cellularWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
        } else {
            wifiWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    fun markFgsSessionStarted() {
        if (quotaSessionStartMs.get() == 0L) {
            quotaSessionStartMs.set(System.currentTimeMillis())
        }
    }

    fun markFgsSessionStopped() {
        quotaSessionStartMs.set(0L)
    }

    fun sampleSnapshot(hasActiveDownloads: Boolean): TelemetrySnapshot {
        val now = System.currentTimeMillis()
        val prev = lastWindowCheckMs.getAndSet(now)
        val dtMs = (now - prev).coerceAtLeast(100L)
        val windowBytes = bytesWindow.getAndSet(0L)
        val bps = if (hasActiveDownloads || windowBytes > 0) {
            (windowBytes * 1000L) / dtMs
        } else {
            0L
        }
        lastInstantBps.set(bps)
        peakBps.updateAndGet { maxOf(it, bps) }

        val historyCopy = synchronized(historyLock) {
            if (speedRing.size >= 48) speedRing.removeFirst()
            speedRing.addLast(bps)
            speedRing.toList()
        }

        val rt = Runtime.getRuntime()
        val jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f)
        val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024f * 1024f)
        val slabAllocMb = slabPool.allocatedBytes() / (1024f * 1024f)
        val slabInUseMb = slabPool.inUseBytes() / (1024f * 1024f)

        val (thermalLabel, thermalOk) = readThermalStatus()
        val sessionStart = quotaSessionStartMs.get()
        val remaining = if (sessionStart > 0L) {
            (DATA_SYNC_QUOTA_MS - (now - sessionStart)).coerceAtLeast(0L)
        } else {
            DATA_SYNC_QUOTA_MS
        }

        return TelemetrySnapshot(
            instantBps = bps,
            peakBps = peakBps.get(),
            speedHistoryBps = historyCopy,
            activeWorkers = activeWorkers.get(),
            wifiWorkers = wifiWorkers.get(),
            cellularWorkers = cellularWorkers.get(),
            slabAllocatedMb = slabAllocMb,
            slabInUseMb = slabInUseMb,
            jvmHeapUsedMb = jvmUsedMb,
            nativeHeapMb = nativeMb,
            totalSyscallWriteBatches = totalSyscallWriteBatches.get(),
            lastTtfbMs = lastTtfbMs.get(),
            thermalStatusLabel = thermalLabel,
            thermalHeadroomClear = thermalOk,
            quotaRemainingMs = remaining,
            quotaActiveSession = sessionStart > 0L,
            pollingHzLabel = if (hasActiveDownloads) "4 Hz Active" else "Idle (0-Alloc)",
        )
    }

    private fun readThermalStatus(): Pair<String, Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Nominal" to true
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Nominal" to true
            PowerManager.THERMAL_STATUS_LIGHT -> "Light Load" to true
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate" to true
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttle" to false
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical" to false
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Emergency" to false
            else -> "Nominal" to true
        }
    }
}
