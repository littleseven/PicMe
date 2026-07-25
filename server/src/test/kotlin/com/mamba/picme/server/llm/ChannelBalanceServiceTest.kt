package com.mamba.picme.server.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelBalanceServiceTest {

    @Test
    fun `parses deepseek balance into cny display`() {
        val json = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"10.03","granted_balance":"10.03","topped_up_balance":"0.00"}]}"""
        assertEquals("¥10.03", parseDeepSeekBalance(json))
    }

    @Test
    fun `parses usd balance with currency code`() {
        val json = """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"5.5"}]}"""
        assertEquals("USD 5.5", parseDeepSeekBalance(json))
    }

    @Test
    fun `is_available false returns dash`() {
        val json = """{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"0"}]}"""
        assertEquals("—", parseDeepSeekBalance(json))
    }

    @Test
    fun `missing balance_infos returns null`() {
        val json = """{"is_available":true}"""
        assertNull(parseDeepSeekBalance(json))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(parseDeepSeekBalance("not json"))
    }
}
