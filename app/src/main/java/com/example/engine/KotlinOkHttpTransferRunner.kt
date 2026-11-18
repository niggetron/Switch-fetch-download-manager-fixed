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
import kotlin.math.ceil

private const val OKHTTP_STALL_WATCHDOG_MS = 1_500L
private const val OKHTTP_MAX_SEGMENT_RETRIES = 4
private const val OKHTTP_MIN_EXTENT_BYTES = 1L * 1024L * 1024L

/**
 * Full-featured Kotlin OkHttp Multi-Network Transfer Engine:
 * - Single-connection Stream 0 probe first (`Range: bytes=0-0`, RFC 6266, magic/MIME, ENOSPC)
 * - Multi-mirror URL bonding across Wi-Fi, 5G/LTE, and hot-plugged USB-C Ethernet
 * - Per-link byte & goodput attribution + live Ketch-style `ActiveConnectionInfo`
 * - Per-session `maxCellularBytes` enforcement
 * - Strict `channel.force(true)` BEFORE marking extents committed in `AETHLDG2`
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
    private val baseClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun run(entityInput: DownloadEntity, settings: EngineSettings) {
        var entity = entityInput
        val id = entity.id
        val callsForTask = mutableListOf<Call>()
        activeCalls[id] = callsForTask
        val urls = entity.allUrls.ifEmpty { listOf(entity.url) }
        val useStagedSaf = entity.isSafDestination && settings.stagedSafPlacement
        val staging = stagingFileProvider(id)

        // Fast-path recovery if staged SAF download already verified before SAF_EBADF_R5
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

        // Stream 0 Probe on Lowest-RTT healthy LinkKey (F5)
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
        val probeClient = buildClientForLink(probeEndpoint, entity.routeModeEnum, settings)

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
                .maxOfOrNull { it.downstreamKbps } ?: 60_000
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

        // Audited 64 MiB ENOSPC reserve guard
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
        val segmentRetries = ConcurrentHashMap<Int, AtomicInteger>()
        var failureReason: Pair<DownloadStatus, String>? = null

        try {
            handle.use { dest ->
                if (totalBytes > 0L && dest.isRandomAccess) {
                    runCatching { dest.ensureSize(totalBytes) }
                }

                val activeLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
                val initialWorkers = LinkScheduler.effectiveWorkerCount(entity.workerCount, activeLanes.size)
                    .coerceIn(1, minOf(8, segmentCount.coerceAtLeast(1)))
                val targetWorkers = AtomicInteger(initialWorkers)

                val flusherJob = engineScope.launch {
                    var lastSampleTime = System.currentTimeMillis()
                    var lastSampleBytes = liveWrittenBytes.get()
                    var prevGoodputBps = 0.0
                    var prevTtfb = probeResult.ttfbMs.toDouble()

                    while (isActive) {
                        delay(500L)
                        val now = System.currentTimeMillis()
                        val curBytes = liveWrittenBytes.get()
                        val dt = (now - lastSampleTime).coerceAtLeast(100L)
                        val curSpeed = ((curBytes - lastSampleBytes).coerceAtLeast(0L) * 1000L) / dt
                        peakSpeed.updateAndGet { maxOf(it, curSpeed) }
                        lastSampleTime = now
                        lastSampleBytes = curBytes

                        if (settings.enableAutoSpeedUp && probeResult.supportsRange && segmentCount > 1) {
                            val liveLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
                            val minW = liveLanes.size.coerceAtLeast(1)
                            val anyBreakerOpen = networkMonitor.breakerRegistry.values.any {
                                it.currentState(now) != BreakerState.CLOSED
                            }
                            val curTtfb = telemetryLedger.sampleSnapshot(true).lastTtfbMs
                                .takeIf { it > 0L }?.toDouble() ?: prevTtfb
                            val adjusted = LinkScheduler.adjustAutoSpeedUp(
                                currentWorkers = targetWorkers.get(),
                                minWorkers = minW,
                                maxWorkers = minOf(8, segmentCount),
                                prevGoodputBps = prevGoodputBps,
                                newGoodputBps = curSpeed.toDouble(),
                                deltaRttMs = (curTtfb - prevTtfb).coerceAtLeast(0.0),
                                throttled = anyBreakerOpen,
                            )
                            targetWorkers.set(adjusted)
                            prevGoodputBps = curSpeed.toDouble()
                            prevTtfb = curTtfb
                        }

                        // Strict ordering: force disk write barrier BEFORE persisting ledger DONE bits
                        runCatching { dest.forceData() }
                        val snapshotBits = bitMutex.withLock {
                            syncLedgerFromBitChars(ledger, bitChars)
                            String(bitChars)
                        }
                        runCatching { ledger.persist(ledgerFileProvider(id)) }
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
                    if (probeResult.supportsRange && totalBytes > 0L && dest.isRandomAccess && segmentCount > 1) {
                        val maxPoolCoroutines = if (settings.enableAutoSpeedUp) minOf(8, segmentCount) else initialWorkers
                        val nextIndex = AtomicInteger(0)

                        coroutineScope {
                            val jobs = (0 until maxPoolCoroutines).map { workerId ->
                                async(Dispatchers.IO) {
                                    while (isActive && failureReason == null) {
                                        if (workerId >= targetWorkers.get()) {
                                            val remaining = bitMutex.withLock {
                                                bitChars.any { SegmentState.fromCode(it) == SegmentState.PENDING }
                                            }
                                            if (!remaining) break
                                            delay(200L)
                                            continue
                                        }

                                        val segIdx = claimNextPendingSegment(bitChars, bitMutex, nextIndex)
                                        if (segIdx == -1) {
                                            if (settings.enableTailHedge) {
                                                val stealIdx = findTailHedgeCandidate(bitChars, bitMutex)
                                                if (stealIdx != -1) {
                                                    val ok = runCatching {
                                                        downloadSegmentWithPinnedLink(
                                                            taskId = id,
                                                            workerId = workerId,
                                                            urls = urls,
                                                            segIndex = stealIdx,
                                                            segSize = segSize,
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
                                                            callsForTask = callsForTask,
                                                            isHedge = true,
                                                        )
                                                    }.getOrDefault(false)
                                                    if (ok) continue
                                                }
                                            }
                                            break
                                        }

                                        try {
                                            downloadSegmentWithPinnedLink(
                                                taskId = id,
                                                workerId = workerId,
                                                urls = urls,
                                                segIndex = segIdx,
                                                segSize = segSize,
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
                                                callsForTask = callsForTask,
                                                isHedge = false,
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
                                                .computeIfAbsent(segIdx) { AtomicInteger(0) }
                                                .incrementAndGet()
                                            val canRetry = attempts < OKHTTP_MAX_SEGMENT_RETRIES &&
                                                (ex is LinkStallException || isNetworkFlap(ex) || ex is IOException)
                                            bitMutex.withLock {
                                                if (SegmentState.fromCode(bitChars[segIdx]) != SegmentState.COMMITTED) {
                                                    bitChars[segIdx] = SegmentState.PENDING.code
                                                }
                                            }
                                            if (canRetry) {
                                                nextIndex.updateAndGet { cur -> minOf(cur, segIdx) }
                                                delay(150L)
                                                continue
                                            }
                                            failureReason = if (isNetworkFlap(ex)) {
                                                DownloadStatus.LINK_MIGRATE_R1 to
                                                    "Radio link interrupted — awaiting Wi-Fi/5G migration"
                                            } else {
                                                DownloadStatus.FAILED to (ex.message ?: "Segment $segIdx failed")
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

    private suspend fun downloadSegmentWithPinnedLink(
        taskId: String,
        workerId: Int,
        urls: List<String>,
        segIndex: Int,
        segSize: Long,
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
        callsForTask: MutableList<Call>,
        isHedge: Boolean,
    ): Boolean {
        val startOffset = segIndex.toLong() * segSize
        val endOffset = minOf(startOffset + segSize - 1L, totalBytes - 1L)
        if (startOffset > endOffset) return false
        val segLen = (endOffset - startOffset + 1L)

        var baseLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, routeMode)
            .map { lane ->
                val q = queuedBytesByLink[lane.linkKeyId]?.get()?.toDouble() ?: 0.0
                lane.copy(queuedBytes = q)
            }

        // Enforce per-session Cellular Data Cap cleanly if non-cellular lanes are available
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
        val targetUrl = LinkScheduler.selectMirrorForLink(urls, pinnedLinkKeyId ?: "wifi:wlan0", segIndex)
        val client = buildClientForLink(pinnedEndpoint, routeMode, settings)

        bitMutex.withLock {
            val cur = SegmentState.fromCode(bitChars[segIndex])
            if (cur == SegmentState.COMMITTED || cur == SegmentState.VERIFIED) return false
            bitChars[segIndex] = when {
                isHedge -> SegmentState.STOLEN_HEDGE.code
                isCell -> SegmentState.ACTIVE_5G.code
                else -> SegmentState.ACTIVE_WIFI.code
            }
        }

        val queueCounter = pinnedLinkKeyId?.let {
            queuedBytesByLink.computeIfAbsent(it) { AtomicLong(0L) }
        }
        queueCounter?.addAndGet(segLen)
        var reservedRemaining = segLen

        val connId = "ok-${taskId.take(6)}-$workerId-$segIndex"
        telemetryLedger.workerStarted(pinnedLinkKeyId)
        val slab = slabPool.acquire()
        val lastProgressMs = AtomicLong(System.currentTimeMillis())
        val stalledOut = AtomicBoolean(false)
        val segStartNs = System.nanoTime()
        var segWritten = 0L

        var callRef: Call? = null
        val watchdogJob = engineScope.launch {
            while (isActive) {
                delay(300L)
                if (System.currentTimeMillis() - lastProgressMs.get() >= OKHTTP_STALL_WATCHDOG_MS) {
                    stalledOut.set(true)
                    callRef?.cancel()
                    break
                }
            }
        }

        try {
            val req = Request.Builder()
                .url(targetUrl)
                .header("Range", "bytes=$startOffset-$endOffset")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", userAgentForProfile(browserProfile))
                .build()
            val call = client.newCall(req)
            callRef = call
            synchronized(callsForTask) { callsForTask.add(call) }

            val reqStartMs = System.currentTimeMillis()
            call.execute().use { resp ->
                val ttfb = (System.currentTimeMillis() - reqStartMs).coerceAtLeast(1L)
                lastProgressMs.set(System.currentTimeMillis())
                if (resp.code == 403 || resp.code == 410) throw HttpAuthExpiredException(resp.code)
                if (!resp.isSuccessful && resp.code != 206) throw IOException("HTTP ${resp.code} on segment $segIndex")

                val proto = resp.protocol.toString().uppercase()
                val source = resp.body?.source() ?: throw IOException("Empty body on segment $segIndex")
                var offset = startOffset

                while (true) {
                    val alreadyDone = bitMutex.withLock {
                        SegmentState.fromCode(bitChars[segIndex]) == SegmentState.COMMITTED
                    }
                    if (alreadyDone) return false

                    slab.clear()
                    val read = source.read(slab)
                    if (read == -1) break
                    if (read > 0) {
                        lastProgressMs.set(System.currentTimeMillis())
                        slab.flip()
                        dest.writeAt(slab, offset)
                        offset += read
                        segWritten += read
                        val dec = minOf(reservedRemaining, read.toLong())
                        reservedRemaining -= dec
                        queueCounter?.addAndGet(-dec)

                        liveWrittenBytes.addAndGet(read.toLong())
                        if (isCell) taskCellBytes.addAndGet(read.toLong()) else taskWifiBytes.addAndGet(read.toLong())
                        telemetryLedger.recordLinkBytesWritten(pinnedLinkKeyId, read.toLong())
                        syscallBatches.incrementAndGet()
                        telemetryLedger.recordSyscallWriteBatch()

                        val elapsedSec = ((System.nanoTime() - segStartNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                        telemetryLedger.upsertKotlinConnection(
                            ActiveConnectionInfo(
                                connId = connId,
                                taskId = taskId,
                                url = targetUrl,
                                linkKeyId = pinnedLinkKeyId ?: "default",
                                protocol = "$proto (OkHttp)",
                                socketState = if (isHedge) "Hedge Racing" else "Streaming",
                                ttfbMs = ttfb,
                                bytesReceived = segWritten,
                                speedBps = (segWritten / elapsedSec).toLong(),
                                rangeStart = startOffset,
                                rangeEnd = endOffset,
                            )
                        )
                    }
                }

                // Commit durability barrier before flipping segment DONE
                dest.forceData()
                bitMutex.withLock {
                    bitChars[segIndex] = SegmentState.COMMITTED.code
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
                    networkMonitor.breakerRegistry[it]?.recordFailureOrStall(immediateTrip = true)
                    networkMonitor.publishState()
                }
                throw LinkStallException(pinnedLinkKeyId ?: "unknown")
            }
            throw ce
        } catch (ex: Exception) {
            if (pinnedLinkKeyId != null && ex !is HttpAuthExpiredException) {
                val isStall = stalledOut.get() || ex is SocketTimeoutException
                networkMonitor.breakerRegistry[pinnedLinkKeyId]?.recordFailureOrStall(immediateTrip = isStall)
                networkMonitor.publishState()
            }
            if (stalledOut.get()) throw LinkStallException(pinnedLinkKeyId ?: "unknown")
            throw ex
        } finally {
            watchdogJob.cancel()
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
        val client = buildClientForLink(endpoint, routeMode, settings)

        val connId = "ok-${taskId.take(6)}-single"
        telemetryLedger.workerStarted(selectedLane?.linkKeyId)
        val slab = slabPool.acquire()
        val segStartNs = System.nanoTime()
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
                while (true) {
                    slab.clear()
                    val read = source.read(slab)
                    if (read == -1) break
                    if (read > 0) {
                        slab.flip()
                        dest.writeAt(slab, offset)
                        offset += read
                        liveWrittenBytes.addAndGet(read.toLong())
                        if (isCell) taskCellBytes.addAndGet(read.toLong()) else taskWifiBytes.addAndGet(read.toLong())
                        telemetryLedger.recordLinkBytesWritten(selectedLane?.linkKeyId, read.toLong())
                        syscallBatches.incrementAndGet()
                        telemetryLedger.recordSyscallWriteBatch()

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
                    }
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
        val buf = ByteArray(64 * 1024)

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

    private fun buildClientForLink(
        endpoint: LinkEndpointInfo?,
        routeMode: NetworkRouteMode,
        settings: EngineSettings,
    ): OkHttpClient {
        val builder = baseClient.newBuilder()
        val isCell = endpoint?.linkKey?.isCellular == true
        val (linkProxyHost, linkProxyPort) = if (isCell) {
            settings.cellularProxyHost.trim() to settings.cellularProxyPort
        } else {
            settings.proxyHost.trim() to settings.proxyPort
        }
        val resolvedHost = when {
            linkProxyHost.isNotBlank() -> linkProxyHost
            routeMode == NetworkRouteMode.PROXY_SOCKS5 && settings.proxyHost.isNotBlank() -> settings.proxyHost.trim()
            else -> ""
        }
        val resolvedPort = if (linkProxyHost.isNotBlank()) linkProxyPort else settings.proxyPort

        if (resolvedHost.isNotBlank()) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(resolvedHost, resolvedPort)))
            endpoint?.network?.let { builder.socketFactory(it.socketFactory) }
        } else {
            builder.proxy(Proxy.NO_PROXY) // Audited Fix #2: prevent Wi-Fi HTTP proxy leak onto cellular links
            if (endpoint != null) {
                val net: Network = endpoint.network
                builder.socketFactory(net.socketFactory)
                builder.dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = try {
                        net.getAllByName(hostname).toList()
                    } catch (_: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
                })
            }
        }
        return builder.build()
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

    private suspend fun findTailHedgeCandidate(bitChars: CharArray, mutex: Mutex): Int = mutex.withLock {
        bitChars.indexOfFirst {
            val s = SegmentState.fromCode(it)
            s == SegmentState.ACTIVE_WIFI || s == SegmentState.ACTIVE_5G
        }
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

    private class DestinationHandle(
        private val channel: FileChannel,
        private val closeable: AutoCloseable,
        val isRandomAccess: Boolean,
    ) : AutoCloseable {
        private val writeMutex = Any()

        fun ensureSize(size: Long) {
            if (size <= 0L) return
            synchronized(writeMutex) {
                if (channel.size() < size) {
                    channel.write(ByteBuffer.allocate(1), size - 1L)
                }
            }
        }

        fun writeAt(buffer: ByteBuffer, offset: Long) {
            synchronized(writeMutex) {
                var pos = offset
                while (buffer.hasRemaining()) pos += channel.write(buffer, pos)
            }
        }

        fun forceData() {
            synchronized(writeMutex) {
                runCatching { channel.force(false) }
            }
        }

        override fun close() {
            runCatching { channel.force(true) }
            runCatching { closeable.close() }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
