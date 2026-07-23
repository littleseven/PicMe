package com.mamba.picme.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import android.app.NotificationManager

class ModelDownloadForegroundService : Service() {

    companion object {
        const val ACTION_START_OR_UPDATE = "com.mamba.picme.download.START_OR_UPDATE"
        const val ACTION_STOP = "com.mamba.picme.download.STOP"

        private const val CHANNEL_ID = "picme_model_download"
        private const val CHANNEL_NAME = "Model Download"
        private const val NOTIFICATION_ID = 10042
    }

    private lateinit var manager: LlmModelDownloadManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        // Android 14+ (API 34+) 要求 startForeground 必须在 onCreate 中尽早调用，
        // 且必须传 foregroundServiceType，否则 ForegroundServiceDidNotStartInTimeException。
        val notification = buildEmptyNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // 通知权限被拒等边界情况：不 crash，降级为普通 Service
        }
        val app = application as PoLangApplication
        manager = app.container.llmModelDownloadManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_START_OR_UPDATE -> {
                // 更新通知为真实下载进度
                val states = manager.snapshotDownloadingStates()
                if (states.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // 更新通知为真实下载进度
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(states))
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(states: List<DownloadState>): Notification {
        val totalBytes = states.sumOf { state -> state.totalBytes }
        val downloadedBytes = states.sumOf { state -> state.downloadedBytes.coerceAtMost(state.totalBytes) }
        val progressPercent = if (totalBytes > 0) {
            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val title = getString(R.string.model_download_notification_title)
        val content = if (states.size == 1) {
            getString(R.string.model_download_notification_single, states.first().modelId, progressPercent)
        } else {
            getString(R.string.model_download_notification_multi, states.size, progressPercent)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progressPercent, false)
            .build()
    }

    private fun buildEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.model_download_notification_title))
            .setContentText(getString(R.string.model_download_preparing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = getString(R.string.model_download_notification_channel_desc)
        }

        manager.createNotificationChannel(channel)
    }
}

