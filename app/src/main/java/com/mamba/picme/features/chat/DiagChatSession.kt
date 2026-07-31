package com.mamba.picme.features.chat

import com.mamba.data.message.AiMessage
import com.mamba.data.message.ChatMessage
import com.mamba.data.message.SystemMessage
import com.mamba.data.message.UserMessage
import com.mamba.model.chat.StreamingChatModel
import com.mamba.model.chat.request.ChatRequest
import com.mamba.model.chat.response.ChatResponse
import com.mamba.model.chat.response.StreamingChatResponseHandler
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 诊断澄清对话会话（spec §2.1）：注入诊断 system prompt 的**普通**远程 LLM 多轮流式对话。
 * 不走 ReAct 工具循环（澄清只需对话）；历史在内存（会话级，进程杀后重开即新对话）。
 *
 * 复用现有流式通道：`RemoteModelFactory.createBuilder(...).buildStreaming()`
 * （与 RemoteReActAgent 同一套 temperature/maxTokens/DeepSeek thinking 禁用/网关认证约定）。
 */
class DiagChatSession(config: RemoteModelConfig) {

    private val model: StreamingChatModel =
        RemoteModelFactory.createBuilder(config, "diag").apply {
            // 官方模型走 PoLang Server 网关：X-App-Token 认证（同 RemoteReActAgent 约定）
            if (config.gatewayToken.isNotBlank()) customHeader("X-App-Token", config.gatewayToken)
        }.buildStreaming()

    private val history = mutableListOf<ChatMessage>(SystemMessage.from(DiagPrompts.SYSTEM_PROMPT))

    /**
     * 发送一轮用户消息，流式返回模型完整回复。
     * [onSnapshot] 携带本轮累计全文快照（非 delta），UI 直接整体替换气泡内容。
     */
    suspend fun chat(userText: String, onSnapshot: (String) -> Unit): Result<String> =
        withContext(Dispatchers.IO) {
            history += UserMessage.from(userText)
            val accumulated = StringBuilder()
            suspendCancellableCoroutine { cont ->
                model.chat(
                    ChatRequest.builder().messages(history.toList()).build(),
                    object : StreamingChatResponseHandler {
                        override fun onPartialResponse(partialResponse: String) {
                            accumulated.append(partialResponse)
                            onSnapshot(accumulated.toString())
                        }

                        override fun onCompleteResponse(completeResponse: ChatResponse) {
                            val text = completeResponse.aiMessage()?.text() ?: accumulated.toString()
                            history += AiMessage.from(text)
                            cont.resume(Result.success(text))
                        }

                        override fun onError(error: Throwable) {
                            // 失败时把本轮 user 消息移出历史，避免污染后续对话
                            if (history.lastOrNull() is UserMessage) history.removeAt(history.size - 1)
                            cont.resume(Result.failure(error))
                        }
                    },
                )
            }
        }
}
