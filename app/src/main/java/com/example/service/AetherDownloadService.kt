package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.AetherApplication
import com.example.MainActivity
import com.example.R
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
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

        app.telemetryLedger.markFgsSessionStarted()
        val notification = buildNotification(
            activeCount = 1,
            speedBps = 0L,
            progressPercent = 0,
            subtitle = "Initializing zero-merge pipeline…",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startMonitorLoop(app)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val app = application as AetherApplication
        serviceScope.launch {
            app.downloadEngine.checkpointForQuotaGuard()
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

                val snapshot = app.telemetryLedger.sampleSnapshot(hasActiveDownloads = true)
                // R3 Quota Guard: proactively checkpoint 30s before 6-hour dataSync cap
                if (snapshot.quotaActiveSession && snapshot.quotaRemainingMs < 30_000L) {
                    app.downloadEngine.checkpointForQuotaGuard()
                    stopForegroundAndSelf()
                    break
                }

                val totalBytes = active.sumOf { it.totalBytes.coerceAtLeast(0L) }
                val doneBytes = active.sumOf { it.downloadedBytes }
                val pct = if (totalBytes > 0L) ((doneBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                val titleFile = if (active.size == 1) active.first().fileName else "${active.size} active transfers"
                val speedText = Formatters.formatSpeedMbps(snapshot.instantBps)
                val subtitle = "$titleFile · $speedText · ${snapshot.activeWorkers}W"

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

    private fun stopForegroundAndSelf() {
        val app = application as AetherApplication
        app.telemetryLedger.markFgsSessionStopped()
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

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_transfers_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_transfers_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "aether_transfers"
        const val NOTIFICATION_ID = 4096
        const val ACTION_START = "com.example.action.START_ENGINE"
        const val ACTION_PAUSE_ALL = "com.example.action.PAUSE_ALL"
        const val ACTION_STOP = "com.example.action.STOP_ENGINE"
    }
}
