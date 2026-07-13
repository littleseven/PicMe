package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.TokenUsage
import com.mamba.picme.server.analytics.fromUpstreamBytes
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("picme-llm")

/**
 * LLM 代理：把 chat completion 请求转发到当前生效渠道（[ChannelRegistry.active]）。
 * - 模型路由对客户端透明：请求的 model 名按渠道 model_map 映射为上游名。
 * - 真实 API key 只在 DB（渠道配置）里。
 * - 强制 stream=false（usage 解析依赖完整响应体）。
 */
class LlmProxy(
    private val httpClient: HttpClient,
    private val maxTokensCap: Int = 4096,
) {
    suspend fun forward(clientIp: String, body: JsonObject): ProxyResult {
        val channel = ChannelRegistry.active()
            ?: return ProxyResult.Error(
                HttpStatusCode.ServiceUnavailable,
                buildJsonObject { put("error", "no_active_channel") },
                logStatus = "no_active_channel",
            )

        val requestedModel = (body["model"] as? JsonPrimitive)?.contentOrNullSafe()
            ?: return ProxyResult.Error(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", "missing model field") },
                logStatus = "bad_request",
            )

        val upstreamModel = channel.modelMap[requestedModel]
            ?: return ProxyResult.Error(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("error", "unsupported_model")
                    put("active_channel", channel.name)
                    put("supported", channel.modelMap.keys.sorted().joinToString(","))
                },
                logStatus = "unsupported_model",
            )

        val maxTokens = (body["max_tokens"] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull()
        if (maxTokens != null && maxTokens > maxTokensCap) {
            return ProxyResult.Error(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", "max_tokens exceeds limit of $maxTokensCap") },
                logStatus = "bad_request",
            )
        }

        if (channel.apiToken.isBlank()) {
            return ProxyResult.Error(
                HttpStatusCode.InternalServerError,
                buildJsonObject {
                    put("error", "channel_token_missing")
                    put("channel", channel.name)
                },
                logStatus = "channel_token_missing",
            )
        }

        val payload = buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            put("model", upstreamModel)
            put("stream", false)
        }

        val (headerName, headerValue) = when (channel.authStyle) {
            AuthStyle.BEARER -> "Authorization" to "Bearer ${channel.apiToken}"
            AuthStyle.CF_AIG -> "cf-aig-authorization" to "Bearer ${channel.apiToken}"
        }

        logger.info("Forwarding to channel={}, model={}, ip={}", channel.name, upstreamModel, clientIp)

        val resp = httpClient.post(channel.baseUrl) {
            contentType(ContentType.Application.Json)
            header(headerName, headerValue)
            setBody(payload.toString())
        }

        val bytes = resp.bodyAsBytes()
        logger.info("Channel {} response status={}, ip={}", channel.name, resp.status.value, clientIp)
        return ProxyResult.Success(
            status = resp.status,
            bytes = bytes,
            model = upstreamModel,
            provider = channel.name,
            usage = fromUpstreamBytes(bytes),
        )
    }
}

sealed class ProxyResult {
    data class Success(
        val status: HttpStatusCode,
        val bytes: ByteArray,
        val model: String,
        val provider: String,
        val usage: TokenUsage?,
    ) : ProxyResult()

    data class Error(
        val status: HttpStatusCode,
        val body: JsonObject,
        val logStatus: String = "upstream_error",
    ) : ProxyResult()
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this.isString) this.content else this.content.takeIf { it.isNotEmpty() }
