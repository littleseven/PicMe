package com.mamba.picme.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.Service
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
        val app = application as PoLangApplication
        manager = app.container.llmModelDownloadManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() 要求必须在 5 秒内调用 startForeground()，
        // 因此无论什么 action 都先立即调用，再处理具体逻辑
        startForeground(NOTIFICATION_ID, buildEmptyNotification())

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

