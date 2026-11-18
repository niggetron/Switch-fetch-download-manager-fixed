package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.webkit.MimeTypeMap
import com.example.data.local.CasChunkEntity
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineSettings
import com.example.data.model.F2FS_SEGMENT_BYTES
import com.example.data.model.NetworkRouteMode
import com.example.data.model.NewDownloadRequest
import com.example.data.model.SegmentState
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.DownloadRepository
import com.example.service.AetherDownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

private const val STALL_WATCHDOG_MS = 1_500L
private const val MAX_SEGMENT_RETRIES = 4
private const val MIN_EXTENT_BYTES = 1L * 1024L * 1024L

class AetherDownloadEngine(
    private val context: Context,
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
    private val slabPool: SlabBufferPool,
    private val telemetryLedger: TelemetryLedger,
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, MutableList<Call>>()

    private val baseClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun enqueue(request: NewDownloadRequest): String {
        val settings = settingsRepository.current()
        val id = UUID.randomUUID().toString()
        val guessedName = request.customFileName?.takeIf { it.isNotBlank() }
            ?: guessFileNameFromUrl(request.url)
        val isSaf = !request.safUriString.isNullOrBlank()
        val destUri = if (isSaf) {
            request.safUriString!!
        } else {
            repository.resolveUniqueTargetFile(guessedName).absolutePath
        }
        val mime = guessMimeType(guessedName)

        // Adaptive BDP baseline segment sizing from current validated link speeds
        val lanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, request.routeMode)
        val fastestKbps = networkMonitor.state.value.activeValidatedLinks
            .maxOfOrNull { it.downstreamKbps } ?: 60_000
        val bdpSegment = if (lanes.isNotEmpty()) {
            LinkScheduler.adaptiveBdpSegmentBytes(fastestKbps)
        } else {
            settings.segmentSizeBytes.coerceAtLeast(MIN_EXTENT_BYTES)
        }

        val entity = DownloadEntity(
            id = id,
            url = request.url.trim(),
            fileName = guessedName,
            mimeType = mime,
            totalBytes = -1L,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED.name,
            routeMode = request.routeMode.name,
            workerCount = request.workers.coerceIn(1, 8),
            destinationUri = destUri,
            isSafDestination = isSaf,
            supportsRange = false,
            segmentSizeBytes = bdpSegment,
            expectedSha256 = request.expectedSha256?.trim()?.takeIf { it.isNotBlank() },
        )
        repository.upsert(entity)
        launchDownload(id)
        return id
    }

    fun launchDownload(id: String) {
        if (activeJobs[id]?.isActive == true) return
        ensureForegroundServiceRunning()
        val job = engineScope.launch {
            try {
                executeDownload(id)
            } finally {
                activeJobs.remove(id)
                activeCalls.remove(id)?.forEach { runCatching { it.cancel() } }
                slabPool.trimIdle()
                if (activeJobs.isEmpty()) {
                    telemetryLedger.markFgsSessionStopped()
                }
            }
        }
        activeJobs[id] = job
    }

    suspend fun pause(id: String) {
        activeCalls[id]?.forEach { runCatching { it.cancel() } }
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        val current = repository.getById(id) ?: return
        if (current.statusEnum != DownloadStatus.COMPLETED) {
            repository.update(
                current.copy(
                    status = DownloadStatus.PAUSED.name,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun pauseAll() {
        val ids = activeJobs.keys.toList()
        ids.forEach { pause(it) }
    }

    suspend fun resumeAll() {
        val all = repository.getAllOnce()
        all.filter {
            it.statusEnum == DownloadStatus.PAUSED ||
                it.statusEnum == DownloadStatus.QUOTA_GUARD_R3 ||
                it.statusEnum == DownloadStatus.LINK_MIGRATE_R1
        }.forEach {
            launchDownload(it.id)
        }
    }

    suspend fun checkpointForQuotaGuard() {
        val ids = activeJobs.keys.toList()
        for (id in ids) {
            activeCalls[id]?.forEach { runCatching { it.cancel() } }
            activeJobs[id]?.cancel()
            activeJobs.remove(id)
            val current = repository.getById(id)
            if (current != null && current.statusEnum != DownloadStatus.COMPLETED) {
                repository.update(
                    current.copy(
                        status = DownloadStatus.QUOTA_GUARD_R3.name,
                        errorMessage = "Paused by Android dataSync 6h Quota Guard. Ledger checkpointed for 1-tap resume.",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
        telemetryLedger.markFgsSessionStopped()
    }

    suspend fun cancel(id: String) {
        activeCalls[id]?.forEach { runCatching { it.cancel() } }
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        runCatching { ledgerFile(id).delete() }
        runCatching { stagingFile(id).delete() }
        val current = repository.getById(id) ?: return
        repository.update(
            current.copy(
                status = DownloadStatus.CANCELLED.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun updateExpiredUrlAndResume(id: String, newUrl: String) {
        val current = repository.getById(id) ?: return
        repository.update(
            current.copy(
                url = newUrl.trim(),
                status = DownloadStatus.QUEUED.name,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        launchDownload(id)
    }

    suspend fun regrantSafUriAndResume(id: String, newSafUri: String) {
        val current = repository.getById(id) ?: return
        repository.update(
            current.copy(
                destinationUri = newSafUri,
                isSafDestination = true,
                status = DownloadStatus.QUEUED.name,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        launchDownload(id)
    }

    private suspend fun executeDownload(id: String) {
        var entity = repository.getById(id) ?: return
        val settings = settingsRepository.current()
        networkMonitor.applyPolicy(settings)
        val callsForTask = mutableListOf<Call>()
        activeCalls[id] = callsForTask

        val useStagedSaf = entity.isSafDestination && settings.stagedSafPlacement
        val staging = stagingFile(id)

        // Fast-path recovery if staged SAF download already completed & verified prior to SAF_EBADF_R5
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
                )
            }
            return
        }

        // S0-S1: Pre-flight 0-RTT / Range 0-0 Probe pinned to Lowest-RTT healthy LinkKey (F5)
        entity = entity.copy(
            status = DownloadStatus.PROBING_0RTT.name,
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(entity)

        val probeLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, entity.routeModeEnum)
        val probeLane = LinkScheduler.selectLowestRttLink(probeLanes, networkMonitor.breakerRegistry)
        val probeEndpoint = probeLane?.let { networkMonitor.getNetworkByKey(it.linkKeyId) }
        val probeClient = buildClientForLink(probeEndpoint, entity.routeModeEnum, settings)

        val probeResult = try {
            performPreflightProbe(probeClient, entity.url, callsForTask).also {
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
                    errorMessage = "HTTP ${ex.code} signed token expired. Update URL to resume byte-for-byte.",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        } catch (ex: Exception) {
            probeLane?.linkKeyId?.let { key ->
                networkMonitor.breakerRegistry[key]?.recordFailureOrStall(immediateTrip = false)
                networkMonitor.publishState()
            }
            if (isNetworkFlap(ex)) {
                val recovered = waitForLinkMigration(entity)
                if (recovered) {
                    executeDownload(id)
                    return
                }
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
        // Keep previously committed segmentSizeBytes if resuming same file, else apply Adaptive BDP sizing
        val segSize = if (entity.totalBytes > 0L && entity.totalBytes == totalBytes) {
            entity.segmentSizeBytes.coerceAtLeast(MIN_EXTENT_BYTES)
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

        // Load or reconcile AETHLDG1 binary crash-safe ledger alongside Room bitfield
        val ledger = AetherBinaryLedger.loadOrCreate(
            file = ledgerFile(id),
            totalLen = totalBytes.coerceAtLeast(0L),
            segmentBytes = segSize,
            etagOrLastMod = probeResult.eTag.orEmpty(),
        )
        val bitfield = reconcileBitfield(entity, ledger, segmentCount, totalBytes)

        // R4: ENOSPC Free Space Guard (checks app filesDir when staging or downloads dir otherwise)
        if (totalBytes > 0L && (!entity.isSafDestination || useStagedSaf)) {
            val dir = if (useStagedSaf) {
                staging.parentFile?.apply { mkdirs() } ?: context.filesDir
            } else {
                repository.getDownloadsDirectory()
            }
            val free = StatFs(dir.absolutePath).availableBytes
            if (free < totalBytes + (8L * 1024L * 1024L)) {
                repository.update(
                    entity.copy(
                        totalBytes = totalBytes,
                        status = DownloadStatus.ENOSPC_R4.name,
                        errorMessage = "Insufficient storage (${free / (1024 * 1024)} MB free). Prune CAS or free disk space.",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                return
            }
        }

        entity = entity.copy(
            fileName = resolvedName,
            mimeType = guessMimeType(resolvedName),
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

        // Open random-access destination handle: staged app-private file for SAF, or direct path/SAF
        val handle = try {
            openDestinationHandle(entity, useStagedSaf, staging)
        } catch (sec: SecurityException) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.SAF_EBADF_R5.name,
                    errorMessage = "SAF permission revoked (EBADF). Re-grant folder/file permission to resume.",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        } catch (ioe: IOException) {
            val status = if (entity.isSafDestination && !useStagedSaf) {
                DownloadStatus.SAF_EBADF_R5
            } else {
                DownloadStatus.ENOSPC_R4
            }
            repository.update(
                entity.copy(
                    status = status.name,
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
                } else {
                    0L
                }
            }
        )
        val liveWrittenBytes = AtomicLong(committedBytes.get())
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

                // Background DB + AETHLDG1 ledger checkpoint & bidirectional autoSpeedUp controller
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

                        // Bidirectional closed-loop autoSpeedUp (+2 / -2 workers)
                        if (settings.enableAutoSpeedUp && probeResult.supportsRange && segmentCount > 1) {
                            val liveLanes = LinkScheduler.filterLanesByMode(
                                networkMonitor.state.value,
                                entity.routeModeEnum,
                            )
                            val minW = liveLanes.size.coerceAtLeast(1)
                            val anyBreakerOpen = networkMonitor.breakerRegistry.values.any {
                                it.currentState(now) != com.example.data.model.BreakerState.CLOSED
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

                        val snapshotBits = bitMutex.withLock {
                            syncLedgerFromBitChars(ledger, bitChars)
                            String(bitChars)
                        }
                        runCatching { ledger.persist(ledgerFile(id)) }
                        networkMonitor.publishState()

                        val latest = repository.getById(id) ?: break
                        if (!latest.statusEnum.isActive) break
                        repository.update(
                            latest.copy(
                                downloadedBytes = if (totalBytes > 0L) curBytes.coerceAtMost(totalBytes) else curBytes,
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
                        val maxPoolCoroutines = if (settings.enableAutoSpeedUp) {
                            minOf(8, segmentCount)
                        } else {
                            initialWorkers
                        }
                        val nextIndex = AtomicInteger(0)

                        coroutineScope {
                            val jobs = (0 until maxPoolCoroutines).map { workerId ->
                                async(Dispatchers.IO) {
                                    while (isActive && failureReason == null) {
                                        // Gate extra standby workers if autoSpeedUp scaled down
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
                                            // Work-stealing tail hedge if enabled (F6: tail hedge pins its own LinkKey)
                                            if (settings.enableTailHedge) {
                                                val stealIdx = findTailHedgeCandidate(bitChars, bitMutex)
                                                if (stealIdx != -1) {
                                                    val ok = runCatching {
                                                        downloadSegmentWithPinnedLink(
                                                            url = entity.url,
                                                            segIndex = stealIdx,
                                                            segSize = segSize,
                                                            totalBytes = totalBytes,
                                                            routeMode = entity.routeModeEnum,
                                                            settings = settings,
                                                            dest = dest,
                                                            bitChars = bitChars,
                                                            bitMutex = bitMutex,
                                                            liveWrittenBytes = liveWrittenBytes,
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
                                                url = entity.url,
                                                segIndex = segIdx,
                                                segSize = segSize,
                                                totalBytes = totalBytes,
                                                routeMode = entity.routeModeEnum,
                                                settings = settings,
                                                dest = dest,
                                                bitChars = bitChars,
                                                bitMutex = bitMutex,
                                                liveWrittenBytes = liveWrittenBytes,
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
                                            val canRetry = attempts < MAX_SEGMENT_RETRIES &&
                                                (ex is LinkStallException || isNetworkFlap(ex) || ex is IOException)
                                            bitMutex.withLock {
                                                if (SegmentState.fromCode(bitChars[segIdx]) != SegmentState.COMMITTED) {
                                                    bitChars[segIdx] = SegmentState.PENDING.code
                                                }
                                            }
                                            if (canRetry) {
                                                // Reset scan cursor so retried segment is picked up promptly by another healthy link (F6/F7)
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
                        // Single-stream fallback (unknown length or Range unsupported)
                        try {
                            downloadSingleStream(
                                url = entity.url,
                                routeMode = entity.routeModeEnum,
                                settings = settings,
                                dest = dest,
                                liveWrittenBytes = liveWrittenBytes,
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
                    runCatching {
                        syncLedgerFromBitChars(ledger, bitChars)
                        ledger.persist(ledgerFile(id))
                    }
                }
            }
        } catch (ce: CancellationException) {
            val latest = repository.getById(id)
            if (latest != null && latest.statusEnum.isActive) {
                repository.update(
                    latest.copy(
                        downloadedBytes = liveWrittenBytes.get(),
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
                    segmentBitfield = String(bitChars),
                    syscallWriteBatches = syscallBatches.get(),
                    errorMessage = fail.second,
                    updatedAt = System.currentTimeMillis(),
                )
                repository.update(updated)
                if (waitForLinkMigration(updated)) {
                    executeDownload(id)
                    return
                }
            }
            repository.update(
                entity.copy(
                    status = fail.first.name,
                    downloadedBytes = liveWrittenBytes.get(),
                    segmentBitfield = String(bitChars),
                    syscallWriteBatches = syscallBatches.get(),
                    errorMessage = fail.second,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        }

        // S3: Incremental SHA-256 & CAS Verification (runs on fast staging file before SAF copy if staged)
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

        // If SAF staged placement is active, verify passed — now copy staging -> SAF URI safely
        if (useStagedSaf) {
            val safReadyEntity = entity.copy(
                totalBytes = finalSize,
                downloadedBytes = finalSize,
                segmentBitfield = verifiedBitfield,
                actualSha256 = actualHash,
                avgSpeedBps = avgBps,
                peakSpeedBps = maxOf(peakSpeed.get(), avgBps),
                syscallWriteBatches = syscallBatches.get(),
                casDedupSkippedBytes = casDedupBytes,
            )
            if (!promoteStagedToSaf(safReadyEntity, staging)) {
                return
            }
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
        )
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
    ) {
        runCatching { ledgerFile(entity.id).delete() }
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
                            while (pos < len) {
                                pos += src.transferTo(pos, len - pos, dst)
                            }
                            dst.force(true)
                        }
                    }
                }
            }
            runCatching { staging.delete() }
            true
        } catch (sec: SecurityException) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.SAF_EBADF_R5.name,
                    errorMessage = "SAF permission revoked before placement. Staged file verified — re-grant SAF to finish.",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            false
        } catch (ioe: IOException) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.SAF_EBADF_R5.name,
                    errorMessage = "SAF placement failed (${ioe.message}). Staged file preserved — tap Re-Grant SAF.",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            false
        }
    }

    private fun reconcileBitfield(
        entity: DownloadEntity,
        ledger: AetherBinaryLedger,
        segmentCount: Int,
        totalBytes: Long,
    ): String {
        val roomValid = entity.segmentBitfield.length == segmentCount && totalBytes == entity.totalBytes
        return CharArray(segmentCount) { idx ->
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

    private suspend fun claimNextPendingSegment(
        bitChars: CharArray,
        mutex: Mutex,
        cursor: AtomicInteger,
    ): Int {
        return mutex.withLock {
            val len = bitChars.size
            while (true) {
                val idx = cursor.getAndIncrement()
                if (idx >= len) return@withLock -1
                if (SegmentState.fromCode(bitChars[idx]) == SegmentState.PENDING) {
                    return@withLock idx
                }
            }
            @Suppress("UNREACHABLE_CODE")
            -1
        }
    }

    private suspend fun findTailHedgeCandidate(
        bitChars: CharArray,
        mutex: Mutex,
    ): Int = mutex.withLock {
        bitChars.indexOfFirst {
            val s = SegmentState.fromCode(it)
            s == SegmentState.ACTIVE_WIFI || s == SegmentState.ACTIVE_5G
        }
    }

    /**
     * F5 + F6 + F7:
     * Selects a link via Earliest-Arrival F_k = (Q_k + S) / R_k + r_k / 2,
     * creates an immutable pinnedLinkKey for this SegmentLease (even on hedges/retries),
     * and enforces a 1,500 ms zero-byte stall watchdog tied to the LinkCircuitBreaker.
     */
    private suspend fun downloadSegmentWithPinnedLink(
        url: String,
        segIndex: Int,
        segSize: Long,
        totalBytes: Long,
        routeMode: NetworkRouteMode,
        settings: EngineSettings,
        dest: DestinationHandle,
        bitChars: CharArray,
        bitMutex: Mutex,
        liveWrittenBytes: AtomicLong,
        syscallBatches: AtomicInteger,
        queuedBytesByLink: ConcurrentHashMap<String, AtomicLong>,
        callsForTask: MutableList<Call>,
        isHedge: Boolean,
    ): Boolean {
        val startOffset = segIndex.toLong() * segSize
        val endOffset = minOf(startOffset + segSize - 1L, totalBytes - 1L)
        if (startOffset > endOffset) return false
        val segLen = (endOffset - startOffset + 1L)

        // Build live lane snapshot with real in-flight queued bytes Q_k
        val baseLanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, routeMode)
            .map { lane ->
                val q = queuedBytesByLink[lane.linkKeyId]?.get()?.toDouble() ?: 0.0
                lane.copy(queuedBytes = q)
            }
        val selectedLane = LinkScheduler.selectEarliestArrivalLink(
            lanes = baseLanes,
            breakers = networkMonitor.breakerRegistry,
            segmentBytes = segLen.toDouble(),
        ) ?: LinkScheduler.selectEarliestArrivalLink(
            // If all breakers temporarily open, force probe on lowest-RTT lane
            lanes = baseLanes,
            breakers = emptyMap(),
            segmentBytes = segLen.toDouble(),
        )

        val pinnedLinkKeyId = selectedLane?.linkKeyId
        val pinnedEndpoint = pinnedLinkKeyId?.let { networkMonitor.getNetworkByKey(it) }
        val isCell = pinnedEndpoint?.linkKey?.isCellular == true || routeMode == NetworkRouteMode.CELLULAR_ONLY
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

        telemetryLedger.workerStarted(isCell)
        val slab = slabPool.acquire()
        val lastProgressMs = AtomicLong(System.currentTimeMillis())
        val stalledOut = AtomicBoolean(false)
        val segStartNs = System.nanoTime()
        var segWritten = 0L

        var callRef: Call? = null
        val watchdogJob = engineScope.launch {
            while (isActive) {
                delay(300L)
                val idleMs = System.currentTimeMillis() - lastProgressMs.get()
                if (idleMs >= STALL_WATCHDOG_MS) {
                    stalledOut.set(true)
                    callRef?.cancel()
                    break
                }
            }
        }

        try {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=$startOffset-$endOffset")
                .header("User-Agent", "AetherIO-Omega/7.1 Android")
                .build()
            val call = client.newCall(req)
            callRef = call
            synchronized(callsForTask) { callsForTask.add(call) }

            call.execute().use { resp ->
                lastProgressMs.set(System.currentTimeMillis())
                if (resp.code == 403 || resp.code == 410) {
                    throw HttpAuthExpiredException(resp.code)
                }
                if (!resp.isSuccessful && resp.code != 206) {
                    throw IOException("HTTP ${resp.code} on segment $segIndex")
                }

                val body = resp.body ?: throw IOException("Empty body on segment $segIndex")
                val source = body.source()
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
                        telemetryLedger.recordBytesWritten(read.toLong())
                        syscallBatches.incrementAndGet()
                        telemetryLedger.recordSyscallWriteBatch()
                    }
                }

                bitMutex.withLock {
                    bitChars[segIndex] = SegmentState.COMMITTED.code
                }

                // Update live link EWMA & close circuit breaker cleanly
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
                if (pinnedLinkKeyId != null) {
                    networkMonitor.breakerRegistry[pinnedLinkKeyId]?.recordFailureOrStall(immediateTrip = true)
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
            if (stalledOut.get()) {
                throw LinkStallException(pinnedLinkKeyId ?: "unknown")
            }
            throw ex
        } finally {
            watchdogJob.cancel()
            if (reservedRemaining > 0L) {
                queueCounter?.addAndGet(-reservedRemaining)
            }
            slabPool.release(slab)
            telemetryLedger.workerStopped(isCell)
        }
    }

    private fun downloadSingleStream(
        url: String,
        routeMode: NetworkRouteMode,
        settings: EngineSettings,
        dest: DestinationHandle,
        liveWrittenBytes: AtomicLong,
        syscallBatches: AtomicInteger,
        callsForTask: MutableList<Call>,
    ) {
        val lanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, routeMode)
        val selectedLane = LinkScheduler.selectEarliestArrivalLink(
            lanes = lanes,
            breakers = networkMonitor.breakerRegistry,
            segmentBytes = settings.segmentSizeBytes.toDouble(),
        )
        val endpoint = selectedLane?.let { networkMonitor.getNetworkByKey(it.linkKeyId) }
        val isCell = endpoint?.linkKey?.isCellular == true || routeMode == NetworkRouteMode.CELLULAR_ONLY
        val client = buildClientForLink(endpoint, routeMode, settings)

        telemetryLedger.workerStarted(isCell)
        val slab = slabPool.acquire()
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AetherIO-Omega/7.1 Android")
                .build()
            val call = client.newCall(req)
            synchronized(callsForTask) { callsForTask.add(call) }
            call.execute().use { resp ->
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
                        telemetryLedger.recordBytesWritten(read.toLong())
                        syscallBatches.incrementAndGet()
                        telemetryLedger.recordSyscallWriteBatch()
                    }
                }
            }
        } finally {
            slabPool.release(slab)
            telemetryLedger.workerStopped(isCell)
        }
    }

    private fun performPreflightProbe(
        client: OkHttpClient,
        url: String,
        callsForTask: MutableList<Call>,
    ): PreflightProbeResult {
        val t0 = System.nanoTime()
        val probeReq = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", "AetherIO-Omega/7.1 Android")
            .build()
        val call = client.newCall(probeReq)
        synchronized(callsForTask) { callsForTask.add(call) }

        call.execute().use { resp ->
            val ttfb = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            if (resp.code == 403 || resp.code == 410) {
                throw HttpAuthExpiredException(resp.code)
            }
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("HTTP ${resp.code} ${resp.message}")
            }
            val contentRange = resp.header("Content-Range")
            val parsedRangeTotal = contentRange
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
            val headerLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val total = when {
                resp.code == 206 && parsedRangeTotal != null -> parsedRangeTotal
                resp.code == 200 && headerLength > 0 -> headerLength
                else -> -1L
            }
            val supportsRange = resp.code == 206 ||
                resp.header("Accept-Ranges").equals("bytes", ignoreCase = true)

            val disp = resp.header("Content-Disposition")
            val dispName = disp?.let {
                Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.getOrNull(1)
            }

            return PreflightProbeResult(
                totalBytes = total,
                supportsRange = supportsRange,
                ttfbMs = ttfb,
                eTag = resp.header("ETag"),
                suggestedFileName = dispName,
            )
        }
    }

    private suspend fun waitForLinkMigration(entity: DownloadEntity): Boolean {
        for (i in 0 until 20) {
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
                    val cHash = chunkDigest.digest().toHexString()
                    if (repository.hasCasChunk(cHash)) dedupMatchBytes += currentChunkBytes
                    chunks += CasChunkEntity(
                        chunkHash = cHash,
                        chunkSize = currentChunkBytes,
                        sourceDownloadId = entity.id,
                    )
                    chunkDigest.reset()
                    chunkRemaining = segSize
                    currentChunkBytes = 0L
                }
            }
            if (currentChunkBytes > 0L) {
                val cHash = chunkDigest.digest().toHexString()
                if (repository.hasCasChunk(cHash)) dedupMatchBytes += currentChunkBytes
                chunks += CasChunkEntity(
                    chunkHash = cHash,
                    chunkSize = currentChunkBytes,
                    sourceDownloadId = entity.id,
                )
            }
        }

        repository.recordCasChunks(chunks)
        return VerifyResult(
            fileSha256 = fileDigest.digest().toHexString(),
            dedupMatchingBytes = dedupMatchBytes,
        )
    }

    /**
     * F3 / F9: Binds sockets + DNS to the specific Android Network, or applies per-LinkKey
     * SOCKS5h unresolved proxy overrides (Cellular vs Wi-Fi) when configured.
     */
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

        val resolvedProxyHost = when {
            linkProxyHost.isNotBlank() -> linkProxyHost
            routeMode == NetworkRouteMode.PROXY_SOCKS5 && settings.proxyHost.isNotBlank() -> settings.proxyHost.trim()
            else -> ""
        }
        val resolvedProxyPort = if (linkProxyHost.isNotBlank()) linkProxyPort else settings.proxyPort

        if (resolvedProxyHost.isNotBlank()) {
            builder.proxy(
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved(resolvedProxyHost, resolvedProxyPort),
                )
            )
            // Keep underlying socket bound to the selected link when present
            endpoint?.network?.let { net -> builder.socketFactory(net.socketFactory) }
        } else if (endpoint != null) {
            val net: Network = endpoint.network
            builder.socketFactory(net.socketFactory)
            builder.dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = try {
                        net.getAllByName(hostname).toList()
                    } catch (_: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            )
        }
        return builder.build()
    }

    private fun openDestinationHandle(
        entity: DownloadEntity,
        useStagedSaf: Boolean,
        staging: File,
    ): DestinationHandle {
        return when {
            useStagedSaf -> {
                staging.parentFile?.mkdirs()
                val raf = RandomAccessFile(staging, "rw")
                DestinationHandle(
                    channel = raf.channel,
                    closeable = raf,
                    isRandomAccess = true,
                )
            }
            entity.isSafDestination -> {
                val uri = Uri.parse(entity.destinationUri)
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                    ?: throw IOException("Unable to open SAF file descriptor (EBADF)")
                val fos = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                DestinationHandle(
                    channel = fos.channel,
                    closeable = fos,
                    isRandomAccess = true,
                )
            }
            else -> {
                val file = File(entity.destinationUri)
                file.parentFile?.mkdirs()
                val raf = RandomAccessFile(file, "rw")
                DestinationHandle(
                    channel = raf.channel,
                    closeable = raf,
                    isRandomAccess = true,
                )
            }
        }
    }

    private fun ledgerFile(id: String): File =
        File(File(context.filesDir, "ledgers"), "$id.aethldg")

    private fun stagingFile(id: String): File =
        File(File(context.filesDir, "staging"), "$id.part")

    private fun ensureForegroundServiceRunning() {
        val intent = Intent(context, AetherDownloadService::class.java).apply {
            action = AetherDownloadService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun isNetworkFlap(ex: Exception): Boolean =
        ex is UnknownHostException || ex is SocketException

    private fun guessFileNameFromUrl(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val segment = clean.substringAfterLast('/')
        return segment.takeIf { it.isNotBlank() && it.contains('.') }
            ?: "aether_${System.currentTimeMillis()}.bin"
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private class HttpAuthExpiredException(val code: Int) : IOException("HTTP $code")
    private class LinkStallException(linkKeyId: String) : IOException("Zero-byte stall >= ${STALL_WATCHDOG_MS}ms on $linkKeyId")

    private data class PreflightProbeResult(
        val totalBytes: Long,
        val supportsRange: Boolean,
        val ttfbMs: Long,
        val eTag: String?,
        val suggestedFileName: String?,
    )

    private data class VerifyResult(
        val fileSha256: String,
        val dedupMatchingBytes: Long,
    )

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
                    val zero = ByteBuffer.allocate(1)
                    channel.write(zero, size - 1L)
                }
            }
        }

        fun writeAt(buffer: ByteBuffer, offset: Long) {
            synchronized(writeMutex) {
                var pos = offset
                while (buffer.hasRemaining()) {
                    pos += channel.write(buffer, pos)
                }
            }
        }

        override fun close() {
            runCatching { channel.force(true) }
            runCatching { closeable.close() }
        }
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }
