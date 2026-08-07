package com.mamba.picme.features.camera.capability

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.core.common.Logger

/**
 * 相机控制 Capability（页面级）
 *
 * **架构设计**：
 * - 页面级生命周期：由 CameraScreen 创建和持有，Screen 销毁时释放
 * - 状态内聚：直接持有可观察的状态，无需 delegate 模式
 * - 零泄漏：不持有 Activity/Screen 的引用，状态通过 Compose 状态系统驱动 UI
 * - 跨页面命令：通过 NavigationCapability 导航到相机页后，新 CameraCapability 自动接管
 *
 * **生命周期**：
 * ```
 * CameraScreen Enter ──► CameraCapability() 创建 ──► 注册到全局 CapabilityRegistry
 *     │
 *     ├── 命令执行直接修改内部状态
 *     │
 * CameraScreen Exit ──► 从 CapabilityRegistry 注销 ──► CameraCapability 被 GC 回收
 * ```
 *
 * **状态同步**：
 * CameraScreen 通过读取 CameraCapability 的状态来驱动 UI：
 * ```kotlin
 * val cameraCapability = remember { CameraCapability() }
 * DisposableEffect(cameraCapability) {
 *     orchestrator.registerCapability(cameraCapability)
 *     onDispose { orchestrator.unregisterCapability(cameraCapability) }
 * }
 *
 * // 状态绑定
 * val aspectRatio = cameraCapability.aspectRatio
 * val beautySettings = cameraCapability.beautySettings
 * ```
 *
 * 仅在 CAMERA 场景可用。
 */
class CameraCapability : BaseCapability() {

    private val tag = "CameraCapability"

    override val name: String = "camera"
    override val description: String =
        "控制相机拍摄、美颜参数、滤镜、风格、变焦、曝光、画幅比例、场景模式和摄像头翻转"

    // ═══════════════════════════════════════════════════════════════════════════
    // 可观察状态（页面级，随 Capability 创建和销毁）
    // ═══════════════════════════════════════════════════════════════════════════

    /** 当前画幅比例 */
    var aspectRatio by mutableIntStateOf(RATIO_FULL)
        private set

    /** 当前摄像头方向 */
    var lensFacing by mutableIntStateOf(androidx.camera.core.CameraSelector.LENS_FACING_BACK)
        private set

    /** 当前美颜设置 */
    var beautySettings by mutableStateOf(BeautySettings(enabled = false))
        private set

    /** 当前滤镜 */
    var filterType by mutableStateOf(FilterType.NONE)
        private set

    /** 当前风格 */
    var styleFilter by mutableStateOf(StyleFilter.NONE)
        private set

    /** 当前曝光补偿 */
    var exposureCompensation by mutableIntStateOf(0)
        private set

    /** 当前变焦比例 */
    var zoomRatio by mutableFloatStateOf(1.0f)
        private set

    /** 当前场景模式 */
    var sceneMode by mutableStateOf(SceneMode.NONE)
        private set

    /** 当前拍摄模式 */
    var captureMode by mutableStateOf(MediaType.PHOTO)
        private set

    /** 是否正在录像 */
    var isRecording by mutableStateOf(false)
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // 状态变更回调（可选，用于通知外部监听者）
    // ═══════════════════════════════════════════════════════════════════════════

    private var onStateChanged: ((StateChange) -> Unit)? = null

    /** 状态变更事件 */
    sealed class StateChange {
        data class AspectRatioChanged(val ratio: Int) : StateChange()
        data class BeautySettingsChanged(val settings: BeautySettings) : StateChange()
        data class FilterChanged(val filter: FilterType) : StateChange()
        data class StyleChanged(val style: StyleFilter) : StateChange()
        data class SceneChanged(val scene: SceneMode) : StateChange()
        data class ExposureChanged(val exposure: Int) : StateChange()
        data class ZoomChanged(val zoom: Float) : StateChange()
        data class LensFacingChanged(val facing: Int) : StateChange()
        data class CaptureModeChanged(val mode: MediaType) : StateChange()
        data object CaptureRequested : StateChange()
        data object RecordingToggled : StateChange()
    }

    /**
     * 设置状态变更监听器
     *
     * @param listener 状态变更回调，null 表示移除监听
     */
    fun setOnStateChangedListener(listener: ((StateChange) -> Unit)?) {
        onStateChanged = listener
    }

    private fun notifyChange(change: StateChange) {
        onStateChanged?.invoke(change)
    }

    override fun isAvailable(): Boolean = true

    /** 场景模式枚举 */
    enum class SceneMode {
        NONE, NIGHT, MOON
    }

    override fun activeScenes(): List<SceneManager.Scene> {
        return listOf(SceneManager.Scene.CAMERA)
    }

    override fun supportedCommands(): List<String> = listOf(
        "delay",
        "adjust_beauty",
        "switch_filter",
        "switch_style",
        "switch_scene",
        "switch_ratio",
        "adjust_exposure",
        "adjust_zoom",
        "flip_camera",
        "capture",
        "toggle_recording",
        "switch_mode"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "delay" -> "等待指定毫秒数后再执行后续命令，必须与其他命令组合使用，参数: delay_ms (毫秒)"
        "adjust_beauty" -> "调整美颜参数，参数: smoothing, whitening, slim_face, big_eyes, lip_color, blush, eyebrow"
        "switch_filter" -> "切换滤镜，参数: filter (NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM)"
        "switch_style" -> "切换风格，参数: style (NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH)"
        "switch_scene" -> "切换场景模式，参数: scene (night|moon|none)"
        "switch_ratio" -> "切换画幅比例，参数: ratio (4:3|16:9|full)"
        "adjust_exposure" -> "调整曝光，参数: exposure (-2~2)"
        "adjust_zoom" -> "调整变焦，参数: zoom (0.5~10.0)"
        "flip_camera" -> "翻转前后摄像头"
        "capture" -> "拍照"
        "toggle_recording" -> "开始/停止录像"
        "switch_mode" -> "切换拍摄模式，参数: mode (PHOTO|VIDEO|PRO|DOCUMENT)"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.AdjustBeauty -> {
                beautySettings = command.settings
                notifyChange(StateChange.BeautySettingsChanged(command.settings))
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.SwitchFilter -> {
                filterType = command.filterType
                notifyChange(StateChange.FilterChanged(command.filterType))
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.SwitchStyle -> {
                styleFilter = command.styleFilter
                notifyChange(StateChange.StyleChanged(command.styleFilter))
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.SwitchScene -> {
                sceneMode = when (command.sceneName.lowercase()) {
                    "night", "夜景" -> SceneMode.NIGHT
                    "moon", "月亮" -> SceneMode.MOON
                    else -> SceneMode.NONE
                }
                notifyChange(StateChange.SceneChanged(sceneMode))
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.SwitchRatio -> {
                aspectRatio = parseRatio(command.ratio)
                notifyChange(StateChange.AspectRatioChanged(aspectRatio))
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.AdjustExposure -> {
                exposureCompensation = command.exposure
                notifyChange(StateChange.ExposureChanged(command.exposure))
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.AdjustZoom -> {
                zoomRatio = command.zoomRatio
                notifyChange(StateChange.ZoomChanged(command.zoomRatio))
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.FlipCamera -> {
                lensFacing = if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                    androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                } else {
                    androidx.camera.core.CameraSelector.LENS_FACING_BACK
                }
                notifyChange(StateChange.LensFacingChanged(lensFacing))
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.CapturePhoto -> {
                notifyChange(StateChange.CaptureRequested)
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.ToggleRecording -> {
                isRecording = !isRecording
                notifyChange(StateChange.RecordingToggled)
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.SwitchMode -> {
                captureMode = command.mode
                notifyChange(StateChange.CaptureModeChanged(command.mode))
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            else -> {
                Logger.w(tag, "Command not supported by CameraCapability: ${command::class.simpleName}")
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "相机页面不支持此命令"
                    )
                )
            }
        }
    }

    companion object {
        const val RATIO_4_3 = 0
        const val RATIO_16_9 = 1
        const val RATIO_FULL = 2
    }

    /**
     * 解析比例字符串为内部枚举值
     */
    private fun parseRatio(ratio: String): Int {
        return when (ratio.trim().lowercase().replace("-", ":")) {
            "4:3", "4_3", "4/3" -> RATIO_4_3
            "16:9", "16_9", "16/9" -> RATIO_16_9
            "full", "full_screen", "fullscreen" -> RATIO_FULL
            else -> RATIO_FULL
        }
    }
}
