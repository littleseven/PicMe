@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.mamba.picme.PoLangApplication
import com.mamba.picme.core.common.Logger
import com.mamba.picme.R
import android.app.NotificationManager

class ModelDownloadForegroundService : Service() {

    companion object {
        const val ACTION_START_OR_UPDATE = "com.mamba.picme.download.START_OR_UPDATE"
        const val ACTION_STOP = "com.mamba.picme.download.STOP"

        private const val CHANNEL_ID = "picme_model_download"
        private const val CHANNEL_NAME = "Model Download"
        private const val NOTIFICATION_ID = 10042
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 2000L
    }

    private lateinit var manager: LlmModelDownloadManager

    /**
     * 通知进度自驱刷新：service 运行期间按固定节拍拉取下载状态刷新通知，把进度刷新从
     * 「Manager 每次 reportProgress 反复 startService 驱动」解耦为 service 自驱——
     * Manager 只在状态转换（开始/结束）碰 service，运行中的进度通知由本 Runnable 节拍刷新。
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationRefreshRunnable = object : Runnable {
        override fun run() {
            if (!::manager.isInitialized) return
            val states = manager.snapshotDownloadingStates()
            if (states.isEmpty()) return // 无下载中任务，停止自刷新（STOP 已在路上）
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(states))
            mainHandler.postDelayed(this, NOTIFICATION_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        val notification = buildEmptyNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // startForeground 失败（通知权限被拒等）：立即停止，避免超时闪退
            Logger.w("ModelDownloadFGS", "startForeground failed, stopping: ${e.message}")
            stopSelf()
            return
        }
        val app = application as PoLangApplication
        manager = app.container.llmModelDownloadManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                mainHandler.removeCallbacks(notificationRefreshRunnable)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_START_OR_UPDATE -> {
                val states = manager.snapshotDownloadingStates()
                if (states.isEmpty()) {
                    mainHandler.removeCallbacks(notificationRefreshRunnable)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // 立即刷新一次 + 启动自驱定时刷新（进度通知由 service 自驱，
                // 不再依赖 Manager 每次 reportProgress 反复 startService 驱动）
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(states))
                mainHandler.removeCallbacks(notificationRefreshRunnable)
                mainHandler.postDelayed(notificationRefreshRunnable, NOTIFICATION_REFRESH_INTERVAL_MS)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(notificationRefreshRunnable)
        // 通知 Manager：前台服务已真正销毁，重置 fgServiceRunning，
        // 使下次 updateServiceState 能重新走首次启动分支（闭环）。
        if (::manager.isInitialized) {
            manager.onServiceStopped()
        }
        super.onDestroy()
    }

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

