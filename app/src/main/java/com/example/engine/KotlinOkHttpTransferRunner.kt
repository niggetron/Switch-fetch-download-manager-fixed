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
private const val OKHTTP_SLAB_FLUSH_THRESHOLD = 448 * 1024 // Coalesce ~448–512 KiB per pwrite
private const val OKHTTP_TELEMETRY_THROTTLE_MS = 150L
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
    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 32
    }

    private val baseClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // Persistent warm client cache per (linkKey, netHandle, proxySpec)
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
        entity = entity.copy(
            status = DownloadStatus.PROBING_0RTT.name,
            engineBackendUsed = ResolvedEngineBackend.KOTLIN_OKHTTP.id,
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(entity)

        val probeLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
        val probeLane = LinkScheduler.selectLowestRttLink(probeLanes, networkMonitor.breakerRegistry)
        val probeEndpoint = probeLane?.let { networkMonitor.getNetworkByKey(it.linkKeyId) }
        val probeClient = getOrBuildClientForLink(probeEndpoint, entity.routeModeEnum, settings)

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
        val taskCellBytes = AtomicLong(entity.cellBytes)
        val cellLimit = when {
            entity.maxCellularBytes > 0L -> entity.maxCellularBytes
            settings.maxCellularBytesPerSession > 0L -> settings.maxCellularBytesPerSession
            else -> Long.MAX_VALUE
        }
        val syscallBatches = AtomicInteger(entity.syscallWriteBatches)
        val peakSpeed = AtomicLong(entity.peakSpeedBps)
        val queuedBytesByLink = ConcurrentHashMap<String, AtomicLong>()
        val activeRanges = ConcurrentHashMap<String, ActiveOkHttpRange>()
        val segmentRetries = ConcurrentHashMap<Int, AtomicInteger>()
        var failureReason: Pair<DownloadStatus, String>? = null

        try {
            handle.use { dest ->
                if (totalBytes > 0L && dest.isRandomAccess) {
                    runCatching { dest.ensureSize(totalBytes) }
                }

                val activeLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
                val maxCap = when (settings.throughputProfile) {
                    ThroughputProfile.TURBO_ANTI_DIP,
                    ThroughputProfile.MAX_MULTI_SOCKET -> 16
                    ThroughputProfile.SUSTAINED_ANTI_DIP -> 12
                }
                val baseDesired = LinkScheduler.effectiveWorkerCount(entity.workerCount, activeLanes.size)
                val boostedDesired = if (settings.throughputProfile != ThroughputProfile.SUSTAINED_ANTI_DIP) {
                    maxOf(baseDesired, (activeLanes.size * 2).coerceAtMost(maxCap))
                } else {
                    baseDesired
                }
                // Allow splitting even when segmentCount is small via live bisection!
                val maxPoolCoroutines = boostedDesired.coerceIn(1, maxCap)
                val targetWorkers = AtomicInteger(maxPoolCoroutines)

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
                        telemetryLedger.recordEnginePeak(ResolvedEngineBackend.KOTLIN_OKHTTP, curSpeed)
                        lastSampleTime = now
                        lastSampleBytes = curBytes

                        if (settings.enableAutoSpeedUp && probeResult.supportsRange && totalBytes > OKHTTP_MIN_EXTENT_BYTES) {
                            val liveLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
                            val minW = maxOf(liveLanes.size * 2, 2).coerceAtMost(maxCap)
                            val anyBreakerOpen = networkMonitor.breakerRegistry.values.any {
                                it.currentState(now) != BreakerState.CLOSED
                            }
                            val curTtfb = telemetryLedger.latestTtfbMs()
                                .takeIf { it > 0L }?.toDouble() ?: prevTtfb
                            val adjusted = LinkScheduler.adjustAutoSpeedUp(
                                currentWorkers = targetWorkers.get(),
                                minWorkers = minW,
                                maxWorkers = maxCap,
                                prevGoodputBps = prevGoodputBps,
                                newGoodputBps = curSpeed.toDouble(),
                                deltaRttMs = (curTtfb - prevTtfb).coerceAtLeast(0.0),
                                throttled = anyBreakerOpen,
                            )
                            targetWorkers.set(adjusted)
                            prevGoodputBps = curSpeed.toDouble()
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
                        repository.update(
                            latest.copy(
                                downloadedBytes = if (totalBytes > 0L) curBytes.coerceAtMost(totalBytes) else curBytes,
                                wifiBytes = taskWifiBytes.get(),
                                cellBytes = taskCellBytes.get(),
                                segmentBitfield = snapshotBits,
                                syscallWriteBatches = syscallBatches.get(),
                                peakSpeedBps = peakSpeed.get(),
                                workerCount = targetWorkers.get(),
                                updatedAt = now,
                            )
                        )
                    }
                }

                try {
                    if (probeResult.supportsRange && totalBytes > OKHTTP_MIN_EXTENT_BYTES && dest.isRandomAccess) {
                        val nextIndex = AtomicInteger(0)

                        coroutineScope {
                            val jobs = (0 until maxPoolCoroutines).map { workerId ->
                                async(Dispatchers.IO) {
                                    while (isActive && failureReason == null) {
                                        if (workerId >= targetWorkers.get()) {
                                            val hasMore = bitMutex.withLock {
                                                bitChars.any { SegmentState.fromCode(it) == SegmentState.PENDING }
                                            } || activeRanges.isNotEmpty()
                                            if (!hasMore) break
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
                                            tryStealBisectedRange(activeRanges)
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
                                                taskCellBytes = taskCellBytes,
                                                cellCapBytes = cellLimit,
                                                syscallBatches = syscallBatches,
                                                queuedBytesByLink = queuedBytesByLink,
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
                                            if (!unit.isBisectedSteal) {
                                                bitMutex.withLock {
                                                    if (SegmentState.fromCode(bitChars[unit.segIndex]) != SegmentState.COMMITTED) {
                                                        bitChars[unit.segIndex] = SegmentState.PENDING.code
                                                    }
                                                }
                                            }
                                            if (canRetry) {
                                                if (!unit.isBisectedSteal) {
                                                    nextIndex.updateAndGet { cur -> minOf(cur, unit.segIndex) }
                                                }
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
            entity = entity,
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
        taskCellBytes: AtomicLong,
        cellCapBytes: Long,
        syscallBatches: AtomicInteger,
        queuedBytesByLink: ConcurrentHashMap<String, AtomicLong>,
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
                lane.copy(queuedBytes = q)
            }

        if (taskCellBytes.get() >= cellCapBytes) {
            val nonCell = baseLanes.filterNot { it.linkKeyId.startsWith("cell:") }
            if (nonCell.isNotEmpty()) baseLanes = nonCell
        }

        val selectedLane = LinkScheduler.selectEarliestArrivalLink(
            lanes = baseLanes,
            breakers = networkMonitor.breakerRegistry,
            segmentBytes = segLen.toDouble(),
        ) ?: LinkScheduler.selectEarliestArrivalLink(
            lanes = baseLanes,
            breakers = emptyMap(),
            segmentBytes = segLen.toDouble(),
        )

        val pinnedLinkKeyId = selectedLane?.linkKeyId
        val pinnedEndpoint = pinnedLinkKeyId?.let { networkMonitor.getNetworkByKey(it) }
        val isCell = pinnedEndpoint?.linkKey?.isCellular == true || routeMode == NetworkRouteMode.CELLULAR_ONLY
        val isEth = pinnedLinkKeyId?.startsWith("eth:") == true || pinnedLinkKeyId?.startsWith("usb:") == true
        val targetUrl = LinkScheduler.selectMirrorForLink(urls, pinnedLinkKeyId ?: "wifi:wlan0", unit.segIndex)
        val client = getOrBuildClientForLink(pinnedEndpoint, routeMode, settings)

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
        queueCounter?.addAndGet(segLen)
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
        var lastTelemetryPushMs = 0L

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
                if (resp.code == 403 || resp.code == 410) throw HttpAuthExpiredException(resp.code)
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

                        liveWrittenBytes.addAndGet(read.toLong())
                        when {
                            isCell -> taskCellBytes.addAndGet(read.toLong())
                            !isEth -> taskWifiBytes.addAndGet(read.toLong())
                        }
                        telemetryLedger.recordLinkBytesWritten(pinnedLinkKeyId, read.toLong())

                        // Coalesce up to ~448-512 KiB per positional pwrite syscall!
                        if (slab.position() >= OKHTTP_SLAB_FLUSH_THRESHOLD || !slab.hasRemaining() || offset > activeState.endOffset.get()) {
                            slab.flip()
                            dest.writeAt(slab, slabWriteOffset)
                            slabWriteOffset = offset
                            slab.clear()
                            syscallBatches.incrementAndGet()
                            telemetryLedger.recordSyscallWriteBatch()
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

                if (!unit.isBisectedSteal) {
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
            if (stalledOut.get()) throw LinkStallException(pinnedLinkKeyId ?: "unknown")
            throw ex
        } finally {
            watchdogJob.cancel()
            activeRanges.remove(connId)
            telemetryLedger.removeKotlinConnection(connId)
            if (reservedRemaining > 0L) queueCounter?.addAndGet(-reservedRemaining)
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
     * Returns a persistent warm `OkHttpClient` pooled per `(linkKey, netHandle, proxySpec)` so
     * TLS 1.3 + HTTP/2 sockets stay open across segments (eliminating Slow Start 1/5th speed drops!).
     */
    private fun getOrBuildClientForLink(
        endpoint: LinkEndpointInfo?,
        routeMode: NetworkRouteMode,
        settings: EngineSettings,
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

        val cacheKey = "${endpoint?.linkKey?.id ?: "default"}|${endpoint?.netHandle ?: 0L}|$proxyType|$resolvedHost:$resolvedPort"
        return clientPool.computeIfAbsent(cacheKey) {
            val builder = baseClient.newBuilder()
                .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
                .dispatcher(sharedDispatcher)

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
                if (SegmentState.fromCode(bitChars[idx]) == SegmentState.PENDING) return@withLock idx
            }
            @Suppress("UNREACHABLE_CODE")
            -1
        }

    private fun userAgentForProfile(profile: String): String = when (profile) {
        "chrome131" -> "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.6778.200 Mobile Safari/537.36"
        "okhttp5" -> "AetherIO-OkHttp/5.0 Android"
        else -> "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/134.0.6998.95 Mobile Safari/537.36"
    }

    private fun isNetworkFlap(ex: Exception): Boolean = ex is UnknownHostException || ex is SocketException

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
