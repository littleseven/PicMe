package com.mamba.picme.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("picme-llm")

/**
 * 调用上游 balance API 并把响应缓存进 [LlmChannels.balanceJson]。
 * 仅由后台「刷新余额」按钮触发（POST /admin/channels/{id}/refresh-balance）；
 * 页面加载只读缓存，不发起外部调用。
 */
class ChannelBalanceService(
    val httpClient: HttpClient,
    val timeoutMs: Long = 8_000,
) {
    /**
     * 拉取并缓存余额。返回是否成功更新缓存。
     * balance_url 空 / 上游非 2xx / 超时 / 异常 → 不覆盖旧缓存，返回 false。
     */
    suspend fun refresh(channelId: Int): Boolean {
        val cfg = ChannelRepository.balanceConfig(channelId) ?: return false
        if (cfg.balanceUrl.isBlank() || cfg.apiToken.isBlank()) return false

        val (headerName, headerValue) = when (cfg.authStyle) {
            AuthStyle.BEARER -> "Authorization" to "Bearer ${cfg.apiToken}"
            AuthStyle.CF_AIG -> "cf-aig-authorization" to "Bearer ${cfg.apiToken}"
        }
        return try {
            val resp = httpClient.get(cfg.balanceUrl) {
                header(headerName, headerValue)
            }
            if (!resp.status.isSuccess()) {
                logger.info("Balance refresh channel={} status={}", channelId, resp.status.value)
                return false
            }
            val body = resp.bodyAsText()
            ChannelRepository.saveBalanceCache(channelId, body, Instant.now().toEpochMilli())
            true
        } catch (e: Exception) {
            logger.warn("Balance refresh channel={} failed: {}", channelId, e.message)
            false
        }
    }

    /** 读缓存的展示串 + 检查时间；无缓存或无法解析返回 null/(null)。 */
    suspend fun cached(channelId: Int): Cached {
        val c = ChannelRepository.cachedBalance(channelId) ?: return Cached(null, null)
        val display = parseDeepSeekBalance(c.json)
        return Cached(display ?: if (c.json.isBlank()) null else "(解析失败)", c.checkedAt)
    }

    data class Cached(val display: String?, val checkedAt: Long?)
}

/**
 * 解析 DeepSeek 形态余额响应。返回展示串（如 "¥10.03"），无可用信息返回 null。
 * is_available=false → "—"；currency=CNY → "¥" 前缀，否则原样附币种。
 */
fun parseDeepSeekBalance(json: String): String? {
    return try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val available = (obj["is_available"] as? JsonPrimitive)?.content
        if (available == "false") return "—"
        val infos = obj["balance_infos"] as? JsonArray ?: return null
        val first = infos.firstOrNull() as? JsonObject ?: return null
        val total = (first["total_balance"] as? JsonPrimitive)?.content ?: return null
        val currency = (first["currency"] as? JsonPrimitive)?.content ?: ""
        when (currency.uppercase()) {
            "CNY" -> "¥$total"
            "USD" -> "USD $total"
            else -> if (currency.isBlank()) total else "$currency $total"
        }
    } catch (e: Exception) {
        null
    }
}
