package com.example.engine

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.example.data.model.ActiveConnectionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

const val DATA_SYNC_QUOTA_MS = 6L * 60L * 60L * 1000L // Android 15/16 6h dataSync limit

data class TelemetrySnapshot(
    val instantBps: Long = 0L,
    val peakBps: Long = 0L,
    val wifiBps: Long = 0L,
    val cellBps: Long = 0L,
    val ethBps: Long = 0L,
    val wifiPeakBps: Long = 0L,
    val cellPeakBps: Long = 0L,
    val ethPeakBps: Long = 0L,
    val rustPeakBps: Long = 0L,
    val kotlinPeakBps: Long = 0L,
    val wifiBytesTotal: Long = 0L,
    val cellBytesTotal: Long = 0L,
    val ethBytesTotal: Long = 0L,
    val speedHistoryBps: List<Long> = emptyList(),
    val wifiHistoryBps: List<Long> = emptyList(),
    val cellHistoryBps: List<Long> = emptyList(),
    val ethHistoryBps: List<Long> = emptyList(),
    val activeWorkers: Int = 0,
    val wifiWorkers: Int = 0,
    val cellularWorkers: Int = 0,
    val ethWorkers: Int = 0,
    val activeConnections: List<ActiveConnectionInfo> = emptyList(),
    val slabAllocatedMb: Float = 0f,
    val slabInUseMb: Float = 0f,
    val jvmHeapUsedMb: Float = 0f,
    val nativeHeapMb: Float = 0f,
    val totalSyscallWriteBatches: Long = 0L,
    val lastTtfbMs: Long = 0L,
    val thermalStatusLabel: String = "Nominal",
    val thermalHeadroomClear: Boolean = true,
    val cpuHeadroomPercent: Int? = null,
    val blockedReasonLabel: String = "None",
    val contiguousVerifiedOffset: Long = 0L,
    val cellularQuotaRemaining: Long = Long.MAX_VALUE,
    val quotaRemainingMs: Long = DATA_SYNC_QUOTA_MS,
    val quotaActiveSession: Boolean = false,
    val pollingHzLabel: String = "4 Hz Active",
    val isNativeRustActive: Boolean = false,
    val isMemfdMapped: Boolean = false,
    val activeEngineBadge: String = "STANDBY",
)

/**
 * Cache-line aligned telemetry aggregator supporting BOTH:
 * 1) Zero-JNI `memfd_create()` page when Rust `libaether_core.so` is driving transfers
 * 2) Per-link live metering + connection registry when Kotlin OkHttp engine is running
 *
 * Thread-safe synchronized sampling prevents competing background callers from draining
 * byte windows out from under the 4 Hz UI speedometer loop, and accurately sums concurrent
 * Rust + Kotlin transfers while tracking per-network peaks and dual-engine A/B peaks.
 */
class TelemetryLedger(
    private val context: Context,
    private val slabPool: SlabBufferPool,
    private val memfdReader: MemfdTelemetryReader = MemfdTelemetryReader(),
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val bytesWindow = AtomicLong(0L)
    private val wifiBytesWindow = AtomicLong(0L)
    private val cellBytesWindow = AtomicLong(0L)
    private val ethBytesWindow = AtomicLong(0L)
    private val wifiBytesCumulative = AtomicLong(0L)
    private val cellBytesCumulative = AtomicLong(0L)
    private val ethBytesCumulative = AtomicLong(0L)

    private val lastWindowCheckMs = AtomicLong(System.currentTimeMillis())
    private val peakBps = AtomicLong(0L)
    private val wifiPeakBps = AtomicLong(0L)
    private val cellPeakBps = AtomicLong(0L)
    private val ethPeakBps = AtomicLong(0L)
    private val rustPeakBps = AtomicLong(0L)
    private val kotlinPeakBps = AtomicLong(0L)

    private val activeWorkers = AtomicInteger(0)
    private val wifiWorkers = AtomicInteger(0)
    private val cellularWorkers = AtomicInteger(0)
    private val ethWorkers = AtomicInteger(0)
    private val totalSyscallWriteBatches = AtomicLong(0L)
    private val lastTtfbMs = AtomicLong(0L)
    private val quotaSessionStartMs = AtomicLong(0L)

    @Volatile
    private var cachedCpuHeadroomPct: Int? = null

    @Volatile
    private var activeEngineBadgeLabel: String =
        if (AetherNativeBridge.isNativeLoaded) "RUST READY" else "KOTLIN OKHTTP"

    @Volatile
    private var cachedSnapshot: TelemetrySnapshot = TelemetrySnapshot(
        isNativeRustActive = AetherNativeBridge.isNativeLoaded,
        activeEngineBadge = activeEngineBadgeLabel,
    )

    @Volatile
    private var lastSampleTimestampMs: Long = 0L

    private val sampleLock = Any()
    private val kotlinConnections = ConcurrentHashMap<String, ActiveConnectionInfo>()
    private val headroomStarted = AtomicBoolean(false)
    private var headroomJob: Job? = null

    private val speedRing = ArrayDeque<Long>(60)
    private val wifiRing = ArrayDeque<Long>(60)
    private val cellRing = ArrayDeque<Long>(60)
    private val ethRing = ArrayDeque<Long>(60)

    fun attachMemfdIfAvailable() {
        memfdReader.attachIfNativeAvailable()
    }

    /**
     * Audited Fix #5: Query `SystemHealthManager.getCpuHeadroomMinIntervalMillis()` once at startup
     * and poll `getCpuHeadroom()` in a dedicated background coroutine with cooldown >= 1000ms.
     */
    fun startCpuHeadroomMonitor(scope: CoroutineScope) {
        if (!headroomStarted.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT < 36) return
        headroomJob = scope.launch {
            val shm = runCatching { context.getSystemService("systemhealth") }.getOrNull() ?: return@launch
            val minIntervalMs = runCatching {
                val m = shm.javaClass.getMethod("getCpuHeadroomMinIntervalMillis")
                (m.invoke(shm) as? Number)?.toLong() ?: 1000L
            }.getOrDefault(1000L)
            val sleepMs = maxOf(minIntervalMs, 1000L)

            val getHeadroomMethod = runCatching {
                val paramsCls = Class.forName("android.os.health.CpuHeadroomParams")
                shm.javaClass.getMethod("getCpuHeadroom", paramsCls) to paramsCls
            }.getOrNull() ?: return@launch

            while (isActive) {
                runCatching {
                    val value = getHeadroomMethod.first.invoke(shm, null) as? Number
                    val pct = value?.toFloat()?.coerceIn(0f, 100f)?.toInt()
                    cachedCpuHeadroomPct = pct
                    if (pct != null && pct < 25) {
                        AetherNativeBridge.setThermalWorkerCapSafe(2)
                    } else if (pct != null && pct < 45) {
                        AetherNativeBridge.setThermalWorkerCapSafe(4)
                    } else {
                        AetherNativeBridge.setThermalWorkerCapSafe(8)
                    }
                }.onFailure {
                    if (it.cause is UnsupportedOperationException || it is UnsupportedOperationException) {
                        cachedCpuHeadroomPct = null
                        return@launch
                    }
                }
                delay(sleepMs)
            }
        }
    }

    fun setActiveEngineBadge(badge: String) {
        activeEngineBadgeLabel = badge
    }

    fun recordEnginePeak(backend: ResolvedEngineBackend, speedBps: Long) {
        if (speedBps <= 0L) return
        when (backend) {
            ResolvedEngineBackend.RUST_LIBAETHER_CORE -> rustPeakBps.updateAndGet { maxOf(it, speedBps) }
            ResolvedEngineBackend.KOTLIN_OKHTTP -> kotlinPeakBps.updateAndGet { maxOf(it, speedBps) }
        }
    }

    fun resetSessionPeaks() {
        peakBps.set(0L)
        wifiPeakBps.set(0L)
        cellPeakBps.set(0L)
        ethPeakBps.set(0L)
        rustPeakBps.set(0L)
        kotlinPeakBps.set(0L)
        synchronized(sampleLock) {
            cachedSnapshot = cachedSnapshot.copy(
                peakBps = 0L,
                wifiPeakBps = 0L,
                cellPeakBps = 0L,
                ethPeakBps = 0L,
                rustPeakBps = 0L,
                kotlinPeakBps = 0L,
            )
        }
    }

    fun latestTtfbMs(): Long =
        memfdReader.readSnapshot()?.ttfbMs?.takeIf { it > 0L } ?: lastTtfbMs.get()

    fun latestSnapshot(): TelemetrySnapshot = cachedSnapshot

    fun upsertKotlinConnection(info: ActiveConnectionInfo) {
        kotlinConnections[info.connId] = info
    }

    fun removeKotlinConnection(connId: String) {
        kotlinConnections.remove(connId)
    }

    fun clearKotlinConnectionsForTask(taskId: String) {
        kotlinConnections.entries.removeIf { it.value.taskId == taskId }
    }

    fun recordLinkBytesWritten(linkKeyId: String?, bytes: Long) {
        if (bytes <= 0L) return
        bytesWindow.addAndGet(bytes)
        when {
            linkKeyId?.startsWith("cell:") == true -> {
                cellBytesWindow.addAndGet(bytes)
                cellBytesCumulative.addAndGet(bytes)
            }
            linkKeyId?.startsWith("eth:") == true -> {
                ethBytesWindow.addAndGet(bytes)
                ethBytesCumulative.addAndGet(bytes)
            }
            else -> {
                wifiBytesWindow.addAndGet(bytes)
                wifiBytesCumulative.addAndGet(bytes)
            }
        }
    }

    fun recordBytesWritten(bytes: Long) {
        recordLinkBytesWritten(null, bytes)
    }

    fun recordSyscallWriteBatch() {
        totalSyscallWriteBatches.incrementAndGet()
    }

    fun recordTtfb(ms: Long) {
        if (ms > 0) lastTtfbMs.set(ms)
    }

    fun workerStarted(linkKeyId: String?) {
        activeWorkers.incrementAndGet()
        when {
            linkKeyId?.startsWith("cell:") == true -> cellularWorkers.incrementAndGet()
            linkKeyId?.startsWith("eth:") == true -> ethWorkers.incrementAndGet()
            else -> wifiWorkers.incrementAndGet()
        }
    }

    fun workerStarted(isCellular: Boolean) {
        workerStarted(if (isCellular) "cell:rmnet0" else "wifi:wlan0")
    }

    fun workerStopped(linkKeyId: String?) {
        activeWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
        when {
            linkKeyId?.startsWith("cell:") == true -> cellularWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
            linkKeyId?.startsWith("eth:") == true -> ethWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
            else -> wifiWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    fun workerStopped(isCellular: Boolean) {
        workerStopped(if (isCellular) "cell:rmnet0" else "wifi:wlan0")
    }

    fun markFgsSessionStarted() {
        if (quotaSessionStartMs.get() == 0L) {
            quotaSessionStartMs.set(System.currentTimeMillis())
        }
    }

    fun markFgsSessionStopped() {
        quotaSessionStartMs.set(0L)
    }

    /**
     * Used by background service notifications: reuses the fresh UI sample when foregrounded
     * so the 1 Hz service never steals window bytes from the 4 Hz UI speedometers.
     */
    fun sampleSnapshotIfStale(hasActiveDownloads: Boolean, maxAgeMs: Long = 600L): TelemetrySnapshot {
        val now = System.currentTimeMillis()
        if (lastSampleTimestampMs > 0L && (now - lastSampleTimestampMs) < maxAgeMs) {
            return cachedSnapshot
        }
        return sampleSnapshot(hasActiveDownloads)
    }

    fun sampleSnapshot(hasActiveDownloads: Boolean): TelemetrySnapshot = synchronized(sampleLock) {
        val now = System.currentTimeMillis()
        if (lastSampleTimestampMs > 0L && (now - lastSampleTimestampMs) < 160L) {
            return@synchronized cachedSnapshot
        }
        lastSampleTimestampMs = now

        val prev = lastWindowCheckMs.getAndSet(now)
        val dtMs = (now - prev).coerceAtLeast(100L)

        val windowTotal = bytesWindow.getAndSet(0L)
        val windowWifi = wifiBytesWindow.getAndSet(0L)
        val windowCell = cellBytesWindow.getAndSet(0L)
        val windowEth = ethBytesWindow.getAndSet(0L)

        val kotlinTotalBps = if (hasActiveDownloads || windowTotal > 0L) (windowTotal * 1000L) / dtMs else 0L
        val kotlinWifiBps = if (hasActiveDownloads || windowWifi > 0L) (windowWifi * 1000L) / dtMs else 0L
        val kotlinCellBps = if (hasActiveDownloads || windowCell > 0L) (windowCell * 1000L) / dtMs else 0L
        val kotlinEthBps = if (hasActiveDownloads || windowEth > 0L) (windowEth * 1000L) / dtMs else 0L

        val memfdSnap = memfdReader.readSnapshot()
        val rustTotalBps = if (memfdSnap != null && (memfdSnap.goodputBps > 0L || memfdSnap.totalWorkers > 0)) {
            memfdSnap.goodputBps / 8L
        } else {
            0L
        }
        val rustWifiBps = if (memfdSnap != null && rustTotalBps > 0L) memfdSnap.wifiGoodputBps / 8L else 0L
        val rustCellBps = if (memfdSnap != null && rustTotalBps > 0L) memfdSnap.cellGoodputBps / 8L else 0L
        val rustEthBps = if (memfdSnap != null && rustTotalBps > 0L) memfdSnap.ethGoodputBps / 8L else 0L

        // Combine both engines cleanly if a Rust task and Kotlin task execute concurrently
        val totalBps = rustTotalBps + kotlinTotalBps
        val wifiBpsNow = rustWifiBps + kotlinWifiBps
        val cellBpsNow = rustCellBps + kotlinCellBps
        val ethBpsNow = rustEthBps + kotlinEthBps

        if (rustTotalBps > 0L) rustPeakBps.updateAndGet { maxOf(it, rustTotalBps) }
        if (kotlinTotalBps > 0L) kotlinPeakBps.updateAndGet { maxOf(it, kotlinTotalBps) }

        val peakAll = peakBps.updateAndGet { maxOf(it, totalBps) }
        val peakWifi = wifiPeakBps.updateAndGet { maxOf(it, wifiBpsNow) }
        val peakCell = cellPeakBps.updateAndGet { maxOf(it, cellBpsNow) }
        val peakEth = ethPeakBps.updateAndGet { maxOf(it, ethBpsNow) }

        if (speedRing.size >= 48) speedRing.removeFirst()
        if (wifiRing.size >= 48) wifiRing.removeFirst()
        if (cellRing.size >= 48) cellRing.removeFirst()
        if (ethRing.size >= 48) ethRing.removeFirst()
        speedRing.addLast(totalBps)
        wifiRing.addLast(wifiBpsNow)
        cellRing.addLast(cellBpsNow)
        ethRing.addLast(ethBpsNow)

        val historyCopy = speedRing.toList()
        val wifiHistCopy = wifiRing.toList()
        val cellHistCopy = cellRing.toList()
        val ethHistCopy = ethRing.toList()

        val rt = Runtime.getRuntime()
        val jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f)
        val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024f * 1024f)

        val nativeStats = AetherNativeBridge.slabStatsSafe()
        val slabAllocMb = if (nativeStats != null && nativeStats.size >= 2 && nativeStats[0] > 0L) {
            nativeStats[0] / (1024f * 1024f)
        } else {
            slabPool.allocatedBytes() / (1024f * 1024f)
        }
        val slabInUseMb = if (nativeStats != null && nativeStats.size >= 2) {
            nativeStats[1] / (1024f * 1024f)
        } else {
            slabPool.inUseBytes() / (1024f * 1024f)
        }

        val (thermalLabel, thermalOk) = readThermalStatus()
        val sessionStart = quotaSessionStartMs.get()
        val remaining = if (sessionStart > 0L) {
            (DATA_SYNC_QUOTA_MS - (now - sessionStart)).coerceAtLeast(0L)
        } else {
            DATA_SYNC_QUOTA_MS
        }

        val nativeConns = AetherNativeBridge.connectionsListSafe()
        val combinedConns = (nativeConns + kotlinConnections.values)
            .sortedBy { it.rangeStart }

        val rustWTotal = memfdSnap?.totalWorkers ?: 0
        val rustWWifi = memfdSnap?.wifiWorkers ?: 0
        val rustWCell = memfdSnap?.cellWorkers ?: 0
        val rustWEth = memfdSnap?.ethWorkers ?: 0

        val blockedLabel = decodeBlockedReason(memfdSnap?.blockedReasonCode ?: 0L)

        val snap = TelemetrySnapshot(
            instantBps = totalBps,
            peakBps = peakAll,
            wifiBps = wifiBpsNow,
            cellBps = cellBpsNow,
            ethBps = ethBpsNow,
            wifiPeakBps = peakWifi,
            cellPeakBps = peakCell,
            ethPeakBps = peakEth,
            rustPeakBps = rustPeakBps.get(),
            kotlinPeakBps = kotlinPeakBps.get(),
            wifiBytesTotal = (memfdSnap?.wifiBytesTotal ?: 0L) + wifiBytesCumulative.get(),
            cellBytesTotal = (memfdSnap?.cellBytesTotal ?: 0L) + cellBytesCumulative.get(),
            ethBytesTotal = ethBytesCumulative.get(),
            speedHistoryBps = historyCopy,
            wifiHistoryBps = wifiHistCopy,
            cellHistoryBps = cellHistCopy,
            ethHistoryBps = ethHistCopy,
            activeWorkers = rustWTotal + activeWorkers.get(),
            wifiWorkers = rustWWifi + wifiWorkers.get(),
            cellularWorkers = rustWCell + cellularWorkers.get(),
            ethWorkers = rustWEth + ethWorkers.get(),
            activeConnections = combinedConns,
            slabAllocatedMb = slabAllocMb,
            slabInUseMb = slabInUseMb,
            jvmHeapUsedMb = jvmUsedMb,
            nativeHeapMb = nativeMb,
            totalSyscallWriteBatches = (memfdSnap?.consumerSeq ?: 0L) + totalSyscallWriteBatches.get(),
            lastTtfbMs = memfdSnap?.ttfbMs?.takeIf { it > 0L } ?: lastTtfbMs.get(),
            thermalStatusLabel = thermalLabel,
            thermalHeadroomClear = thermalOk,
            cpuHeadroomPercent = cachedCpuHeadroomPct,
            blockedReasonLabel = blockedLabel,
            contiguousVerifiedOffset = memfdSnap?.contiguousVerifiedOffset ?: 0L,
            cellularQuotaRemaining = memfdSnap?.cellularQuotaRemaining ?: Long.MAX_VALUE,
            quotaRemainingMs = remaining,
            quotaActiveSession = sessionStart > 0L,
            pollingHzLabel = if (hasActiveDownloads) "4 Hz Active" else "Idle (0-Alloc)",
            isNativeRustActive = AetherNativeBridge.isNativeLoaded,
            isMemfdMapped = memfdReader.isMapped,
            activeEngineBadge = activeEngineBadgeLabel,
        )
        cachedSnapshot = snap
        snap
    }

    /**
     * Exact discriminant mapping matching Rust `NetworkBlockedReason` enum (`transport.rs`):
     * 0=None, 1=DataSaver, 2=Oem, 3=Doze, 4=LocalNetwork, 5=UnknownPolicy.
     */
    private fun decodeBlockedReason(code: Long): String = when (code) {
        0L -> "None"
        1L -> "Data Saver (BLOCKED_REASON_DATA_SAVER)"
        2L -> "OEM / HyperOS Firewall (BLOCKED_REASON_OEM)"
        3L -> "Doze Standby (BLOCKED_REASON_DOZE)"
        4L -> "Android 16 LNP Local Net (BLOCKED_REASON_LOCAL_NETWORK)"
        5L -> "NDK Blocked Policy"
        else -> "NDK Blocked ($code)"
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
