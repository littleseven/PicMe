package com.mamba.picme.agent.core.inference.remote.log

/**
 * 一次远程 LLM 调用的记录（request/response 摘要），用于本地调试落库。
 *
 * 纯数据类，不依赖 Android / Room：由 Koog agent 的 EventHandler（onLLMCallCompleted）产出，
 * 再由 :app 侧的 RoomLlmCallRecorder 持久化到独立数据库 llm_call_log。
 *
 * @param source 调用来源标签（如 "chat-koog" / "camera-koog" / "feishu-koog"），便于在 Debug 页筛选。
 * @param requestJson 序列化后的请求摘要。DEBUG 含完整 messages JSON；
 *   release（captureContent=false）只含 model / toolsCount / messageCount / hasSystemPrompt 纯指标。
 * @param responseJson 序列化后的响应摘要。DEBUG 含 text / thinking / toolCalls 全文；
 *   release 只含 finishReason / toolCallNames / textLength / usage 纯指标。
 * @param errorMessage 失败时（onError）的异常信息；成功时为 null。release 下截断到 500 字符。
 */
data class LlmCallRecord(
    val createdAt: Long,
    val source: String,
    val model: String?,
    val success: Boolean,
    val latencyMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val requestJson: String,
    val responseJson: String?,
    val errorMessage: String?,
    /** 关联 ID：一条用户消息一个 traceId；非 chat 来源为 null。 */
    val traceId: String? = null
) {
    companion object {
        /** 单字段最大字符数（约 32KB），超出截断，防止超大 prompt/响应撑爆本地调试库。 */
        const val MAX_FIELD_CHARS = 32 * 1024

        /**
         * 把字符串截断到 [max] 字符；超出时尾部追加截断标记。null 原样返回。
         */
        fun cap(value: String?, max: Int = MAX_FIELD_CHARS): String? {
            if (value == null) return null
            return if (value.length <= max) value else value.substring(0, max) + "\n…<truncated ${value.length - max} chars>"
        }
    }
}
