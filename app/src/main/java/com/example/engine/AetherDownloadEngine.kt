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
import com.example.data.repository.DownloadRepository
import com.example.data.preferences.SettingsRepository
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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

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

    // OkHttp defaults cap a single host at 5 concurrent connections, which silently
    // throttled the 8-worker engine. Dedicated pool + dispatcher unlock full parallelism.
    private val connectionPool = ConnectionPool(
        maxIdleConnections = 16,
        keepAliveDuration = 10,
        timeUnit = TimeUnit.MINUTES,
    )
    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 32
    }

    private val baseClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
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
            segmentSizeBytes = settings.segmentSizeBytes,
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
        val callsForTask = mutableListOf<Call>()
        activeCalls[id] = callsForTask

        // S0-S1: Pre-flight 0-RTT/Range Probe
        entity = entity.copy(
            status = DownloadStatus.PROBING_0RTT.name,
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(entity)

        val (selectedNet, _) = networkMonitor.selectNetworkForWorker(entity.routeModeEnum, 0)
        val probeClient = buildClient(selectedNet, entity.routeModeEnum, settings)

        val probeResult = try {
            performPreflightProbe(probeClient, entity.url, callsForTask)
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

        val resolvedName = if (entity.fileName.startsWith("download_") || entity.fileName == "probe.bin") {
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
        val segSize = entity.segmentSizeBytes.coerceAtLeast(F2FS_SEGMENT_BYTES)
        val segmentCount = if (totalBytes > 0L && probeResult.supportsRange) {
            ceil(totalBytes.toDouble() / segSize.toDouble()).toInt().coerceAtLeast(1)
        } else {
            1
        }

        val bitfield = if (entity.segmentBitfield.length == segmentCount && totalBytes == entity.totalBytes) {
            // Reset any previously in-flight active markers back to pending on resume
            entity.segmentBitfield.map { ch ->
                when (SegmentState.fromCode(ch)) {
                    SegmentState.COMMITTED, SegmentState.VERIFIED -> ch
                    else -> SegmentState.PENDING.code
                }
            }.joinToString("")
        } else {
            SegmentState.PENDING.code.toString().repeat(segmentCount)
        }

        // R4: ENOSPC Free Space Guard
        if (totalBytes > 0L && !entity.isSafDestination) {
            val dir = repository.getDownloadsDirectory()
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
            segmentBitfield = bitfield,
            eTag = probeResult.eTag,
            ttfbMs = probeResult.ttfbMs,
            status = DownloadStatus.STREAMING.name,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(entity)

        // Open zero-merge destination FileChannel (local file or SAF detached ParcelFileDescriptor)
        val handle = try {
            openDestinationHandle(entity)
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
            val status = if (entity.isSafDestination) DownloadStatus.SAF_EBADF_R5 else DownloadStatus.ENOSPC_R4
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
                } else 0L
            }
        )
        val liveWrittenBytes = AtomicLong(committedBytes.get())
        val syscallBatches = AtomicInteger(entity.syscallWriteBatches)
        val peakSpeed = AtomicLong(entity.peakSpeedBps)

        var failureReason: Pair<DownloadStatus, String>? = null

        try {
            handle.use { dest ->
                if (totalBytes > 0L && dest.isRandomAccess) {
                    // Pre-size extent cleanly once for zero post-download merge
                    runCatching { dest.ensureSize(totalBytes) }
                }

                // Background DB checkpoint flusher every 500ms while streaming
                val flusherJob = engineScope.launch {
                    var lastSampleTime = System.currentTimeMillis()
                    var lastSampleBytes = liveWrittenBytes.get()
                    while (isActive) {
                        delay(500L)
                        val now = System.currentTimeMillis()
                        val curBytes = liveWrittenBytes.get()
                        val dt = (now - lastSampleTime).coerceAtLeast(100L)
                        val curSpeed = ((curBytes - lastSampleBytes).coerceAtLeast(0L) * 1000L) / dt
                        peakSpeed.updateAndGet { maxOf(it, curSpeed) }
                        lastSampleTime = now
                        lastSampleBytes = curBytes

                        val snapshotBits = bitMutex.withLock { String(bitChars) }
                        val latest = repository.getById(id) ?: break
                        if (!latest.statusEnum.isActive) break
                        repository.update(
                            latest.copy(
                                downloadedBytes = if (totalBytes > 0L) curBytes.coerceAtMost(totalBytes) else curBytes,
                                segmentBitfield = snapshotBits,
                                syscallWriteBatches = syscallBatches.get(),
                                peakSpeedBps = peakSpeed.get(),
                                updatedAt = now,
                            )
                        )
                    }
                }

                try {
                    if (probeResult.supportsRange && totalBytes > 0L && dest.isRandomAccess && segmentCount > 1) {
                        val workerCount = minOf(entity.workerCount, settings.maxConcurrentWorkers)
                            .coerceIn(1, minOf(8, segmentCount))
                        val nextIndex = AtomicInteger(0)

                        coroutineScope {
                            val jobs = (0 until workerCount).map { workerId ->
                                async(Dispatchers.IO) {
                                    while (isActive && failureReason == null) {
                                        val segIdx = claimNextPendingSegment(bitChars, bitMutex, nextIndex)
                                        if (segIdx == -1) {
                                            // Work-stealing tail hedge if enabled
                                            if (settings.enableTailHedge) {
                                                val stealIdx = findTailHedgeCandidate(bitChars, bitMutex)
                                                if (stealIdx != -1) {
                                                    val ok = downloadSegment(
                                                        taskId = id,
                                                        url = entity.url,
                                                        segIndex = stealIdx,
                                                        segSize = segSize,
                                                        totalBytes = totalBytes,
                                                        routeMode = entity.routeModeEnum,
                                                        workerId = workerId,
                                                        settings = settings,
                                                        dest = dest,
                                                        bitChars = bitChars,
                                                        bitMutex = bitMutex,
                                                        liveWrittenBytes = liveWrittenBytes,
                                                        syscallBatches = syscallBatches,
                                                        callsForTask = callsForTask,
                                                        isHedge = true,
                                                    )
                                                    if (ok) continue
                                                }
                                            }
                                            break
                                        }

                                        try {
                                            downloadSegment(
                                                taskId = id,
                                                url = entity.url,
                                                segIndex = segIdx,
                                                segSize = segSize,
                                                totalBytes = totalBytes,
                                                routeMode = entity.routeModeEnum,
                                                workerId = workerId,
                                                settings = settings,
                                                dest = dest,
                                                bitChars = bitChars,
                                                bitMutex = bitMutex,
                                                liveWrittenBytes = liveWrittenBytes,
                                                syscallBatches = syscallBatches,
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
                                            bitMutex.withLock {
                                                if (SegmentState.fromCode(bitChars[segIdx]) != SegmentState.COMMITTED) {
                                                    bitChars[segIdx] = SegmentState.PENDING.code
                                                }
                                            }
                                            if (isNetworkFlap(ex)) {
                                                failureReason = DownloadStatus.LINK_MIGRATE_R1 to
                                                    "Radio link interrupted — awaiting Wi-Fi/5G migration"
                                            } else {
                                                failureReason = DownloadStatus.FAILED to
                                                    (ex.message ?: "Segment $segIdx failed")
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

        // S3: Incremental SHA-256 & 2 MB CAS Verification
        val finalSize = if (totalBytes > 0L) totalBytes else liveWrittenBytes.get()
        val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
        val avgBps = (finalSize * 1000L) / elapsedMs

        var actualHash: String? = null
        var casDedupBytes = 0L
        var verifiedBitfield = String(bitChars)

        if (settings.enableCasVerification) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.VERIFYING_BAO.name,
                    downloadedBytes = finalSize,
                    segmentBitfield = verifiedBitfield,
                    syscallWriteBatches = syscallBatches.get(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
            val verifyResult = verifyFileAndIndexCas(entity, segSize)
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

        repository.update(
            entity.copy(
                totalBytes = finalSize,
                downloadedBytes = finalSize,
                status = DownloadStatus.COMPLETED.name,
                segmentBitfield = verifiedBitfield,
                actualSha256 = actualHash,
                avgSpeedBps = avgBps,
                peakSpeedBps = maxOf(peakSpeed.get(), avgBps),
                syscallWriteBatches = syscallBatches.get(),
                casDedupSkippedBytes = casDedupBytes,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
            )
        )
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

    private suspend fun downloadSegment(
        taskId: String,
        url: String,
        segIndex: Int,
        segSize: Long,
        totalBytes: Long,
        routeMode: NetworkRouteMode,
        workerId: Int,
        settings: EngineSettings,
        dest: DestinationHandle,
        bitChars: CharArray,
        bitMutex: Mutex,
        liveWrittenBytes: AtomicLong,
        syscallBatches: AtomicInteger,
        callsForTask: MutableList<Call>,
        isHedge: Boolean,
    ): Boolean {
        val startOffset = segIndex.toLong() * segSize
        val endOffset = minOf(startOffset + segSize - 1L, totalBytes - 1L)
        if (startOffset > endOffset) return false

        val (net, isCell) = networkMonitor.selectNetworkForWorker(routeMode, workerId)
        val client = buildClient(net, routeMode, settings)

        bitMutex.withLock {
            val cur = SegmentState.fromCode(bitChars[segIndex])
            if (cur == SegmentState.COMMITTED || cur == SegmentState.VERIFIED) return false
            bitChars[segIndex] = when {
                isHedge -> SegmentState.STOLEN_HEDGE.code
                isCell -> SegmentState.ACTIVE_5G.code
                else -> SegmentState.ACTIVE_WIFI.code
            }
        }

        telemetryLedger.workerStarted(isCell)
        val slab = slabPool.acquire()
        try {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=$startOffset-$endOffset")
                .header("User-Agent", "AetherIO-Omega/6.0 Android")
                .build()
            val call = client.newCall(req)
            synchronized(callsForTask) { callsForTask.add(call) }

            call.execute().use { resp ->
                if (resp.code == 403 || resp.code == 410) {
                    throw HttpAuthExpiredException(resp.code)
                }
                if (!resp.isSuccessful && resp.code != 206) {
                    throw IOException("HTTP ${resp.code} on segment $segIndex")
                }

                val body = resp.body ?: throw IOException("Empty body on segment $segIndex")
                val source = body.source()
                var offset = startOffset
                var segWritten = 0L

                while (true) {
                    // If another hedge worker already committed this segment, exit early
                    val alreadyDone = bitMutex.withLock {
                        SegmentState.fromCode(bitChars[segIndex]) == SegmentState.COMMITTED
                    }
                    if (alreadyDone) return false

                    slab.clear()
                    val read = source.read(slab)
                    if (read == -1) break
                    if (read > 0) {
                        slab.flip()
                        dest.writeAt(slab, offset)
                        offset += read
                        segWritten += read
                        liveWrittenBytes.addAndGet(read.toLong())
                        telemetryLedger.recordBytesWritten(read.toLong())
                        syscallBatches.incrementAndGet()
                        telemetryLedger.recordSyscallWriteBatch()
                    }
                }

                bitMutex.withLock {
                    bitChars[segIndex] = SegmentState.COMMITTED.code
                }
                // Feed the committed extent into the native (Rust) telemetry page.
                AetherNativeBridge.recordCommit(segWritten)
                return true
            }
        } finally {
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
        val (net, isCell) = networkMonitor.selectNetworkForWorker(routeMode, 0)
        val client = buildClient(net, routeMode, settings)
        telemetryLedger.workerStarted(isCell)
        val slab = slabPool.acquire()
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AetherIO-Omega/6.0 Android")
                .build()
            val call = client.newCall(req)
            synchronized(callsForTask) { callsForTask.add(call) }
            call.execute().use { resp ->
                if (resp.code == 403 || resp.code == 410) throw HttpAuthExpiredException(resp.code)
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val source = resp.body?.source() ?: throw IOException("Empty response body")
                var offset = 0L
                var streamWritten = 0L
                while (true) {
                    slab.clear()
                    val read = source.read(slab)
                    if (read == -1) break
                    if (read > 0) {
                        slab.flip()
                        dest.writeAt(slab, offset)
                        offset += read
                        streamWritten += read.toLong()
                        liveWrittenBytes.addAndGet(read.toLong())
                        telemetryLedger.recordBytesWritten(read.toLong())
                        syscallBatches.incrementAndGet()
                        telemetryLedger.recordSyscallWriteBatch()
                    }
                }
                AetherNativeBridge.recordCommit(streamWritten)
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
            .header("User-Agent", "AetherIO-Omega/6.0 Android")
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
    ): VerifyResult {
        val fileDigest = MessageDigest.getInstance("SHA-256")
        val chunkDigest = MessageDigest.getInstance("SHA-256")
        val chunks = mutableListOf<CasChunkEntity>()
        var dedupMatchBytes = 0L
        // Larger reads mean fewer syscalls on the verification pass.
        val buf = ByteArray(256 * 1024)

        // When the Rust core is loaded, chunk digests come from SIMD BLAKE3 via JNI.
        // SHA-256 stays as the file digest (needed for user-supplied expectedSha256)
        // and as the fallback chunk hasher.
        val chunkCap = segSize.coerceIn(1L, 16L * 1024L * 1024L).toInt()
        val nativeBuf = if (AetherNativeBridge.isNativeLoaded) ByteArray(chunkCap) else null
        var nativeFilled = 0

        val stream = if (entity.isSafDestination) {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(entity.destinationUri), "r")
                ?: return VerifyResult("", 0L)
            ParcelFileDescriptor.AutoCloseInputStream(pfd)
        } else {
            FileInputStream(File(entity.destinationUri))
        }

        fun chunkHash(currentBytes: Long): String {
            val native = nativeBuf
            return if (native != null && nativeFilled >= 0) {
                AetherNativeBridge.blake3Hex(native.copyOf(nativeFilled))
                    ?: chunkDigest.digest().toHexString()
            } else {
                chunkDigest.digest().toHexString()
            }
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
                nativeBuf?.let { native ->
                    if (nativeFilled >= 0 && nativeFilled + read <= native.size) {
                        System.arraycopy(buf, 0, native, nativeFilled, read)
                        nativeFilled += read
                    } else {
                        // Chunk larger than the native hash buffer: fall back to SHA-256.
                        nativeFilled = -1
                    }
                }
                currentChunkBytes += read
                chunkRemaining -= read
                if (chunkRemaining == 0L) {
                    val cHash = chunkHash(currentChunkBytes)
                    if (repository.hasCasChunk(cHash)) dedupMatchBytes += currentChunkBytes
                    chunks += CasChunkEntity(
                        chunkHash = cHash,
                        chunkSize = currentChunkBytes,
                        sourceDownloadId = entity.id,
                    )
                    chunkDigest.reset()
                    nativeFilled = 0
                    chunkRemaining = segSize
                    currentChunkBytes = 0L
                }
            }
            if (currentChunkBytes > 0L) {
                val cHash = chunkHash(currentChunkBytes)
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

    private fun buildClient(
        network: Network?,
        routeMode: NetworkRouteMode,
        settings: EngineSettings,
    ): OkHttpClient {
        val builder = baseClient.newBuilder()
        if (routeMode == NetworkRouteMode.PROXY_SOCKS5 && settings.proxyHost.isNotBlank()) {
            builder.proxy(
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved(settings.proxyHost, settings.proxyPort),
                )
            )
        } else if (network != null) {
            builder.socketFactory(network.socketFactory)
        }
        return builder.build()
    }

    private fun openDestinationHandle(entity: DownloadEntity): DestinationHandle {
        return if (entity.isSafDestination) {
            val uri = Uri.parse(entity.destinationUri)
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Unable to open SAF file descriptor (EBADF)")
            val fos = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            val channel = fos.channel
            DestinationHandle(
                channel = channel,
                closeable = fos,
                isRandomAccess = true,
            )
        } else {
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
