package com.mamba.picme.testing.agent.device


import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.testing.agent.core.AgentTestContext
import com.mamba.picme.testing.agent.core.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 设备端测试控制器
 *
 * 封装所有设备操作，为 Agent 测试提供统一接口。
 * 将 adb 命令、截屏、日志收集等操作抽象为 Kotlin 协程 API。
 */
class DeviceTestController(private val context: Context) {

    companion object {
        private const val TAG = "AgentDeviceController"
        private const val SCREENSHOT_DIR = "/sdcard/PoLang_Agent_Test"
    }

    private var lastScreenshotPath: String? = null

    // ============================================
    // 应用生命周期控制
    // ============================================

    /**
     * 启动 PoLang 应用
     */
    suspend fun launchApp(): Boolean = withContext(Dispatchers.IO) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.mamba.picme")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                delay(2000)
                true
            } else {
                Logger.e(TAG, "Cannot find launch intent for com.mamba.picme")
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to launch app", e)
            false
        }
    }

    /**
     * 检查应用是否在前台运行
     */
    fun isAppForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningApps = activityManager.runningAppProcesses
        return runningApps?.any { it.processName == "com.mamba.picme" && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND } == true
    }

    // ============================================
    // 相机操作
    // ============================================

    private val registry = CapabilityRegistry.getInstance()

    /**
     * 触发拍照
     */
    suspend fun capture(): Boolean {
        dispatchCommand(AgentCommand.CapturePhoto())
        delay(500)
        return true
    }

    /**
     * 切换前后摄像头
     */
    suspend fun flipCamera(): Boolean {
        dispatchCommand(AgentCommand.FlipCamera())
        delay(1500)
        return true
    }

    /**
     * 设置美颜参数
     */
    suspend fun setBeauty(smooth: Int? = null, whiten: Int? = null, slimFace: Int? = null, bigEye: Int? = null) {
        dispatchCommand(
            AgentCommand.AdjustBeauty(
                settings = BeautySettings(
                    enabled = true,
                    smoothing = (smooth ?: 0) / 100f,
                    whitening = (whiten ?: 0) / 100f,
                    slimFace = (slimFace ?: 0) / 100f,
                    bigEyes = (bigEye ?: 0) / 100f
                )
            )
        )
        delay(300)
    }

    /**
     * 设置滤镜
     */
    suspend fun setFilter(filter: String) {
        dispatchCommand(AgentCommand.SwitchFilter(filterType = parseFilterType(filter)))
        delay(500)
    }

    /**
     * 切换场景模式
     */
    suspend fun setScene(scene: String) {
        dispatchCommand(AgentCommand.SwitchScene(sceneName = scene))
        delay(500)
    }

    /**
     * 切换画幅比例
     */
    suspend fun setRatio(ratio: String) {
        dispatchCommand(AgentCommand.SwitchRatio(ratio = ratio))
        delay(500)
    }

    /**
     * 设置曝光补偿
     */
    suspend fun setExposure(exposure: Int) {
        dispatchCommand(AgentCommand.AdjustExposure(exposure = exposure))
        delay(300)
    }

    /**
     * 设置缩放
     */
    suspend fun setZoom(zoom: Float) {
        dispatchCommand(AgentCommand.AdjustZoom(zoomRatio = zoom))
        delay(300)
    }

    /**
     * 切换美颜面板（通过 navigate_to settings 或语音命令入口）
     */
    suspend fun toggleBeautyPanel() {
        // 页面级面板切换暂通过 Settings 导航实现，后续可接入专用 Capability 命令
        Logger.w(TAG, "toggleBeautyPanel deprecated, use setBeauty instead")
    }

    /**
     * 切换滤镜面板
     */
    suspend fun toggleFilterPanel() {
        Logger.w(TAG, "toggleFilterPanel deprecated, use setFilter instead")
    }

    // ============================================
    // 相册操作
    // ============================================

    /**
     * 进入相册
     */
    suspend fun enterGallery() {
        dispatchCommand(AgentCommand.NavigateTo(destination = "gallery"))
        delay(1500)
    }

    /**
     * 打开指定索引的照片
     */
    suspend fun openPhoto(index: Int) {
        dispatchCommand(AgentCommand.ViewMedia(mediaId = index.toString()))
        delay(1000)
    }

    /**
     * 进入编辑模式
     */
    suspend fun startEdit() {
        // 编辑模式通过 ViewMedia 后由 GalleryCapability 处理
        Logger.w(TAG, "startEdit: gallery edit commands to be implemented in GalleryCapability")
    }

    /**
     * 保存编辑
     */
    suspend fun saveEdit() {
        Logger.w(TAG, "saveEdit: gallery edit commands to be implemented in GalleryCapability")
    }

    /**
     * 设置编辑模式磨皮
     */
    suspend fun setEditSmooth(value: Int) {
        Logger.w(TAG, "setEditSmooth: gallery edit commands to be implemented in GalleryCapability")
    }

    /**
     * 设置编辑模式美白
     */
    suspend fun setEditWhiten(value: Int) {
        Logger.w(TAG, "setEditWhiten: gallery edit commands to be implemented in GalleryCapability")
    }

    // ============================================
    // 状态查询
    // ============================================

    /**
     * 获取当前相机状态快照
     *
     * 返回模拟状态对象供测试用例使用。
     * 实际状态应通过 AgentStateProbe.captureSnapshot() 获取。
     */
    suspend fun getCameraState(timeout: Duration = 3.seconds): CameraStateSnapshot? {
        return CameraStateSnapshot()
    }

    /**
     * 相机状态快照（供测试用例使用）
     */
    data class CameraStateSnapshot(
        val lensFacing: String = "back",
        val captureMode: String = "photo",
        val aspectRatio: String = "full",
        val beautySmooth: Float = 0f,
        val beautyWhiten: Float = 0f,
        val beautyEnabled: Boolean = false,
        val currentFilter: String = "none"
    )

    // ============================================
    // 截屏与图像分析
    // ============================================

    /**
     * 截屏并保存
     */
    suspend fun takeScreenshot(name: String, ctx: AgentTestContext): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val filename = "${name}_${timestamp}.png"
            val path = "$SCREENSHOT_DIR/$filename"

            // 确保目录存在
            File(SCREENSHOT_DIR).mkdirs()

            // 使用 MediaProjection API 或 adb screencap
            // 这里通过 Runtime 执行 screencap（需要 shell 权限或 root）
            val process = Runtime.getRuntime().exec("screencap -p $path")
            process.waitFor()

            if (File(path).exists()) {
                lastScreenshotPath = path
                ctx.addScreenshot(name, path)
                Logger.i(TAG, "Screenshot saved: $path")
                path
            } else {
                Logger.w(TAG, "Screenshot file not created: $path")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to take screenshot", e)
            null
        }
    }

    /**
     * 获取最后截屏路径
     */
    fun getLastScreenshotPath(): String? = lastScreenshotPath

    // ============================================
    // 日志收集
    // ============================================

    /**
     * 收集 PoLang 相关日志
     */
    fun collectLogs(ctx: AgentTestContext, lines: Int = 200) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d -s PoLang:* *:S")
            val output = process.inputStream.bufferedReader().readText()

            output.lines().take(lines).forEach { line ->
                val level = when {
                    line.contains(" E ") -> LogLevel.ERROR
                    line.contains(" W ") -> LogLevel.WARN
                    line.contains(" D ") -> LogLevel.DEBUG
                    else -> LogLevel.INFO
                }
                ctx.addLog("PoLang", line, level)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect logs", e)
        }
    }

    // ============================================
    // 性能监控
    // ============================================

    /**
     * 获取 FPS 信息
     */
    fun getFpsInfo(): Map<String, Any> {
        return try {
            val process = Runtime.getRuntime().exec("dumpsys gfxinfo com.mamba.picme")
            val output = process.inputStream.bufferedReader().readText()

            val jankCount = Regex("Janky frames: ([0-9]+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val totalFrames = Regex("Total frames rendered: ([0-9]+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1

            mapOf(
                "jankCount" to jankCount,
                "totalFrames" to totalFrames,
                "rawOutput" to output
            )
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * 获取内存信息
     */
    fun getMemoryInfo(): Map<String, Any> {
        return try {
            val process = Runtime.getRuntime().exec("dumpsys meminfo com.mamba.picme")
            val output = process.inputStream.bufferedReader().readText()

            val totalPss = Regex("TOTAL PSS: ([0-9,]+)K").find(output)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: -1

            mapOf(
                "totalPssKb" to totalPss,
                "rawOutput" to output
            )
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    // ============================================
    // 私有方法
    // ============================================

    private suspend fun dispatchCommand(command: AgentCommand) {
        Logger.i(TAG, "Dispatching command: ${command::class.simpleName}")
        val scene = when (command) {
            is AgentCommand.NavigateTo -> {
                when (command.destination.lowercase()) {
                    "gallery" -> AgentScene.GALLERY
                    "settings" -> AgentScene.SETTINGS
                    else -> AgentScene.CAMERA
                }
            }
            is AgentCommand.GoBack -> AgentScene.CAMERA
            is AgentCommand.ViewMedia,
            is AgentCommand.DeleteMedia,
            is AgentCommand.ShareMedia,
            is AgentCommand.SelectMedia,
            is AgentCommand.FavoriteMedia,
            is AgentCommand.SearchMedia -> AgentScene.GALLERY
            else -> AgentScene.CAMERA
        }
        registry.dispatch(command, AgentContext(scene = scene))
    }

    private fun parseFilterType(filter: String) = FilterType.valueOf(
        filter.uppercase().replace("LEICA_CLASSIC", "LEICA_CLASSIC")
            .replace("LEICA_VIBRANT", "LEICA_VIBRANT")
            .replace("LEICA_BW", "LEICA_BW")
    )
}
