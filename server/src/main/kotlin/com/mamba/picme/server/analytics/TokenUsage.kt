package com.mamba.picme.server.analytics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 上游 OpenAI 兼容响应里 usage 的三件套。 */
data class TokenUsage(val prompt: Int, val completion: Int, val total: Int)

/** 模型单价：¥ / 1M tokens，输入/输出分开。用于成本估算。 */
data class Price(val inPerMillion: Double, val outPerMillion: Double)

/**
 * 从上游 chat completion 响应体解析 usage。
 * 响应 stream=false，正常必带 usage；错误/异常响应返回 null。
 */
fun fromUpstreamBytes(bytes: ByteArray): TokenUsage? {
    return try {
        val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val usage = obj["usage"] as? JsonObject ?: return null
        val prompt = usage.int("prompt_tokens")
        val completion = usage.int("completion_tokens")
        val total = usage.int("total_tokens")
        if (prompt == null && completion == null && total == null) return null
        val p = prompt ?: 0
        val c = completion ?: 0
        TokenUsage(p, c, total ?: (p + c))
    } catch (e: Exception) {
        null
    }
}

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

/** 按 [prices] 计算单次调用估算成本（¥）；未知模型或无 usage → 0。 */
fun costCny(usage: TokenUsage?, model: String, prices: Map<String, Price>): Double {
    if (usage == null) return 0.0
    val price = prices[model] ?: return 0.0
    return usage.prompt / 1_000_000.0 * price.inPerMillion +
        usage.completion / 1_000_000.0 * price.outPerMillion
}

/**
 * 内置默认单价（¥ / 1M tokens）。**估算用**，实际随上游调价漂移，
 * 生产可用环境变量 LLM_PRICES_JSON 覆盖（见 AppConfig）。
 */
fun defaultPrices(): Map<String, Price> = mapOf(
    "deepseek/deepseek-chat" to Price(2.0, 8.0),
    "deepseek-chat" to Price(2.0, 8.0),
    "deepseek-v4-flash" to Price(0.5, 1.0),
    "deepseek-v4-flash-202605" to Price(0.5, 1.0),
    "kimi-k2.6" to Price(4.0, 12.0),
    "kimi-k2.7-code" to Price(4.0, 12.0),
)
