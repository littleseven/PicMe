package com.mamba.picme.service.tag

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * onTimeout 兜底:dataSync 前台服务被系统超时停止后,定时唤醒重启扫描。
 *
 * ## 背景
 * Android 14+ 对 `dataSync` 类型前台服务有运行时长上限(单实例约 6h,15+ 每日聚合配额),
 * 超时走 [android.app.Service.onTimeout],默认实现 `stopSelf()` 后 `START_STICKY` 不保证
 * 重启 —— 大图库跨夜扫描会因此彻底停滞。
 *
 * ## 机制
 * [TagGenerationService.onTimeout] 触发时调用 [scheduleResume] 注册一个 AlarmManager 闹钟,
 * 到点后本 Receiver 重新拉起 TagGenerationService,由其 onCreate →
 * TagScanOrchestrator 的 `init { resetRunningToPending(); maybeResumeOnStartup() }` 恢复
 * 被中断的 PENDING 会话。
 *
 * ## 前提
 * 用户已加入电池优化白名单(Android 12+ 后台启动 FGS 需豁免),由 BackgroundScanGuard 引导。
 * 精确闹钟优先 [AlarmManager.canScheduleExactAlarms],未授权时降级为 inexact 不致抛异常。
 */
class TagScanRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Resume alarm fired, restarting TagGenerationService")
        TagGenerationService.startForeground(context)
    }

    companion object {
        private const val TAG = "TagScanReschedule"
        private const val REQUEST_CODE = 10043
        private const val ACTION_RESUME_SCAN = "com.mamba.picme.tag.RESUME_FROM_TIMEOUT"

        /**
         * 安排 [delayMs] 后唤醒重启扫描服务。
         * 优先精确闹钟(API 31+ 需 SCHEDULE_EXACT_ALARM,已在 manifest 声明);
         * 未授权或低版本时降级为 [AlarmManager.setAndAllowWhileIdle],避免 SecurityException。
         */
        fun scheduleResume(context: Context, delayMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) {
                Log.w(TAG, "AlarmManager unavailable, cannot schedule resume")
                return
            }

            val intent = Intent(context, TagScanRescheduleReceiver::class.java).apply {
                action = ACTION_RESUME_SCAN
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                    Log.i(TAG, "Scheduled exact resume alarm in ${delayMs}ms")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                    Log.i(TAG, "Scheduled inexact resume alarm in ${delayMs}ms")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to schedule resume alarm: ${e.message}")
            }
        }

        /**
         * 取消已安排的续跑闹钟(扫描正常完成或用户主动取消时调用,避免无谓唤醒)。
         */
        @Suppress("unused")
        fun cancelResume(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, TagScanRescheduleReceiver::class.java).apply {
                action = ACTION_RESUME_SCAN
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }
}
