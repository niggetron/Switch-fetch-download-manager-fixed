package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AetherApplication
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineSettings
import com.example.data.model.FileCategory
import com.example.data.model.NewDownloadRequest
import com.example.data.model.TransferFilter
import com.example.data.repository.StorageStats
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.util.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AetherViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AetherApplication
    private val repository = app.downloadRepository
    private val settingsRepo = app.settingsRepository
    private val engine = app.downloadEngine
    private val telemetryLedger = app.telemetryLedger
    private val networkMonitor = app.networkMonitor

    private val _transferFilter = MutableStateFlow(TransferFilter.ALL)
    val transferFilter: StateFlow<TransferFilter> = _transferFilter.asStateFlow()

    private val _fileCategory = MutableStateFlow(FileCategory.ALL)
    val fileCategory: StateFlow<FileCategory> = _fileCategory.asStateFlow()

    private val _fileSearchQuery = MutableStateFlow("")
    val fileSearchQuery: StateFlow<String> = _fileSearchQuery.asStateFlow()

    private val _telemetry = MutableStateFlow(telemetryLedger.sampleSnapshot(false))
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    val downloads: StateFlow<List<DownloadEntity>> = repository.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredDownloads: StateFlow<List<DownloadEntity>> =
        combine(downloads, _transferFilter) { list, filter ->
            when (filter) {
                TransferFilter.ALL -> list
                TransferFilter.ACTIVE -> list.filter { it.statusEnum.isActive }
                TransferFilter.PAUSED -> list.filter { it.statusEnum == DownloadStatus.PAUSED }
                TransferFilter.COMPLETED -> list.filter { it.statusEnum == DownloadStatus.COMPLETED }
                TransferFilter.ISSUES -> list.filter { it.statusEnum.isIssue }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedFiles: StateFlow<List<DownloadEntity>> =
        combine(
            repository.completedDownloads,
            _fileCategory,
            _fileSearchQuery,
        ) { list, cat, query ->
            val q = query.trim().lowercase()
            list.filter { entity ->
                val matchesQuery = q.isBlank() ||
                    entity.fileName.lowercase().contains(q) ||
                    entity.mimeType.lowercase().contains(q)
                val matchesCat = when (cat) {
                    FileCategory.ALL -> true
                    FileCategory.DOCUMENTS -> entity.mimeType.startsWith("text/") ||
                        entity.mimeType.contains("pdf") ||
                        entity.fileName.endsWith(".txt", true) ||
                        entity.fileName.endsWith(".md", true) ||
                        entity.fileName.endsWith(".json", true)
                    FileCategory.ARCHIVES -> entity.fileName.endsWith(".zip", true) ||
                        entity.fileName.endsWith(".tar", true) ||
                        entity.fileName.endsWith(".gz", true) ||
                        entity.fileName.endsWith(".7z", true) ||
                        entity.fileName.endsWith(".xz", true)
                    FileCategory.MEDIA -> entity.mimeType.startsWith("image/") ||
                        entity.mimeType.startsWith("video/") ||
                        entity.mimeType.startsWith("audio/")
                    FileCategory.PACKAGES -> entity.fileName.endsWith(".bin", true) ||
                        entity.fileName.endsWith(".iso", true) ||
                        entity.fileName.endsWith(".apk", true) ||
                        entity.mimeType == "application/octet-stream"
                }
                matchesQuery && matchesCat
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val storageStats: StateFlow<StorageStats> = repository.completedDownloads
        .map { completed -> repository.queryStorageStats(completed) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            repository.queryStorageStats(emptyList()),
        )

    val settings: StateFlow<EngineSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EngineSettings())

    val networkMatrix: StateFlow<NetworkMatrixState> = networkMonitor.state

    val casChunkCount: StateFlow<Int> = repository.casChunkCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val casTotalBytes: StateFlow<Long> = repository.casTotalBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private var telemetryPollJob: Job? = null

    /**
     * Lifecycle-aware telemetry sampling: 4 Hz (250ms) when UI is active, 0 Hz on ON_STOP.
     */
    fun setUiForeground(active: Boolean) {
        if (active) {
            if (telemetryPollJob?.isActive == true) return
            telemetryPollJob = viewModelScope.launch(Dispatchers.Default) {
                while (isActive) {
                    val hasActive = downloads.value.any { it.statusEnum.isActive }
                    _telemetry.value = telemetryLedger.sampleSnapshot(hasActive)
                    delay(250L)
                }
            }
        } else {
            telemetryPollJob?.cancel()
            telemetryPollJob = null
        }
    }

    fun setTransferFilter(filter: TransferFilter) {
        _transferFilter.value = filter
    }

    fun setFileCategory(category: FileCategory) {
        _fileCategory.value = category
    }

    fun setFileSearchQuery(query: String) {
        _fileSearchQuery.value = query
    }

    fun enqueueDownload(request: NewDownloadRequest) {
        val trimmed = request.url.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            _snackbarEvents.tryEmit("Enter a valid http:// or https:// URL")
            return
        }
        viewModelScope.launch {
            if (!request.safUriString.isNullOrBlank()) {
                runCatching {
                    val uri = Uri.parse(request.safUriString)
                    app.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            engine.enqueue(request)
            _snackbarEvents.tryEmit("Pipeline queued · 2 MB extent ledger armed")
        }
    }

    fun pauseDownload(id: String) {
        viewModelScope.launch { engine.pause(id) }
    }

    fun resumeDownload(id: String) {
        engine.launchDownload(id)
    }

    fun pauseAll() {
        viewModelScope.launch {
            engine.pauseAll()
            _snackbarEvents.tryEmit("All active pipelines paused")
        }
    }

    fun resumeAll() {
        viewModelScope.launch {
            engine.resumeAll()
            _snackbarEvents.tryEmit("Resuming checkpointed transfers")
        }
    }

    fun cancelDownload(id: String) {
        viewModelScope.launch { engine.cancel(id) }
    }

    fun deleteDownload(id: String, deleteFile: Boolean) {
        viewModelScope.launch {
            engine.cancel(id)
            repository.deleteDownload(id, deleteFile)
            _snackbarEvents.tryEmit(if (deleteFile) "Transfer & file deleted" else "Removed from list")
        }
    }

    fun updateExpiredUrl(id: String, newUrl: String) {
        val clean = newUrl.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
            _snackbarEvents.tryEmit("Invalid URL format")
            return
        }
        viewModelScope.launch {
            engine.updateExpiredUrlAndResume(id, clean)
            _snackbarEvents.tryEmit("Signed URL refreshed · Resuming from bitfield")
        }
    }

    fun regrantSafUri(id: String, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            engine.regrantSafUriAndResume(id, uri.toString())
            _snackbarEvents.tryEmit("SAF descriptor re-granted · Resuming")
        }
    }

    fun openFile(entity: DownloadEntity) {
        val ok = FileOpener.openFile(app, entity)
        if (!ok) {
            _snackbarEvents.tryEmit("No app found or file missing on storage")
        }
    }

    fun shareFile(entity: DownloadEntity) {
        val ok = FileOpener.shareFile(app, entity)
        if (!ok) {
            _snackbarEvents.tryEmit("Unable to share file")
        }
    }

    fun pruneCasCache() {
        viewModelScope.launch {
            repository.pruneCasCache()
            _snackbarEvents.tryEmit("CAS digest index pruned")
        }
    }

    fun refreshNetworkMatrix() {
        networkMonitor.refreshState()
    }

    fun updateSettings(transform: (EngineSettings) -> EngineSettings) {
        viewModelScope.launch {
            settingsRepo.update(transform)
        }
    }
}
