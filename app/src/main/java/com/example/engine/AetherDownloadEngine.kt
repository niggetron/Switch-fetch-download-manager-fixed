package com.example.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.MimeTypeMap
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineSettings
import com.example.data.model.NewDownloadRequest
import com.example.data.model.SegmentState
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.DownloadRepository
import com.example.service.AetherDownloadService
import com.example.service.AetherDownloadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MIN_EXTENT_BYTES = 1L * 1024L * 1024L

/**
 * Unified Dual-Engine Coordinator for AetherIO v8.0:
 * Routes each download strictly according to user toggles (`enableRustEngine`,
 * `enableKotlinOkHttpEngine`, and `preferredEngineBackend` / per-task override) between:
 *  1) Pure Rust `libaether_core.so` (`wreq` BoringSSL + `quinn` HTTP/3 + `memfd` + `AETHLDG2`)
 *  2) Upgraded Kotlin OkHttp Multi-Network Engine (`KotlinOkHttpTransferRunner`)
 */
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

    private val aria2Engine = Aria2NextEngine(context = context, repository = repository)

    fun aria2Status(): Pair<Boolean, String> = aria2Engine.isAvailable() to aria2Engine.lastError

    private val kotlinRunner = KotlinOkHttpTransferRunner(
        context = context,
        repository = repository,
        networkMonitor = networkMonitor,
        slabPool = slabPool,
        telemetryLedger = telemetryLedger,
        engineScope = engineScope,
        activeCalls = activeCalls,
        ledgerFileProvider = ::ledgerFile,
        stagingFileProvider = ::stagingFile,
    )

    /**
     * Startup & BOOT_COMPLETED Room <-> AETHLDG2 reconciliation:
     * Any transfer that was active when the process died has its `STATE_LEASED` ledger blocks
     * rolled back to `STATE_PENDING` and its Room status set cleanly to `PAUSED`.
     */
    suspend fun reconcileLedgersOnStartup() {
        val all = repository.getAllOnce()
        for (entity in all) {
            if (!entity.statusEnum.isActive || activeJobs[entity.id]?.isActive == true) continue
            val ldgFile = ledgerFile(entity.id)
            var updatedBitfield = entity.segmentBitfield
            if (ldgFile.exists()) {
                val decoded = runCatching { AetherBinaryLedger.decode(ldgFile.readBytes()) }.getOrNull()
                if (decoded != null) {
                    if (decoded.rollbackLeasedSegments()) {
                        runCatching { decoded.persist(ldgFile) }
                    }
                    updatedBitfield = CharArray(decoded.states.size) { idx ->
                        when (decoded.states[idx]) {
                            AetherBinaryLedger.STATE_DONE -> SegmentState.COMMITTED.code
                            else -> SegmentState.PENDING.code
                        }
                    }.concatToString()
                }
            } else {
                updatedBitfield = entity.segmentBitfield.map { ch ->
                    when (SegmentState.fromCode(ch)) {
                        SegmentState.COMMITTED -> SegmentState.COMMITTED.code
                        SegmentState.VERIFIED -> SegmentState.VERIFIED.code
                        else -> SegmentState.PENDING.code
                    }
                }.toCharArray().concatToString()
            }

            repository.update(
                entity.copy(
                    status = DownloadStatus.PAUSED.name,
                    segmentBitfield = updatedBitfield,
                    errorMessage = "Interrupted — Tap to Resume from AETHLDG2 checkpoint",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            DiagnosticsCenter.log("RECONCILE", "Recovered interrupted transfer ${entity.fileName} -> PAUSED")
        }
    }

    suspend fun enqueue(request: NewDownloadRequest): String {
        val settings = settingsRepository.current()
        val id = UUID.randomUUID().toString()
        val zipEntry = request.selectedZipEntry
        val guessedName = request.customFileName?.takeIf { it.isNotBlank() }
            ?: zipEntry?.fileName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: guessFileNameFromUrl(request.url)
        val isSaf = !request.safUriString.isNullOrBlank()
        val destUri = if (isSaf) {
            request.safUriString!!
        } else {
            repository.resolveUniqueTargetFile(guessedName).absolutePath
        }
        val mime = guessMimeType(guessedName)

        val lanes = LinkScheduler.filterLanesByMode(networkMonitor.state.value, request.routeMode)
        val fastestKbps = networkMonitor.state.value.activeValidatedLinks
            .maxOfOrNull { it.downstreamKbps } ?: 60_000
        val bdpSegment = if (lanes.isNotEmpty()) {
            LinkScheduler.adaptiveBdpSegmentBytes(fastestKbps)
        } else {
            settings.segmentSizeBytes.coerceAtLeast(MIN_EXTENT_BYTES)
        }

        val backendLabel = when (
            val sel = StoragePlacementHelper.selectBackend(
                settings = settings,
                nativeLoaded = AetherNativeBridge.isNativeLoaded,
                overridePreference = request.backendOverride,
            )
        ) {
            is BackendSelectionResult.Selected -> sel.backend.id
            is BackendSelectionResult.Unavailable -> request.backendOverride?.name ?: ""
        }

        val now = System.currentTimeMillis()
        val entity = DownloadEntity(
            id = id,
            url = request.url.trim(),
            mirrorUrls = request.mirrorUrls.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n"),
            fileName = guessedName,
            mimeType = mime,
            totalBytes = zipEntry?.uncompressedSize ?: -1L,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED.name,
            routeMode = request.routeMode.name,
            workerCount = request.workers.coerceIn(1, com.example.data.model.MAX_CONCURRENT_WORKERS_CAP),
            destinationUri = destUri,
            isSafDestination = isSaf,
            supportsRange = zipEntry != null,
            segmentSizeBytes = bdpSegment,
            expectedSha256 = request.expectedSha256?.trim()?.takeIf { it.isNotBlank() },
            engineBackendUsed = backendLabel,
            maxCellularBytes = request.maxCellularBytes,
            browserProfile = request.browserProfile.wireId,
            protocolMode = request.protocolMode.wireId,
            startedAt = now,
            streamType = if (zipEntry != null) "ZIP_ENTRY" else "DIRECT",
        )
        repository.upsert(entity)
        if (zipEntry != null) {
            launchZipEntryExtraction(entity, zipEntry)
        } else {
            launchDownload(id)
        }
        return id
    }

    private fun launchZipEntryExtraction(entity: DownloadEntity, entry: com.example.data.model.RemoteZipEntry) {
        ensureForegroundServiceRunning()
        val job = engineScope.launch {
            val startMs = System.currentTimeMillis()
            try {
                repository.update(entity.copy(status = DownloadStatus.STREAMING.name, updatedAt = startMs))
                val targetFile = File(entity.destinationUri)
                val written = RemoteZipInspector.extractRemoteZipEntry(
                    url = entity.url,
                    entry = entry,
                    targetFile = targetFile,
                ) { done, total ->
                    telemetryLedger.recordBytesWritten(done)
                }
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val avg = (written * 1000L) / elapsed
                repository.update(
                    entity.copy(
                        totalBytes = written,
                        downloadedBytes = written,
                        status = DownloadStatus.COMPLETED.name,
                        avgSpeedBps = avg,
                        peakSpeedBps = avg,
                        activeDownloadMs = elapsed,
                        maxConnectionsUsed = 1,
                        segmentBitfield = SegmentState.VERIFIED.code.toString(),
                        completedAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } catch (ex: Exception) {
                repository.update(
                    entity.copy(
                        status = DownloadStatus.FAILED.name,
                        errorMessage = "Remote ZIP extraction failed: ${ex.message}",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } finally {
                activeJobs.remove(entity.id)
                if (activeJobs.isEmpty()) telemetryLedger.markFgsSessionStopped()
            }
        }
        activeJobs[entity.id] = job
    }

    fun launchDownload(id: String) {
        if (activeJobs[id]?.isActive == true) return
        aria2Engine.resume(id) // no-op unless this task was paused on the aria2-next backend
        ensureForegroundServiceRunning()
        val job = engineScope.launch {
            try {
                executeDownload(id)
            } finally {
                activeJobs.remove(id)
                activeCalls.remove(id)?.forEach { runCatching { it.cancel() } }
                telemetryLedger.clearKotlinConnectionsForTask(id)
                slabPool.trimIdle()
                if (activeJobs.isEmpty()) {
                    telemetryLedger.markFgsSessionStopped()
                }
            }
        }
        activeJobs[id] = job
    }

    suspend fun pause(id: String) {
        AetherNativeBridge.pauseDownloadSafe(id)
        aria2Engine.pause(id)
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
        activeJobs.keys.toList().forEach { pause(it) }
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
            AetherNativeBridge.pauseDownloadSafe(id)
            aria2Engine.pause(id)
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
        AetherNativeBridge.cancelDownloadSafe(id)
        aria2Engine.stop(id)
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
        val newList = listOf(newUrl.trim()) + current.allUrls.drop(1)
        AetherNativeBridge.updateTaskUrlsSafe(id, newList)
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

    suspend fun switchTaskToKotlinAndResume(id: String) {
        val current = repository.getById(id) ?: return
        settingsRepository.update {
            it.copy(
                enableKotlinOkHttpEngine = true,
                preferredEngineBackend = com.example.data.model.EngineBackendPreference.FORCE_KOTLIN_OKHTTP,
            )
        }
        repository.update(
            current.copy(
                engineBackendUsed = ResolvedEngineBackend.KOTLIN_OKHTTP.id,
                status = DownloadStatus.QUEUED.name,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        launchDownload(id)
    }

    private suspend fun executeDownload(id: String) {
        val entity = repository.getById(id) ?: return
        val settings = settingsRepository.current()
        networkMonitor.applyPolicy(settings)

        // Respect per-task backend pin only if that engine is still enabled & available;
        // otherwise honor the user's current Settings switchboard cleanly on resume.
        val perTaskPref = when (entity.engineBackendUsed) {
            ResolvedEngineBackend.RUST_LIBAETHER_CORE.id ->
                if (settings.enableRustEngine && AetherNativeBridge.isNativeLoaded) {
                    com.example.data.model.EngineBackendPreference.FORCE_RUST_NATIVE
                } else {
                    null
                }
            ResolvedEngineBackend.KOTLIN_OKHTTP.id ->
                if (settings.enableKotlinOkHttpEngine) {
                    com.example.data.model.EngineBackendPreference.FORCE_KOTLIN_OKHTTP
                } else {
                    null
                }
            else -> null
        }

        val selection = StoragePlacementHelper.selectBackend(
            settings = settings,
            nativeLoaded = AetherNativeBridge.isNativeLoaded,
            overridePreference = perTaskPref,
            aria2Available = aria2Engine.isAvailable(),
        )

        when (selection) {
            is BackendSelectionResult.Unavailable -> {
                repository.update(
                    entity.copy(
                        status = DownloadStatus.FAILED.name,
                        errorMessage = selection.reason,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            is BackendSelectionResult.Selected -> {
                telemetryLedger.setActiveEngineBadge(selection.backend.badge)
                DiagnosticsCenter.log("DISPATCH", "Starting ${entity.fileName} via ${selection.backend.badge}")
                val tagged = entity.copy(engineBackendUsed = selection.backend.id)
                repository.update(tagged)

                when (selection.backend) {
                    ResolvedEngineBackend.RUST_LIBAETHER_CORE -> executeWithRustNative(tagged, settings)
                    ResolvedEngineBackend.KOTLIN_OKHTTP -> kotlinRunner.run(tagged, settings)
                    ResolvedEngineBackend.ARIA2_NEXT -> aria2Engine.run(tagged, settings)
                }
            }
        }
    }

    private suspend fun executeWithRustNative(entity: DownloadEntity, settings: EngineSettings) {
        // Handles rotate on Wi-Fi/cellular reconnects. Re-push every link right
        // before the Rust engine starts, otherwise workers fall back to
        // `default:auto` and flap between networks mid-download.
        networkMonitor.syncAllToNative()
        if (networkMonitor.state.value.activeValidatedLinks.isEmpty()) {
            DiagnosticsCenter.log(
                "RUST_FALLBACK",
                "No validated native link handles; routing via Kotlin OkHttp instead of unbound default:auto",
            )
            kotlinRunner.run(entity.copy(engineBackendUsed = ResolvedEngineBackend.KOTLIN_OKHTTP.id), settings)
            return
        }

        val id = entity.id
        val urls = entity.allUrls.ifEmpty { listOf(entity.url) }
        val cellCap = when {
            entity.maxCellularBytes > 0L -> entity.maxCellularBytes
            settings.maxCellularBytesPerSession > 0L -> settings.maxCellularBytesPerSession
            else -> -1L
        }

        // Audited MediaStore direct FD (< 1 GiB) vs raw F2FS app storage (>= 1 GiB)
        var mediaStoreTarget: MediaStoreTarget? = null
        val useDirectMediaStore = !entity.isSafDestination &&
            StoragePlacementHelper.shouldUseDirectMediaStoreFd(entity.totalBytes, false)
        val directFd = if (useDirectMediaStore) {
            mediaStoreTarget = StoragePlacementHelper.openPendingMediaStoreDownload(
                context = context,
                fileName = entity.fileName,
                mimeType = entity.mimeType,
            )
            mediaStoreTarget?.detachedFd ?: -1
        } else {
            -1
        }

        val started = AetherNativeBridge.startDownloadSafe(
            taskId = id,
            urls = urls,
            outputPath = entity.destinationUri,
            ledgerPath = ledgerFile(id).absolutePath,
            directFd = directFd,
            maxWorkers = entity.workerCount.coerceIn(1, com.example.data.model.MAX_CONCURRENT_WORKERS_CAP),
            maxCellularBytes = cellCap,
            browserProfile = entity.browserProfile,
            protocolMode = entity.protocolMode,
        )
        if (!started) {
            mediaStoreTarget?.let { target ->
                runCatching { target.pfd.close() }
                StoragePlacementHelper.abortPendingMediaStoreDownload(context, target.uri)
            }
            if (settings.enableKotlinOkHttpEngine) {
                DiagnosticsCenter.log("FALLBACK", "Native dispatch unavailable; falling back to Kotlin OkHttp")
                kotlinRunner.run(entity.copy(engineBackendUsed = ResolvedEngineBackend.KOTLIN_OKHTTP.id), settings)
            } else {
                repository.update(
                    entity.copy(
                        status = DownloadStatus.FAILED.name,
                        errorMessage = "Rust native dispatch failed and Kotlin OkHttp engine is turned OFF.",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            return
        }

        val startMs = System.currentTimeMillis()
        try {
            while (coroutineScopeIsActive()) {
                delay(250L)
                val outcome = AetherNativeBridge.taskOutcomeSafe(id) ?: continue
                val current = repository.getById(id) ?: break
                val total = outcome.totalBytes.takeIf { it > 0L } ?: current.totalBytes

                when (outcome.state) {
                    "COMPLETED" -> {
                        val resolvedFileName = outcome.fileName.ifBlank { current.fileName }
                        val resolvedMimeType = outcome.mimeType.ifBlank { current.mimeType }
                        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        val avgBps = (total.coerceAtLeast(outcome.downloadedBytes) * 1000L) / elapsed
                        val hashOk = entity.expectedSha256.isNullOrBlank() ||
                            entity.expectedSha256.equals(outcome.sha256Hex, ignoreCase = true)

                        if (hashOk) {
                            telemetryLedger.recordEnginePeak(ResolvedEngineBackend.RUST_LIBAETHER_CORE, avgBps)
                            mediaStoreTarget?.let { target ->
                                StoragePlacementHelper.finalizePendingMediaStoreDownload(
                                    context = context,
                                    uri = target.uri,
                                    updatedFileName = resolvedFileName,
                                    updatedMimeType = resolvedMimeType,
                                )
                            }
                            if (mediaStoreTarget == null && !entity.isSafDestination &&
                                StoragePlacementHelper.shouldUseDirectMediaStoreFd(total, false)
                            ) {
                                StoragePlacementHelper.promoteLocalFileToMediaStore(
                                    context = context,
                                    sourceFile = File(entity.destinationUri),
                                    fileName = resolvedFileName,
                                    mimeType = resolvedMimeType,
                                )
                            }
                        } else {
                            mediaStoreTarget?.let { target ->
                                StoragePlacementHelper.abortPendingMediaStoreDownload(context, target.uri)
                            }
                        }

                        repository.update(
                            current.copy(
                                fileName = resolvedFileName,
                                mimeType = resolvedMimeType,
                                totalBytes = total,
                                downloadedBytes = outcome.downloadedBytes,
                                wifiBytes = outcome.wifiBytes,
                                cellBytes = outcome.cellBytes,
                                actualSha256 = outcome.sha256Hex.ifBlank { current.actualSha256 },
                                ttfbMs = outcome.ttfbMs.takeIf { it > 0L } ?: current.ttfbMs,
                                avgSpeedBps = avgBps,
                                peakSpeedBps = maxOf(current.peakSpeedBps, avgBps),
                                activeDownloadMs = current.activeDownloadMs + elapsed,
                                maxConnectionsUsed = maxOf(current.maxConnectionsUsed, current.workerCount),
                                status = if (hashOk) DownloadStatus.COMPLETED.name else DownloadStatus.FAILED.name,
                                errorMessage = if (hashOk) null else "SHA-256 mismatch from Rust verifier",
                                updatedAt = System.currentTimeMillis(),
                                completedAt = if (hashOk) System.currentTimeMillis() else null,
                            )
                        )
                        break
                    }
                    "TOKEN_EXPIRED" -> {
                        repository.update(
                            current.copy(
                                status = DownloadStatus.TOKEN_EXPIRED_R2.name,
                                downloadedBytes = outcome.downloadedBytes,
                                wifiBytes = outcome.wifiBytes,
                                cellBytes = outcome.cellBytes,
                                errorMessage = outcome.errorMessage.ifBlank { "HTTP 403/410 expired URL" },
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        break
                    }
                    "ENOSPC" -> {
                        mediaStoreTarget?.let { target ->
                            StoragePlacementHelper.abortPendingMediaStoreDownload(context, target.uri)
                        }
                        repository.update(
                            current.copy(
                                status = DownloadStatus.ENOSPC_R4.name,
                                errorMessage = outcome.errorMessage.ifBlank { "Insufficient storage (ENOSPC 64 MiB reserve)" },
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        break
                    }
                    "PAUSED" -> {
                        repository.update(
                            current.copy(
                                status = DownloadStatus.PAUSED.name,
                                downloadedBytes = outcome.downloadedBytes,
                                wifiBytes = outcome.wifiBytes,
                                cellBytes = outcome.cellBytes,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        break
                    }
                    "CANCELLED" -> {
                        mediaStoreTarget?.let { target ->
                            StoragePlacementHelper.abortPendingMediaStoreDownload(context, target.uri)
                        }
                        repository.update(
                            current.copy(
                                status = DownloadStatus.CANCELLED.name,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        break
                    }
                    "FAILED" -> {
                        mediaStoreTarget?.let { target ->
                            StoragePlacementHelper.abortPendingMediaStoreDownload(context, target.uri)
                        }
                        repository.update(
                            current.copy(
                                status = DownloadStatus.FAILED.name,
                                downloadedBytes = outcome.downloadedBytes,
                                errorMessage = outcome.errorMessage.ifBlank { "Rust engine failure" },
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        break
                    }
                    else -> {
                        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        val liveAvg = (outcome.downloadedBytes * 1000L) / elapsed
                        val snap = telemetryLedger.latestSnapshot()
                        repository.update(
                            current.copy(
                                fileName = outcome.fileName.ifBlank { current.fileName },
                                totalBytes = total,
                                downloadedBytes = outcome.downloadedBytes,
                                wifiBytes = outcome.wifiBytes,
                                cellBytes = outcome.cellBytes,
                                ttfbMs = outcome.ttfbMs.takeIf { it > 0L } ?: current.ttfbMs,
                                avgSpeedBps = liveAvg,
                                peakSpeedBps = maxOf(current.peakSpeedBps, snap.instantBps),
                                activeDownloadMs = elapsed,
                                maxConnectionsUsed = maxOf(current.maxConnectionsUsed, snap.activeWorkers),
                                status = DownloadStatus.STREAMING.name,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
            }
        } catch (ce: CancellationException) {
            AetherNativeBridge.pauseDownloadSafe(id)
            throw ce
        }
    }

    private suspend fun coroutineScopeIsActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive

    private fun ledgerFile(id: String): File =
        File(File(context.filesDir, "ledgers"), "$id.aethldg")

    private fun stagingFile(id: String): File =
        File(File(context.filesDir, "staging"), "$id.part")

    private fun ensureForegroundServiceRunning() {
        val intent = Intent(context, AetherDownloadService::class.java).apply {
            action = AetherDownloadService.ACTION_START
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            DiagnosticsCenter.log("FGS_WARN", "startForegroundService failed (${it.javaClass.simpleName}); handing off to WorkManager")
            AetherDownloadWorker.enqueueHandoff(context)
        }
    }

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
}
