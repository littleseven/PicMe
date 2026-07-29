package com.mamba.picme.agent.core.inference.remote

import com.mamba.model.chat.ChatModel
import com.mamba.model.chat.StreamingChatModel
import com.mamba.model.chat.request.ChatRequest
import com.mamba.model.chat.request.ChatRequestParameters
import com.mamba.model.chat.response.ChatResponse
import com.mamba.model.chat.response.PartialResponse
import com.mamba.model.chat.response.PartialResponseContext
import com.mamba.model.chat.response.StreamingChatResponseHandler
import com.mamba.model.chat.response.StreamingHandle
import com.mamba.picme.agent.core.platform.logging.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 「同步外观、流式内核」的 ChatModel 适配器。
 *
 * 内部持有流式 [StreamingChatModel]（SSE），对外实现同步 [ChatModel] 接口：
 * [doChat] 用 [CountDownLatch] 阻塞等待流式完成，返回完整 [ChatResponse]
 * （含组装好的 toolCalls，AiServices 据此继续工具循环，行为与原同步模型一致）。
 *
 * 流式过程中把逐 token 增量旁路给可注入的 [StreamListener]：
 * - [StreamListener.onTextSnapshot] 携带**本轮累计全文**（非 delta），UI 直接替换气泡内容，
 *   避免乱序累积问题；每轮（每次 [doChat] 调用）从空重新累计。
 * - [StreamListener.onRoundFinished] 在一轮流式结束时回调，携带完整 [ChatResponse]；
 *   `response.aiMessage().hasToolExecutionRequests()` 为 true 表示本轮产出 tool_calls、
 *   即将进入端侧工具执行（供 UI 切换"正在调用工具"状态）。
 *
 * 未注入监听器时行为与原同步模型完全一致（对 RemoteReActAgent 之外的使用方零影响）。
 *
 * [fallbackModel]：可选降级模型。某轮流式在任何内容吐出前失败（典型：网关/上游
 * 不支持 stream=true）时，该轮降级为 [fallbackModel] 的同步调用，保证对话可用
 * （退化为一次性返回）；已吐出部分内容则无法干净重试，错误原样上抛。
 *
 * 监听器事件模型（listeners()）不外溢：本类 `listeners()` 返回空列表，
 * ChatModelListener 的 onRequest/onResponse/onError 仅由内部流式模型触发一次，
 * 避免双重记录（LlmCallRecord / token usage 累加）。
 */
class StreamingSyncChatModel(
    private val streamingModel: StreamingChatModel,
    private val fallbackModel: ChatModel? = null,
    private val roundTimeoutMs: Long = DEFAULT_ROUND_TIMEOUT_MS
) : ChatModel {

    companion object {
        private const val TAG = "StreamingSyncChatModel"

        /** 单轮流式等待上限（防死等）。OkHttp 层超时（60s）通常先触发 onError。 */
        const val DEFAULT_ROUND_TIMEOUT_MS = 180_000L
    }

    /** 流式旁路监听器；由 RemoteReActAgent 在任务执行前注入、结束后清空。 */
    interface StreamListener {
        /** 本轮累计文本快照（从空开始累计），每次模型吐出新 token 时回调。 */
        fun onTextSnapshot(snapshot: String)

        /**
         * 一轮流式结束，携带完整响应（含组装好的 toolCalls）。
         * `response.aiMessage()?.hasToolExecutionRequests() == true` 表示将进入工具执行。
         */
        fun onRoundFinished(response: ChatResponse)
    }

    @Volatile
    private var streamListener: StreamListener? = null

    fun setStreamListener(listener: StreamListener?) {
        streamListener = listener
    }

    // 请求参数（modelName / temperature / tool specs 合并）必须沿用底层模型的
    // OpenAiChatRequestParameters，否则 OpenAiStreamingChatModel.doChat 强转失败。
    override fun defaultRequestParameters(): ChatRequestParameters =
        streamingModel.defaultRequestParameters()

    // listeners() 刻意不委托：保持默认空列表，事件仅由内部流式模型上报一次（见类注释）。
    override fun provider() = streamingModel.provider()

    override fun supportedCapabilities() = streamingModel.supportedCapabilities()

    override fun doChat(chatRequest: ChatRequest): ChatResponse {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<ChatResponse>()
        val errorRef = AtomicReference<Throwable>()
        val handleRef = AtomicReference<StreamingHandle>()
        val listener = streamListener
        // 本轮累计缓冲：每次 doChat（= 一轮）新建，天然实现"新一轮从空开始累计"。
        val accumulated = StringBuilder()

        streamingModel.chat(chatRequest, object : StreamingChatResponseHandler {
            override fun onPartialResponse(partialResponse: PartialResponse, context: PartialResponseContext) {
                handleRef.compareAndSet(null, context.streamingHandle())
                val text = partialResponse.text()
                if (!text.isNullOrEmpty()) {
                    accumulated.append(text)
                    listener?.onTextSnapshot(accumulated.toString())
                }
            }

            override fun onCompleteResponse(completeResponse: ChatResponse) {
                resultRef.set(completeResponse)
                listener?.onRoundFinished(completeResponse)
                latch.countDown()
            }

            override fun onError(error: Throwable) {
                errorRef.set(error)
                latch.countDown()
            }
        })

        try {
            if (!latch.await(roundTimeoutMs, TimeUnit.MILLISECONDS)) {
                Logger.e(TAG, "Streaming round timeout after ${roundTimeoutMs}ms, cancelling SSE")
                handleRef.get()?.cancel()
                throw RuntimeException("流式响应超时（${roundTimeoutMs / 1000}秒）")
            }
        } catch (e: InterruptedException) {
            // 调用线程被中断（如协程取消）：尽力取消底层 SSE 流并中断等待。
            handleRef.get()?.cancel()
            Thread.currentThread().interrupt()
            throw RuntimeException("流式等待被中断", e)
        }

        errorRef.get()?.let { error ->
            // 本轮尚未吐出任何内容时流式失败（典型：网关/上游拒绝 stream=true），
            // 降级为同步调用保证对话可用；已吐部分内容则无法干净重试，原样抛错。
            val fallback = fallbackModel
            if (fallback != null && accumulated.isEmpty()) {
                Logger.w(TAG, "Streaming round failed before any content, fallback to sync: ${error.message}")
                return fallback.chat(chatRequest)
            }
            throw error
        }
        return resultRef.get() ?: throw IllegalStateException("流式结束但未收到响应")
    }
}
