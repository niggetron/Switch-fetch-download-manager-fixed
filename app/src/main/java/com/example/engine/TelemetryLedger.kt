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
    val wifiBytesTotal: Long = 0L,
    val cellBytesTotal: Long = 0L,
    val ethBytesTotal: Long = 0L,
    val speedHistoryBps: List<Long> = emptyList(),
    val wifiHistoryBps: List<Long> = emptyList(),
    val cellHistoryBps: List<Long> = emptyList(),
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
 * Also polls Android 16 `SystemHealthManager.getCpuHeadroom()` on a dedicated background
 * coroutine respecting `getCpuHeadroomMinIntervalMillis()`, never inside the 4 Hz UI loop.
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
    private var activeEngineBadgeLabel: String = if (AetherNativeBridge.isNativeLoaded) "RUST READY" else "KOTLIN OKHTTP"

    private val kotlinConnections = ConcurrentHashMap<String, ActiveConnectionInfo>()
    private val headroomStarted = AtomicBoolean(false)
    private var headroomJob: Job? = null

    private val historyLock = Any()
    private val speedRing = ArrayDeque<Long>(60)
    private val wifiRing = ArrayDeque<Long>(60)
    private val cellRing = ArrayDeque<Long>(60)

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
                    // UnsupportedOperationException on emulators / older kernels -> stop polling gracefully
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

    fun sampleSnapshot(hasActiveDownloads: Boolean): TelemetrySnapshot {
        val now = System.currentTimeMillis()
        val prev = lastWindowCheckMs.getAndSet(now)
        val dtMs = (now - prev).coerceAtLeast(100L)

        val windowTotal = bytesWindow.getAndSet(0L)
        val windowWifi = wifiBytesWindow.getAndSet(0L)
        val windowCell = cellBytesWindow.getAndSet(0L)
        val windowEth = ethBytesWindow.getAndSet(0L)

        val memfdSnap = memfdReader.readSnapshot()
        val rustActiveNow = memfdSnap != null && (memfdSnap.goodputBps > 0L || memfdSnap.totalWorkers > 0)

        val totalBps = when {
            rustActiveNow && memfdSnap != null -> memfdSnap.goodputBps / 8L
            hasActiveDownloads || windowTotal > 0 -> (windowTotal * 1000L) / dtMs
            else -> 0L
        }
        val wifiBpsNow = when {
            rustActiveNow && memfdSnap != null -> memfdSnap.wifiGoodputBps / 8L
            hasActiveDownloads || windowWifi > 0 -> (windowWifi * 1000L) / dtMs
            else -> 0L
        }
        val cellBpsNow = when {
            rustActiveNow && memfdSnap != null -> memfdSnap.cellGoodputBps / 8L
            hasActiveDownloads || windowCell > 0 -> (windowCell * 1000L) / dtMs
            else -> 0L
        }
        val ethBpsNow = when {
            rustActiveNow && memfdSnap != null -> memfdSnap.ethGoodputBps / 8L
            hasActiveDownloads || windowEth > 0 -> (windowEth * 1000L) / dtMs
            else -> 0L
        }

        peakBps.updateAndGet { maxOf(it, totalBps) }

        val (historyCopy, wifiHistCopy, cellHistCopy) = synchronized(historyLock) {
            if (speedRing.size >= 48) speedRing.removeFirst()
            if (wifiRing.size >= 48) wifiRing.removeFirst()
            if (cellRing.size >= 48) cellRing.removeFirst()
            speedRing.addLast(totalBps)
            wifiRing.addLast(wifiBpsNow)
            cellRing.addLast(cellBpsNow)
            Triple(speedRing.toList(), wifiRing.toList(), cellRing.toList())
        }

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

        val blockedLabel = decodeBlockedReason(memfdSnap?.blockedReasonCode ?: 0L)

        return TelemetrySnapshot(
            instantBps = totalBps,
            peakBps = peakBps.get(),
            wifiBps = wifiBpsNow,
            cellBps = cellBpsNow,
            ethBps = ethBpsNow,
            wifiBytesTotal = maxOf(memfdSnap?.wifiBytesTotal ?: 0L, wifiBytesCumulative.get()),
            cellBytesTotal = maxOf(memfdSnap?.cellBytesTotal ?: 0L, cellBytesCumulative.get()),
            ethBytesTotal = ethBytesCumulative.get(),
            speedHistoryBps = historyCopy,
            wifiHistoryBps = wifiHistCopy,
            cellHistoryBps = cellHistCopy,
            activeWorkers = if (rustActiveNow && memfdSnap != null) memfdSnap.totalWorkers else activeWorkers.get(),
            wifiWorkers = if (rustActiveNow && memfdSnap != null) memfdSnap.wifiWorkers else wifiWorkers.get(),
            cellularWorkers = if (rustActiveNow && memfdSnap != null) memfdSnap.cellWorkers else cellularWorkers.get(),
            ethWorkers = if (rustActiveNow && memfdSnap != null) memfdSnap.ethWorkers else ethWorkers.get(),
            activeConnections = combinedConns,
            slabAllocatedMb = slabAllocMb,
            slabInUseMb = slabInUseMb,
            jvmHeapUsedMb = jvmUsedMb,
            nativeHeapMb = nativeMb,
            totalSyscallWriteBatches = maxOf(memfdSnap?.consumerSeq ?: 0L, totalSyscallWriteBatches.get()),
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
    }

    private fun decodeBlockedReason(code: Long): String = when {
        code == 0L -> "None"
        (code and 1L) != 0L -> "Data Saver (BLOCKED_REASON_DATA_SAVER)"
        (code and 2L) != 0L -> "Doze Standby (BLOCKED_REASON_DOZE)"
        (code and 4L) != 0L -> "OEM / HyperOS Firewall (BLOCKED_REASON_OEM)"
        (code and 8L) != 0L -> "Android 16 LNP Local Net (BLOCKED_REASON_LOCAL_NETWORK)"
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
