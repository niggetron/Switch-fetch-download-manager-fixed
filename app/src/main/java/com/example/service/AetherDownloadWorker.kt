package com.example.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.AetherApplication
import com.example.engine.DiagnosticsCenter
import kotlinx.coroutines.delay

/**
 * WorkManager fallback worker invoked automatically when Android 15/16 raises
 * `ForegroundServiceStartNotAllowedException` or exhausts the 6-hour `dataSync` FGS budget.
 */
class AetherDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AetherApplication ?: return Result.failure()
        val wakeLock = runCatching {
            (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AetherIO::WorkerWakeLock")
                .apply { setReferenceCounted(false); acquire(6L * 60L * 60L * 1000L) }
        }.getOrNull()
        try {
        runCatching {
            AetherDownloadService.ensureChannel(applicationContext)
            val notification = NotificationCompat.Builder(applicationContext, AetherDownloadService.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("AetherIO WorkManager Transfer")
                .setContentText("Continuing checkpointed AETHLDG2 pipeline…")
                .setOngoing(true)
                .build()
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    AetherDownloadService.NOTIFICATION_ID + 2,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(AetherDownloadService.NOTIFICATION_ID + 2, notification)
            }
            setForeground(info)
        }.onFailure {
            DiagnosticsCenter.log("WORKMANAGER", "Running in expedited background quota mode (${it.javaClass.simpleName})")
        }

        val savedSettings = app.settingsRepository.current()
        app.networkMonitor.start(savedSettings)
        app.downloadEngine.reconcileLedgersOnStartup()
        app.downloadEngine.resumeAll()

        // Keep worker alive while downloads finish or pause
        while (!isStopped) {
            val hasActive = app.downloadRepository.getAllOnce().any { it.statusEnum.isActive }
            if (!hasActive) break
            delay(1500L)
        }
        return Result.success()
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "aether_download_handoff"

        fun enqueueHandoff(context: Context) {
            val req = OneTimeWorkRequestBuilder<AetherDownloadWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                req,
            )
            DiagnosticsCenter.log("WORKMANAGER", "Enqueued WorkManager handoff for active pipelines")
        }
    }
}
