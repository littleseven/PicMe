package com.mamba.picme.util.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.mamba.picme.core.common.Logger

/**
 * 小米 MIUI  ROM 权限跳转辅助类
 *
 * MIUI 在 Android 标准权限之外还有若干应用行为管控：
 * - 自启动 / 后台运行：无障碍服务和前台 Service 容易被系统杀死
 * - 后台弹出界面：从 Service/后台启动 Activity 必须开启
 * - 显示悬浮窗：悬浮窗权限在 MIUI 中拆分到应用详情页
 *
 * 该类通过反射读取 `ro.miui.ui.version.name` 判断是否为 MIUI，
 * 并提供跳转到 MIUI 权限编辑页、自启动管理页的 Intent。
 */
object MiuiPermissionUtils {

    private const val TAG = "MiuiPermissionUtils"

    /**
     * 判断当前 ROM 是否为小米 MIUI。
     */
    fun isMiui(): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            val versionName = method.invoke(null, "ro.miui.ui.version.name") as? String ?: ""
            versionName.isNotBlank()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read MIUI property", e)
            false
        }
    }

    /**
     * 打开当前应用的 MIUI 权限编辑页（可开启「显示悬浮窗」「后台弹出界面」等）。
     */
    fun openMiuiPermissionEditor(context: Context): Boolean {
        return try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to open MIUI permission editor", e)
            openAppInfo(context)
        }
    }

    /**
     * 打开 MIUI 自启动管理页（允许 PoLang 自启动/后台运行）。
     */
    fun openMiuiAutoStart(context: Context): Boolean {
        return try {
            val intent = Intent("miui.intent.action.OP_AUTO_START").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to open MIUI auto-start page", e)
            openAppInfo(context)
        }
    }

    /**
     *  fallback：打开系统应用详情页。
     */
    fun openAppInfo(context: Context): Boolean {
        return try {
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to open app info", e)
            false
        }
    }
}
