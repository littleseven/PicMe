package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.data.IosChartBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * iOS Chat「画图表」Capability —— `draw_chart` 命令的 iOS 执行端。
 *
 * 在 CHAT 场景接收 [AgentCommand.DrawChart]，委托 [bridge]（Swift `ChartJsEngine`：
 * JavaScriptCore + chart_bootstrap.js）端侧渲染 SVG。渲染结果（SVG）经 Swift 自有通道
 * 交给 ChatViewModel 作 CHART 消息展示；[AgentAction.TextReply] 的 summary 回传远程 LLM
 * 做文字总结（对齐 Android `ChatRunScriptCapability.Delegate.onDrawChart` 的语义）。
 *
 * 路由：`CapabilityRegistry.findCapabilityForCommand` 按 `supportedCommands()` 匹配——
 * 本能力仅声明 `draw_chart`，与 [IosChatGalleryCapability]（相册命令）无冲突，可同场景共存。
 *
 * [PRIVACY]：draw_chart 数据来自远程 LLM（已聚合统计），端侧纯渲染，无媒体上传。
 */
class IosChartCapability(
    private val bridge: IosChartBridge? = null
) : BaseCapability() {

    private val tag = "PoLang:IosChartCapability"

    override val name: String = "ios_chart"
    override val description: String = "端侧渲染图表（柱状/折线/饼图）为图卡消息"

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf(COMMAND_DRAW_CHART)

    override fun isAvailable(): Boolean = bridge != null

    override fun getCommandDescription(command: String): String = when (command) {
        COMMAND_DRAW_CHART -> "画图表（柱状/折线/饼图）并端侧渲染成真实图卡。参数: type/title/labels/values/unit。"
        else -> super.getCommandDescription(command)
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> = try {
        when (command) {
            is AgentCommand.DrawChart -> handleDrawChart(command)
            else -> Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.METHOD_NOT_FOUND,
                    "IosChartCapability 不支持此命令"
                )
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(tag, "draw_chart failed", e)
        Result.success(
            AgentAction.Error(
                command.commandId,
                AgentErrorCode.INTERNAL_ERROR,
                "图表渲染失败：${e.message ?: "未知错误"}"
            )
        )
    }

    private suspend fun handleDrawChart(command: AgentCommand.DrawChart): Result<AgentAction> {
        val renderer = bridge
            ?: return Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    "图表渲染暂不可用（渲染桥未注入）"
                )
            )
        val summary = awaitRender(
            bridge = renderer,
            type = command.type,
            title = command.title,
            labels = command.labels,
            values = command.values,
            unit = command.unit
        )
        return Result.success(AgentAction.TextReply(command.commandId, summary))
    }

    /**
     * completion 回调转 suspend（对齐 [IosChatGalleryCapability.awaitEngineSearch] 范式）。
     * 异常绝不逃逸出 Kotlin 边界（signal 6 铁律）——桥异常时回退兜底 summary。
     */
    private suspend fun awaitRender(
        bridge: IosChartBridge,
        type: String,
        title: String,
        labels: List<String>,
        values: List<Double>,
        unit: String?
    ): String = suspendCancellableCoroutine { cont ->
        try {
            bridge.renderChart(type, title, labels, values, unit) { summary ->
                if (cont.isActive) cont.resume(summary)
            }
        } catch (t: Throwable) {
            Logger.e(tag, "renderChart bridge threw", t)
            if (cont.isActive) cont.resume(fallbackSummary(title, labels))
        }
    }

    private fun fallbackSummary(title: String, labels: List<String>): String =
        "图表「${title.ifBlank { "数据" }}」已生成（${labels.size} 个分类）"

    companion object {
        private const val COMMAND_DRAW_CHART = "draw_chart"
    }
}
