package com.mamba.picme.server.llm

import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ParseModelMapLinesErrorTest(private val input: String) {

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<out Any>> = listOf(
            arrayOf("deepseek-chat glm-5.2"),
            arrayOf("deepseek-chat="),
        )
    }

    @Test
    fun `invalid input throws IllegalArgumentException`() {
        try {
            parseModelMapLines(input)
            fail("expected IllegalArgumentException for: $input")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
