package com.mamba.picme.data.remote.anthropic

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Claude Coding API 请求/响应数据模型
 *
 * 基于 Claude API 格式设计。
 * Endpoint: https://api.anthropic.com/v1/messages
 */

@JsonClass(generateAdapter = true)
data class ClaudeCodingRequest(
    val model: String,
    val messages: List<ClaudeCodingMessage>,
    val system: String? = null,
    @Json(name = "max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Double = 0.3,
    val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ClaudeCodingMessage(
    val role: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class ClaudeCodingResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ClaudeCodingContentBlock>,
    val model: String,
    @Json(name = "stop_reason")
    val stopReason: String?,
    val usage: ClaudeCodingUsage?
)

@JsonClass(generateAdapter = true)
data class ClaudeCodingContentBlock(
    val type: String,
    val text: String
)

@JsonClass(generateAdapter = true)
data class ClaudeCodingUsage(
    @Json(name = "input_tokens")
    val inputTokens: Int,
    @Json(name = "output_tokens")
    val outputTokens: Int,
    @Json(name = "total_tokens")
    val totalTokens: Int
)

@JsonClass(generateAdapter = true)
data class ClaudeCodingErrorResponse(
    val error: ClaudeCodingErrorDetail?
)

@JsonClass(generateAdapter = true)
data class ClaudeCodingErrorDetail(
    val message: String,
    val type: String?
)
