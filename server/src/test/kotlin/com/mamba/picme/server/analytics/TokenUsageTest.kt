package com.mamba.picme.server.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageTest {

    @Test
    fun `parses normal usage`() {
        val u = fromUpstreamBytes(
            """{"id":"x","usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}""".toByteArray(),
        )
        assertEquals(TokenUsage(100, 50, 150), u)
    }

    @Test
    fun `no usage returns null`() {
        assertNull(fromUpstreamBytes("""{"id":"x"}""".toByteArray()))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(fromUpstreamBytes("not json".toByteArray()))
    }

    @Test
    fun `missing total derives from prompt plus completion`() {
        val u = fromUpstreamBytes(
            """{"usage":{"prompt_tokens":10,"completion_tokens":5}}""".toByteArray(),
        )
        assertEquals(TokenUsage(10, 5, 15), u)
    }

    // ── fromSseStream：流式 SSE 尾帧 usage 解析 ──

    @Test
    fun `sse stream parses usage from trailing frame`() {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n" +
            "data: [DONE]\n\n"
        assertEquals(TokenUsage(10, 5, 15), fromSseStream(sse))
    }

    @Test
    fun `sse stream tolerates crlf line endings`() {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\r\n\r\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\r\n\r\n" +
            "data: [DONE]\r\n\r\n"
        assertEquals(TokenUsage(3, 2, 5), fromSseStream(sse))
    }

    @Test
    fun `sse stream without usage returns null`() {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n" +
            "data: [DONE]\n\n"
        assertNull(fromSseStream(sse))
    }

    @Test
    fun `sse stream skips garbage frames and finds usage`() {
        val sse = "data: not-json\n\n" +
            ": comment line\n\n" +
            "data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}\n\n" +
            "data: [DONE]\n\n"
        // total 缺失 → prompt+completion 推导
        assertEquals(TokenUsage(1, 2, 3), fromSseStream(sse))
    }

    @Test
    fun `cost computes per million tokens`() {
        val prices = mapOf("m" to Price(inPerMillion = 2.0, outPerMillion = 8.0))
        // 1M prompt + 0.5M completion → 2.0 + 4.0
        val cost = costCny(TokenUsage(1_000_000, 500_000, 1_500_000), "m", prices)
        assertEquals(6.0, cost, 0.000001)
    }

    @Test
    fun `unknown model yields zero cost`() {
        val cost = costCny(
            TokenUsage(1_000_000, 0, 1_000_000),
            "nope",
            mapOf("m" to Price(2.0, 8.0)),
        )
        assertEquals(0.0, cost, 0.0)
    }

    @Test
    fun `null usage yields zero cost`() {
        assertEquals(0.0, costCny(null, "m", mapOf("m" to Price(2.0, 8.0))), 0.0)
    }

    // ── defaultPrices：路由在役的 DeepSeek 模型必须计得上价 ──

    @Test
    fun `default prices cover routed deepseek upstream models`() {
        val prices = defaultPrices()
        // 各渠道 model_map 映射后的上游名（LlmProxy 按 upstreamModel 计费）
        val routedUpstreamModels = listOf(
            "deepseek/deepseek-chat", // Cloudflare
            "deepseek-v4-flash", "deepseek-v4-pro", // DeepSeek 直连
            "deepseek-v4-flash-202605", "deepseek-v4-pro-202606", // TokenHub 快照名
        )
        routedUpstreamModels.forEach { model ->
            assertNotNull("defaultPrices 缺 $model，成本会静默记 0", prices[model])
        }
        prices.values.forEach { price ->
            assertTrue("单价应为正数: $price", price.inPerMillion > 0 && price.outPerMillion > 0)
        }
    }
}
