package com.mamba.picme.server.config

import com.mamba.picme.server.analytics.Price
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

internal class ParsePricesCase(
    val name: String,
    val input: String?,
    val verify: (Map<String, Price>) -> Unit,
) {
    override fun toString() = name
}

@RunWith(Parameterized::class)
internal class AppConfigTest(private val case: ParsePricesCase) {

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<out Any>> = listOf(
            arrayOf(
                ParsePricesCase("override", """{"deepseek-chat":{"in":1.5,"out":6.0}}""") { p ->
                    assertEquals(1.5, p["deepseek-chat"]!!.inPerMillion, 0.0)
                    assertEquals(6.0, p["deepseek-chat"]!!.outPerMillion, 0.0)
                    assertTrue(p.containsKey("kimi-k2.6"))
                },
            ),
            arrayOf(
                ParsePricesCase("new-model", """{"new-model":{"in":1.0,"out":2.0}}""") { p ->
                    assertEquals(1.0, p["new-model"]!!.inPerMillion, 0.0)
                },
            ),
            arrayOf(
                ParsePricesCase("null-input", null) { p ->
                    assertTrue(p.isNotEmpty())
                },
            ),
            arrayOf(
                ParsePricesCase("bad-json", "not json") { p ->
                    assertTrue(p.isNotEmpty())
                },
            ),
            arrayOf(
                ParsePricesCase("missing-fields-skipped", """{"deepseek-chat":{"in":1.0}}""") { p ->
                    // in 缺 out → 整条跳过，仍为默认值
                    assertEquals(8.0, p["deepseek-chat"]!!.outPerMillion, 0.0)
                },
            ),
        )
    }

    @Test
    fun parsePrices() {
        case.verify(AppConfig.parsePrices(case.input))
    }
}
