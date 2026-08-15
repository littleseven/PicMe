package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.data.IosRunScriptBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * iOS Chat「执行端侧 JS 脚本」Capability —— `run_gallery_script` 命令的 iOS 执行端。
 *
 * 在 CHAT 场景接收 [AgentCommand.ExecuteScript]，委托 [bridge]（Swift `RunScriptBridge`：
 * `JsRuntime` + `JsCoreEngine`：JavaScriptCore + gallery 只读 handler）端侧执行脚本，
 * 结果（JSON 文本）经 completion 回传，作为 [AgentAction.TextReply] 的 message 回传远程 LLM
 * 做文字总结（对齐 Android `ChatRunScriptCapability.Delegate.onRunScript` 的语义）。
 *
 * 路由：`CapabilityRegistry.findCapabilityForCommand` 按 `supportedCommands()` 匹配——
 * 本能力仅声明 `run_gallery_script`，与 [IosChatGalleryCapability]（相册媒体命令）、
 * [IosChartCapability]（draw_chart）无冲突，可同场景共存。
 *
 * [PRIVACY]：脚本仅触发端侧只读盘点 handler（gallery.summary/tags、tag.scan_status），
 * 结果只含计数/标签聚合，无媒体上传。
 */
class IosRunScriptCapability(
    private val bridge: IosRunScriptBridge? = null
) : BaseCapability() {

    private val tag = "PoLang:IosRunScriptCapability"

    override val name: String = "ios_run_script"
    override val description: String = "端侧沙箱执行 JS 脚本（相册盘点/统计，取数只读）"

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf(COMMAND_RUN_GALLERY_SCRIPT)

    override fun isAvailable(): Boolean = bridge != null

    override fun getCommandDescription(command: String): String = when (command) {
        COMMAND_RUN_GALLERY_SCRIPT -> "执行 JS 脚本（端侧沙箱，相册盘点/统计）。参数: code (string, JS 源码)。" +
            "脚本用 await bridge.callAsync(name, {}) 取数：" +
            "'gallery.summary'(相册总览)|'gallery.tags'(标签清单)|'tag.scan_status'(扫描状态)，" +
            "在 JS 内组合计算后 return 结果（作为 observation 回传做文字总结）。"
        else -> super.getCommandDescription(command)
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> = try {
        when (command) {
            is AgentCommand.ExecuteScript -> handleRunScript(command)
            else -> Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.METHOD_NOT_FOUND,
                    "IosRunScriptCapability 不支持此命令"
                )
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(tag, "run_gallery_script failed", e)
        Result.success(
            AgentAction.Error(
                command.commandId,
                AgentErrorCode.INTERNAL_ERROR,
                "脚本执行失败：${e.message ?: "未知错误"}"
            )
        )
    }

    private suspend fun handleRunScript(command: AgentCommand.ExecuteScript): Result<AgentAction> {
        val runner = bridge
            ?: return Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    "脚本执行暂不可用（执行桥未注入）"
                )
            )
        val result = awaitRunScript(bridge = runner, code = command.code)
        return Result.success(AgentAction.TextReply(command.commandId, result))
    }

    /**
     * completion 回调转 suspend（对齐 [IosChartCapability.awaitRender] 范式）。
     * 异常绝不逃逸出 Kotlin 边界（signal 6 铁律）——桥异常时回传兜底错误文案。
     */
    private suspend fun awaitRunScript(bridge: IosRunScriptBridge, code: String): String =
        suspendCancellableCoroutine { cont ->
            try {
                bridge.runScript(code) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            } catch (t: Throwable) {
                Logger.e(tag, "runScript bridge threw", t)
                if (cont.isActive) cont.resume("脚本执行失败：${t.message ?: "未知错误"}")
            }
        }

    companion object {
        private const val COMMAND_RUN_GALLERY_SCRIPT = "run_gallery_script"
    }
}
