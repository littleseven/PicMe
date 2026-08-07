package com.mamba.picme.domain.agent.capability

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统级 Capability
 *
 * 职责：
 * - 启动本应用或其他应用（通过包名或应用名）
 * - 打开系统设置项
 *
 * 设计原则：
 * - 构造函数显式注入 [Context]，不依赖全局 Application 单例
 * - 仅执行显式用户指令，不自动启动任何应用
 * - 应用名到包名的解析只在本地进行，不上传任何数据
 */
class SystemCapability(
    private val context: Context
) : BaseCapability() {

    override val name: String = "system"
    override val description: String = "系统控制：打开其他应用、系统设置"

    override fun supportedCommands(): List<String> = listOf(
        "launch_app",
        "open_system_settings"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "launch_app" -> "启动应用，参数: package_name (可选), app_name (可选)"
        "open_system_settings" -> "打开系统设置，参数: setting (wifi|bluetooth|display|location|app_notifications)"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.d(TAG, "Executing command: ${command::class.simpleName}")

        return when (command) {
            is AgentCommand.LaunchApp -> launchApp(command)
            is AgentCommand.OpenSystemSettings -> openSystemSettings(command)
            else -> Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                    message = "SystemCapability 不支持此命令"
                )
            )
        }
    }

    private suspend fun launchApp(command: AgentCommand.LaunchApp): Result<AgentAction> {
        val packageName = command.packageName
        val appName = command.appName
        val activityClass = command.activityClass

        return withContext(Dispatchers.Main) {
            try {
                val resolvedPackage = resolveTargetPackage(packageName, appName)
                    ?: return@withContext Result.success(
                        AgentAction.Error(
                            commandId = command.commandId,
                            errorCode = AgentErrorCode.INVALID_PARAMS,
                            message = if (appName.isNullOrBlank()) {
                                "未找到目标应用"
                            } else {
                                "未找到应用：$appName"
                            }
                        )
                    )

                val intent = when {
                    !activityClass.isNullOrBlank() -> {
                        Intent(Intent.ACTION_MAIN).apply {
                            setClassName(resolvedPackage, activityClass)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                    }
                    resolvedPackage == context.packageName -> {
                        context.packageManager.getLaunchIntentForPackage(resolvedPackage)
                            ?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                    }
                    else -> {
                        context.packageManager.getLaunchIntentForPackage(resolvedPackage)
                            ?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                    }
                }

                if (intent == null) {
                    return@withContext Result.success(
                        AgentAction.Error(
                            commandId = command.commandId,
                            errorCode = AgentErrorCode.INTERNAL_ERROR,
                            message = "无法构造启动 Intent"
                        )
                    )
                }

                command.extras.forEach { (key, value) ->
                    intent.putExtra(key, value)
                }

                context.startActivity(intent)

                Result.success(
                    AgentAction.Success(
                        commandId = command.commandId,
                        command = command
                    )
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to launch app", e)
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.INTERNAL_ERROR,
                        message = "启动应用失败：${e.message ?: "未知错误"}"
                    )
                )
            }
        }
    }

    private fun resolveTargetPackage(packageName: String?, appName: String?): String? {
        // 1. 优先使用包名
        if (!packageName.isNullOrBlank()) {
            val exists = isPackageInstalled(packageName)
            if (exists) return packageName
        }

        // 2. 使用应用名模糊匹配
        val targetName = appName?.trim() ?: return null
        if (targetName.isBlank()) return null

        // 3. 常见中文应用映射兜底（避免 OEM 权限限制导致查不到）
        COMMON_APP_NAME_TO_PACKAGE[targetName]?.let { mappedPackage ->
            if (isPackageInstalled(mappedPackage)) return mappedPackage
        }

        return findPackageByAppName(targetName)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun findPackageByAppName(appName: String): String? {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(0)

        // 先尝试完全匹配
        installedApps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString()
            label == appName
        }?.let { return it.packageName }

        // 再尝试包含匹配
        installedApps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString()
            label.contains(appName, ignoreCase = true) || app.packageName.contains(appName, ignoreCase = true)
        }?.let { return it.packageName }

        return null
    }

    private fun openSystemSettings(command: AgentCommand.OpenSystemSettings): Result<AgentAction> {
        val intent = when (command.setting.lowercase()) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "app_notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            "application_details" -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            else -> Intent(Settings.ACTION_SETTINGS)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            Result.success(
                AgentAction.Success(
                    commandId = command.commandId,
                    command = command
                )
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to open system settings", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "打开设置失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }

    companion object {
        private const val TAG = "SystemCapability"

        /**
         * 常见中文应用名到包名的兜底映射。
         */
        private val COMMON_APP_NAME_TO_PACKAGE = mapOf(
            "微信" to "com.tencent.mm",
            "qq" to "com.tencent.mobileqq",
            "QQ" to "com.tencent.mobileqq",
            "支付宝" to "com.eg.android.AlipayGphone",
            "淘宝" to "com.taobao.taobao",
            "微博" to "com.sina.weibo",
            "抖音" to "com.ss.android.ugc.aweme",
            "小红书" to "com.xingin.xhs",
            "哔哩哔哩" to "tv.danmaku.bili",
            "b站" to "tv.danmaku.bili",
            "B站" to "tv.danmaku.bili",
            "京东" to "com.jingdong.app.mall",
            "美团" to "com.sankuai.meituan",
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "网易云音乐" to "com.netease.cloudmusic",
            "qq音乐" to "com.tencent.qqmusic",
            "QQ音乐" to "com.tencent.qqmusic",
            "滴滴出行" to "com.sdu.didi.psnger"
        )
    }
}
