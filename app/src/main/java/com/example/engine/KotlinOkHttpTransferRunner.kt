package com.example.engine

import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.webkit.MimeTypeMap
import com.example.data.local.CasChunkEntity
import com.example.data.local.DownloadEntity
import com.example.data.model.ActiveConnectionInfo
import com.example.data.model.BreakerState
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineSettings
import com.example.data.model.NetworkRouteMode
import com.example.data.model.SegmentState
import com.example.data.model.ThroughputProfile
import com.example.data.repository.DownloadRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlin.math.ceil

private const val OKHTTP_STALL_WATCHDOG_MS = 4_500L
private const val OKHTTP_MAX_SEGMENT_RETRIES = 5
private const val OKHTTP_MIN_EXTENT_BYTES = 1L * 1024L * 1024L
private const val OKHTTP_SLAB_FLUSH_THRESHOLD = 256 * 1024 // Slab size; flush on full slab
private const val OKHTTP_TELEMETRY_THROTTLE_MS = 450L
private const val OKHTTP_MIN_BISECT_BYTES = 2L * 1024L * 1024L
private const val OKHTTP_STALL_BISECT_BYTES = 1L * 1024L * 1024L
private const val OKHTTP_TCP_RCVBUF_BYTES = 4 * 1024 * 1024

/**
 * High-Throughput Quad-Link Kotlin OkHttp Transfer Engine (v9.0 Superbike & Anti-Dip):
 * - Persistent cached `OkHttpClient` pool per link (4 MiB TCP SO_RCVBUF, TCP_NODELAY,
 *   `maxRequestsPerHost = 32`, `ConnectionPool(32, 5m)`) — zero Slow Start between segments!
 * - Lock-free positional `pwrite` via `FileChannel.write(buf, offset)` + 512 KiB slab coalescing
 *   + non-blocking batched `force(false)` every 2s (eliminates writeMutex worker starvation & flip-flopping!)
 * - Supports all 4 simultaneous links: Wi-Fi 1, Wi-Fi 2 (Dual-STA or HTTP/SOCKS5h Companion Relay),
 *   Mobile 5G/LTE, and USB Tethering / USB-C Ethernet
 * - Live atomic range bisection (Anti-Dip tail rescue) up to 16 parallel workers
 */
internal class KotlinOkHttpTransferRunner(
    private val context: Context,
    private val repository: DownloadRepository,
    private val networkMonitor: NetworkMonitor,
    private val slabPool: SlabBufferPool,
    private val telemetryLedger: TelemetryLedger,
    private val engineScope: CoroutineScope,
    private val activeCalls: ConcurrentHashMap<String, MutableList<Call>>,
    private val ledgerFileProvider: (String) -> File,
    private val stagingFileProvider: (String) -> File,
) {
    // A segment split by anti-dip has two writers. Track outstanding parts so a
    // segment is marked COMMITTED only when BOTH halves are actually on disk.
    private val bisectParts = ConcurrentHashMap<String, ConcurrentHashMap<Int, AtomicInteger>>()
    private val hostLimits = HostLimits()
    private var pendingSegments = AtomicInteger(0)

    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 128
        maxRequestsPerHost = 128
    }

    private val baseClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        // We own retry/backoff (and Idempotency of Range requests). OkHttp's
        // built-in retry would silently re-issue range requests behind our back.
        .retryOnConnectionFailure(false)
        .build()

    // Persistent sharded warm client cache per (linkKey, netHandle, proxySpec, shardId)
    // Sharding breaks HTTP/2 single-TCP-socket congestion window collapse!
    private val clientPool = ConcurrentHashMap<String, OkHttpClient>()

    private class ActiveOkHttpRange(
        val segIndex: Int,
        val currentOffset: AtomicLong,
        val endOffset: AtomicLong,
        val ewmaBps: AtomicLong,
        val lastProgressMs: AtomicLong,
    )

    private data class OkHttpWorkUnit(
        val segIndex: Int,
        val startOffset: Long,
        val endOffset: Long,
        val isBisectedSteal: Boolean,
    )

    suspend fun run(entityInput: DownloadEntity, settings: EngineSettings) {
        var entity = entityInput
        val id = entity.id
        val callsForTask = mutableListOf<Call>()
        activeCalls[id] = callsForTask
        val urls = entity.allUrls.ifEmpty { listOf(entity.url) }
        val useStagedSaf = entity.isSafDestination && settings.stagedSafPlacement
        val staging = stagingFileProvider(id)

        if (useStagedSaf &&
            entity.totalBytes > 0L &&
            staging.exists() &&
            staging.length() == entity.totalBytes &&
            entity.segmentBitfield.isNotEmpty() &&
            entity.segmentBitfield.all {
                val st = SegmentState.fromCode(it)
                st == SegmentState.COMMITTED || st == SegmentState.VERIFIED
            }
        ) {
            if (promoteStagedToSaf(entity, staging)) {
                finalizeCompletedEntity(
                    entity = entity,
                    finalSize = entity.totalBytes,
                    avgBps = entity.avgSpeedBps.coerceAtLeast(1L),
                    peakBps = entity.peakSpeedBps,
                    actualHash = entity.actualSha256,
                    casDedupBytes = entity.casDedupSkippedBytes,
                    verifiedBitfield = entity.segmentBitfield,
                    syscallCount = entity.syscallWriteBatches,
                    wifiBytes = entity.wifiBytes,
                    cellBytes = entity.cellBytes,
                )
            }
            return
        }

        // Stream 0 Probe on Lowest-RTT healthy LinkKey
        val runStartedAt = if (entity.startedAt > 0L) entity.startedAt else System.currentTimeMillis()
        val isHlsOrDash = isStreamingManifestUrl(urls.first())
        entity = entity.copy(
            status = DownloadStatus.PROBING_0RTT.name,
            engineBackendUsed = ResolvedEngineBackend.KOTLIN_OKHTTP.id,
            startedAt = runStartedAt,
            streamType = if (isHlsOrDash) "HLS_DASH" else "DIRECT",
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(entity)

        if (isHlsOrDash) {
            runHlsDashParallelTransfer(entity, urls, settings, callsForTask)
            return
        }

        val probeLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
        val probeLane = LinkScheduler.selectLowestRttLink(probeLanes, networkMonitor.breakerRegistry)
        val probeEndpoint = probeLane?.let { networkMonitor.getNetworkByKey(it.linkKeyId) }
        val probeClient = getOrBuildClientForLink(probeEndpoint, entity.routeModeEnum, settings, shardId = 0)

        val probeResult = try {
            performPreflightProbe(probeClient, urls.first(), entity.browserProfile, callsForTask).also {
                probeLane?.linkKeyId?.let { key ->
                    networkMonitor.breakerRegistry[key]?.recordSuccess()
                    networkMonitor.publishState()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: HttpAuthExpiredException) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.TOKEN_EXPIRED_R2.name,
                    errorMessage = "HTTP ${ex.code} signed token expired. Update URL to resume byte-exact.",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        } catch (ex: Exception) {
            probeLane?.linkKeyId?.let { key ->
                networkMonitor.breakerRegistry[key]?.recordFailureOrStall(immediateTrip = false)
                networkMonitor.publishState()
            }
            if (isNetworkFlap(ex) && waitForLinkMigration(entity)) {
                run(entity, settings)
                return
            }
            repository.update(
                entity.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = ex.message ?: "Pre-flight probe failed",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        }

        telemetryLedger.recordTtfb(probeResult.ttfbMs)
        val resolvedName = if (entity.fileName.startsWith("aether_") ||
            entity.fileName.startsWith("download_") ||
            entity.fileName == "probe.bin"
        ) {
            probeResult.suggestedFileName ?: entity.fileName
        } else {
            entity.fileName
        }

        val resolvedDest = if (!entity.isSafDestination && resolvedName != entity.fileName && entity.downloadedBytes == 0L) {
            val oldFile = File(entity.destinationUri)
            if (oldFile.exists() && oldFile.length() == 0L) oldFile.delete()
            repository.resolveUniqueTargetFile(resolvedName).absolutePath
        } else {
            entity.destinationUri
        }

        val totalBytes = probeResult.totalBytes
        val segSize = if (entity.totalBytes > 0L && entity.totalBytes == totalBytes) {
            entity.segmentSizeBytes.coerceAtLeast(OKHTTP_MIN_EXTENT_BYTES)
        } else {
            val fastestKbps = networkMonitor.state.value.activeValidatedLinks
                .maxOfOrNull { it.downstreamKbps } ?: 100_000
            LinkScheduler.adaptiveBdpSegmentBytes(fastestKbps)
        }

        val segmentCount = if (totalBytes > 0L && probeResult.supportsRange) {
            ceil(totalBytes.toDouble() / segSize.toDouble()).toInt().coerceAtLeast(1)
        } else {
            1
        }

        val ledger = AetherBinaryLedger.loadOrCreate(
            file = ledgerFileProvider(id),
            totalLen = totalBytes.coerceAtLeast(0L),
            segmentBytes = segSize,
            etagOrLastMod = probeResult.eTag.orEmpty(),
        )
        val bitfield = reconcileBitfield(entity, ledger, segmentCount, totalBytes)

        if (totalBytes > 0L && (!entity.isSafDestination || useStagedSaf)) {
            val dir = if (useStagedSaf) {
                staging.parentFile?.apply { mkdirs() } ?: context.filesDir
            } else {
                repository.getDownloadsDirectory()
            }
            val free = StatFs(dir.absolutePath).availableBytes
            if (free < totalBytes + (64L * 1024L * 1024L)) {
                repository.update(
                    entity.copy(
                        totalBytes = totalBytes,
                        status = DownloadStatus.ENOSPC_R4.name,
                        errorMessage = "ENOSPC guard: ${free / (1024 * 1024)} MB free < needed + 64 MB reserve.",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                return
            }
        }

        val mime = guessMimeType(resolvedName)
        entity = entity.copy(
            fileName = resolvedName,
            mimeType = mime,
            destinationUri = resolvedDest,
            totalBytes = totalBytes,
            supportsRange = probeResult.supportsRange,
            segmentSizeBytes = segSize,
            segmentBitfield = bitfield,
            eTag = probeResult.eTag,
            ttfbMs = probeResult.ttfbMs,
            status = DownloadStatus.STREAMING.name,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(entity)

        val handle = try {
            openDestinationHandle(entity, useStagedSaf, staging)
        } catch (sec: SecurityException) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.SAF_EBADF_R5.name,
                    errorMessage = "SAF permission revoked (EBADF). Re-grant permission to resume.",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        } catch (ioe: IOException) {
            repository.update(
                entity.copy(
                    status = if (entity.isSafDestination && !useStagedSaf) DownloadStatus.SAF_EBADF_R5.name else DownloadStatus.ENOSPC_R4.name,
                    errorMessage = ioe.message ?: "Unable to open destination handle",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        }

        val startTimeMs = System.currentTimeMillis()
        val bitChars = bitfield.toCharArray()
        val bitMutex = Mutex()
        pendingSegments = AtomicInteger(bitChars.count { it == SegmentState.PENDING.code })
        val committedBytes = AtomicLong(
            bitChars.indices.sumOf { idx ->
                val state = SegmentState.fromCode(bitChars[idx])
                if (state == SegmentState.COMMITTED || state == SegmentState.VERIFIED) {
                    val s = idx.toLong() * segSize
                    val e = minOf(s + segSize - 1L, (totalBytes - 1L).coerceAtLeast(0L))
                    (e - s + 1L)
                } else 0L
            }
        )
        val liveWrittenBytes = AtomicLong(committedBytes.get())
        val taskWifiBytes = AtomicLong(entity.wifiBytes)
        val taskWifi2Bytes = AtomicLong(entity.wifi2Bytes)
        val taskCellBytes = AtomicLong(entity.cellBytes)
        val taskUsbBytes = AtomicLong(entity.usbBytes)
        val cellLimit = when {
            entity.maxCellularBytes > 0L -> entity.maxCellularBytes
            settings.maxCellularBytesPerSession > 0L -> settings.maxCellularBytesPerSession
            else -> Long.MAX_VALUE
        }
        val syscallBatches = AtomicInteger(entity.syscallWriteBatches)
        val peakSpeed = AtomicLong(entity.peakSpeedBps)
        val maxConnsObserved = AtomicInteger(entity.maxConnectionsUsed)
        val queuedBytesByLink = ConcurrentHashMap<String, AtomicLong>()
        val activeWorkersByLink = ConcurrentHashMap<String, AtomicInteger>()
        val activeRanges = ConcurrentHashMap<String, ActiveOkHttpRange>()
        val segmentRetries = ConcurrentHashMap<Int, AtomicInteger>()
        var failureReason: Pair<DownloadStatus, String>? = null
        val baseActiveMs = entity.activeDownloadMs

        try {
            handle.use { dest ->
                if (totalBytes > 0L && dest.isRandomAccess) {
                    runCatching { dest.ensureSize(totalBytes) }
                }

                val activeLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
                val maxCap = when (settings.throughputProfile) {
                    ThroughputProfile.TURBO_ANTI_DIP -> 96
                    ThroughputProfile.MAX_MULTI_SOCKET -> 128
                    ThroughputProfile.SUSTAINED_ANTI_DIP -> 64
                }
                val downloadHost = runCatching { java.net.URL(entity.url).host }.getOrNull().orEmpty()
                val learnedCap = downloadHost.takeIf { it.isNotBlank() }?.let { hostLimits.cap(it) }
                val effectiveMaxCap = if (learnedCap != null) minOf(maxCap, learnedCap) else maxCap
                if (learnedCap != null) {
                    DiagnosticsCenter.log(
                        "HOST_LIMIT",
                        "Using learned connection cap $learnedCap for $downloadHost",
                    )
                }
                val baseDesired = LinkScheduler.effectiveWorkerCount(entity.workerCount, activeLanes.size)
                val boostedDesired = if (settings.enableAutoSpeedUp || settings.throughputProfile != ThroughputProfile.SUSTAINED_ANTI_DIP) {
                    maxOf(baseDesired, (activeLanes.size * 6).coerceAtMost(effectiveMaxCap))
                } else {
                    baseDesired
                }
                // Start at the desired lane count, not the hard cap. The old
                // `maxOf(boostedDesired, maxCap)` always launched 64 coroutines and
                // the governor could never recover once congestion set in.
                // MDM's small-file rule: opening 128 sockets on a 256 KiB file cuts
                // it into slivers and re-fetches bytes. Start with at most one
                // 64 KiB piece per connection; the governor can ramp later.
                val bySize = if (totalBytes > 0L) {
                    ((totalBytes + 65_535L) / 65_536L).coerceAtLeast(1L)
                } else Long.MAX_VALUE
                val rampCap = minOf(effectiveMaxCap.toLong(), bySize).toInt().coerceAtLeast(1)
                // Launch enough coroutines for the GOVERNOR's full range so a ramp-up
                // can actually materialize. Surplus workers park cheaply (O(1) check)
                // until targetWorkers rises. Previously only `boostedDesired` workers
                // existed, so a governor target of 96 was a no-op with 32 coroutines.
                val maxPoolCoroutines = rampCap.coerceIn(1, effectiveMaxCap)
                val targetWorkers = AtomicInteger(minOf(boostedDesired, rampCap).coerceAtLeast(1))

                val flusherJob = engineScope.launch(Dispatchers.IO) {
                    var lastSampleTime = System.currentTimeMillis()
                    var lastSampleBytes = liveWrittenBytes.get()
                    var lastDiskForceMs = System.currentTimeMillis()
                    var prevGoodputBps = 0.0
                    var prevTtfb = probeResult.ttfbMs.toDouble()

                    while (isActive) {
                        delay(500L)
                        val now = System.currentTimeMillis()
                        val curBytes = liveWrittenBytes.get()
                        val dt = (now - lastSampleTime).coerceAtLeast(100L)
                        val curSpeed = ((curBytes - lastSampleBytes).coerceAtLeast(0L) * 1000L) / dt
                        peakSpeed.updateAndGet { maxOf(it, curSpeed) }
                        val currentActiveCount = activeRanges.size
                        maxConnsObserved.updateAndGet { maxOf(it, currentActiveCount) }
                        telemetryLedger.recordEnginePeak(ResolvedEngineBackend.KOTLIN_OKHTTP, curSpeed)
                        lastSampleTime = now
                        lastSampleBytes = curBytes

                        if (settings.enableAutoSpeedUp && probeResult.supportsRange && totalBytes > OKHTTP_MIN_EXTENT_BYTES) {
                            val liveLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
                            val minW = maxOf(liveLanes.size * 3, 4).coerceAtMost(maxCap)
                            val anyBreakerOpen = networkMonitor.breakerRegistry.values.any {
                                it.currentState(now) != BreakerState.CLOSED
                            }
                            val curTtfb = telemetryLedger.latestTtfbMs()
                                .takeIf { it in 1L..5_000L }?.toDouble() ?: prevTtfb
                            val adjusted = LinkScheduler.adjustAutoSpeedUp(
                                currentWorkers = targetWorkers.get(),
                                minWorkers = minW,
                                maxWorkers = effectiveMaxCap,
                                prevGoodputBps = prevGoodputBps,
                                newGoodputBps = curSpeed.toDouble(),
                                deltaRttMs = (curTtfb - prevTtfb).coerceAtLeast(0.0),
                                throttled = anyBreakerOpen,
                            )
                            targetWorkers.set(adjusted)
                            // EWMA avoids shedding lanes on every normal 5G wobble.
                            prevGoodputBps = if (prevGoodputBps <= 0.0) {
                                curSpeed.toDouble()
                            } else {
                                0.6 * prevGoodputBps + 0.4 * curSpeed
                            }
                            prevTtfb = curTtfb
                        }

                        // Non-blocking batched disk barrier every 2,000 ms (never stalls workers!)
                        val snapshotBits = bitMutex.withLock {
                            syncLedgerFromBitChars(ledger, bitChars)
                            String(bitChars)
                        }
                        if (now - lastDiskForceMs >= 2_000L) {
                            runCatching { dest.forceData() }
                            runCatching { ledger.persist(ledgerFileProvider(id)) }
                            lastDiskForceMs = now
                        }
                        networkMonitor.publishState()

                        val latest = repository.getById(id) ?: break
                        if (!latest.statusEnum.isActive) break
                        val elapsedTotalMs = baseActiveMs + (now - startTimeMs).coerceAtLeast(1L)
                        val liveAvgBps = if (elapsedTotalMs > 0L) (curBytes * 1000L) / elapsedTotalMs else 0L
                        repository.update(
                            latest.copy(
                                downloadedBytes = if (totalBytes > 0L) curBytes.coerceAtMost(totalBytes) else curBytes,
                                wifiBytes = taskWifiBytes.get(),
                                wifi2Bytes = taskWifi2Bytes.get(),
                                cellBytes = taskCellBytes.get(),
                                usbBytes = taskUsbBytes.get(),
                                segmentBitfield = snapshotBits,
                                syscallWriteBatches = syscallBatches.get(),
                                avgSpeedBps = liveAvgBps,
                                peakSpeedBps = peakSpeed.get(),
                                workerCount = targetWorkers.get(),
                                activeDownloadMs = elapsedTotalMs,
                                maxConnectionsUsed = maxOf(maxConnsObserved.get(), targetWorkers.get()),
                                updatedAt = now,
                            )
                        )
                    }
                }

                try {
                    if (probeResult.supportsRange && totalBytes > OKHTTP_MIN_EXTENT_BYTES && dest.isRandomAccess) {
                        val nextIndex = AtomicInteger(0)

                        coroutineScope {
                            // Parked workers exit when there is no work left AND the
                            // governor target has not grown beyond their index.
                            val jobs = (0 until maxPoolCoroutines).map { workerId ->
                                async(Dispatchers.IO) {
                                    while (isActive && failureReason == null) {
                                        if (workerId >= targetWorkers.get()) {
                                            // O(1) pending check instead of scanning the whole
                                            // (possibly 16k-entry) bitfield every 150 ms under
                                            // the global claim mutex.
                                            if (pendingSegments.get() <= 0 && activeRanges.isEmpty()) break
                                            delay(150L)
                                            continue
                                        }

                                        val segIdx = claimNextPendingSegment(bitChars, bitMutex, nextIndex)
                                        val unit: OkHttpWorkUnit? = if (segIdx != -1) {
                                            val s = segIdx.toLong() * segSize
                                            val e = minOf(s + segSize - 1L, totalBytes - 1L)
                                            if (s <= e) OkHttpWorkUnit(segIdx, s, e, false) else null
                                        } else if (settings.enableTailHedge ||
                                            settings.throughputProfile != ThroughputProfile.MAX_MULTI_SOCKET
                                        ) {
                                            tryStealBisectedRange(id, activeRanges)
                                        } else {
                                            null
                                        }

                                        if (unit == null) {
                                            if (activeRanges.isEmpty()) break
                                            delay(80L)
                                            continue
                                        }

                                        try {
                                            downloadRangeWithPinnedLink(
                                                taskId = id,
                                                workerId = workerId,
                                                urls = urls,
                                                unit = unit,
                                                totalBytes = totalBytes,
                                                routeMode = entity.routeModeEnum,
                                                settings = settings,
                                                browserProfile = entity.browserProfile,
                                                dest = dest,
                                                bitChars = bitChars,
                                                bitMutex = bitMutex,
                                                liveWrittenBytes = liveWrittenBytes,
                                                taskWifiBytes = taskWifiBytes,
                                                taskWifi2Bytes = taskWifi2Bytes,
                                                taskCellBytes = taskCellBytes,
                                                taskUsbBytes = taskUsbBytes,
                                                cellCapBytes = cellLimit,
                                                syscallBatches = syscallBatches,
                                                queuedBytesByLink = queuedBytesByLink,
                                                activeWorkersByLink = activeWorkersByLink,
                                                activeRanges = activeRanges,
                                                callsForTask = callsForTask,
                                            )
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (authEx: HttpAuthExpiredException) {
                                            failureReason = DownloadStatus.TOKEN_EXPIRED_R2 to
                                                "HTTP ${authEx.code} signed token expired. Update URL to resume."
                                            break
                                        } catch (sec: SecurityException) {
                                            failureReason = DownloadStatus.SAF_EBADF_R5 to
                                                "SAF permission lost during write (EBADF)."
                                            break
                                        } catch (ex: Exception) {
                                            val attempts = segmentRetries
                                                .computeIfAbsent(unit.segIndex) { AtomicInteger(0) }
                                                .incrementAndGet()
                                            val canRetry = attempts < OKHTTP_MAX_SEGMENT_RETRIES &&
                                                (ex is LinkStallException || isNetworkFlap(ex) || ex is IOException)
                                            // On ANY failure, cancel every active range for
                                            // this segment, restore its full extent, and reset
                                            // to PENDING. This guarantees the whole segment is
                                            // re-downloaded from scratch with no overlapping
                                            // pwrites and no permanently un-COMMITTABLE state.
                                            bisectParts[id]?.remove(unit.segIndex)
                                            val fullEnd = minOf(
                                                unit.segIndex.toLong() * segSize + segSize - 1L,
                                                totalBytes - 1L,
                                            )
                                            val siblingCalls = mutableListOf<Call>()
                                            activeRanges.entries.removeIf { entry ->
                                                val range = entry.value
                                                if (range.segIndex == unit.segIndex) {
                                                    range.endOffset.set(fullEnd)
                                                    synchronized(callsForTask) {
                                                        callsForTask.firstOrNull {
                                                        it.request().header("Range")?.contains("=${range.segIndex.toLong() * segSize}-") == true
                                                    }?.let { siblingCalls.add(it) }
                                                    }
                                                    true
                                                } else false
                                            }
                                            siblingCalls.forEach { runCatching { it.cancel() } }
                                            bitMutex.withLock {
                                                if (SegmentState.fromCode(bitChars[unit.segIndex]) != SegmentState.COMMITTED) {
                                                    bitChars[unit.segIndex] = SegmentState.PENDING.code
                                                    pendingSegments.incrementAndGet()
                                                }
                                            }
                                            if (canRetry) {
                                                nextIndex.updateAndGet { cur -> minOf(cur, unit.segIndex) }
                                                delay(120L)
                                                continue
                                            }
                                            failureReason = if (isNetworkFlap(ex)) {
                                                DownloadStatus.LINK_MIGRATE_R1 to
                                                    "Radio link interrupted — awaiting Wi-Fi/5G/USB migration"
                                            } else {
                                                DownloadStatus.FAILED to (ex.message ?: "Segment ${unit.segIndex} failed")
                                            }
                                            break
                                        }
                                    }
                                }
                            }
                            jobs.awaitAll()
                        }
                    } else {
                        try {
                            downloadSingleStream(
                                taskId = id,
                                urls = urls,
                                routeMode = entity.routeModeEnum,
                                settings = settings,
                                browserProfile = entity.browserProfile,
                                dest = dest,
                                liveWrittenBytes = liveWrittenBytes,
                                taskWifiBytes = taskWifiBytes,
                                taskCellBytes = taskCellBytes,
                                syscallBatches = syscallBatches,
                                callsForTask = callsForTask,
                            )
                            bitMutex.withLock {
                                if (bitChars.isNotEmpty()) bitChars[0] = SegmentState.COMMITTED.code
                            }
                        } catch (authEx: HttpAuthExpiredException) {
                            failureReason = DownloadStatus.TOKEN_EXPIRED_R2 to
                                "HTTP ${authEx.code} signed token expired. Update URL to resume."
                        } catch (ex: Exception) {
                            failureReason = if (isNetworkFlap(ex)) {
                                DownloadStatus.LINK_MIGRATE_R1 to "Radio link interrupted — awaiting network"
                            } else {
                                DownloadStatus.FAILED to (ex.message ?: "Stream error")
                            }
                        }
                    }
                } finally {
                    flusherJob.cancel()
                    telemetryLedger.clearKotlinConnectionsForTask(id)
                    runCatching { dest.forceData() }
                    runCatching {
                        syncLedgerFromBitChars(ledger, bitChars)
                        ledger.persist(ledgerFileProvider(id))
                    }
                }
            }
        } catch (ce: CancellationException) {
            val latest = repository.getById(id)
            if (latest != null && latest.statusEnum.isActive) {
                repository.update(
                    latest.copy(
                        downloadedBytes = liveWrittenBytes.get(),
                        wifiBytes = taskWifiBytes.get(),
                        cellBytes = taskCellBytes.get(),
                        segmentBitfield = String(bitChars),
                        syscallWriteBatches = syscallBatches.get(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            throw ce
        }

        val fail = failureReason
        if (fail != null) {
            if (fail.first == DownloadStatus.LINK_MIGRATE_R1) {
                val updated = entity.copy(
                    status = DownloadStatus.LINK_MIGRATE_R1.name,
                    downloadedBytes = liveWrittenBytes.get(),
                    wifiBytes = taskWifiBytes.get(),
                    cellBytes = taskCellBytes.get(),
                    segmentBitfield = String(bitChars),
                    syscallWriteBatches = syscallBatches.get(),
                    errorMessage = fail.second,
                    updatedAt = System.currentTimeMillis(),
                )
                repository.update(updated)
                if (waitForLinkMigration(updated)) {
                    run(updated, settings)
                    return
                }
            }
            repository.update(
                entity.copy(
                    status = fail.first.name,
                    downloadedBytes = liveWrittenBytes.get(),
                    wifiBytes = taskWifiBytes.get(),
                    cellBytes = taskCellBytes.get(),
                    segmentBitfield = String(bitChars),
                    syscallWriteBatches = syscallBatches.get(),
                    errorMessage = fail.second,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        }

        val finalSize = if (totalBytes > 0L) totalBytes else liveWrittenBytes.get()
        val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
        val avgBps = (finalSize * 1000L) / elapsedMs
        var actualHash: String? = null
        var casDedupBytes = 0L
        var verifiedBitfield = String(bitChars)

        if (settings.enableCasVerification || !entity.expectedSha256.isNullOrBlank()) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.VERIFYING_BAO.name,
                    downloadedBytes = finalSize,
                    wifiBytes = taskWifiBytes.get(),
                    cellBytes = taskCellBytes.get(),
                    segmentBitfield = verifiedBitfield,
                    syscallWriteBatches = syscallBatches.get(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
            val verifyResult = verifyFileAndIndexCas(
                entity = entity,
                segSize = segSize,
                stagingFileIfUsed = if (useStagedSaf) staging else null,
            )
            actualHash = verifyResult.fileSha256
            casDedupBytes = verifyResult.dedupMatchingBytes
            verifiedBitfield = SegmentState.VERIFIED.code.toString().repeat(bitChars.size.coerceAtLeast(1))

            if (!entity.expectedSha256.isNullOrBlank() &&
                !entity.expectedSha256.equals(actualHash, ignoreCase = true)
            ) {
                repository.update(
                    entity.copy(
                        status = DownloadStatus.FAILED.name,
                        totalBytes = finalSize,
                        downloadedBytes = finalSize,
                        actualSha256 = actualHash,
                        errorMessage = "SHA-256 mismatch: expected ${entity.expectedSha256.take(12)}…, got ${actualHash.take(12)}…",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                return
            }
        }

        if (useStagedSaf) {
            val safReady = entity.copy(
                totalBytes = finalSize,
                downloadedBytes = finalSize,
                segmentBitfield = verifiedBitfield,
                actualSha256 = actualHash,
                avgSpeedBps = avgBps,
                peakSpeedBps = maxOf(peakSpeed.get(), avgBps),
                syscallWriteBatches = syscallBatches.get(),
                casDedupSkippedBytes = casDedupBytes,
                wifiBytes = taskWifiBytes.get(),
                cellBytes = taskCellBytes.get(),
            )
            if (!promoteStagedToSaf(safReady, staging)) return
        } else if (!entity.isSafDestination && StoragePlacementHelper.shouldUseDirectMediaStoreFd(finalSize, false)) {
            StoragePlacementHelper.promoteLocalFileToMediaStore(
                context = context,
                sourceFile = File(entity.destinationUri),
                fileName = entity.fileName,
                mimeType = entity.mimeType,
            )
        }

        finalizeCompletedEntity(
            entity = entity.copy(
                activeDownloadMs = baseActiveMs + elapsedMs,
                maxConnectionsUsed = maxOf(maxConnsObserved.get(), entity.workerCount),
                wifi2Bytes = taskWifi2Bytes.get(),
                usbBytes = taskUsbBytes.get(),
            ),
            finalSize = finalSize,
            avgBps = avgBps,
            peakBps = maxOf(peakSpeed.get(), avgBps),
            actualHash = actualHash,
            casDedupBytes = casDedupBytes,
            verifiedBitfield = verifiedBitfield,
            syscallCount = syscallBatches.get(),
            wifiBytes = taskWifiBytes.get(),
            cellBytes = taskCellBytes.get(),
        )
    }

    /**
     * Atomically bisects the slowest or largest remaining active worker range (Anti-Dip).
     */
    private fun tryStealBisectedRange(
        taskId: String,
        activeRanges: ConcurrentHashMap<String, ActiveOkHttpRange>,
    ): OkHttpWorkUnit? {
        val now = System.currentTimeMillis()
        var bestVictim: ActiveOkHttpRange? = null
        var bestScore = -1.0

        for (st in activeRanges.values) {
            val cur = st.currentOffset.get()
            val end = st.endOffset.get()
            if (end <= cur) continue
            val rem = end - cur + 1L
            val isStalled = (now - st.lastProgressMs.get()) >= 900L
            val minReq = if (isStalled) OKHTTP_STALL_BISECT_BYTES else OKHTTP_MIN_BISECT_BYTES
            if (rem < minReq) continue
            val speed = st.ewmaBps.get().coerceAtLeast(65_536L).toDouble()
            val score = (rem.toDouble() / speed) + if (isStalled) 10.0 else 0.0
            if (score > bestScore) {
                bestScore = score
                bestVictim = st
            }
        }
        val victim = bestVictim ?: return null
        while (true) {
            val cur = victim.currentOffset.get()
            val oldEnd = victim.endOffset.get()
            if (oldEnd <= cur) return null
            val rem = oldEnd - cur + 1L
            if (rem < OKHTTP_STALL_BISECT_BYTES) return null
            val mid = cur + (rem / 2L)
            val newVictimEnd = mid - 1L
            if (newVictimEnd <= cur || mid > oldEnd) return null
            if (victim.endOffset.compareAndSet(oldEnd, newVictimEnd)) {
                telemetryLedger.recordAntiDipRescue()
                // Two writers now own this segment; it is COMMITTED only when both finish.
                bisectParts.computeIfAbsent(taskId) { ConcurrentHashMap() }
                    .compute(victim.segIndex) { _, prev ->
                        (prev ?: AtomicInteger(1)).also { it.incrementAndGet() }
                    }
                return OkHttpWorkUnit(
                    segIndex = victim.segIndex,
                    startOffset = mid,
                    endOffset = oldEnd,
                    isBisectedSteal = true,
                )
            }
        }
    }

    private suspend fun downloadRangeWithPinnedLink(
        taskId: String,
        workerId: Int,
        urls: List<String>,
        unit: OkHttpWorkUnit,
        totalBytes: Long,
        routeMode: NetworkRouteMode,
        settings: EngineSettings,
        browserProfile: String,
        dest: DestinationHandle,
        bitChars: CharArray,
        bitMutex: Mutex,
        liveWrittenBytes: AtomicLong,
        taskWifiBytes: AtomicLong,
        taskWifi2Bytes: AtomicLong,
        taskCellBytes: AtomicLong,
        taskUsbBytes: AtomicLong,
        cellCapBytes: Long,
        syscallBatches: AtomicInteger,
        queuedBytesByLink: ConcurrentHashMap<String, AtomicLong>,
        activeWorkersByLink: ConcurrentHashMap<String, AtomicInteger>,
        activeRanges: ConcurrentHashMap<String, ActiveOkHttpRange>,
        callsForTask: MutableList<Call>,
    ): Boolean {
        val startOffset = unit.startOffset
        val initialEndOffset = unit.endOffset
        if (startOffset > initialEndOffset) return false
        val segLen = (initialEndOffset - startOffset + 1L)

        var baseLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, routeMode)
            .map { lane ->
                val q = queuedBytesByLink[lane.linkKeyId]?.get()?.toDouble() ?: 0.0
                val wCount = activeWorkersByLink[lane.linkKeyId]?.get() ?: 0
                lane.copy(queuedBytes = q, activeWorkersOnLink = wCount)
            }

        if (taskCellBytes.get() >= cellCapBytes) {
            val nonCell = baseLanes.filterNot { it.linkKeyId.startsWith("cell:") }
            if (nonCell.isNotEmpty()) baseLanes = nonCell
        }

        val minPerLink = if (settings.forceAllLinks && baseLanes.size > 1) {
            maxOf(2, (settings.maxConcurrentWorkers / (baseLanes.size * 2)).coerceIn(2, 8))
        } else {
            1
        }
        val selectedLane = LinkScheduler.selectLinkWithForceAll(
            lanes = baseLanes,
            breakers = networkMonitor.breakerRegistry,
            segmentBytes = segLen.toDouble(),
            forceAllLinks = settings.forceAllLinks,
            workerId = workerId,
            minReservedPerLink = minPerLink,
        ) ?: LinkScheduler.selectLinkWithForceAll(
            lanes = baseLanes,
            breakers = emptyMap(),
            segmentBytes = segLen.toDouble(),
            forceAllLinks = settings.forceAllLinks,
            workerId = workerId,
            minReservedPerLink = minPerLink,
        )

        val pinnedLinkKeyId = selectedLane?.linkKeyId
        val pinnedEndpoint = pinnedLinkKeyId?.let { networkMonitor.getNetworkByKey(it) }
        val isCell = pinnedEndpoint?.linkKey?.isCellular == true || routeMode == NetworkRouteMode.CELLULAR_ONLY
        val isWifi2 = pinnedLinkKeyId?.startsWith("wifi2:") == true
        val isEth = pinnedLinkKeyId?.startsWith("eth:") == true || pinnedLinkKeyId?.startsWith("usb:") == true
        val targetUrl = LinkScheduler.selectMirrorForLink(urls, pinnedLinkKeyId ?: "wifi:wlan0", unit.segIndex)
        // Shard client pool across workerId % 16 so every worker group gets its own independent TCP socket!
        val client = getOrBuildClientForLink(pinnedEndpoint, routeMode, settings, shardId = workerId % 16)

        if (!unit.isBisectedSteal) {
            bitMutex.withLock {
                val cur = SegmentState.fromCode(bitChars[unit.segIndex])
                if (cur == SegmentState.COMMITTED || cur == SegmentState.VERIFIED) return false
                bitChars[unit.segIndex] = when {
                    isCell -> SegmentState.ACTIVE_5G.code
                    else -> SegmentState.ACTIVE_WIFI.code
                }
            }
        } else {
            bitMutex.withLock {
                if (unit.segIndex < bitChars.size &&
                    SegmentState.fromCode(bitChars[unit.segIndex]) != SegmentState.COMMITTED
                ) {
                    bitChars[unit.segIndex] = SegmentState.STOLEN_HEDGE.code
                }
            }
        }

        val queueCounter = pinnedLinkKeyId?.let {
            queuedBytesByLink.computeIfAbsent(it) { AtomicLong(0L) }
        }
        val linkWorkerCounter = pinnedLinkKeyId?.let {
            activeWorkersByLink.computeIfAbsent(it) { AtomicInteger(0) }
        }
        queueCounter?.addAndGet(segLen)
        linkWorkerCounter?.incrementAndGet()
        var reservedRemaining = segLen

        val connId = "ok-${taskId.take(6)}-w$workerId-${unit.segIndex}"
        val nowMs = System.currentTimeMillis()
        val activeState = ActiveOkHttpRange(
            segIndex = unit.segIndex,
            currentOffset = AtomicLong(startOffset),
            endOffset = AtomicLong(initialEndOffset),
            ewmaBps = AtomicLong(0L),
            lastProgressMs = AtomicLong(nowMs),
        )
        activeRanges[connId] = activeState

        telemetryLedger.workerStarted(pinnedLinkKeyId)
        val slab = slabPool.acquire()
        val stalledOut = AtomicBoolean(false)
        val segStartNs = System.nanoTime()
        var segWritten = 0L
        var chunkBytes = 0L
        var lastTelemetryPushMs = 0L
        var retryAfterMs = 0L

        var callRef: Call? = null
        val watchdogJob = engineScope.launch {
            while (isActive) {
                delay(500L)
                if (System.currentTimeMillis() - activeState.lastProgressMs.get() >= OKHTTP_STALL_WATCHDOG_MS) {
                    stalledOut.set(true)
                    callRef?.cancel()
                    break
                }
            }
        }

        try {
            val req = Request.Builder()
                .url(targetUrl)
                .header("Range", "bytes=$startOffset-$initialEndOffset")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", userAgentForProfile(browserProfile))
                .build()
            val call = client.newCall(req)
            callRef = call
            synchronized(callsForTask) { callsForTask.add(call) }

            val reqStartMs = System.currentTimeMillis()
            call.execute().use { resp ->
                val ttfb = (System.currentTimeMillis() - reqStartMs).coerceAtLeast(1L)
                activeState.lastProgressMs.set(System.currentTimeMillis())
                if (resp.code == 429 || resp.code == 503) {
                    // aria2-next/MDM rule: Retry-After is a floor, never shortened.
                    retryAfterMs = resp.header("Retry-After")?.toLongOrNull()
                        ?.let { (it * 1000L).coerceIn(0L, 600_000L) } ?: 0L
                }
                if (resp.code == 403 || resp.code == 410) throw HttpAuthExpiredException(resp.code)
                // A server that answers a Range request with 200 sends the WHOLE
                // file; writing it at `startOffset` silently corrupts the file.
                if (resp.code == 200 && req.header("Range") != null) {
                    throw IOException("Server ignored Range (HTTP 200) on segment ${unit.segIndex}")
                }
                if (!resp.isSuccessful && resp.code != 206) throw IOException("HTTP ${resp.code} on segment ${unit.segIndex}")

                val proto = resp.protocol.toString().uppercase()
                val source = resp.body?.source() ?: throw IOException("Empty body on segment ${unit.segIndex}")
                var offset = startOffset
                var slabWriteOffset = startOffset
                slab.clear()

                while (true) {
                    val dynamicEnd = activeState.endOffset.get()
                    if (offset > dynamicEnd) break

                    // Cap read to remaining bytes in our dynamic (possibly bisected) range
                    val remainingInRange = (dynamicEnd - offset + 1L)
                    if (slab.remaining().toLong() > remainingInRange) {
                        slab.limit(slab.position() + remainingInRange.toInt())
                    }

                    val read = source.read(slab)
                    if (read == -1) break
                    if (read > 0) {
                        val tickNow = System.currentTimeMillis()
                        activeState.lastProgressMs.set(tickNow)
                        offset += read
                        segWritten += read
                        activeState.currentOffset.set(offset)

                        val dec = minOf(reservedRemaining, read.toLong())
                        reservedRemaining -= dec
                        queueCounter?.addAndGet(-dec)

                        // Coalesce all per-chunk byte accounting into the slab flush.
                        // Previously these 4-6 atomics ran per socket read (often 8-64 KiB),
                        // which is thousands of contended RMWs/sec at 128 workers.
                        chunkBytes += read.toLong()

                        if (!slab.hasRemaining() || offset > activeState.endOffset.get()) {
                            slab.flip()
                            dest.writeAt(slab, slabWriteOffset)
                            slabWriteOffset = offset
                            slab.clear()
                            syscallBatches.incrementAndGet()
                            telemetryLedger.recordSyscallWriteBatch()
                            liveWrittenBytes.addAndGet(chunkBytes)
                            when {
                                isCell -> taskCellBytes.addAndGet(chunkBytes)
                                isWifi2 -> taskWifi2Bytes.addAndGet(chunkBytes)
                                isEth -> taskUsbBytes.addAndGet(chunkBytes)
                                else -> taskWifiBytes.addAndGet(chunkBytes)
                            }
                            telemetryLedger.recordLinkBytesWritten(pinnedLinkKeyId, chunkBytes)
                            chunkBytes = 0L
                        }

                        if (tickNow - lastTelemetryPushMs >= OKHTTP_TELEMETRY_THROTTLE_MS) {
                            val elapsedSec = ((System.nanoTime() - segStartNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                            val bps = (segWritten / elapsedSec).toLong()
                            activeState.ewmaBps.set(bps)
                            if (pinnedLinkKeyId != null && bps > 0L) {
                                networkMonitor.updateLinkEwma(pinnedLinkKeyId, (bps * 8.0) / 1_000_000.0)
                            }
                            telemetryLedger.upsertKotlinConnection(
                                ActiveConnectionInfo(
                                    connId = connId,
                                    taskId = taskId,
                                    url = targetUrl,
                                    linkKeyId = pinnedLinkKeyId ?: "default",
                                    protocol = "$proto (OkHttp)",
                                    socketState = if (unit.isBisectedSteal) "Anti-Dip Split" else "Streaming",
                                    ttfbMs = ttfb,
                                    bytesReceived = segWritten,
                                    speedBps = bps,
                                    rangeStart = startOffset,
                                    rangeEnd = activeState.endOffset.get(),
                                )
                            )
                            lastTelemetryPushMs = tickNow
                        }
                    }
                }

                // Flush remaining coalesced slab bytes
                if (slab.position() > 0) {
                    slab.flip()
                    dest.writeAt(slab, slabWriteOffset)
                    slab.clear()
                    syscallBatches.incrementAndGet()
                    telemetryLedger.recordSyscallWriteBatch()
                }
                // Account any tail bytes not covered by a full-slab flush.
                if (chunkBytes > 0L) {
                    liveWrittenBytes.addAndGet(chunkBytes)
                    when {
                        isCell -> taskCellBytes.addAndGet(chunkBytes)
                        isWifi2 -> taskWifi2Bytes.addAndGet(chunkBytes)
                        isEth -> taskUsbBytes.addAndGet(chunkBytes)
                        else -> taskWifiBytes.addAndGet(chunkBytes)
                    }
                    telemetryLedger.recordLinkBytesWritten(pinnedLinkKeyId, chunkBytes)
                    chunkBytes = 0L
                }

                val parts = bisectParts[taskId]?.get(unit.segIndex)
                val fullyDone = if (parts == null) {
                    true
                } else {
                    parts.decrementAndGet() <= 0
                }
                if (fullyDone) {
                    bisectParts[taskId]?.remove(unit.segIndex)
                    bitMutex.withLock {
                        if (unit.segIndex < bitChars.size) {
                            bitChars[unit.segIndex] = SegmentState.COMMITTED.code
                        }
                    }
                }

                if (pinnedLinkKeyId != null && segWritten > 0L) {
                    val elapsedSec = ((System.nanoTime() - segStartNs) / 1_000_000_000.0).coerceAtLeast(0.01)
                    val mbps = (segWritten * 8.0) / (elapsedSec * 1_000_000.0)
                    networkMonitor.updateLinkEwma(pinnedLinkKeyId, mbps)
                    networkMonitor.breakerRegistry[pinnedLinkKeyId]?.recordSuccess()
                }
                runCatching { java.net.URL(urls.firstOrNull().orEmpty()).host }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { host ->
                        hostLimits.working(host, activeWorkersByLink.values.sumOf { it.get() })
                    }
                return true
            }
        } catch (ce: CancellationException) {
            if (stalledOut.get()) {
                pinnedLinkKeyId?.let {
                    // Non-immediate trip (requires 3 consecutive failures) kills Wi-Fi <-> 5G flip-flopping!
                    networkMonitor.breakerRegistry[it]?.recordFailureOrStall(immediateTrip = false)
                    networkMonitor.publishState()
                }
                throw LinkStallException(pinnedLinkKeyId ?: "unknown")
            }
            throw ce
        } catch (ex: Exception) {
            if (pinnedLinkKeyId != null && ex !is HttpAuthExpiredException) {
                networkMonitor.breakerRegistry[pinnedLinkKeyId]?.recordFailureOrStall(immediateTrip = false)
                networkMonitor.publishState()
            }
            // A refusal while other connections are receiving tells us the host's
            // real connection ceiling. Learn it so future workers do not overshoot.
            val msg = ex.message.orEmpty()
            if (msg.contains("429") || msg.contains("503") ||
                msg.contains("connect", ignoreCase = true) || msg.contains("refused", ignoreCase = true)
            ) {
                val host = runCatching { java.net.URL(urls.firstOrNull().orEmpty()).host }.getOrNull()
                if (!host.isNullOrBlank()) {
                    val receiving = activeWorkersByLink.values.sumOf { it.get() }
                    hostLimits.refused(host, receiving)
                }
            }
            if (retryAfterMs > 0) {
                DiagnosticsCenter.log("RETRY_AFTER", "Server asked for ${retryAfterMs}ms backoff")
                // Stop the watchdog before sleeping: otherwise a Retry-After longer
                // than the stall threshold trips the breaker for a compliant server.
                watchdogJob.cancel()
                delay(retryAfterMs)
            }
            if (stalledOut.get()) throw LinkStallException(pinnedLinkKeyId ?: "unknown")
            throw ex
        } finally {
            watchdogJob.cancel()
            activeRanges.remove(connId)
            telemetryLedger.removeKotlinConnection(connId)
            if (reservedRemaining > 0L) queueCounter?.addAndGet(-reservedRemaining)
            linkWorkerCounter?.decrementAndGet()
            slabPool.release(slab)
            telemetryLedger.workerStopped(pinnedLinkKeyId)
        }
    }

    private fun downloadSingleStream(
        taskId: String,
        urls: List<String>,
        routeMode: NetworkRouteMode,
        settings: EngineSettings,
        browserProfile: String,
        dest: DestinationHandle,
        liveWrittenBytes: AtomicLong,
        taskWifiBytes: AtomicLong,
        taskCellBytes: AtomicLong,
        syscallBatches: AtomicInteger,
        callsForTask: MutableList<Call>,
    ) {
        val lanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, routeMode)
        val selectedLane = LinkScheduler.selectEarliestArrivalLink(lanes, networkMonitor.breakerRegistry, settings.segmentSizeBytes.toDouble())
        val endpoint = selectedLane?.let { networkMonitor.getNetworkByKey(it.linkKeyId) }
        val isCell = endpoint?.linkKey?.isCellular == true || routeMode == NetworkRouteMode.CELLULAR_ONLY
        val isEth = selectedLane?.linkKeyId?.startsWith("eth:") == true
        val client = getOrBuildClientForLink(endpoint, routeMode, settings)

        val connId = "ok-${taskId.take(6)}-single"
        telemetryLedger.workerStarted(selectedLane?.linkKeyId)
        val slab = slabPool.acquire()
        val segStartNs = System.nanoTime()
        var lastPushMs = 0L
        try {
            val req = Request.Builder()
                .url(urls.first())
                .header("Accept-Encoding", "identity")
                .header("User-Agent", userAgentForProfile(browserProfile))
                .build()
            val call = client.newCall(req)
            synchronized(callsForTask) { callsForTask.add(call) }
            val reqStart = System.currentTimeMillis()
            call.execute().use { resp ->
                val ttfb = (System.currentTimeMillis() - reqStart).coerceAtLeast(1L)
                if (resp.code == 403 || resp.code == 410) throw HttpAuthExpiredException(resp.code)
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val source = resp.body?.source() ?: throw IOException("Empty response body")
                var offset = 0L
                var slabWriteOffset = 0L
                slab.clear()
                while (true) {
                    val read = source.read(slab)
                    if (read == -1) break
                    if (read > 0) {
                        offset += read
                        liveWrittenBytes.addAndGet(read.toLong())
                        when {
                            isCell -> taskCellBytes.addAndGet(read.toLong())
                            !isEth -> taskWifiBytes.addAndGet(read.toLong())
                        }
                        telemetryLedger.recordLinkBytesWritten(selectedLane?.linkKeyId, read.toLong())
                        if (slab.position() >= OKHTTP_SLAB_FLUSH_THRESHOLD || !slab.hasRemaining()) {
                            slab.flip()
                            dest.writeAt(slab, slabWriteOffset)
                            slabWriteOffset = offset
                            slab.clear()
                            syscallBatches.incrementAndGet()
                            telemetryLedger.recordSyscallWriteBatch()
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastPushMs >= OKHTTP_TELEMETRY_THROTTLE_MS) {
                            val elapsedSec = ((System.nanoTime() - segStartNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                            telemetryLedger.upsertKotlinConnection(
                                ActiveConnectionInfo(
                                    connId = connId,
                                    taskId = taskId,
                                    url = urls.first(),
                                    linkKeyId = selectedLane?.linkKeyId ?: "default",
                                    protocol = "${resp.protocol} (OkHttp)",
                                    socketState = "Single Stream",
                                    ttfbMs = ttfb,
                                    bytesReceived = offset,
                                    speedBps = (offset / elapsedSec).toLong(),
                                    rangeStart = 0L,
                                    rangeEnd = offset,
                                )
                            )
                            lastPushMs = now
                        }
                    }
                }
                if (slab.position() > 0) {
                    slab.flip()
                    dest.writeAt(slab, slabWriteOffset)
                    slab.clear()
                }
                dest.forceData()
            }
        } finally {
            telemetryLedger.removeKotlinConnection(connId)
            slabPool.release(slab)
            telemetryLedger.workerStopped(selectedLane?.linkKeyId)
        }
    }

    private fun performPreflightProbe(
        client: OkHttpClient,
        url: String,
        browserProfile: String,
        callsForTask: MutableList<Call>,
    ): PreflightProbeResult {
        val t0 = System.nanoTime()
        val probeReq = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", userAgentForProfile(browserProfile))
            .build()
        val call = client.newCall(probeReq)
        synchronized(callsForTask) { callsForTask.add(call) }

        call.execute().use { resp ->
            val ttfb = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            if (resp.code == 403 || resp.code == 410) throw HttpAuthExpiredException(resp.code)
            if (!resp.isSuccessful && resp.code != 206) throw IOException("HTTP ${resp.code} ${resp.message}")
            val parsedRangeTotal = resp.header("Content-Range")?.substringAfterLast('/', "")?.toLongOrNull()
            val headerLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val total = when {
                resp.code == 206 && parsedRangeTotal != null -> parsedRangeTotal
                resp.code == 200 && headerLength > 0 -> headerLength
                else -> -1L
            }
            val supportsRange = resp.code == 206 || resp.header("Accept-Ranges").equals("bytes", ignoreCase = true)
            val dispName = StoragePlacementHelper.parseContentDispositionFilename(resp.header("Content-Disposition"))
            return PreflightProbeResult(total, supportsRange, ttfb, resp.header("ETag"), dispName)
        }
    }

    private suspend fun finalizeCompletedEntity(
        entity: DownloadEntity,
        finalSize: Long,
        avgBps: Long,
        peakBps: Long,
        actualHash: String?,
        casDedupBytes: Long,
        verifiedBitfield: String,
        syscallCount: Int,
        wifiBytes: Long,
        cellBytes: Long,
    ) {
        bisectParts.remove(entity.id)
        runCatching { ledgerFileProvider(entity.id).delete() }
        repository.update(
            entity.copy(
                totalBytes = finalSize,
                downloadedBytes = finalSize,
                status = DownloadStatus.COMPLETED.name,
                segmentBitfield = verifiedBitfield,
                actualSha256 = actualHash,
                avgSpeedBps = avgBps,
                peakSpeedBps = peakBps,
                syscallWriteBatches = syscallCount,
                casDedupSkippedBytes = casDedupBytes,
                wifiBytes = wifiBytes,
                cellBytes = cellBytes,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun promoteStagedToSaf(entity: DownloadEntity, staging: File): Boolean {
        return try {
            val uri = Uri.parse(entity.destinationUri)
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Unable to open SAF descriptor (EBADF)")
            pfd.use { descriptor ->
                val nativeCopied = AetherNativeBridge.copyStagingToFdSafe(staging.absolutePath, descriptor.fd)
                if (!nativeCopied) {
                    FileInputStream(staging).channel.use { src ->
                        FileOutputStream(descriptor.fileDescriptor).channel.use { dst ->
                            dst.truncate(0L)
                            var pos = 0L
                            val len = src.size()
                            while (pos < len) pos += src.transferTo(pos, len - pos, dst)
                            dst.force(true)
                        }
                    }
                }
            }
            runCatching { staging.delete() }
            true
        } catch (ex: Exception) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.SAF_EBADF_R5.name,
                    errorMessage = "SAF placement failed (${ex.message}). Staged file preserved — tap Re-Grant SAF.",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            false
        }
    }

    private suspend fun waitForLinkMigration(entity: DownloadEntity): Boolean {
        repeat(20) {
            delay(1000L)
            networkMonitor.refreshState()
            if (networkMonitor.state.value.hasAnyInternet) return true
            val current = repository.getById(entity.id) ?: return false
            if (current.statusEnum == DownloadStatus.PAUSED || current.statusEnum == DownloadStatus.CANCELLED) {
                return false
            }
        }
        return false
    }

    private suspend fun verifyFileAndIndexCas(
        entity: DownloadEntity,
        segSize: Long,
        stagingFileIfUsed: File? = null,
    ): VerifyResult {
        val fileDigest = MessageDigest.getInstance("SHA-256")
        val chunkDigest = MessageDigest.getInstance("SHA-256")
        val chunks = mutableListOf<CasChunkEntity>()
        var dedupMatchBytes = 0L
        val buf = ByteArray(128 * 1024)

        val stream = when {
            stagingFileIfUsed != null && stagingFileIfUsed.exists() -> FileInputStream(stagingFileIfUsed)
            entity.isSafDestination -> {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(entity.destinationUri), "r")
                    ?: return VerifyResult("", 0L)
                ParcelFileDescriptor.AutoCloseInputStream(pfd)
            }
            else -> FileInputStream(File(entity.destinationUri))
        }

        stream.use { input ->
            var chunkRemaining = segSize
            var currentChunkBytes = 0L
            while (true) {
                val maxRead = minOf(buf.size.toLong(), chunkRemaining).toInt()
                val read = input.read(buf, 0, maxRead)
                if (read == -1) break
                fileDigest.update(buf, 0, read)
                chunkDigest.update(buf, 0, read)
                currentChunkBytes += read
                chunkRemaining -= read
                if (chunkRemaining == 0L) {
                    val cHash = chunkDigest.digest().toHex()
                    if (repository.hasCasChunk(cHash)) dedupMatchBytes += currentChunkBytes
                    chunks += CasChunkEntity(cHash, currentChunkBytes, entity.id)
                    chunkDigest.reset()
                    chunkRemaining = segSize
                    currentChunkBytes = 0L
                }
            }
            if (currentChunkBytes > 0L) {
                val cHash = chunkDigest.digest().toHex()
                if (repository.hasCasChunk(cHash)) dedupMatchBytes += currentChunkBytes
                chunks += CasChunkEntity(cHash, currentChunkBytes, entity.id)
            }
        }
        repository.recordCasChunks(chunks)
        return VerifyResult(fileDigest.digest().toHex(), dedupMatchBytes)
    }

    /**
     * Returns a persistent sharded warm `OkHttpClient` pooled per `(linkKey, netHandle, proxySpec, shardId)`.
     * Sharding forces independent TCP congestion windows (`Protocol.HTTP_1_1` multi-socket lanes)
     * so 16-64 workers NEVER collapse onto 1 single HTTP/2 TCP stream!
     */
    private fun getOrBuildClientForLink(
        endpoint: LinkEndpointInfo?,
        routeMode: NetworkRouteMode,
        settings: EngineSettings,
        shardId: Int = 0,
    ): OkHttpClient {
        val isCell = endpoint?.linkKey?.isCellular == true
        val isRelay = endpoint?.isRelay == true

        val resolvedHost: String
        val resolvedPort: Int
        val proxyType: Proxy.Type
        if (isRelay && endpoint != null && endpoint.relayProxyHost.isNotBlank()) {
            resolvedHost = endpoint.relayProxyHost.trim()
            resolvedPort = endpoint.relayProxyPort
            proxyType = if (endpoint.relayIsHttp) Proxy.Type.HTTP else Proxy.Type.SOCKS
        } else {
            val (linkProxyHost, linkProxyPort) = if (isCell) {
                settings.cellularProxyHost.trim() to settings.cellularProxyPort
            } else {
                settings.proxyHost.trim() to settings.proxyPort
            }
            resolvedHost = when {
                linkProxyHost.isNotBlank() -> linkProxyHost
                routeMode == NetworkRouteMode.PROXY_SOCKS5 && settings.proxyHost.isNotBlank() -> settings.proxyHost.trim()
                else -> ""
            }
            resolvedPort = if (linkProxyHost.isNotBlank()) linkProxyPort else settings.proxyPort
            proxyType = Proxy.Type.SOCKS
        }

        val effectiveShard = shardId.coerceIn(0, 15)
        val linkId = endpoint?.linkKey?.id ?: "default"
        val handle = endpoint?.netHandle ?: 0L

        // Evict clients bound to an older Network handle. OkHttpClients created via
        // newBuilder() share the base pool, but we give each shard its own pool, so
        // stale entries otherwise leak sockets/threads on every Wi-Fi/5G reconnect.
        if (handle != 0L) {
            val stale = clientPool.keys.filter { it.startsWith("$linkId|") && !it.contains("|$handle|") }
            stale.forEach { key ->
                clientPool.remove(key)?.let { old ->
                    runCatching { old.connectionPool.evictAll() }
                    runCatching { old.dispatcher.executorService.shutdown() }
                }
            }
        }

        val cacheKey = "$linkId|$handle|$proxyType|$resolvedHost:$resolvedPort|s$effectiveShard"
        return clientPool.computeIfAbsent(cacheKey) {
            val builder = baseClient.newBuilder()
                .connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
                .dispatcher(sharedDispatcher)
                .protocols(listOf(Protocol.HTTP_1_1))

            if (resolvedHost.isNotBlank()) {
                builder.proxy(Proxy(proxyType, InetSocketAddress.createUnresolved(resolvedHost, resolvedPort)))
                val baseSf = endpoint?.network?.socketFactory ?: SocketFactory.getDefault()
                builder.socketFactory(TunedSocketFactory(baseSf, OKHTTP_TCP_RCVBUF_BYTES))
            } else {
                builder.proxy(Proxy.NO_PROXY)
                if (endpoint != null) {
                    val net: Network = endpoint.network
                    builder.socketFactory(TunedSocketFactory(net.socketFactory, OKHTTP_TCP_RCVBUF_BYTES))
                    builder.dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> = try {
                            net.getAllByName(hostname).toList()
                        } catch (_: Exception) {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    })
                } else {
                    builder.socketFactory(TunedSocketFactory(SocketFactory.getDefault(), OKHTTP_TCP_RCVBUF_BYTES))
                }
            }
            builder.build()
        }
    }

    private fun isStreamingManifestUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd")
    }

    /**
     * Real HLS (`.m3u8`) & DASH (`.mpd`) parallel segment downloader across all active links.
     * Emits live segment filenames into [TelemetryLedger.recordLiveSegment] to power Rayburst live pills.
     */
    private suspend fun runHlsDashParallelTransfer(
        entity: DownloadEntity,
        urls: List<String>,
        settings: EngineSettings,
        callsForTask: MutableList<Call>,
    ) {
        val manifestUrl = urls.first()
        val lanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
        val probeEndpoint = lanes.firstOrNull()?.let { networkMonitor.getNetworkByKey(it.linkKeyId) }
        val manifestClient = getOrBuildClientForLink(probeEndpoint, entity.routeModeEnum, settings, shardId = 0)
        val startMs = System.currentTimeMillis()

        val segmentUrls = try {
            val req = Request.Builder()
                .url(manifestUrl)
                .header("User-Agent", userAgentForProfile(entity.browserProfile))
                .build()
            val body = manifestClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Manifest HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            parseMediaSegments(manifestUrl, body)
        } catch (ex: Exception) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = "Manifest parse failed: ${ex.message}",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        }

        if (segmentUrls.isEmpty()) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = "No downloadable media segments found in playlist",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        }

        val bitChars = CharArray(segmentUrls.size) { SegmentState.PENDING.code }
        val destFile = File(entity.destinationUri).apply { parentFile?.mkdirs() }
        val tempDir = File(context.cacheDir, "hls_${entity.id}").apply { mkdirs() }
        val totalWritten = AtomicLong(0L)
        val wifiBytes = AtomicLong(0L)
        val cellBytes = AtomicLong(0L)
        val wifi2Bytes = AtomicLong(0L)
        val usbBytes = AtomicLong(0L)
        val nextIdx = AtomicInteger(0)

        repository.update(
            entity.copy(
                status = DownloadStatus.STREAMING.name,
                streamType = "HLS_DASH",
                segmentBitfield = String(bitChars),
                updatedAt = System.currentTimeMillis(),
            )
        )

        val workerTotal = settings.maxConcurrentWorkers.coerceIn(2, 16)
        try {
            coroutineScope {
                val jobs = (0 until workerTotal).map { wId ->
                    async(Dispatchers.IO) {
                        while (isActive) {
                            val idx = nextIdx.getAndIncrement()
                            if (idx >= segmentUrls.size) break
                            val segUrl = segmentUrls[idx]
                            val segName = segUrl.substringBefore('?').substringAfterLast('/')
                                .ifBlank { "seg-%04d.ts".format(idx + 1) }
                            val curLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
                            val lane = curLanes.getOrNull(wId % curLanes.size.coerceAtLeast(1))
                            val ep = lane?.let { networkMonitor.getNetworkByKey(it.linkKeyId) }
                            val client = getOrBuildClientForLink(ep, entity.routeModeEnum, settings, shardId = wId % 8)

                            bitChars[idx] = if (ep?.linkKey?.isCellular == true) SegmentState.ACTIVE_5G.code else SegmentState.ACTIVE_WIFI.code
                            telemetryLedger.workerStarted(lane?.linkKeyId)
                            try {
                                val req = Request.Builder()
                                    .url(segUrl)
                                    .header("User-Agent", userAgentForProfile(entity.browserProfile))
                                    .build()
                                val call = client.newCall(req)
                                synchronized(callsForTask) { callsForTask.add(call) }
                                call.execute().use { resp ->
                                    if (!resp.isSuccessful) throw IOException("Segment HTTP ${resp.code}")
                                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                                    File(tempDir, "s_%06d.part".format(idx)).writeBytes(bytes)
                                    val n = bytes.size.toLong()
                                    totalWritten.addAndGet(n)
                                    when {
                                        lane?.linkKeyId?.startsWith("cell:") == true -> cellBytes.addAndGet(n)
                                        lane?.linkKeyId?.startsWith("wifi2:") == true -> wifi2Bytes.addAndGet(n)
                                        lane?.linkKeyId?.startsWith("eth:") == true -> usbBytes.addAndGet(n)
                                        else -> wifiBytes.addAndGet(n)
                                    }
                                    telemetryLedger.recordLinkBytesWritten(lane?.linkKeyId, n)
                                    telemetryLedger.recordLiveSegment(segName)
                                    bitChars[idx] = SegmentState.COMMITTED.code
                                }
                            } finally {
                                telemetryLedger.workerStopped(lane?.linkKeyId)
                            }
                            if (idx % 3 == 0 || idx == segmentUrls.lastIndex) {
                                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                                val written = totalWritten.get()
                                repository.update(
                                    entity.copy(
                                        status = DownloadStatus.STREAMING.name,
                                        streamType = "HLS_DASH",
                                        downloadedBytes = written,
                                        totalBytes = if (idx > 0) (written * segmentUrls.size) / (idx + 1) else written,
                                        segmentBitfield = String(bitChars),
                                        wifiBytes = wifiBytes.get(),
                                        wifi2Bytes = wifi2Bytes.get(),
                                        cellBytes = cellBytes.get(),
                                        usbBytes = usbBytes.get(),
                                        avgSpeedBps = (written * 1000L) / elapsed,
                                        activeDownloadMs = elapsed,
                                        maxConnectionsUsed = workerTotal,
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }

            // Assemble ordered segments into destination media file
            FileOutputStream(destFile).use { fos ->
                for (i in segmentUrls.indices) {
                    val part = File(tempDir, "s_%06d.part".format(i))
                    if (part.exists()) {
                        part.inputStream().use { it.copyTo(fos) }
                    }
                }
                fos.fd.sync()
            }
            val finalBytes = destFile.length()
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val avg = (finalBytes * 1000L) / elapsed
            finalizeCompletedEntity(
                entity = entity.copy(
                    streamType = "HLS_DASH",
                    activeDownloadMs = elapsed,
                    maxConnectionsUsed = workerTotal,
                    wifi2Bytes = wifi2Bytes.get(),
                    usbBytes = usbBytes.get(),
                ),
                finalSize = finalBytes,
                avgBps = avg,
                peakBps = maxOf(entity.peakSpeedBps, avg),
                actualHash = null,
                casDedupBytes = 0L,
                verifiedBitfield = SegmentState.VERIFIED.code.toString().repeat(bitChars.size),
                syscallCount = segmentUrls.size,
                wifiBytes = wifiBytes.get(),
                cellBytes = cellBytes.get(),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = ex.message ?: "HLS/DASH transfer failed",
                    updatedAt = System.currentTimeMillis(),
                )
            )
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    private fun parseMediaSegments(baseUrl: String, manifest: String): List<String> {
        val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/') + "/"
        val lines = manifest.lines().map { it.trim() }.filter { it.isNotBlank() }
        val hls = lines.filterNot { it.startsWith("#") }.map { entry ->
            when {
                entry.startsWith("http://") || entry.startsWith("https://") -> entry
                entry.startsWith("/") -> {
                    val origin = Regex("^(https?://[^/]+)").find(baseUrl)?.value.orEmpty()
                    origin + entry
                }
                else -> baseDir + entry
            }
        }
        if (hls.isNotEmpty() && !manifest.trimStart().startsWith("<")) return hls
        // Basic DASH BaseURL/media extraction
        val mediaRegex = Regex("""media="([^"]+)"""")
        return mediaRegex.findAll(manifest).map { m ->
            val seg = m.groupValues[1]
            if (seg.startsWith("http")) seg else baseDir + seg
        }.toList()
    }

    private fun openDestinationHandle(entity: DownloadEntity, useStagedSaf: Boolean, staging: File): DestinationHandle {
        return when {
            useStagedSaf -> {
                staging.parentFile?.mkdirs()
                val raf = RandomAccessFile(staging, "rw")
                DestinationHandle(raf.channel, raf, true)
            }
            entity.isSafDestination -> {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(entity.destinationUri), "rw")
                    ?: throw IOException("Unable to open SAF file descriptor (EBADF)")
                val fos = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                DestinationHandle(fos.channel, fos, true)
            }
            else -> {
                val file = File(entity.destinationUri).apply { parentFile?.mkdirs() }
                val raf = RandomAccessFile(file, "rw")
                DestinationHandle(raf.channel, raf, true)
            }
        }
    }

    private fun reconcileBitfield(entity: DownloadEntity, ledger: AetherBinaryLedger, segCount: Int, totalBytes: Long): String {
        val roomValid = entity.segmentBitfield.length == segCount && totalBytes == entity.totalBytes
        return CharArray(segCount) { idx ->
            val ledgerDone = idx < ledger.states.size && ledger.states[idx] == AetherBinaryLedger.STATE_DONE
            val roomState = if (roomValid) SegmentState.fromCode(entity.segmentBitfield[idx]) else SegmentState.PENDING
            when {
                roomState == SegmentState.VERIFIED -> SegmentState.VERIFIED.code
                roomState == SegmentState.COMMITTED || ledgerDone -> SegmentState.COMMITTED.code
                else -> SegmentState.PENDING.code
            }
        }.concatToString()
    }

    private fun syncLedgerFromBitChars(ledger: AetherBinaryLedger, bitChars: CharArray) {
        val limit = minOf(ledger.states.size, bitChars.size)
        for (i in 0 until limit) {
            ledger.states[i] = when (SegmentState.fromCode(bitChars[i])) {
                SegmentState.COMMITTED, SegmentState.VERIFIED -> AetherBinaryLedger.STATE_DONE
                SegmentState.ACTIVE_WIFI, SegmentState.ACTIVE_5G, SegmentState.STOLEN_HEDGE -> AetherBinaryLedger.STATE_LEASED
                else -> AetherBinaryLedger.STATE_PENDING
            }
        }
    }

    private suspend fun claimNextPendingSegment(bitChars: CharArray, mutex: Mutex, cursor: AtomicInteger): Int =
        mutex.withLock {
            val len = bitChars.size
            while (true) {
                val idx = cursor.getAndIncrement()
                if (idx >= len) return@withLock -1
                if (SegmentState.fromCode(bitChars[idx]) == SegmentState.PENDING) {
                    pendingSegments.decrementAndGet()
                    bitChars[idx] = SegmentState.ACTIVE_WIFI.code
                    return@withLock idx
                }
            }
            @Suppress("UNREACHABLE_CODE")
            -1
        }

    private fun userAgentForProfile(profile: String): String = when (profile) {
        "chrome131" -> "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.6778.200 Mobile Safari/537.36"
        "okhttp5" -> "AetherIO-OkHttp/5.0 Android"
        else -> "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/134.0.6998.95 Mobile Safari/537.36"
    }

    private fun isNetworkFlap(ex: Exception): Boolean =
        ex is UnknownHostException || ex is SocketException || ex is LinkStallException

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private class HttpAuthExpiredException(val code: Int) : IOException("HTTP $code")
    private class LinkStallException(linkKeyId: String) : IOException("Zero-byte stall >= ${OKHTTP_STALL_WATCHDOG_MS}ms on $linkKeyId")
    private data class PreflightProbeResult(val totalBytes: Long, val supportsRange: Boolean, val ttfbMs: Long, val eTag: String?, val suggestedFileName: String?)
    private data class VerifyResult(val fileSha256: String, val dedupMatchingBytes: Long)

    /**
     * Wraps a network-bound SocketFactory to apply 4 MiB TCP SO_RCVBUF & TCP_NODELAY before connect,
     * letting high-BDP Wi-Fi 6 / 5G links scale TCP windows immediately without OS buffer bottlenecking.
     */
    private class TunedSocketFactory(
        private val delegate: SocketFactory,
        private val rcvBufBytes: Int,
    ) : SocketFactory() {
        private fun tune(s: Socket): Socket {
            runCatching { s.receiveBufferSize = rcvBufBytes }
            runCatching { s.tcpNoDelay = true }
            return s
        }

        override fun createSocket(): Socket = tune(delegate.createSocket())
        override fun createSocket(host: String?, port: Int): Socket = tune(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            tune(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = tune(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            tune(delegate.createSocket(address, port, localAddress, localPort))
    }

    /**
     * Lock-free positional pwrite destination handle:
     * `FileChannel.write(ByteBuffer, long position)` does NOT modify channel position and is safe
     * for concurrent non-overlapping worker ranges without a global mutex or blocking `force(false)`.
     */
    private class DestinationHandle(
        private val channel: FileChannel,
        private val closeable: AutoCloseable,
        val isRandomAccess: Boolean,
    ) : AutoCloseable {
        private val sizeMutex = Any()

        fun ensureSize(size: Long) {
            if (size <= 0L) return
            synchronized(sizeMutex) {
                if (channel.size() < size) {
                    channel.write(ByteBuffer.allocate(1), size - 1L)
                }
            }
        }

        fun writeAt(buffer: ByteBuffer, offset: Long) {
            var pos = offset
            while (buffer.hasRemaining()) {
                pos += channel.write(buffer, pos)
            }
        }

        fun forceData() {
            runCatching { channel.force(false) }
        }

        override fun close() {
            runCatching { channel.force(true) }
            runCatching { closeable.close() }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
