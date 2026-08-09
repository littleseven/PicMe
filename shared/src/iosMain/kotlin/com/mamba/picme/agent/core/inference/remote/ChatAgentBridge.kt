package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.shared.FlowWatcher
import com.mamba.picme.shared.watch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Swift ↔ Kotlin chat 桥（Phase 6.2 T5）。
 *
 * signal 6 纪律（kmp-ios-interop 铁律 1-3）：
 * - **非 suspend**：所有方法返回 [FlowWatcher]（Swift 持有并在离开时 `cancel()`），
 *   不跨边界 expose suspend 函数（K/N ObjC 导出 suspend → completion handler，
 *   Swift 侧 GCD/Task 交织易引发线程安全或取消级联问题）。
 * - **try/catch(Throwable) 全兜**：CancellationException 语义保留（重新抛出不吞），
 *   其余异常在 Kotlin 侧吞掉，经 `onComplete(errorMessage)` 回传 Swift——
 *   未声明 @Throws 的异常逃逸到 Swift 会 signal 6 (SIGABRT)。
 * - **回调线程**：onText/onToolCall/onAction 可在任意调度器线程触发；
 *   Swift 侧必须在 `Task { @MainActor in }` 内更新 UI（漏一处即 UI 线程违规）。
 *
 * 设计说明：
 * - `sendMessage` 启动协程跑 [RemoteChatEngine.streamChat]，流式事件经回调闭包实时上报；
 *   返回 [FlowWatcher]（内含 Job），Swift 可 `cancel()` 取消当前推理。
 * - `watchUiActions` 订阅 [ChatToolService.uiActions]（SharedFlow），工具执行产出
 *   [ChatUiActionDto] 经回调上报（媒体卡片 / 文本提示）。
 * - `clearHistory` 清空 Koog 记忆层（`koog_memory_default` 键空间）。
 * - `isRunning` 供 Swift 侧串行发送守卫（同一时刻只跑一轮推理）。
 *
 * @param orchestrator 已初始化的 [AgentOrchestrator] 实例
 * @param sessionId Koog 记忆 session id，默认 "default"（单会话版）
 */
class ChatAgentBridge(
    private val orchestrator: AgentOrchestrator,
    private val sessionId: String = DEFAULT_SESSION_ID
) {
    private val tag = "ChatAgentBridge"

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentJob: Job? = null

    /**
     * 发送消息（启动流式远程推理）。
     *
     * @param input 用户文本输入
     * @param onText 模型本轮累计文本快照回调（非 delta，整体替换）
     * @param onToolCall 工具调用开始回调（可触发「正在搜索…」状态文案）
     * @param onComplete 推理结束回调：summary 为最终文本，errorMessage 非空表示出错
     * @return [FlowWatcher]，Swift 持有并可在离开时 cancel 中止推理
     */
    fun sendMessage(
        input: String,
        onText: (String) -> Unit,
        onToolCall: () -> Unit,
        onComplete: (summary: String, errorMessage: String?) -> Unit
    ): FlowWatcher {
        val job = bridgeScope.launch {
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
        currentJob = job
        return FlowWatcher(job)
    }

    /**
     * 订阅 chat 工具执行产出的 UI 动作（媒体卡片 / 文本提示）。
     * Swift 侧在页面出现时调用并持有 watcher，页面消失时 cancel。
     *
     * @param onAction 回调，参数为 Swift 安全的 [ChatUiActionDto]
     * @return [FlowWatcher]
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
     * 清空对话记忆（Koog koog_memory_<sessionId> 键空间）。
     *
     * @param onDone 完成回调（成功或失败均调用，Swift 侧刷新 UI）
     * @return [FlowWatcher]
     */
    fun clearHistory(onDone: () -> Unit): FlowWatcher {
        val job = bridgeScope.launch {
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
        return FlowWatcher(job)
    }

    /**
     * 当前是否有推理在进行（Swift 侧串行发送守卫）。
     */
    fun isRunning(): Boolean = currentJob?.isActive == true

    companion object {
        const val DEFAULT_SESSION_ID = "default"
    }
}
