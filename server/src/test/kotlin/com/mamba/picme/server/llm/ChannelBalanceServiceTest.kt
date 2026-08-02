package com.mamba.picme.server.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ChannelBalanceServiceTest(private val input: String, private val expected: String?) {

    companion object {
        @Parameterized.Parameters(name = "{index}")
        @JvmStatic
        fun data(): Collection<Array<out Any?>> = listOf(
            arrayOf<Any?>(
                """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"10.03","granted_balance":"10.03","topped_up_balance":"0.00"}]}""",
                "¥10.03",
            ),
            arrayOf<Any?>(
                """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"5.5"}]}""",
                "USD 5.5",
            ),
            arrayOf<Any?>(
                """{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"0"}]}""",
                "—",
            ),
            arrayOf<Any?>(
                """{"is_available":true}""",
                null,
            ),
            arrayOf<Any?>(
                "not json",
                null,
            ),
        )
    }

    @Test
    fun parseDeepSeekBalance() {
        assertEquals(expected, parseDeepSeekBalance(input))
    }
}
