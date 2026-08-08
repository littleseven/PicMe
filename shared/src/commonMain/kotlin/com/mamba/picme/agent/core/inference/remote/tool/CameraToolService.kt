package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import com.mamba.picme.agent.core.inference.remote.log.TraceIdAware
import com.mamba.picme.agent.core.inference.remote.log.TraceIdHolder
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.capability.CommandExecutor
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout

/**
 * 相机场景专用 ToolService（远程 tool_calls，ADR-005 协议分离；Koog 驱动）。
 *
 * 端侧文本 LLM 移除后，相机 AI 指令改为远程模型 tool_calls：模型输出标准 OpenAI tool_calls，
 * 本类的 @Tool 方法把相机场景 capability（拍照/录像/美颜/滤镜/变焦/曝光/翻转等）暴露给模型，
 * 命令统一进 [CapabilityRegistry]（scene=CAMERA），由页面级 CameraCapability 执行——
 * 与 [ChatToolService]（scene=CHAT）同一范式，写操作复用既有 CommandRisk/确认机制，不新造协议。
 *
 * **Koog 工具表面**：用 Koog `@Tool(customName=...)`（保 LLM-facing 蛇形工具名，确定性）
 * + 方法级/参数级 `@LLMDescription`。组合根（Android/JVM 侧）经 Koog 反射
 *（`asToolsByClass()`，与 `reflect.ToolSet.asTools()` 同一扫描函数）展开 @Tool 方法拿到
 * 类型化参数——`reflect.ToolSet` 标记接口是 Koog 1.1.1 jvmCommonMain API，commonMain
 * 不可引用，故本类不实现它（KMP 抽取 Task 7）。各工具**直接构造 [AgentCommand]**——
 * langchain4j 期「拼 argsJson → ToolExecutionRequest
 * → ToolCallCommandParser.parse」的往返已内联消除（滤镜/风格的中文别名解析随迁至本类私有函数）。
 * 仅 `adjust_beauty` 因支持 enabled 开关与「未提及参数保持当前值」语义，命令构建稍复杂
 *（当前美颜值经 [beautySettingsProvider] 由 app 注入，:shared 不依赖 app 的 CameraCapability）。
 *
 * **并发模型（KMP 抽取 Task 7 suspend 化）**：@Tool 方法为 suspend（Koog 1.1.1 支持 suspend
 * 工具函数），dispatch 从 `future{}.get(5s)` 阻塞桥改写为 `withTimeout(5s)` 结构化等待，
 * 超时抛 `TimeoutCancellationException`（语义对齐旧 `java.util.concurrent.TimeoutException` 分支）。
 *
 * **重要**：@Tool 参数**不能用 Kotlin 默认值**（同 [ChatToolService] 的 R8/反射约束）。可选语义
 * 用空串表示「不调整」，由 @LLMDescription 描述说明。
 */
class CameraToolService private constructor() : TraceIdAware {

    companion object {
        /** dispatch 等待超时（毫秒），语义对齐旧 `future{}.get(5, SECONDS)`。 */
        private const val DISPATCH_TIMEOUT_MS = 5000L

        // KMP commonMain 无 synchronized，lazy 默认 SYNCHRONIZED 模式保证同款线程安全单例语义
        private val singleton: CameraToolService by lazy { CameraToolService() }
        fun getInstance(): CameraToolService = singleton
    }

    private val tag = "CameraToolService"

    /** UI 事件流：dispatchCommand 执行后的原始 AgentAction 发到此 flow，供 app 侧渲染/提示。 */
    val uiActions = MutableSharedFlow<AgentAction>(extraBufferCapacity = 16)

    /**
     * 当轮 traceId 持有器：由组合根自 [com.mamba.picme.agent.core.inference.remote.koog.KoogReActAgent]
     * 的 traceIdHolder 接线写入（agent 每轮任务开始时写值），dispatchCommand 读取后注入
     * AgentContext，与 LLM 调用日志关联。
     */
    @Volatile
    override var traceIdHolder: TraceIdHolder? = null

    /**
     * 当前美颜设置供给者（由 app 相机页注入，读取 CameraCapability.beautySettings）。
     * 未注入时 adjust_beauty 以默认设置为基线。
     */
    @Volatile
    var beautySettingsProvider: (() -> BeautySettings)? = null

    // ── 拍摄 ──────────────────────────────────────────────────────

    @Tool(customName = "capture")
    @LLMDescription("拍照。用户说「拍照」「拍一张」「咔嚓」时使用；延时拍摄用 delay + capture 组合。")
    suspend fun capture(): String = dispatchCommand(AgentCommand.CapturePhoto())

    @Tool(customName = "toggle_recording")
    @LLMDescription("开始/停止录像（开关式）。")
    suspend fun toggleRecording(): String = dispatchCommand(AgentCommand.ToggleRecording())

    @Tool(customName = "flip_camera")
    @LLMDescription("翻转前后摄像头。用户说「切前置」「换摄像头」时使用。")
    suspend fun flipCamera(): String = dispatchCommand(AgentCommand.FlipCamera())

    @Tool(customName = "switch_mode")
    @LLMDescription("切换拍摄模式。mode: PHOTO(拍照)/VIDEO(录像)/PRO(专业)/DOCUMENT(文档)。")
    suspend fun switchMode(
        @LLMDescription("PHOTO/VIDEO/PRO/DOCUMENT") mode: String
    ): String {
        val mediaType = runCatching { MediaType.valueOf(mode) }.getOrDefault(MediaType.PHOTO)
        return dispatchCommand(AgentCommand.SwitchMode(mode = mediaType))
    }

    // ── 美颜 / 滤镜 / 风格 ─────────────────────────────────────────

    @Tool(customName = "adjust_beauty")
    @LLMDescription("调整美颜参数。enabled: true 开美颜 / false 关美颜 / 空串=有调整参数时自动开启。smoothing(磨皮)/whitening(美白)/big_eyes(大眼)/lip_color(唇色)/blush(腮红)/eyebrow(眉毛) 0~100，slim_face(瘦脸) -50~50；未提及的参数留空串保持当前值。")
    suspend fun adjustBeauty(
        @LLMDescription("true/false，留空=有调整参数时自动开启") enabled: String,
        @LLMDescription("磨皮 0~100，留空=不变") smoothing: String,
        @LLMDescription("美白 0~100，留空=不变") whitening: String,
        @LLMDescription("瘦脸 -50~50，留空=不变") slimFace: String,
        @LLMDescription("大眼 0~100，留空=不变") bigEyes: String,
        @LLMDescription("唇色 0~100，留空=不变") lipColor: String,
        @LLMDescription("腮红 0~100，留空=不变") blush: String,
        @LLMDescription("眉毛 0~100，留空=不变") eyebrow: String
    ): String {
        val current = beautySettingsProvider?.invoke() ?: BeautySettings(enabled = true)
        val settings = current.copy(
            enabled = when (enabled.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> true // 有调整参数时默认开启
            },
            smoothing = smoothing.toFloatOrNull() ?: current.smoothing,
            whitening = whitening.toFloatOrNull() ?: current.whitening,
            slimFace = slimFace.toFloatOrNull() ?: current.slimFace,
            bigEyes = bigEyes.toFloatOrNull() ?: current.bigEyes,
            lipColor = lipColor.toFloatOrNull() ?: current.lipColor,
            blush = blush.toFloatOrNull() ?: current.blush,
            eyebrow = eyebrow.toFloatOrNull() ?: current.eyebrow
        )
        return dispatchCommand(AgentCommand.AdjustBeauty(settings = settings))
    }

    @Tool(customName = "switch_filter")
    @LLMDescription("切换滤镜。filter: NONE/LEICA_CLASSIC/LEICA_VIBRANT/LEICA_BW/FILM_GOLD/FILM_FUJI/VINTAGE/COOL/WARM，中文名（如「徕卡经典」「胶片金」「冷调」）亦可。")
    suspend fun switchFilter(
        @LLMDescription("滤镜名") filter: String
    ): String = dispatchCommand(AgentCommand.SwitchFilter(filterType = resolveFilterType(filter)))

    @Tool(customName = "switch_style")
    @LLMDescription("切换风格特效。style: NONE/TOON(卡通)/SKETCH(素描)/POSTERIZE(海报)/EMBOSS(浮雕)/CROSSHATCH(交叉线)。")
    suspend fun switchStyle(
        @LLMDescription("风格名") style: String
    ): String = dispatchCommand(AgentCommand.SwitchStyle(styleFilter = resolveStyleFilter(style)))

    // ── 取景参数 ──────────────────────────────────────────────────

    @Tool(customName = "switch_scene")
    @LLMDescription("切换场景模式。scene: night(夜景)/moon(月亮)/none(关闭)。")
    suspend fun switchScene(
        @LLMDescription("night/moon/none") scene: String
    ): String = dispatchCommand(AgentCommand.SwitchScene(sceneName = scene))

    @Tool(customName = "switch_ratio")
    @LLMDescription("切换画幅比例。ratio: 4:3 / 16:9 / full(全屏)。")
    suspend fun switchRatio(
        @LLMDescription("4:3/16:9/full") ratio: String
    ): String = dispatchCommand(AgentCommand.SwitchRatio(ratio = ratio))

    @Tool(customName = "adjust_exposure")
    @LLMDescription("调整曝光补偿。exposure: -2~2 整数，正值调亮、负值调暗。")
    suspend fun adjustExposure(
        @LLMDescription("-2~2") exposure: Long
    ): String = dispatchCommand(
        AgentCommand.AdjustExposure(exposure = exposure.toInt().coerceIn(-2, 2))
    )

    @Tool(customName = "adjust_zoom")
    @LLMDescription("调整变焦。zoom: 0.5~10.0，1.0=不变，2.0=放大两倍。")
    suspend fun adjustZoom(
        @LLMDescription("0.5~10.0") zoom: Double
    ): String = dispatchCommand(
        AgentCommand.AdjustZoom(zoomRatio = zoom.toFloat().coerceAtLeast(0.5f))
    )

    // ── 通用 ──────────────────────────────────────────────────────

    @Tool(customName = "delay")
    @LLMDescription("等待指定毫秒后再执行后续操作（与其他工具组合，如延时拍照）。delay_ms 1~300000。")
    suspend fun delay(
        @LLMDescription("延迟毫秒") delayMs: Long
    ): String = dispatchCommand(AgentCommand.Delay(delayMs = delayMs.coerceIn(1, 300000)))

    @Tool(customName = "finish")
    @LLMDescription("任务完成时调用，提供完成摘要给用户。")
    fun finish(
        @LLMDescription("给用户的完成摘要") summary: String
    ): String = summary

    // ── 内部：命令分发（CapabilityRegistry，scene=CAMERA）────────────

    private fun currentAgentContext(): AgentContext = AgentContext(
        scene = AgentScene.CAMERA,
        beautySettings = beautySettingsProvider?.invoke() ?: BeautySettings(),
        traceId = traceIdHolder?.value
    )

    private suspend fun dispatchCommand(command: AgentCommand): String {
        return try {
            // 结构化等待（替代 future{}.get(5s) 阻塞桥）：超时经协程取消级联终止底层 dispatch。
            val result = withTimeout(DISPATCH_TIMEOUT_MS) {
                CapabilityRegistry.getInstance()
                    .dispatch(command, currentAgentContext(), null)
            }
            result.fold(
                onSuccess = { action ->
                    // UI 通道：把原始 AgentAction 发给 app 侧渲染/提示
                    uiActions.tryEmit(action)
                    // LLM observation：基于真实执行结果生成
                    when (action) {
                        is AgentAction.TextReply -> action.message
                        is AgentAction.Success -> "OK"
                        is AgentAction.Error -> "Error: ${action.message}"
                        else -> "OK: ${action::class.simpleName}"
                    }
                },
                onFailure = { "Error: ${it.message}" },
            )
        } catch (e: TimeoutCancellationException) {
            // 等待 dispatch 5s 超时（语义对齐旧 java.util.concurrent.TimeoutException 分支）：
            // withTimeout 已级联取消底层 dispatch 协程，无协程裸跑。
            // 记调用方视角的等待超时，二者可经 traceId 关联。
            Logger.w(tag, "dispatchCommand wait timed out: ${command::class.simpleName}")
            CommandExecutor.recordDispatchEvent(
                capability = "(camera_tool)",
                commandType = AgentCommand.getMethodName(command),
                success = false,
                errorCode = CommandExecutor.ERROR_CODE_TIMEOUT,
                errorMessage = "dispatch wait timed out after 5s",
                traceId = traceIdHolder?.value
            )
            "Error: ${e.message}"
        } catch (e: CancellationException) {
            // 外部取消（agent cancel）：结构化并发要求透传，不吞为错误字符串。
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "dispatchCommand failed: ${command::class.simpleName}: ${e.message}")
            "Error: ${e.message}"
        }
    }

    // ── 滤镜/风格名解析（自 ToolCallCommandParser 随迁：Koog 下工具方法直接拿类型化参数，
    //    parser 往返已消除，中文别名映射保留在唯一使用方本类内）──────────────────────

    private fun resolveFilterType(name: String): FilterType {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> FilterType.NONE
            "LEICA_CLASSIC", "徕卡经典", "徕卡经典滤镜" -> FilterType.LEICA_CLASSIC
            "LEICA_VIBRANT", "VIBRANT", "LEICA_VIVID", "VIVID", "徕卡鲜艳", "徕卡鲜艳滤镜" -> FilterType.LEICA_VIBRANT
            "LEICA_BW", "BW", "BLACK_WHITE", "LEICA_MONOCHROME", "MONOCHROME", "徕卡黑白", "徕卡黑白滤镜" -> FilterType.LEICA_BW
            "FILM_GOLD", "胶片金", "胶片金滤镜" -> FilterType.FILM_GOLD
            "FILM_FUJI", "胶片富士", "富士", "胶片富士滤镜" -> FilterType.FILM_FUJI
            "VINTAGE", "RETRO", "OLD", "复古", "怀旧" -> FilterType.VINTAGE
            "COOL", "COLD", "冷色", "冷色调", "冷色滤镜", "冷调", "冷调滤镜", "冷滤镜" -> FilterType.COOL
            "WARM", "暖色", "暖色调", "暖色滤镜", "暖调", "暖调滤镜", "暖滤镜" -> FilterType.WARM
            else -> runCatching { FilterType.valueOf(normalized) }.getOrDefault(FilterType.NONE)
        }
    }

    private fun resolveStyleFilter(name: String): StyleFilter {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> StyleFilter.NONE
            "TOON", "CARTOON", "COMIC" -> StyleFilter.TOON
            "SKETCH" -> StyleFilter.SKETCH
            "POSTERIZE", "POSTER" -> StyleFilter.POSTERIZE
            "EMBOSS" -> StyleFilter.EMBOSS
            "CROSSHATCH", "CROSS_HATCH" -> StyleFilter.CROSSHATCH
            else -> runCatching { StyleFilter.valueOf(normalized) }.getOrDefault(StyleFilter.NONE)
        }
    }
}
