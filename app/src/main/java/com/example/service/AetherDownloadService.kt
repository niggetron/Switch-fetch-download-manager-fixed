package com.example.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.AetherApplication
import com.example.MainActivity
import com.example.R
import com.example.engine.AetherNativeBridge
import com.example.engine.DiagnosticsCenter
import com.example.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AetherDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as AetherApplication
        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                serviceScope.launch {
                    app.downloadEngine.pauseAll()
                    stopForegroundAndSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }

        // Fix F4: Restore persisted network selection & cellular lease inside onStartCommand
        serviceScope.launch {
            val savedSettings = app.settingsRepository.current()
            app.networkMonitor.start(savedSettings)
        }

        acquireWifiHighPerfLock()
        app.telemetryLedger.markFgsSessionStarted()
        acquireWakeLock()
        val notification = buildNotification(
            activeCount = 1,
            speedBps = 0L,
            progressPercent = 0,
            subtitle = "Initializing AETHLDG2 pipeline…",
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // C16: on API 36 HyperOS, SecurityException (FGS type mismatch),
            // BadNotificationException or binder failures are also possible.
            // Rethrowing used to crash the app with locks + quota clock left dirty.
            // Degrade every FGS-start failure to the same safe handoff.
            DiagnosticsCenter.log(
                "FGS_HANDOFF",
                "${e.javaClass.simpleName} — handing off to WorkManager: ${e.message.orEmpty()}",
            )
            runCatching { markFgsSessionStopped() }
            AetherDownloadWorker.enqueueHandoff(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }

        startMonitorLoop(app)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val app = application as AetherApplication
        AetherNativeBridge.pauseQuotaGuardSafe()
        DiagnosticsCenter.log("QUOTA_GUARD", "dataSync 6h timeout reached — handing off to WorkManager")
        serviceScope.launch {
            app.downloadEngine.checkpointForQuotaGuard()
            AetherDownloadWorker.enqueueHandoff(applicationContext)
            showQuotaResumeNotification()
            stopForegroundAndSelf()
        }
    }

    private fun startMonitorLoop(app: AetherApplication) {
        if (monitorJob?.isActive == true) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                val all = app.downloadRepository.allDownloads.first()
                val active = all.filter { it.statusEnum.isActive }
                if (active.isEmpty()) {
                    stopForegroundAndSelf()
                    break
                }

                val snapshot = app.telemetryLedger.sampleSnapshotIfStale(
                    hasActiveDownloads = true,
                    maxAgeMs = 600L,
                )
                // R3 Quota Guard: proactively checkpoint 30s before 6-hour dataSync cap
                if (snapshot.quotaActiveSession && snapshot.quotaRemainingMs < 30_000L) {
                    AetherNativeBridge.pauseQuotaGuardSafe()
                    app.downloadEngine.checkpointForQuotaGuard()
                    AetherDownloadWorker.enqueueHandoff(applicationContext)
                    showQuotaResumeNotification()
                    stopForegroundAndSelf()
                    break
                }

                val totalBytes = active.sumOf { it.totalBytes.coerceAtLeast(0L) }
                val doneBytes = active.sumOf { it.downloadedBytes }
                val pct = if (totalBytes > 0L) ((doneBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                val titleFile = if (active.size == 1) active.first().fileName else "${active.size} active transfers"
                val speedText = Formatters.formatSpeedMbps(snapshot.instantBps)
                val splitText = buildString {
                    if (snapshot.wifiBps > 0L) append("W1:${Formatters.formatSpeedMbps(snapshot.wifiBps)} ")
                    if (snapshot.wifi2Bps > 0L) append("W2:${Formatters.formatSpeedMbps(snapshot.wifi2Bps)} ")
                    if (snapshot.cellBps > 0L) append("5G:${Formatters.formatSpeedMbps(snapshot.cellBps)} ")
                    if (snapshot.ethBps > 0L) append("USB:${Formatters.formatSpeedMbps(snapshot.ethBps)} ")
                }.trim()
                val subtitle = if (splitText.isNotEmpty()) {
                    "$titleFile · $speedText ($splitText) · ${snapshot.activeWorkers}W"
                } else {
                    "$titleFile · $speedText · ${snapshot.activeWorkers}W"
                }

                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        activeCount = active.size,
                        speedBps = snapshot.instantBps,
                        progressPercent = pct,
                        subtitle = subtitle,
                    )
                )
            }
        }
    }

    private fun acquireWifiHighPerfLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "AetherIO:HighPerfTransfer").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    /** FGS alone does not keep the CPU awake with the screen off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AetherIO::TransferWakeLock",
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire(6L * 60L * 60L * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun showQuotaResumeNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("AetherIO Quota Guard Checkpointed")
            .setContentText("AETHLDG2 ledger synced & WorkManager queued cleanly.")
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        nm.notify(NOTIFICATION_ID + 1, notif)
    }

    private fun stopForegroundAndSelf() {
        val app = application as AetherApplication
        app.telemetryLedger.markFgsSessionStopped()
        releaseWifiLock()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(
        activeCount: Int,
        speedBps: Long,
        progressPercent: Int,
        subtitle: String,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AetherDownloadService::class.java).apply { action = ACTION_PAUSE_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AetherIO Engine ($activeCount active)")
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent, progressPercent == 0 && speedBps == 0L)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_pause), pauseIntent)
            .build()
    }

    override fun onDestroy() {
        // Keep cellular lease alive if user has Quad-Link cellular bonding enabled
        // so consecutive downloads never drop 5G/LTE between tasks!
        releaseWifiLock()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "aether_transfers"
        const val NOTIFICATION_ID = 4096
        const val ACTION_START = "com.example.action.START_ENGINE"
        const val ACTION_PAUSE_ALL = "com.example.action.PAUSE_ALL"
        const val ACTION_STOP = "com.example.action.STOP_ENGINE"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_transfers_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_transfers_desc)
                }
                nm.createNotificationChannel(channel)
            }
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun createBatteryExemptionIntent(context: Context): Intent =
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun createAppBatteryDetailsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
