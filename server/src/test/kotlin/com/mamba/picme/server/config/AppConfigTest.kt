package com.mamba.picme.server.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun `parsePrices merges override over defaults`() {
        val p = AppConfig.parsePrices("""{"deepseek-chat":{"in":1.5,"out":6.0}}""")
        assertEquals(1.5, p["deepseek-chat"]!!.inPerMillion, 0.0)
        assertEquals(6.0, p["deepseek-chat"]!!.outPerMillion, 0.0)
        // 未覆盖的默认保留
        assertTrue(p.containsKey("kimi-k2.6"))
    }

    @Test
    fun `parsePrices adds a brand-new model`() {
        val p = AppConfig.parsePrices("""{"new-model":{"in":1.0,"out":2.0}}""")
        assertEquals(1.0, p["new-model"]!!.inPerMillion, 0.0)
    }

    @Test
    fun `parsePrices null returns defaults`() {
        assertTrue(AppConfig.parsePrices(null).isNotEmpty())
    }

    @Test
    fun `parsePrices bad json returns defaults`() {
        assertTrue(AppConfig.parsePrices("not json").isNotEmpty())
    }

    @Test
    fun `parsePrices missing fields are skipped`() {
        val p = AppConfig.parsePrices("""{"deepseek-chat":{"in":1.0}}""")
        // in 缺 out → 整条跳过，仍为默认值
        assertEquals(8.0, p["deepseek-chat"]!!.outPerMillion, 0.0)
    }
}
