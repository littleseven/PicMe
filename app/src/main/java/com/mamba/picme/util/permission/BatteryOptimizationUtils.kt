package com.mamba.picme.util.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.mamba.picme.core.common.Logger

/**
 * 电池优化白名单辅助类
 *
 * 无障碍服务和前台悬浮窗服务在国产 ROM（尤其是 MIUI）上容易被系统省电策略杀死，
 * 引导用户将 PoLang 加入电池优化白名单可显著提升可用性。
 */
object BatteryOptimizationUtils {

    private const val TAG = "BatteryOptimizationUtils"

    /**
     * 判断当前应用是否已忽略电池优化。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } else {
            true
        }
    }

    /**
     * 请求用户将当前应用加入电池优化白名单。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to request ignore battery optimizations", e)
        }
    }
}
