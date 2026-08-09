package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.shared.FlowWatcher
import com.mamba.picme.shared.watch
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * Swift → chat 推理链路的桥（Phase 6.2 T5）。
 *
 * 回调式非 suspend API（K/N → ObjC 导出友好；suspend 函数导出为 completion-handler
 * 形式，SwiftUI 组合不如闭包直观）。所有回调在 Kotlin 后台调度器线程触发，
 * **Swift 侧须自行 dispatch 到 main queue 再更新 UI**。
 *
 * SharedBridge 铁律（kmp-ios-interop）：异常绝不跨边界逃逸——所有入口
 * try/catch(Throwable) 全兜（[CancellationException] 属正常取消语义，继续上抛）。
 *
 * 生产链路非流式（poLangSingleRunStrategy 走 execute 而非流式），[onText] 通常只在
 * 末帧到达一次；Swift 按「累计全文快照直接替换气泡内容」消费即可。
 */
class ChatAgentBridge(
    private val orchestrator: AgentOrchestrator,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var runningJob: Job? = null

    /** 当前是否有推理在进行（Swift 用于发送按钮置灰/loading 态）。 */
    fun isRunning(): Boolean = runningJob?.isActive == true

    /**
     * 发送用户消息。
     *
     * @param input 用户输入文本
     * @param onText 本轮累计全文快照（UI 直接替换气泡内容）
     * @param onToolCall 进入工具调用轮时触发（UI 显示「正在调用工具…」）
     * @param onComplete 完成回调：(fullResponse, errorMessage) 二选一——
     *   成功时 errorMessage 为 null；失败/并发冲突时 fullResponse 为空串、errorMessage 非空。
     */
    fun sendMessage(
        input: String,
        onText: (String) -> Unit,
        onToolCall: () -> Unit,
        onComplete: (String, String?) -> Unit
    ) {
        if (isRunning()) {
            onComplete("", "Agent 正在执行其他任务，请稍后")
            return
        }
        runningJob = scope.launch {
            try {
                val result = orchestrator.remoteChatEngine.streamChat(
                    input = input,
                    agentContext = AgentContext(scene = AgentScene.CHAT, memorySessionId = SESSION_ID),
                    onEvent = { event ->
                        when (event) {
                            is ChatStreamEvent.TextSnapshot -> onText(event.text)
                            is ChatStreamEvent.ToolCallStarted -> onToolCall()
                        }
                    }
                )
                result.fold(
                    onSuccess = { onComplete(it.fullResponse, null) },
                    onFailure = { onComplete("", it.message ?: "未知错误") },
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onComplete("", t.message ?: "未知错误")
            }
        }
    }

    /**
     * 订阅工具执行产出的 UI 动作（搜索结果卡片 / 文本回复 / 直通命令 / 错误）。
     *
     * @return [FlowWatcher]，Swift 退出 chat 页时 `cancel()` 防泄漏。
     */
    fun watchUiActions(onAction: (ChatUiActionDto) -> Unit): FlowWatcher =
        ChatToolService.getInstance().uiActions
            .mapNotNull { ChatUiActionDto.from(it) }
            .watch(onAction)

    /** 清空 chat 会话记忆（Koog 记忆 NSUserDefaults 键 + no-op 旧键空间清理）。 */
    fun clearHistory(onDone: () -> Unit) {
        scope.launch {
            try {
                orchestrator.clearChatMemory(SESSION_ID)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 清空失败不致命（下次加载以 store 现状为准），静默后继续回调
            }
            onDone()
        }
    }

    companion object {
        /** iOS v1 单会话 id（与 Android 默认 sessionId="chat" 对齐）。 */
        const val SESSION_ID = "chat"
    }
}
