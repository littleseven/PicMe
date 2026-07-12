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

enum class LlmProvider { CLOUDFLARE, TOKENHUB }

/**
 * LLM proxy that forwards chat completion requests to the correct upstream provider.
 * Ports infra/tencentscf/index.js logic to Ktor.
 *
 * - Model routing is transparent to the client.
 * - Real API keys live only in server env vars.
 * - Forces stream=false (same as SCF).
 */
class LlmProxy(
    private val httpClient: HttpClient,
    private val cloudflareUrl: String,
    private val cloudflareAigToken: String,
    private val tokenhubUrl: String,
    private val tokenhubApiToken: String,
    private val forceProvider: String?,
    private val maxTokensCap: Int = 4096,
) {
    companion object {
        private val MODEL_ROUTES = mapOf(
            "deepseek/deepseek-chat" to LlmProvider.CLOUDFLARE,
            "deepseek-chat" to LlmProvider.CLOUDFLARE,
            "deepseek-v4-flash-202605" to LlmProvider.TOKENHUB,
            "deepseek-v4-flash" to LlmProvider.TOKENHUB,
            "kimi-k2.6" to LlmProvider.TOKENHUB,
            "kimi-k2.7-code" to LlmProvider.TOKENHUB,
        )

        private val MODEL_ALIASES = mapOf(
            "deepseek-chat" to "deepseek/deepseek-chat",
            "deepseek-v4-flash" to "deepseek-v4-flash",
        )

        private val TOKENHUB_MODELS = setOf(
            "deepseek-v4-flash",
            "deepseek-v4-flash-202605",
            "kimi-k2.6",
            "kimi-k2.7-code",
        )
    }

    suspend fun forward(
        clientIp: String,
        body: JsonObject,
    ): ProxyResult {
        val requestedModel = (body["model"] as? JsonPrimitive)?.contentOrNullSafe()
            ?: return ProxyResult.Error(HttpStatusCode.BadRequest, buildJsonObject {
                put("error", "missing model field")
            })

        // max_tokens cap
        val maxTokens = (body["max_tokens"] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull()
        if (maxTokens != null && maxTokens > maxTokensCap) {
            return ProxyResult.Error(HttpStatusCode.BadRequest, buildJsonObject {
                put("error", "max_tokens exceeds limit of $maxTokensCap")
            })
        }

        // resolve provider
        val provider = resolveProvider(requestedModel)
            ?: return ProxyResult.Error(HttpStatusCode.BadRequest, buildJsonObject {
                put("error", "Unsupported model: $requestedModel. Supported: ${MODEL_ROUTES.keys.joinToString(", ")}")
            })

        val upstreamModel = resolveUpstreamModel(requestedModel)

        return when (provider) {
            LlmProvider.CLOUDFLARE -> forwardToCloudflare(clientIp, body, upstreamModel)
            LlmProvider.TOKENHUB -> {
                if (upstreamModel !in TOKENHUB_MODELS) {
                    return ProxyResult.Error(HttpStatusCode.BadRequest, buildJsonObject {
                        put("error", "Unsupported model: $requestedModel. Supported: ${TOKENHUB_MODELS.joinToString(", ")}")
                    })
                }
                forwardToTokenhub(clientIp, body, upstreamModel)
            }
        }
    }

    private fun resolveProvider(modelId: String): LlmProvider? {
        if (forceProvider.equals("cloudflare", ignoreCase = true)) return LlmProvider.CLOUDFLARE
        if (forceProvider.equals("tokenhub", ignoreCase = true)) return LlmProvider.TOKENHUB
        return MODEL_ROUTES[modelId]
    }

    private fun resolveUpstreamModel(modelId: String): String =
        MODEL_ALIASES[modelId] ?: modelId

    private suspend fun forwardToCloudflare(
        clientIp: String,
        body: JsonObject,
        upstreamModel: String,
    ): ProxyResult {
        if (cloudflareAigToken.isBlank()) {
            logger.error("CLOUDFLARE_AIG_TOKEN not configured")
            return ProxyResult.Error(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", "Server configuration error: CLOUDFLARE_AIG_TOKEN missing")
            })
        }

        val payload = buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            put("model", upstreamModel)
            put("stream", false)
        }

        logger.info("Forwarding to Cloudflare AI Gateway, model={}, ip={}", upstreamModel, clientIp)

        val resp = httpClient.post(cloudflareUrl) {
            contentType(ContentType.Application.Json)
            header("cf-aig-authorization", "Bearer $cloudflareAigToken")
            setBody(payload.toString())
        }

        val bytes = resp.bodyAsBytes()
        logger.info("Cloudflare response status={}, ip={}", resp.status.value, clientIp)
        return ProxyResult.Success(
            status = resp.status,
            bytes = bytes,
            model = upstreamModel,
            provider = LlmProvider.CLOUDFLARE,
            usage = fromUpstreamBytes(bytes),
        )
    }

    private suspend fun forwardToTokenhub(
        clientIp: String,
        body: JsonObject,
        upstreamModel: String,
    ): ProxyResult {
        if (tokenhubApiToken.isBlank()) {
            logger.error("TOKENHUB_API_TOKEN not configured")
            return ProxyResult.Error(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", "Server configuration error: TOKENHUB_API_TOKEN missing")
            })
        }

        val payload = buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            put("model", upstreamModel)
            put("stream", false)
        }

        logger.info("Forwarding to TokenHub, model={}, ip={}", upstreamModel, clientIp)

        val resp = httpClient.post(tokenhubUrl) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $tokenhubApiToken")
            setBody(payload.toString())
        }

        val bytes = resp.bodyAsBytes()
        logger.info("TokenHub response status={}, ip={}", resp.status.value, clientIp)
        return ProxyResult.Success(
            status = resp.status,
            bytes = bytes,
            model = upstreamModel,
            provider = LlmProvider.TOKENHUB,
            usage = fromUpstreamBytes(bytes),
        )
    }
}

sealed class ProxyResult {
    data class Success(
        val status: HttpStatusCode,
        val bytes: ByteArray,
        val model: String,
        val provider: LlmProvider,
        val usage: TokenUsage?,
    ) : ProxyResult()
    data class Error(val status: HttpStatusCode, val body: JsonObject) : ProxyResult()
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this.isString) this.content else this.content.takeIf { it.isNotEmpty() }
