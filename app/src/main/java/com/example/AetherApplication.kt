package com.example

import android.app.Application
import com.example.data.local.AetherDatabase
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.DownloadRepository
import com.example.engine.AetherDownloadEngine
import com.example.engine.AetherNativeBridge
import com.example.engine.DiagnosticsCenter
import com.example.engine.DualWifiConnector
import com.example.engine.MemfdTelemetryReader
import com.example.engine.NetworkMonitor
import com.example.engine.SlabBufferPool
import com.example.engine.TelemetryLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AetherApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AetherDatabase.getInstance(this) }
    val downloadRepository by lazy {
        DownloadRepository(
            context = this,
            downloadDao = database.downloadDao(),
            casChunkDao = database.casChunkDao(),
        )
    }
    val settingsRepository by lazy { SettingsRepository(this) }
    val slabBufferPool by lazy { SlabBufferPool() }
    val memfdTelemetryReader by lazy { MemfdTelemetryReader() }
    val networkMonitor by lazy { NetworkMonitor(this) }
    val dualWifiConnector by lazy { DualWifiConnector(this, networkMonitor) }
    val telemetryLedger by lazy { TelemetryLedger(this, slabBufferPool, memfdTelemetryReader) }
    val downloadEngine by lazy {
        AetherDownloadEngine(
            context = this,
            repository = downloadRepository,
            settingsRepository = settingsRepository,
            networkMonitor = networkMonitor,
            slabPool = slabBufferPool,
            telemetryLedger = telemetryLedger,
        )
    }

    @Volatile
    var aria2RpcSecret: String = ""
        private set

    fun syncAria2RpcState(enabled: Boolean) {
        if (enabled && AetherNativeBridge.isNativeLoaded) {
            if (aria2RpcSecret.isBlank()) {
                aria2RpcSecret = AetherNativeBridge.startAria2RpcSafe()
                if (aria2RpcSecret.isNotBlank()) {
                    DiagnosticsCenter.log("ARIA2_RPC", "Loopback 127.0.0.1:6800 armed with per-boot secret")
                }
            }
        } else {
            AetherNativeBridge.stopAria2RpcSafe()
            aria2RpcSecret = ""
        }
    }

    override fun onCreate() {
        super.onCreate()
        telemetryLedger.startCpuHeadroomMonitor(appScope)
        appScope.launch {
            // Keep every native entry point off the main startup path. If the .so
            // is missing, incompatible, or crashes during load, the UI still opens
            // and the Kotlin OkHttp engine remains fully usable.
            runCatching { telemetryLedger.attachMemfdIfAvailable() }
            runCatching { telemetryLedger.refreshNativeBadge() }
            val saved = settingsRepository.current()
            dualWifiConnector.probeCapabilities()
            networkMonitor.start(saved)
            downloadEngine.reconcileLedgersOnStartup()
            syncAria2RpcState(saved.enableAria2Rpc)
        }
    }
}
