package com.mamba.picme.agent.core.inference.remote.koog

import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeSystemPromptTest {

    @Test
    fun nullProvider_returnsBase() {
        assertEquals("BASE", composeSystemPrompt("BASE", null))
    }

    @Test
    fun blankSnapshot_returnsBase() {
        assertEquals("BASE", composeSystemPrompt("BASE", stub("   ")))
        assertEquals("BASE", composeSystemPrompt("BASE", stub("")))
    }

    @Test
    fun nonBlank_appendsWithBlankLine() {
        assertEquals("BASE\n\nMEM", composeSystemPrompt("BASE", stub("MEM")))
    }

    private fun stub(value: String): MemoryContextProvider = object : MemoryContextProvider {
        override fun snapshot(): String = value
    }
}
