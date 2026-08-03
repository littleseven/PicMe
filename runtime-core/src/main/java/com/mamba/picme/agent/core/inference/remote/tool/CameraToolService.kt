package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.picme.agent.core.inference.remote.log.TraceIdHolder
import com.mamba.picme.agent.core.inference.remote.parser.ToolCallCommandParser
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.capability.CommandExecutor
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.tool.P
import com.mamba.tool.Tool
import com.mamba.tool.ToolExecutionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.future.future
import java.util.concurrent.TimeUnit

/**
 * 相机场景专用 ToolService（远程 tool_calls，ADR-005 协议分离）。
 *
 * 端侧文本 LLM 移除后，相机 AI 指令改为远程模型 tool_calls：模型输出标准 OpenAI tool_calls，
 * 本类的 @Tool 方法把相机场景 capability（拍照/录像/美颜/滤镜/变焦/曝光/翻转等）暴露给模型，
 * 命令统一进 [CapabilityRegistry]（scene=CAMERA），由页面级 CameraCapability 执行——
 * 与 [ChatToolService]（scene=CHAT）同一范式，写操作复用既有 CommandRisk/确认机制，不新造协议。
 *
 * 命令解析复用 [ToolCallCommandParser]（tool_calls → AgentCommand），保证与远程协议严格一致；
 * 仅 `adjust_beauty` 因支持 enabled 开关与「未提及参数保持当前值」语义，在本类内直接构建命令
 * （当前美颜值经 [beautySettingsProvider] 由 app 注入，runtime-core 不依赖 app 的 CameraCapability）。
 *
 * **重要**：@Tool 参数**不能用 Kotlin 默认值**（同 [ChatToolService] 的反射约束）。可选语义
 * 用空串表示「不调整」，由 @P 描述说明。
 */
class CameraToolService private constructor() {

    companion object {
        @Volatile
        private var instance: CameraToolService? = null
        fun getInstance(): CameraToolService =
            instance ?: synchronized(this) {
                instance ?: CameraToolService().also { instance = it }
            }
    }

    private val tag = "CameraToolService"

    /**
     * dispatch 常驻内部 scope（SupervisorJob 隔离单命令失败）。替代 GlobalScope：
     * 等待超时后通过 deferred.cancel() 级联取消底层 dispatch 协程，避免协程裸跑。
     */
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** UI 事件流：dispatchCommand 执行后的原始 AgentAction 发到此 flow，供 app 侧渲染/提示。 */
    val uiActions = MutableSharedFlow<AgentAction>(extraBufferCapacity = 16)

    /**
     * 当轮 traceId 持有器：由 [com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgent]
     * 每轮任务开始时写入，dispatchCommand 读取后注入 AgentContext，与 LLM 调用日志关联。
     */
    @Volatile
    var traceIdHolder: TraceIdHolder? = null

    /**
     * 当前美颜设置供给者（由 app 相机页注入，读取 CameraCapability.beautySettings）。
     * 未注入时 adjust_beauty 以默认设置为基线。
     */
    @Volatile
    var beautySettingsProvider: (() -> BeautySettings)? = null

    // ── 拍摄 ──────────────────────────────────────────────────────

    @Tool(name = "capture", value = ["拍照。用户说「拍照」「拍一张」「咔嚓」时使用；延时拍摄用 delay + capture 组合。"])
    fun capture(): String = dispatchTool("capture", "{}")

    @Tool(name = "toggle_recording", value = ["开始/停止录像（开关式）。"])
    fun toggleRecording(): String = dispatchTool("toggle_recording", "{}")

    @Tool(name = "flip_camera", value = ["翻转前后摄像头。用户说「切前置」「换摄像头」时使用。"])
    fun flipCamera(): String = dispatchTool("flip_camera", "{}")

    @Tool(name = "switch_mode", value = ["切换拍摄模式。mode: PHOTO(拍照)/VIDEO(录像)/PRO(专业)/DOCUMENT(文档)。"])
    fun switchMode(
        @P(name = "mode", value = "PHOTO/VIDEO/PRO/DOCUMENT") mode: String
    ): String = dispatchTool("switch_mode", stringArgsJson("mode" to mode))

    // ── 美颜 / 滤镜 / 风格 ─────────────────────────────────────────

    @Tool(
        name = "adjust_beauty",
        value = ["调整美颜参数。enabled: true 开美颜 / false 关美颜 / 空串=有调整参数时自动开启。smoothing(磨皮)/whitening(美白)/big_eyes(大眼)/lip_color(唇色)/blush(腮红)/eyebrow(眉毛) 0~100，slim_face(瘦脸) -50~50；未提及的参数留空串保持当前值。"]
    )
    fun adjustBeauty(
        @P(name = "enabled", value = "true/false，留空=有调整参数时自动开启") enabled: String,
        @P(name = "smoothing", value = "磨皮 0~100，留空=不变") smoothing: String,
        @P(name = "whitening", value = "美白 0~100，留空=不变") whitening: String,
        @P(name = "slim_face", value = "瘦脸 -50~50，留空=不变") slimFace: String,
        @P(name = "big_eyes", value = "大眼 0~100，留空=不变") bigEyes: String,
        @P(name = "lip_color", value = "唇色 0~100，留空=不变") lipColor: String,
        @P(name = "blush", value = "腮红 0~100，留空=不变") blush: String,
        @P(name = "eyebrow", value = "眉毛 0~100，留空=不变") eyebrow: String
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

    @Tool(name = "switch_filter", value = ["切换滤镜。filter: NONE/LEICA_CLASSIC/LEICA_VIBRANT/LEICA_BW/FILM_GOLD/FILM_FUJI/VINTAGE/COOL/WARM，中文名（如「徕卡经典」「胶片金」「冷调」）亦可。"])
    fun switchFilter(
        @P(name = "filter", value = "滤镜名") filter: String
    ): String = dispatchTool("switch_filter", stringArgsJson("filter" to filter))

    @Tool(name = "switch_style", value = ["切换风格特效。style: NONE/TOON(卡通)/SKETCH(素描)/POSTERIZE(海报)/EMBOSS(浮雕)/CROSSHATCH(交叉线)。"])
    fun switchStyle(
        @P(name = "style", value = "风格名") style: String
    ): String = dispatchTool("switch_style", stringArgsJson("style" to style))

    // ── 取景参数 ──────────────────────────────────────────────────

    @Tool(name = "switch_scene", value = ["切换场景模式。scene: night(夜景)/moon(月亮)/none(关闭)。"])
    fun switchScene(
        @P(name = "scene", value = "night/moon/none") scene: String
    ): String = dispatchTool("switch_scene", stringArgsJson("scene" to scene))

    @Tool(name = "switch_ratio", value = ["切换画幅比例。ratio: 4:3 / 16:9 / full(全屏)。"])
    fun switchRatio(
        @P(name = "ratio", value = "4:3/16:9/full") ratio: String
    ): String = dispatchTool("switch_ratio", stringArgsJson("ratio" to ratio))

    @Tool(name = "adjust_exposure", value = ["调整曝光补偿。exposure: -2~2 整数，正值调亮、负值调暗。"])
    fun adjustExposure(
        @P(name = "exposure", value = "-2~2") exposure: Long
    ): String = dispatchTool("adjust_exposure", """{"exposure":$exposure}""")

    @Tool(name = "adjust_zoom", value = ["调整变焦。zoom: 0.5~10.0，1.0=不变，2.0=放大两倍。"])
    fun adjustZoom(
        @P(name = "zoom", value = "0.5~10.0") zoom: Double
    ): String = dispatchTool("adjust_zoom", """{"zoom":$zoom}""")

    // ── 通用 ──────────────────────────────────────────────────────

    @Tool(name = "delay", value = ["等待指定毫秒后再执行后续操作（与其他工具组合，如延时拍照）。delay_ms 1~300000。"])
    fun delay(
        @P(name = "delay_ms", value = "延迟毫秒") delayMs: Long
    ): String = dispatchTool("delay", """{"delay_ms":$delayMs}""")

    @Tool(name = "finish", value = ["任务完成时调用，提供完成摘要给用户。"])
    fun finish(
        @P(name = "summary", value = "给用户的完成摘要") summary: String
    ): String = summary

    /**
     * langchain4j AiServices 约定的统一工具入口（解析 argsJson + 分发到 @Tool 方法）。
     * 与 [ChatToolService.callTool] 同约：必须实现，否则 AiServices fallback 不支持带参调用。
     */
    fun callTool(toolName: String, argsJson: String): String {
        val args = try {
            org.json.JSONObject(argsJson)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        return when (toolName) {
            "capture" -> capture()
            "toggle_recording" -> toggleRecording()
            "flip_camera" -> flipCamera()
            "switch_mode" -> switchMode(args.optString("mode", "PHOTO"))
            "adjust_beauty" -> adjustBeauty(
                args.optString("enabled", ""),
                args.optString("smoothing", ""),
                args.optString("whitening", ""),
                args.optString("slim_face", ""),
                args.optString("big_eyes", ""),
                args.optString("lip_color", ""),
                args.optString("blush", ""),
                args.optString("eyebrow", "")
            )
            "switch_filter" -> switchFilter(args.optString("filter", "NONE"))
            "switch_style" -> switchStyle(args.optString("style", "NONE"))
            "switch_scene" -> switchScene(args.optString("scene", "none"))
            "switch_ratio" -> switchRatio(args.optString("ratio", "full"))
            "adjust_exposure" -> adjustExposure(args.optLong("exposure", 0))
            "adjust_zoom" -> adjustZoom(args.optDouble("zoom", 1.0))
            "delay" -> delay(args.optLong("delay_ms", 1000))
            "finish" -> finish(args.optString("summary", "任务完成"))
            else -> "Error: Unknown tool: $toolName"
        }
    }

    // ── 内部：命令解析与分发（复用 ToolCallCommandParser + CapabilityRegistry，scene=CAMERA）────

    /** 字符串参数安全拼 JSON（LLM 输出不受控，禁字面量插值防注入）。 */
    private fun stringArgsJson(vararg pairs: Pair<String, String>): String =
        org.json.JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    /** 经 [ToolCallCommandParser] 把 tool_call 解析为 [AgentCommand] 后分发（ADR-005 标准协议）。 */
    private fun dispatchTool(toolName: String, argsJson: String): String {
        val command = try {
            val request = ToolExecutionRequest.builder()
                .name(toolName)
                .arguments(argsJson)
                .build()
            ToolCallCommandParser.parse(request, currentAgentContext())
        } catch (e: Exception) {
            Logger.w(tag, "parse tool call failed: $toolName($argsJson): ${e.message}")
            return "Error: ${e.message}"
        }
        return dispatchCommand(command)
    }

    private fun currentAgentContext(): AgentContext = AgentContext(
        scene = AgentScene.CAMERA,
        beautySettings = beautySettingsProvider?.invoke() ?: BeautySettings(),
        traceId = traceIdHolder?.value
    )

    private fun dispatchCommand(command: AgentCommand): String {
        val deferred = dispatchScope.future {
            CapabilityRegistry.getInstance()
                .dispatch(command, currentAgentContext(), null)
        }
        return try {
            val result = deferred.get(5, TimeUnit.SECONDS)
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
        } catch (e: java.util.concurrent.TimeoutException) {
            // 等待 dispatch 5s 超时：取消底层 dispatch 协程，避免超时后协程裸跑
            // （CompletableFuture.cancel 会级联取消 future 协程）。
            deferred.cancel(true)
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
        } catch (e: Exception) {
            Logger.w(tag, "dispatchCommand failed: ${command::class.simpleName}: ${e.message}")
            "Error: ${e.message}"
        }
    }
}
