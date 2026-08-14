package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.shared.FlowWatcher
import com.mamba.picme.shared.watch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Swift ↔ Kotlin chat 桥（Phase 6.2 T5）。
 *
 * signal 6 纪律（kmp-ios-interop 铁律 1-3）：
 * - **非 suspend**：所有方法返回 Unit（void），watcher 生命周期由本类内部管理；
 *   Swift 经 [cancelCurrent] 取消推理，经 [watchUiActions] 拿 FlowWatcher（单参数方法 K/N 不丢返回类型）。
 * - **try/catch(Throwable) 全兜**：CancellationException 语义保留（重新抛出不吞），
 *   其余异常在 Kotlin 侧吞掉，经 `onComplete(errorMessage)` 回传 Swift——
 *   未声明 @Throws 的异常逃逸到 Swift 会 signal 6 (SIGABRT)。
 * - **回调线程**：onText/onToolCall/onAction 可在任意调度器线程触发；
 *   Swift 侧必须在 `Task { @MainActor in }` 内更新 UI（漏一处即 UI 线程违规）。
 *
 * @param orchestrator 已初始化的 [AgentOrchestrator] 实例
 * @param initialSessionId Koog 记忆 session id 初值，默认 "default"；
 *   多会话切换经 [setSessionId]（对齐 KoogReActAgent 语义：换 memory ID，历史按 sessionId 分键持久化）。
 */
class ChatAgentBridge(
    private val orchestrator: AgentOrchestrator,
    initialSessionId: String = DEFAULT_SESSION_ID
) {
    private val tag = "ChatAgentBridge"

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentJob: Job? = null

    /** 当前会话的 memory ID。Swift 侧切会话时经 [setSessionId] 切换。 */
    private var sessionId: String = initialSessionId

    /** 切换会话：换 memory ID。历史按 sessionId 分键持久化，无需重建 bridge。空白 id 忽略。 */
    fun setSessionId(id: String) {
        if (id.isNotBlank()) sessionId = id
    }

    /**
     * 发送消息（启动流式远程推理）。返回 void（K/N 多参数方法丢返回类型，
     * watcher 生命周期内部管理，Swift 经 [cancelCurrent] 取消）。
     */
    fun sendMessage(
        input: String,
        onText: (String) -> Unit,
        onToolCall: () -> Unit,
        onComplete: (summary: String, errorMessage: String?) -> Unit
    ) {
        currentJob = bridgeScope.launch {
            try {
                val context = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId
                )
                val result = orchestrator.remoteChatEngine.streamChat(
                    input = input,
                    agentContext = context,
                    onEvent = { event ->
                        when (event) {
                            is ChatStreamEvent.TextSnapshot -> onText(event.text)
                            is ChatStreamEvent.ToolCallStarted -> onToolCall()
                        }
                    }
                )
                result.fold(
                    onSuccess = { streamResult ->
                        onComplete(streamResult.fullResponse, null)
                    },
                    onFailure = { e ->
                        Logger.e(tag, "sendMessage failed", e)
                        onComplete("", e.message ?: "未知错误")
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e(tag, "sendMessage exception", e)
                onComplete("", e.message ?: "未知错误")
            }
        }
    }

    /**
     * 订阅 chat 工具执行产出的 UI 动作（媒体卡片 / 文本提示）。
     * Swift 侧在页面出现时调用并持有 watcher，页面消失时 cancel。
     *
     * @return [FlowWatcher]（单参数方法，K/N 导出保留返回类型）
     */
    fun watchUiActions(onAction: (ChatUiActionDto) -> Unit): FlowWatcher {
        val flow = ChatToolService.getInstance().uiActions
        return flow.watch { action ->
            val dto = ChatUiActionDto.from(action)
            if (dto != null) {
                onAction(dto)
            }
        }
    }

    /**
     * 调试触发：直接经 [CapabilityRegistry] 派发 [AgentCommand.DrawChart]（绕过远程 LLM），
     * 跑通完整触发链（IosChartCapability → IosChartBridge → ChartJsEngine → onChart → 图卡）。
     *
     * 用于确定性验证 draw_chart 接线（不依赖访客模型是否真正发起 tool_call）。
     * 渲染产物（SVG）经 IosChartBridge 的 Swift 侧通道回到 ChatViewModel；summary 经 [onComplete]。
     */
    fun dispatchDrawChart(
        type: String,
        title: String,
        labels: List<String>,
        valuesCsv: String,
        unit: String?,
        onComplete: (summary: String, errorMessage: String?) -> Unit
    ) {
        bridgeScope.launch {
            try {
                val values = valuesCsv.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                val command = AgentCommand.DrawChart(
                    type = type, title = title, labels = labels, values = values, unit = unit
                )
                val context = AgentContext(scene = AgentScene.CHAT, memorySessionId = sessionId)
                val result = withTimeout(DISPATCH_TIMEOUT_MS) {
                    CapabilityRegistry.getInstance().dispatch(command, context, null)
                }
                result.fold(
                    onSuccess = { action ->
                        val summary = (action as? AgentAction.TextReply)?.message ?: "图表已生成"
                        onComplete(summary, null)
                    },
                    onFailure = { e ->
                        Logger.w(tag, "dispatchDrawChart failed: ${e.message}")
                        onComplete("", e.message ?: "dispatch failed")
                    }
                )
            } catch (e: TimeoutCancellationException) {
                Logger.w(tag, "dispatchDrawChart timed out")
                onComplete("", "dispatch timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(tag, "dispatchDrawChart exception", e)
                onComplete("", e.message ?: "未知错误")
            }
        }
    }

    /**
     * 清空指定会话的对话记忆（Koog koog_memory_<sessionId> 键空间）。返回 void。
     * 显式 sessionId 参数：删除非当前会话时也能清其记忆（K/N 不导出默认参数）。
     */
    fun clearHistory(sessionId: String, onDone: () -> Unit) {
        bridgeScope.launch {
            try {
                orchestrator.clearChatMemory(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e(tag, "clearHistory exception", e)
            } finally {
                onDone()
            }
        }
    }

    /** 清空当前会话的对话记忆（顶栏「清空对话」语义）。 */
    fun clearCurrentHistory(onDone: () -> Unit) = clearHistory(sessionId, onDone)

    /** 当前是否有推理在进行（Swift 侧串行发送守卫）。 */
    fun isRunning(): Boolean = currentJob?.isActive == true

    /** 取消当前推理（Swift 侧 stop 按钮调用）。幂等：无推理进行时无操作。 */
    fun cancelCurrent() {
        currentJob?.cancel()
        currentJob = null
    }

    companion object {
        const val DEFAULT_SESSION_ID = "default"
        private const val DISPATCH_TIMEOUT_MS = 5000L
    }
}
