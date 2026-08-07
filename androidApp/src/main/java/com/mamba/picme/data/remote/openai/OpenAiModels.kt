package com.mamba.picme.data.remote.openai

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * OpenAI 兼容 API 请求/响应数据模型
 *
 * 支持 tokenhub.tencentmaas.com/v1/ 等 OpenAI 兼容接口。
 * 通过 model 字段区分不同模型（kimi-k2.6 / deepseek-v4-flash）。
 */

/**
 * OpenAI 兼容聊天请求
 *
 * @property model 模型 ID，如 "kimi-k2.6" 或 "deepseek-v4-flash"
 * @property messages 消息列表，包含 system 和 user 角色
 * @property maxTokens 最大生成 token 数
 * @property temperature 控制随机性，0-2 之间
 * @property stream 是否开启流式响应
 */
@JsonClass(generateAdapter = true)
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @Json(name = "max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Double = 0.3,
    val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class OpenAiMessage(
    val role: String,
    val content: String,
    @Json(name = "reasoning_content")
    val reasoningContent: String? = null
)

/**
 * OpenAI 兼容聊天响应（非流式）
 */
@JsonClass(generateAdapter = true)
data class OpenAiChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage?
)

@JsonClass(generateAdapter = true)
data class OpenAiChoice(
    val index: Int,
    val message: OpenAiMessage,
    @Json(name = "finish_reason")
    val finishReason: String?
)

@JsonClass(generateAdapter = true)
data class OpenAiUsage(
    @Json(name = "prompt_tokens")
    val promptTokens: Int,
    @Json(name = "completion_tokens")
    val completionTokens: Int,
    @Json(name = "total_tokens")
    val totalTokens: Int
)

/**
 * OpenAI 兼容错误响应
 */
@JsonClass(generateAdapter = true)
data class OpenAiErrorResponse(
    val error: OpenAiErrorDetail?
)

@JsonClass(generateAdapter = true)
data class OpenAiErrorDetail(
    val message: String,
    val type: String?,
    val code: String?
)
