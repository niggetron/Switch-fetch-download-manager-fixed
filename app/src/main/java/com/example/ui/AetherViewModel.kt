package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AetherApplication
import com.example.data.local.DownloadEntity
import com.example.data.model.ActiveConnectionInfo
import com.example.data.model.BreakerState
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineSettings
import com.example.data.model.FileCategory
import com.example.data.model.LinkKey
import com.example.data.model.NewDownloadRequest
import com.example.data.model.SpeedometerStyle
import com.example.data.model.TransferFilter
import com.example.data.model.WifiBandPreference
import com.example.data.repository.StorageStats
import com.example.engine.AetherNativeBridge
import com.example.engine.DiagnosticsCenter
import com.example.engine.DualWifiCapabilityReport
import com.example.engine.LinkEndpointInfo
import com.example.engine.NearbyWifiNetwork
import com.example.engine.NetworkMatrixState
import com.example.engine.ShizukuWifiState
import com.example.engine.TelemetrySnapshot
import com.example.ui.screens.AnimationSimulationState
import com.example.ui.screens.SpeedSimulationPreset
import com.example.ui.theme.UiThemeConfig
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
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AetherViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AetherApplication
    private val repository = app.downloadRepository
    private val settingsRepo = app.settingsRepository
    private val engine = app.downloadEngine
    private val telemetryLedger = app.telemetryLedger
    private val networkMonitor = app.networkMonitor
    private val dualWifiConnector = app.dualWifiConnector

    private val _transferFilter = MutableStateFlow(TransferFilter.ALL)
    val transferFilter: StateFlow<TransferFilter> = _transferFilter.asStateFlow()

    private val _fileCategory = MutableStateFlow(FileCategory.ALL)
    val fileCategory: StateFlow<FileCategory> = _fileCategory.asStateFlow()

    private val _fileSearchQuery = MutableStateFlow("")
    val fileSearchQuery: StateFlow<String> = _fileSearchQuery.asStateFlow()

    // Do not sample on construction: that used to load the native library and
    // initialize the Rust engine before the first frame was drawn.
    private val _realTelemetry = MutableStateFlow(TelemetrySnapshot())
    private val _simulatedTelemetry = MutableStateFlow<TelemetrySnapshot?>(null)
    private val _simulatedMatrix = MutableStateFlow<NetworkMatrixState?>(null)

    private val _simulationState = MutableStateFlow(AnimationSimulationState())
    val simulationState: StateFlow<AnimationSimulationState> = _simulationState.asStateFlow()

    val telemetry: StateFlow<TelemetrySnapshot> =
        combine(_realTelemetry, _simulatedTelemetry) { real, sim ->
            sim ?: real
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _realTelemetry.value)

    private val _diagnosticsPreview = MutableStateFlow("")
    val diagnosticsPreview: StateFlow<String> = _diagnosticsPreview.asStateFlow()

    private val _aria2Secret = MutableStateFlow(app.aria2RpcSecret)
    val aria2Secret: StateFlow<String> = _aria2Secret.asStateFlow()

    private val _useMBpsSpeedUnit = MutableStateFlow(false)
    val useMBpsSpeedUnit: StateFlow<Boolean> = _useMBpsSpeedUnit.asStateFlow()

    val dualWifiCapabilities: StateFlow<DualWifiCapabilityReport> = dualWifiConnector.capabilities
    val nearbyWifiNetworks: StateFlow<List<NearbyWifiNetwork>> = dualWifiConnector.nearbyNetworks
    val connectedWifi1Ssid: StateFlow<String> = dualWifiConnector.connectedWifi1Ssid
    val isScanningWifi: StateFlow<Boolean> = dualWifiConnector.isScanning
    val isConnectingWifi2: StateFlow<Boolean> = dualWifiConnector.isConnecting
    val shizukuState: StateFlow<ShizukuWifiState> = dualWifiConnector.shizukuHelper.state

    private val _flightSampleCount = MutableStateFlow(DiagnosticsCenter.flightSampleCount())
    val flightSampleCount: StateFlow<Int> = _flightSampleCount.asStateFlow()

    val fontLicenseAudit: String by lazy {
        DiagnosticsCenter.verifyBundledFontLicenses(app)
    }

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
                        entity.mimeType.startsWith("audio/") ||
                        entity.fileName.endsWith(".m3u8", true) ||
                        entity.fileName.endsWith(".mpd", true)
                    FileCategory.PACKAGES -> entity.fileName.endsWith(".bin", true) ||
                        entity.fileName.endsWith(".iso", true) ||
                        entity.fileName.endsWith(".apk", true) ||
                        entity.mimeType == "application/octet-stream"
                }
                matchesQuery && matchesCat
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val storageStats: StateFlow<StorageStats> = repository.completedDownloads
        // M1: queryStorageStats does StatFs/mkdirs (blocking FUSE I/O) — stateIn
        // collects on Main, so force the work onto IO.
        .map { completed -> kotlinx.coroutines.withContext(Dispatchers.IO) { repository.queryStorageStats(completed) } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            StorageStats(0L, 0L, 0, 0L),
        )

    val settings: StateFlow<EngineSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EngineSettings())

    val networkMatrix: StateFlow<NetworkMatrixState> =
        combine(networkMonitor.state, _simulatedMatrix) { real, sim ->
            sim ?: real
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), networkMonitor.state.value)

    val casChunkCount: StateFlow<Int> = repository.casChunkCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val casTotalBytes: StateFlow<Long> = repository.casTotalBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private var telemetryPollJob: Job? = null
    private var simulationJob: Job? = null
    private var autoCycleLayoutsJob: Job? = null
    private var burstClearJob: Job? = null

    /**
     * Lifecycle-aware telemetry sampling: 4 Hz (250ms) when UI is active, 0 Hz on ON_STOP.
     */
    fun setUiForeground(active: Boolean) {
        if (active) {
            if (telemetryPollJob?.isActive == true) return
            // M1: refresh the cached aria2 status off Main so composition never
            // re-enters the engine's synchronized monitor (which can hold a 5s RPC).
            refreshAria2Status()
            // Re-arm the Sim Lab jobs that ON_STOP cancelled, so backgrounding
            // does not desync the simulation pill / auto-cycle state on return.
            if (_simulationState.value.isRunning && simulationJob?.isActive != true) {
                startSpeedSimulation(_simulationState.value.preset)
            }
            if (_simulationState.value.autoCycleSpeedometerLayouts && autoCycleLayoutsJob?.isActive != true) {
                setAutoCycleSpeedometerLayouts(true)
            }
            telemetryPollJob = viewModelScope.launch(Dispatchers.Default) {
                var ticks = 0
                while (isActive) {
                    val hasActive = downloads.value.any { it.statusEnum.isActive }
                    val snap = telemetryLedger.sampleSnapshot(hasActive)
                    _realTelemetry.value = snap
                    DiagnosticsCenter.recordFlightSample(snap)
                    _flightSampleCount.value = DiagnosticsCenter.flightSampleCount()
                    if (ticks % 4 == 0) {
                        _diagnosticsPreview.value = DiagnosticsCenter.exportReport(
                            context = app,
                            backendLabel = AetherNativeBridge.engineBackendLabel,
                            telemetry = snap,
                            networkState = networkMonitor.state.value,
                            shizukuState = dualWifiConnector.shizukuHelper.state.value,
                        )
                    }
                    ticks++
                    delay(250L)
                }
            }
        } else {
            // M3: only telemetryPollJob was cancelled. simulationJob (10 Hz),
            // autoCycleLayoutsJob (DataStore write + binder + JNI on Main) and
            // burstClearJob kept running while backgrounded, adding to the IO
            // pressure and starving Main when the user returned and tapped.
            telemetryPollJob?.cancel(); telemetryPollJob = null
            simulationJob?.cancel(); simulationJob = null
            autoCycleLayoutsJob?.cancel(); autoCycleLayoutsJob = null
            burstClearJob?.cancel(); burstClearJob = null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Speed & Animation Simulation Lab (completely isolated from real metrics)
    // ──────────────────────────────────────────────────────────────────────────

    fun startSpeedSimulation(preset: SpeedSimulationPreset) {
        _simulationState.value = _simulationState.value.copy(
            isRunning = true,
            preset = preset,
        )
        if (simulationJob?.isActive == true) return
        simulationJob = viewModelScope.launch(Dispatchers.Default) {
            val totalHist = ArrayDeque<Long>(48)
            val wifi1Hist = ArrayDeque<Long>(48)
            val wifi2Hist = ArrayDeque<Long>(48)
            val cellHist = ArrayDeque<Long>(48)
            val ethHist = ArrayDeque<Long>(48)

            var t = 0.0
            var peakTotal = 0L
            var peakW1 = 0L
            var peakW2 = 0L
            var peakCell = 0L
            var peakEth = 0L
            var sumTotal = 0L
            var sumW1 = 0L
            var sumW2 = 0L
            var sumCell = 0L
            var sumEth = 0L
            var samples = 0L
            var bytesW1 = 0L
            var bytesW2 = 0L
            var bytesCell = 0L
            var bytesEth = 0L
            var rescues = 0L
            var rustPeak = 0L
            var kotlinPeak = 0L

            val cm = runCatching {
                app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            }.getOrNull()

            while (isActive && _simulationState.value.isRunning) {
                val st = _simulationState.value
                val (rawW1Mbps, rawCellMbps, rawW2Mbps, rawUsbMbps) = when (st.preset) {
                    SpeedSimulationPreset.QUAD_GIGABIT_REDLINE -> {
                        val w1 = 310.0 + 135.0 * sin(t * 0.95) + 45.0 * cos(t * 2.1)
                        val cell = 360.0 + 145.0 * sin(t * 0.72 + 1.1) + 55.0 * sin(t * 1.9)
                        val w2 = 225.0 + 95.0 * cos(t * 0.85 + 0.5) + 30.0 * sin(t * 2.4)
                        val usb = 210.0 + 120.0 * sin(t * 0.62 + 2.2) + 35.0 * cos(t * 1.7)
                        QuadMbps(w1, cell, w2, usb)
                    }
                    SpeedSimulationPreset.ANTI_DIP_RESCUE -> {
                        val cycle = t % 7.0
                        val inDip = cycle in 1.8..3.1
                        val inRescue = cycle in 3.1..4.8
                        val w1 = when {
                            inDip -> 38.0 + 14.0 * sin(t * 4.0)
                            inRescue -> 395.0 + 55.0 * sin(t * 2.3)
                            else -> 335.0 + 35.0 * sin(t * 1.1)
                        }
                        val cell = when {
                            inDip -> 440.0 + 45.0 * cos(t * 2.0) // 5G absorbs dip via live bisection
                            inRescue -> 375.0 + 35.0 * sin(t * 1.5)
                            else -> 285.0 + 40.0 * sin(t * 0.9)
                        }
                        val w2 = if (inDip || inRescue) 265.0 + 30.0 * sin(t * 1.6) else 190.0 + 25.0 * cos(t * 1.2)
                        val usb = if (inDip) 240.0 + 25.0 * sin(t * 1.8) else 175.0 + 20.0 * sin(t * 0.8)
                        if (inRescue && (samples % 6L == 0L)) rescues++
                        QuadMbps(w1, cell, w2, usb)
                    }
                    SpeedSimulationPreset.DUAL_LINK_RACE -> {
                        val w1 = 240.0 + 185.0 * sin(t * 0.85)
                        val cell = 240.0 + 185.0 * cos(t * 0.85)
                        val w2 = 120.0 + 70.0 * abs(sin(t * 0.55))
                        val usb = 95.0 + 60.0 * abs(cos(t * 0.48))
                        QuadMbps(w1, cell, w2, usb)
                    }
                    SpeedSimulationPreset.CUSTOM_SLIDERS -> {
                        val jitter = 1.0 + 0.03 * sin(t * 3.1)
                        QuadMbps(
                            st.customWifi1Mbps * jitter,
                            st.customCellMbps * (1.0 + 0.03 * cos(t * 2.7)),
                            st.customWifi2Mbps * jitter,
                            st.customUsbMbps * (1.0 + 0.025 * sin(t * 2.2)),
                        )
                    }
                }

                // Apply optional single-link Redline Burst boost from FocusedGaugeOverlay
                val burstId = st.focusedBurstLinkId
                val w1Mbps = (rawW1Mbps + if (burstId == "wifi1") 340.0 else 0.0).coerceAtLeast(0.0)
                val cellMbps = (rawCellMbps + if (burstId == "cell") 360.0 else 0.0).coerceAtLeast(0.0)
                val w2Mbps = (rawW2Mbps + if (burstId == "wifi2") 290.0 else 0.0).coerceAtLeast(0.0)
                val usbMbps = (rawUsbMbps + if (burstId == "usb") 320.0 else 0.0).coerceAtLeast(0.0)

                val w1Bps = ((w1Mbps * 1_000_000.0) / 8.0).toLong()
                val cellBps = ((cellMbps * 1_000_000.0) / 8.0).toLong()
                val w2Bps = ((w2Mbps * 1_000_000.0) / 8.0).toLong()
                val usbBps = ((usbMbps * 1_000_000.0) / 8.0).toLong()
                val totalBps = w1Bps + cellBps + w2Bps + usbBps

                peakW1 = maxOf(peakW1, w1Bps)
                peakW2 = maxOf(peakW2, w2Bps)
                peakCell = maxOf(peakCell, cellBps)
                peakEth = maxOf(peakEth, usbBps)
                peakTotal = maxOf(peakTotal, totalBps)

                samples++
                sumTotal += totalBps
                sumW1 += w1Bps
                sumW2 += w2Bps
                sumCell += cellBps
                sumEth += usbBps

                // 100ms tick -> 0.1s worth of bytes
                bytesW1 += w1Bps / 10L
                bytesW2 += w2Bps / 10L
                bytesCell += cellBps / 10L
                bytesEth += usbBps / 10L

                rustPeak = maxOf(rustPeak, (totalBps * 1.08).toLong())
                kotlinPeak = maxOf(kotlinPeak, (totalBps * 0.94).toLong())

                pushRing(totalHist, totalBps)
                pushRing(wifi1Hist, w1Bps)
                pushRing(wifi2Hist, w2Bps)
                pushRing(cellHist, cellBps)
                pushRing(ethHist, usbBps)

                val simConnections = listOf(
                    ActiveConnectionInfo(
                        connId = "sim-w1",
                        taskId = "sim-pipeline",
                        url = "https://cdn-edge-1.aether.sim/linux-6.12-arm64.tar.zst",
                        linkKeyId = "wifi:wlan0",
                        protocol = "H3 QUIC 0-RTT",
                        speedBps = w1Bps,
                        bytesReceived = bytesW1,
                        rangeStart = 0L,
                        rangeEnd = 256L * 1024L * 1024L,
                        ttfbMs = 14L,
                        socketState = "SIM-STREAM",
                    ),
                    ActiveConnectionInfo(
                        connId = "sim-cell",
                        taskId = "sim-pipeline",
                        url = "https://cdn-edge-2.aether.sim/linux-6.12-arm64.tar.zst",
                        linkKeyId = "cell:rmnet0",
                        protocol = "wreq H2 ALPS",
                        speedBps = cellBps,
                        bytesReceived = bytesCell,
                        rangeStart = 256L * 1024L * 1024L,
                        rangeEnd = 512L * 1024L * 1024L,
                        ttfbMs = 24L,
                        socketState = "SIM-STREAM",
                    ),
                    ActiveConnectionInfo(
                        connId = "sim-w2",
                        taskId = "sim-pipeline",
                        url = "https://cdn-edge-3.aether.sim/linux-6.12-arm64.tar.zst",
                        linkKeyId = "wifi2:wlan1",
                        protocol = "Dual-STA Relay",
                        speedBps = w2Bps,
                        bytesReceived = bytesW2,
                        rangeStart = 512L * 1024L * 1024L,
                        rangeEnd = 768L * 1024L * 1024L,
                        ttfbMs = 19L,
                        socketState = "SIM-STREAM",
                    ),
                    ActiveConnectionInfo(
                        connId = "sim-usb",
                        taskId = "sim-pipeline",
                        url = "https://cdn-edge-4.aether.sim/linux-6.12-arm64.tar.zst",
                        linkKeyId = "eth:usb0",
                        protocol = "USB-C Gigabit",
                        speedBps = usbBps,
                        bytesReceived = bytesEth,
                        rangeStart = 768L * 1024L * 1024L,
                        rangeEnd = 1024L * 1024L * 1024L,
                        ttfbMs = 9L,
                        socketState = "SIM-STREAM",
                    ),
                )

                val stability = if (peakTotal > 0L) {
                    ((totalBps.toDouble() / peakTotal.toDouble()) * 100.0).toInt().coerceIn(72, 99)
                } else {
                    98
                }

                val simLiveSegments = if (st.simulatedTransferHlsMode) {
                    val base = (samples / 2L).toInt() + 100
                    listOf(
                        "seg_%04d.m4s".format(base),
                        "seg_%04d.m4s".format(base + 1),
                        "seg_%04d.m4s".format(base + 2),
                        "seg_%04d.m4s".format(base + 3),
                    )
                } else {
                    emptyList()
                }
                val simW1Workers = if (w1Bps > 0L) 16 else 0
                val simW2Workers = if (w2Bps > 0L) 16 else 0
                val simCellWorkers = if (cellBps > 0L) 16 else 0
                val simUsbWorkers = if (usbBps > 0L) 16 else 0

                _simulatedTelemetry.value = TelemetrySnapshot(
                    instantBps = totalBps,
                    peakBps = peakTotal,
                    avgBps = sumTotal / samples,
                    wifiBps = w1Bps,
                    wifi2Bps = w2Bps,
                    cellBps = cellBps,
                    ethBps = usbBps,
                    wifiPeakBps = peakW1,
                    wifi2PeakBps = peakW2,
                    cellPeakBps = peakCell,
                    ethPeakBps = peakEth,
                    wifiAvgBps = sumW1 / samples,
                    wifi2AvgBps = sumW2 / samples,
                    cellAvgBps = sumCell / samples,
                    ethAvgBps = sumEth / samples,
                    rustPeakBps = rustPeak,
                    kotlinPeakBps = kotlinPeak,
                    wifiBytesTotal = bytesW1,
                    wifi2BytesTotal = bytesW2,
                    cellBytesTotal = bytesCell,
                    ethBytesTotal = bytesEth,
                    speedHistoryBps = totalHist.toList(),
                    wifiHistoryBps = wifi1Hist.toList(),
                    wifi2HistoryBps = wifi2Hist.toList(),
                    cellHistoryBps = cellHist.toList(),
                    ethHistoryBps = ethHist.toList(),
                    activeWorkers = simW1Workers + simW2Workers + simCellWorkers + simUsbWorkers,
                    wifiWorkers = simW1Workers,
                    wifi2Workers = simW2Workers,
                    cellularWorkers = simCellWorkers,
                    ethWorkers = simUsbWorkers,
                    antiDipRescues = rescues,
                    stabilityPercent = stability,
                    activeConnections = simConnections,
                    slabAllocatedMb = 10.4f,
                    slabInUseMb = (6.2f + 2.4f * abs(sin(t)).toFloat()),
                    jvmHeapUsedMb = 28.5f,
                    nativeHeapMb = 14.2f,
                    totalSyscallWriteBatches = samples * 12L,
                    lastTtfbMs = 14L,
                    thermalStatusLabel = "Nominal (Sim)",
                    thermalHeadroomClear = true,
                    pollingHzLabel = "10 Hz Sim",
                    isNativeRustActive = _realTelemetry.value.isNativeRustActive,
                    isMemfdMapped = false,
                    activeEngineBadge = "SIMULATOR",
                    recentLiveSegments = simLiveSegments,
                )

                val realMatrix = networkMonitor.state.value
                val activeNet = cm?.activeNetwork
                _simulatedMatrix.value = if (activeNet != null) {
                    realMatrix.copy(
                        wifiLink = LinkEndpointInfo(
                            linkKey = LinkKey.Wifi("wlan0"),
                            network = activeNet,
                            label = "Wi-Fi 1 (Sim)",
                            interfaceName = "wlan0",
                            localIp = "192.168.1.24",
                            netHandle = 1001L,
                            downstreamKbps = 600_000,
                            upstreamKbps = 150_000,
                            isMetered = false,
                            isValidated = true,
                            breakerState = BreakerState.CLOSED,
                            ewmaMbps = w1Mbps,
                            bandLabel = "5 GHz",
                        ),
                        wifi2Link = LinkEndpointInfo(
                            linkKey = LinkKey.WifiSecondary("wlan1"),
                            network = activeNet,
                            label = "Wi-Fi 2 (Sim)",
                            interfaceName = "wlan1",
                            localIp = "192.168.2.42",
                            netHandle = 1002L,
                            downstreamKbps = 450_000,
                            upstreamKbps = 100_000,
                            isMetered = false,
                            isValidated = true,
                            breakerState = BreakerState.CLOSED,
                            ewmaMbps = w2Mbps,
                            bandLabel = "6 GHz",
                        ),
                        cellularLink = LinkEndpointInfo(
                            linkKey = LinkKey.Cellular("rmnet0"),
                            network = activeNet,
                            label = "Mobile 5G (Sim)",
                            interfaceName = "rmnet0",
                            localIp = "10.42.8.15",
                            netHandle = 1003L,
                            downstreamKbps = 650_000,
                            upstreamKbps = 120_000,
                            isMetered = true,
                            isValidated = true,
                            breakerState = BreakerState.CLOSED,
                            ewmaMbps = cellMbps,
                        ),
                        ethernetLink = LinkEndpointInfo(
                            linkKey = LinkKey.Ethernet("usb0"),
                            network = activeNet,
                            label = "USB Tether (Sim)",
                            interfaceName = "usb0",
                            localIp = "192.168.42.129",
                            netHandle = 1004L,
                            downstreamKbps = 500_000,
                            upstreamKbps = 250_000,
                            isMetered = false,
                            isValidated = true,
                            breakerState = BreakerState.CLOSED,
                            ewmaMbps = usbMbps,
                        ),
                        hasAnyInternet = true,
                        cellularLeaseRequested = true,
                    )
                } else {
                    realMatrix.copy(hasAnyInternet = true)
                }

                val stepScale = st.motionSpeedMultiplier.coerceIn(0.25f, 1.5f)
                t += 0.14 * stepScale
                delay(100L)
            }
        }
    }

    fun stopSpeedSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        burstClearJob?.cancel()
        burstClearJob = null
        _simulationState.value = _simulationState.value.copy(
            isRunning = false,
            focusedBurstLinkId = null,
        )
        _simulatedTelemetry.value = null
        _simulatedMatrix.value = null
        _snackbarEvents.tryEmit("Speed simulation stopped · Showing live device telemetry")
    }

    fun updateCustomSimSpeeds(wifi1: Float, cell: Float, wifi2: Float, usb: Float) {
        _simulationState.value = _simulationState.value.copy(
            preset = SpeedSimulationPreset.CUSTOM_SLIDERS,
            customWifi1Mbps = wifi1,
            customCellMbps = cell,
            customWifi2Mbps = wifi2,
            customUsbMbps = usb,
        )
        if (!_simulationState.value.isRunning) {
            startSpeedSimulation(SpeedSimulationPreset.CUSTOM_SLIDERS)
        }
    }

    fun setMotionSpeedMultiplier(multiplier: Float) {
        val clamped = multiplier.coerceIn(0.25f, 1.5f)
        _simulationState.value = _simulationState.value.copy(motionSpeedMultiplier = clamped)
    }

    fun setAutoCycleSpeedometerLayouts(enabled: Boolean) {
        _simulationState.value = _simulationState.value.copy(autoCycleSpeedometerLayouts = enabled)
        autoCycleLayoutsJob?.cancel()
        autoCycleLayoutsJob = null
        if (enabled) {
            autoCycleLayoutsJob = viewModelScope.launch {
                while (isActive && _simulationState.value.autoCycleSpeedometerLayouts) {
                    val mult = _simulationState.value.motionSpeedMultiplier.coerceIn(0.25f, 1.5f)
                    delay((1800f / mult).toLong().coerceIn(900L, 6000L))
                    val all = SpeedometerStyle.entries
                    val current = settings.value.speedometerStyle
                    val next = all[(all.indexOf(current) + 1) % all.size]
                    updateSettings { it.copy(speedometerStyle = next) }
                }
            }
        }
    }

    fun pulseLinkBurst(gaugeId: String) {
        _simulationState.value = _simulationState.value.copy(focusedBurstLinkId = gaugeId)
        if (!_simulationState.value.isRunning) {
            startSpeedSimulation(_simulationState.value.preset)
        }
        burstClearJob?.cancel()
        burstClearJob = viewModelScope.launch {
            delay(2400L)
            _simulationState.value = _simulationState.value.copy(focusedBurstLinkId = null)
        }
    }

    fun setSimulatedTransferHlsMode(enabled: Boolean) {
        _simulationState.value = _simulationState.value.copy(simulatedTransferHlsMode = enabled)
    }

    private fun pushRing(q: ArrayDeque<Long>, v: Long) {
        if (q.size >= 48) q.removeFirst()
        q.addLast(v)
    }

    private data class QuadMbps(
        val w1: Double,
        val cell: Double,
        val w2: Double,
        val usb: Double,
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Real Engine, Downloads, Network, Diagnostics & Settings Controls
    // ──────────────────────────────────────────────────────────────────────────

    fun copyDiagnosticsToClipboard() {
        // M1: exportReport does asset reads + a full native log dump (JNI) — a
        // Main click handler blocked here for seconds. Build on IO, post to Main.
        viewModelScope.launch(Dispatchers.IO) {
            val report = DiagnosticsCenter.exportReport(
                context = app,
                backendLabel = AetherNativeBridge.engineBackendLabel,
                telemetry = _realTelemetry.value,
                networkState = networkMonitor.state.value,
                shizukuState = dualWifiConnector.shizukuHelper.state.value,
            )
            _diagnosticsPreview.value = report
            withContext(Dispatchers.Main) {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AetherIO Diagnostics", report))
                _snackbarEvents.tryEmit("Redacted diagnostics report copied to clipboard")
            }
        }
    }

    fun saveDiagnosticsTxtToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val report = DiagnosticsCenter.exportReport(
                context = app,
                backendLabel = AetherNativeBridge.engineBackendLabel,
                telemetry = _realTelemetry.value,
                networkState = networkMonitor.state.value,
                shizukuState = dualWifiConnector.shizukuHelper.state.value,
            )
            val ok = runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(report.toByteArray(Charsets.UTF_8))
                }
                true
            }.getOrDefault(false)
            _snackbarEvents.tryEmit(
                if (ok) "Saved human-readable TXT diagnostic report" else "Failed to write TXT report",
            )
        }
    }

    fun saveDiagnosticsJsonToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = DiagnosticsCenter.exportStructuredJsonTrace(
                backendLabel = AetherNativeBridge.engineBackendLabel,
                telemetry = _realTelemetry.value,
                networkState = networkMonitor.state.value,
                shizukuState = dualWifiConnector.shizukuHelper.state.value,
            )
            val ok = runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                true
            }.getOrDefault(false)
            _snackbarEvents.tryEmit(
                if (ok) "Saved structured JSON flight trace" else "Failed to write JSON trace",
            )
        }
    }

    fun shareDiagnosticsBundle() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val bundle = DiagnosticsCenter.writeShareableBundle(
                    context = app,
                    backendLabel = AetherNativeBridge.engineBackendLabel,
                    telemetry = _realTelemetry.value,
                    networkState = networkMonitor.state.value,
                    shizukuState = dualWifiConnector.shizukuHelper.state.value,
                )
                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        arrayListOf(bundle.txtUri, bundle.jsonUri),
                    )
                    putExtra(Intent.EXTRA_SUBJECT, "AetherIO Omega v9.1 Diagnostic Trace (TXT + JSON)")
                    putExtra(Intent.EXTRA_TEXT, "Attached: AetherIO Omega flight recorder TXT report + structured JSON trace.")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(shareIntent, "Share AetherIO Diagnostics").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(chooser)
            }.onFailure {
                _snackbarEvents.tryEmit("Unable to launch share sheet: ${it.message}")
            }
        }
    }

    fun copyAria2SecretToClipboard() {
        val secret = _aria2Secret.value
        if (secret.isBlank()) return
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Aria2 RPC Secret", "token:$secret"))
        _snackbarEvents.tryEmit("Loopback RPC token copied to clipboard")
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
        viewModelScope.launch(Dispatchers.IO) {
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
            _snackbarEvents.tryEmit("Pipeline queued · Quad-Link Anti-Dip scheduler armed")
        }
    }

    fun pauseDownload(id: String) {
        viewModelScope.launch { engine.pause(id) }
    }

    fun resumeDownload(id: String) {
        // M1: launchDownload does native JNI + FGS binder + aria2 resume — all
        // blocking. Running it on Main caused a 5 s input-dispatch ANR on tap.
        viewModelScope.launch(Dispatchers.IO) { engine.launchDownload(id) }
    }

    fun switchTaskToKotlinAndResume(id: String) {
        viewModelScope.launch {
            engine.switchTaskToKotlinAndResume(id)
            _snackbarEvents.tryEmit("Switched task to Kotlin OkHttp Turbo & resuming")
        }
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
            _snackbarEvents.tryEmit("Signed URL refreshed · Resuming from AETHLDG2 ledger")
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
        dualWifiConnector.probeCapabilities()
    }

    fun toggleSpeedDisplayUnit() {
        _useMBpsSpeedUnit.value = !_useMBpsSpeedUnit.value
    }

    fun cycleSpeedometerStyle() {
        val all = SpeedometerStyle.entries
        val current = settings.value.speedometerStyle
        val next = all[(all.indexOf(current) + 1) % all.size]
        updateSettings { it.copy(speedometerStyle = next) }
        _snackbarEvents.tryEmit("Speedometer layout: ${next.label}")
    }

    fun probeDualWifi() {
        dualWifiConnector.probeCapabilities()
    }

    fun scanNearbyWifi() {
        viewModelScope.launch {
            val list = dualWifiConnector.scanNearbyNetworksAsync()
            if (list.isEmpty()) {
                _snackbarEvents.tryEmit("No nearby Wi-Fi results yet (grant permission or enable Shizuku)")
            } else {
                _snackbarEvents.tryEmit("Found ${list.size} nearby Wi-Fi networks")
            }
        }
    }

    fun requestShizukuPermission() {
        val grantedOrRequested = dualWifiConnector.shizukuHelper.requestPermission()
        dualWifiConnector.shizukuHelper.refreshStatus()
        if (grantedOrRequested) {
            _snackbarEvents.tryEmit("Shizuku permission active / requested")
        } else {
            _snackbarEvents.tryEmit("Opening Shizuku app to enable Wireless Debugging service")
        }
    }

    fun toggleShizukuLowLatency(enable: Boolean) {
        viewModelScope.launch {
            val ok = dualWifiConnector.shizukuHelper.toggleWifiLowLatencyBoost(enable)
            updateSettings { it.copy(enableShizukuWifiBoost = enable) }
            _snackbarEvents.tryEmit(
                if (ok) {
                    if (enable) "Shizuku Wi-Fi low-latency mode ENABLED" else "Shizuku Wi-Fi low-latency mode disabled"
                } else {
                    "Shizuku authorization required for low-latency Wi-Fi lock"
                },
            )
        }
    }

    fun saveDownloadMd5(id: String, md5: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateMd5(id, md5)
        }
    }

    fun saveDownloadSha256(id: String, sha256: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateSha256(id, sha256)
        }
    }

    fun updateUiThemeConfig(config: UiThemeConfig) {
        updateSettings { it.copy(uiThemeConfig = config) }
    }

    fun connectDirectWifi2(ssid: String, passphrase: String, band: WifiBandPreference) {
        dualWifiConnector.connectDirectWifi2(ssid, passphrase, band)
        updateSettings {
            it.copy(
                dualWifiEnabled = true,
                wifi2Ssid = ssid.trim(),
                wifi2BandPreference = band,
            )
        }
        _snackbarEvents.tryEmit("Requesting Direct Wi-Fi 2 (${ssid.trim()})…")
    }

    fun connectWifi2Relay(host: String, port: Int, isHttp: Boolean) {
        val ok = dualWifiConnector.connectCompanionRelay(host, port, isHttp)
        if (ok) {
            updateSettings {
                it.copy(
                    dualWifiEnabled = true,
                    wifi2RelayHost = host.trim(),
                    wifi2RelayPort = port.coerceIn(1, 65535),
                    wifi2RelayIsHttp = isHttp,
                )
            }
            _snackbarEvents.tryEmit("Wi-Fi 2 Companion Relay armed (${host.trim()}:$port)")
        } else {
            _snackbarEvents.tryEmit("Enter a valid Relay LAN IP and Port")
        }
    }

    fun disconnectWifi2() {
        dualWifiConnector.disconnectDirectWifi2(silent = false)
        updateSettings {
            it.copy(
                wifi2RelayHost = "",
            )
        }
        _snackbarEvents.tryEmit("Wi-Fi 2 disconnected")
    }

    fun resetTelemetryPeaks() {
        telemetryLedger.resetSessionPeaks()
        _realTelemetry.value = telemetryLedger.latestSnapshot()
        if (_simulationState.value.isRunning) {
            val preset = _simulationState.value.preset
            simulationJob?.cancel()
            simulationJob = null
            startSpeedSimulation(preset)
        }
        _snackbarEvents.tryEmit("Speedometer peaks, averages & benchmarks reset")
    }

    fun updateSettings(transform: (EngineSettings) -> EngineSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.update(transform)
            val updated = settingsRepo.current()
            // M1: binder (requestNetwork), native JNI flags and aria2 RPC process
            // start are all blocking — never on Main.
            networkMonitor.applyPolicy(updated)
            app.syncAria2RpcState(updated.enableAria2Rpc)
            _aria2Secret.value = app.aria2RpcSecret
            refreshAria2Status()
        }
    }

    // M2: aria2Status() was called TWICE per composition from SpeedDashboardScreen,
    // and engine.aria2Status() -> isAvailable() -> binaryFile() could block Main
    // behind Aria2NextEngine's synchronized monitor (which holds a 5s-timeout RPC).
    // Cache the result and refresh it only on IO.
    @Volatile
    private var cachedAria2Status: Pair<Boolean, String> = false to ""

    fun refreshAria2Status() {
        viewModelScope.launch(Dispatchers.IO) {
            cachedAria2Status = engine.aria2Status()
        }
    }

    fun aria2Status(): Pair<Boolean, String> = cachedAria2Status

    // ── Test mode: auto profile benchmark ──────────────────────────────────────
    private val _benchmarkResult = kotlinx.coroutines.flow.MutableStateFlow("Not run yet")
    val benchmarkResult: kotlinx.coroutines.flow.StateFlow<String> = _benchmarkResult.asStateFlow()

    /**
     * Runs every transport/profile combination for a short burst, picks the
     * fastest, and saves a PNG result card + log into Download/AetherIO/Benchmark.
     */
    fun runProfileBenchmark(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _benchmarkResult.value = "Benchmarking $url …"
            runCatching {
                val handle = networkMonitor.state.value.activeValidatedLinks.firstOrNull()?.netHandle ?: 0L
                val report = com.example.engine.ProfileBenchmark.run(app, url, handle)
                val best = report.best
                if (best != null) {
                    com.example.engine.BenchmarkPreference.profile = best.profile
                    com.example.engine.BenchmarkPreference.protocol = best.protocol
                }
                val summary = buildString {
                    append("Best: ${best?.protocol}/${best?.profile} — ")
                    append(String.format(Locale.US, "%.2f MB/s", (best?.bytesPerSec ?: 0L) / 1_048_576.0))
                    append("\nSaved: ${report.pngFile.absolutePath}\nLog: ${report.logFile.absolutePath}")
                }
                _benchmarkResult.value = summary
                _snackbarEvents.tryEmit("Profile benchmark finished · result card saved")
            }.onFailure {
                _benchmarkResult.value = "Benchmark failed: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }
}
