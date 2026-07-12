package com.mamba.picme.server.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `default prices cover known models`() {
        val p = defaultPrices()
        assertEquals(6, p.size)
        assert(p.containsKey("deepseek-chat"))
        assert(p.containsKey("kimi-k2.6"))
    }
}
