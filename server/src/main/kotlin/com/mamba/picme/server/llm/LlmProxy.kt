package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.TokenUsage
import com.mamba.picme.server.analytics.fromUpstreamBytes
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("picme-llm")

/**
 * LLM 代理：把 chat completion 请求转发到当前生效渠道（[ChannelRegistry.active]）。
 * - 模型路由对客户端透明：请求的 model 名按渠道 model_map 映射为上游名。
 * - 真实 API key 只在 DB（渠道配置）里。
 * - stream=true 走流式 SSE 透传（[ProxyResult.Streaming]），原样转发 stream/stream_options；
 *   非流式请求强制 stream=false（usage 解析依赖完整响应体）。
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

        val mapped = channel.modelMap[requestedModel]
        val upstreamModel: String = when {
            mapped != null -> mapped
            channel.defaultModel.isNotBlank() -> channel.defaultModel.also {
                logger.info(
                    "Model {} not in map of channel {}, fell back to default {}",
                    requestedModel, channel.name, it,
                )
            }
            else -> return ProxyResult.Error(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("error", "unsupported_model")
                    put("active_channel", channel.name)
                    put("supported", channel.modelMap.keys.sorted().joinToString(","))
                    put("default_model", channel.defaultModel)
                },
                logStatus = "unsupported_model",
            )
        }

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

        val streamRequested = (body["stream"] as? JsonPrimitive)?.booleanOrNull == true

        val payload = buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            put("model", upstreamModel)
            // 流式请求原样转发 stream/stream_options；非流式强制 stream=false（usage 解析依赖完整响应体）
            if (!streamRequested) put("stream", false)
        }

        val (headerName, headerValue) = when (channel.authStyle) {
            AuthStyle.BEARER -> "Authorization" to "Bearer ${channel.apiToken}"
            AuthStyle.CF_AIG -> "cf-aig-authorization" to "Bearer ${channel.apiToken}"
        }

        logger.info(
            "Forwarding to channel={}, model={}, stream={}, ip={}",
            channel.name, upstreamModel, streamRequested, clientIp,
        )

        if (streamRequested) {
            val resp = httpClient.preparePost(channel.baseUrl) {
                contentType(ContentType.Application.Json)
                header(headerName, headerValue)
                setBody(payload.toString())
            }.execute()

            // 上游错误（非 2xx）：错误体体积小，整体缓冲后原样透传，与非流式路径行为一致
            if (!resp.status.isSuccess()) {
                val errBytes = resp.bodyAsBytes()
                logger.warn(
                    "Channel {} streaming request failed status={}, ip={}",
                    channel.name, resp.status.value, clientIp,
                )
                return ProxyResult.Success(
                    status = resp.status,
                    bytes = errBytes,
                    model = upstreamModel,
                    provider = channel.name,
                    usage = fromUpstreamBytes(errBytes),
                )
            }
            logger.info("Channel {} streaming response status={}, ip={}", channel.name, resp.status.value, clientIp)
            return ProxyResult.Streaming(
                status = resp.status,
                channel = resp.bodyAsChannel(),
                model = upstreamModel,
                provider = channel.name,
            )
        }

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

    /**
     * 流式 SSE 透传：channel 为上游响应体流（未消费），由路由层逐 chunk 转发给客户端，
     * 同时 tee 到内存累积器供流结束后解析 usage 帧。
     */
    data class Streaming(
        val status: HttpStatusCode,
        val channel: ByteReadChannel,
        val model: String,
        val provider: String,
    ) : ProxyResult()

    data class Error(
        val status: HttpStatusCode,
        val body: JsonObject,
        val logStatus: String = "upstream_error",
    ) : ProxyResult()
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this.isString) this.content else this.content.takeIf { it.isNotEmpty() }
