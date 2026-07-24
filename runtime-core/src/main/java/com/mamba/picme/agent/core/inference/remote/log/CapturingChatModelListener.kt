package com.mamba.picme.agent.core.inference.remote.log

import com.mamba.data.message.AiMessage
import com.mamba.data.message.ChatMessageSerializer
import com.mamba.model.chat.listener.ChatModelErrorContext
import com.mamba.model.chat.listener.ChatModelListener
import com.mamba.model.chat.listener.ChatModelRequestContext
import com.mamba.model.chat.listener.ChatModelResponseContext
import com.mamba.model.chat.request.ChatRequest
import com.mamba.model.output.FinishReason
import com.mamba.model.output.TokenUsage
import com.mamba.picme.agent.core.platform.logging.Logger

/**
 * 捕获远程 LLM 每次调用的 request/response 摘要并交给 [LlmCallRecorder] 落库。
 *
 * - [onRequest]：把开始时间戳塞进 listener attributes（langchain4j 在同一调用的
 *   request/response/error 回调间共享同一 attributes map），用于后续计算 latency。
 * - [onResponse]：成功落一条（model / latency / tokens / 序列化的 req 与 resp）。
 * - [onError]：失败落一条（success=false + errorMessage）。
 *
 * ReAct 多轮工具循环中**每一轮 LLM 调用各触发一次** onResponse，因此每轮各一行，
 * 正是排查 agent 死循环 / 错误工具调用的理想粒度。
 *
 * 所有逻辑 try/catch 包裹，**绝不向 LLM 主调用抛异常**。
 *
 * @param source 调用来源标签，写入记录便于 Debug 页筛选。
 * @param recorder 落库接收端。
 */
class CapturingChatModelListener(
    private val source: String,
    private val recorder: LlmCallRecorder
) : ChatModelListener {

    override fun onRequest(requestContext: ChatModelRequestContext) {
        try {
            requestContext.attributes()[START_TIME_KEY] = System.currentTimeMillis()
        } catch (e: Exception) {
            Logger.w(TAG, "onRequest capture failed", e)
        }
    }

    override fun onResponse(responseContext: ChatModelResponseContext) {
        try {
            val now = System.currentTimeMillis()
            val start = responseContext.attributes()[START_TIME_KEY] as? Long
            val request = responseContext.chatRequest()
            val response = responseContext.chatResponse()
            val usage: TokenUsage? = runCatching { response?.tokenUsage() }.getOrNull()
            recorder.record(
                LlmCallRecord(
                    createdAt = now,
                    source = source,
                    model = request?.modelName() ?: response?.modelName(),
                    success = true,
                    latencyMs = start?.let { now - it },
                    promptTokens = usage?.inputTokenCount(),
                    completionTokens = usage?.outputTokenCount(),
                    totalTokens = usage?.totalTokenCount(),
                    requestJson = buildRequestJson(request),
                    responseJson = buildResponseJson(response?.aiMessage(), response?.finishReason(), usage),
                    errorMessage = null
                )
            )
        } catch (e: Exception) {
            Logger.w(TAG, "onResponse capture failed", e)
        }
    }

    override fun onError(errorContext: ChatModelErrorContext) {
        try {
            val now = System.currentTimeMillis()
            val start = errorContext.attributes()[START_TIME_KEY] as? Long
            val request = errorContext.chatRequest()
            recorder.record(
                LlmCallRecord(
                    createdAt = now,
                    source = source,
                    model = request?.modelName(),
                    success = false,
                    latencyMs = start?.let { now - it },
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    requestJson = buildRequestJson(request),
                    responseJson = null,
                    errorMessage = errorContext.error()?.message
                        ?: errorContext.error()?.javaClass?.simpleName
                )
            )
        } catch (e: Exception) {
            Logger.w(TAG, "onError capture failed", e)
        }
    }

    /**
     * 构造请求摘要 JSON：model / temperature / maxTokens / tools 数量 + 完整 messages（复用
     * agent-core 的 [ChatMessageSerializer]）。messages 是排查 prompt 问题的核心信息。
     */
    private fun buildRequestJson(request: ChatRequest?): String {
        val sb = StringBuilder("{")
        if (request != null) {
            sb.append("\"model\":").append(jsonStr(request.modelName())).append(',')
            sb.append("\"temperature\":").append(request.temperature() ?: "null").append(',')
            sb.append("\"maxTokens\":").append(request.maxOutputTokens() ?: "null").append(',')
            sb.append("\"toolsCount\":").append(request.toolSpecifications()?.size ?: 0).append(',')
            val messages = runCatching { ChatMessageSerializer.messagesToJson(request.messages()) }
                .getOrElse { "[]" }
            sb.append("\"messages\":").append(messages)
        }
        sb.append('}')
        return LlmCallRecord.cap(sb.toString()) ?: "{}"
    }

    /**
     * 构造响应摘要 JSON：text / thinking / toolCalls / finishReason / usage。
     * 三者全为空时返回 null（不写响应段）。
     */
    private fun buildResponseJson(
        ai: AiMessage?,
        finishReason: FinishReason?,
        usage: TokenUsage?
    ): String? {
        if (ai == null && finishReason == null && usage == null) return null
        val sb = StringBuilder("{")
        sb.append("\"text\":").append(jsonStr(ai?.text())).append(',')
        val thinking = ai?.thinking()
        if (thinking != null) {
            sb.append("\"thinking\":").append(jsonStr(thinking)).append(',')
        }
        if (ai?.hasToolExecutionRequests() == true) {
            val tools = ai.toolExecutionRequests().joinToString(",", "[", "]") { req ->
                "{\"id\":${jsonStr(req.id())},\"name\":${jsonStr(req.name())}," +
                    "\"arguments\":${jsonStr(req.arguments())}}"
            }
            sb.append("\"toolCalls\":").append(tools).append(',')
        }
        sb.append("\"finishReason\":").append(jsonStr(finishReason?.name)).append(',')
        sb.append("\"usage\":{\"promptTokens\":").append(usage?.inputTokenCount() ?: 0)
            .append(",\"completionTokens\":").append(usage?.outputTokenCount() ?: 0)
            .append(",\"totalTokens\":").append(usage?.totalTokenCount() ?: 0).append("}")
        sb.append('}')
        return LlmCallRecord.cap(sb.toString())
    }

    private fun jsonStr(s: String?): String =
        if (s == null) {
            "null"
        } else {
            "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
        }

    companion object {
        private const val TAG = "LlmCallLog"
        private val START_TIME_KEY = "polang.llmlog.startTime"
    }
}
