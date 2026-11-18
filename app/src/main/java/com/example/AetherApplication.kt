package com.example

import android.app.Application
import com.example.data.local.AetherDatabase
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.DownloadRepository
import com.example.engine.AetherDownloadEngine
import com.example.engine.NetworkMonitor
import com.example.engine.SlabBufferPool
import com.example.engine.TelemetryLedger

class AetherApplication : Application() {
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
    val networkMonitor by lazy { NetworkMonitor(this) }
    val telemetryLedger by lazy { TelemetryLedger(this, slabBufferPool) }
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

    override fun onCreate() {
        super.onCreate()
        networkMonitor.start()
    }
}
