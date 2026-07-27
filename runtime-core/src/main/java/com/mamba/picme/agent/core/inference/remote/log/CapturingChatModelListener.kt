package com.mamba.picme.agent.core.inference.remote.log

import com.mamba.data.message.AiMessage
import com.mamba.data.message.ChatMessageSerializer
import com.mamba.data.message.ChatMessageType
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
 * @param captureContent true（DEBUG）时记录 request/response 全文；
 *   false（release）时只落纯指标（model/latency/tokens/消息数等），**绝不序列化消息内容**（隐私红线）。
 */
class CapturingChatModelListener(
    private val source: String,
    private val recorder: LlmCallRecorder,
    private val captureContent: Boolean = true,
    private val traceIdHolder: TraceIdHolder? = null
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
                    errorMessage = null,
                    traceId = traceIdHolder?.value
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
                    errorMessage = capErrorMessage(
                        errorContext.error()?.message
                            ?: errorContext.error()?.javaClass?.simpleName
                    ),
                    traceId = traceIdHolder?.value
                )
            )
        } catch (e: Exception) {
            Logger.w(TAG, "onError capture failed", e)
        }
    }

    /**
     * 构造请求摘要 JSON。
     *
     * - [captureContent] = true（DEBUG）：model / temperature / maxTokens / tools 数量 +
     *   完整 messages（复用 agent-core 的 [ChatMessageSerializer]），messages 是排查 prompt 问题的核心信息。
     * - [captureContent] = false（release）：只落 model / toolsCount / messageCount /
     *   hasSystemPrompt 纯指标，**绝不序列化消息内容**。
     */
    private fun buildRequestJson(request: ChatRequest?): String {
        val sb = StringBuilder("{")
        if (request != null) {
            sb.append("\"model\":").append(jsonStr(request.modelName())).append(',')
            sb.append("\"toolsCount\":").append(request.toolSpecifications()?.size ?: 0)
            if (captureContent) {
                sb.append(",\"temperature\":").append(request.temperature() ?: "null")
                sb.append(",\"maxTokens\":").append(request.maxOutputTokens() ?: "null")
                val messages = runCatching { ChatMessageSerializer.messagesToJson(request.messages()) }
                    .getOrElse { "[]" }
                sb.append(",\"messages\":").append(messages)
            } else {
                val messages = request.messages() ?: emptyList()
                sb.append(",\"messageCount\":").append(messages.size)
                sb.append(",\"hasSystemPrompt\":")
                    .append(messages.any { it.type() == ChatMessageType.SYSTEM })
            }
        }
        sb.append('}')
        return LlmCallRecord.cap(sb.toString()) ?: "{}"
    }

    /**
     * 构造响应摘要 JSON。
     *
     * - [captureContent] = true（DEBUG）：text / thinking / toolCalls(含 arguments) / finishReason / usage。
     * - [captureContent] = false（release）：只保留 finishReason / toolCallNames / textLength /
     *   usage 纯指标，剔除 text / thinking / arguments 全文。
     * 三者全为空时返回 null（不写响应段）。
     */
    private fun buildResponseJson(
        ai: AiMessage?,
        finishReason: FinishReason?,
        usage: TokenUsage?
    ): String? {
        if (ai == null && finishReason == null && usage == null) return null
        val sb = StringBuilder("{")
        if (captureContent) {
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
        } else {
            if (ai?.hasToolExecutionRequests() == true) {
                val names = ai.toolExecutionRequests()
                    .joinToString(",", "[", "]") { jsonStr(it.name()) }
                sb.append("\"toolCallNames\":").append(names).append(',')
            }
            sb.append("\"textLength\":").append(ai?.text()?.length ?: 0).append(',')
        }
        sb.append("\"finishReason\":").append(jsonStr(finishReason?.name)).append(',')
        sb.append("\"usage\":{\"promptTokens\":").append(usage?.inputTokenCount() ?: 0)
            .append(",\"completionTokens\":").append(usage?.outputTokenCount() ?: 0)
            .append(",\"totalTokens\":").append(usage?.totalTokenCount() ?: 0).append("}")
        sb.append('}')
        return LlmCallRecord.cap(sb.toString())
    }

    /** 指标模式下 errorMessage 截断到 [ERROR_MESSAGE_MAX_CHARS] 字符，防止超长堆栈撑爆本地库。 */
    private fun capErrorMessage(message: String?): String? =
        if (captureContent) message else LlmCallRecord.cap(message, ERROR_MESSAGE_MAX_CHARS)

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

        /** 指标模式（release）下 errorMessage 的最大字符数。 */
        const val ERROR_MESSAGE_MAX_CHARS = 500
    }
}
