package com.mamba.picme.features.debug

import com.mamba.picme.data.local.llmlog.JsRunLogEntity
import com.mamba.picme.data.local.llmlog.LlmCallLogEntity
import com.mamba.picme.data.local.llmlog.ToolCallLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRecordMergerTest {

    private fun llm(t: Long) = LlmCallLogEntity(
        createdAt = t, source = "s", model = "m", success = true, latencyMs = 1,
        promptTokens = null, completionTokens = null, totalTokens = null,
        requestJson = "{}", responseJson = null, errorMessage = null, traceId = "T"
    )

    private fun tool(t: Long) = ToolCallLogEntity(
        createdAt = t, capability = "c", commandType = "cmd", latencyMs = 1,
        success = true, errorCode = null, errorMessage = null, traceId = "T"
    )

    private fun js(t: Long) = JsRunLogEntity(
        createdAt = t, source = "chat", kind = "eval", script = null, scriptLength = 0,
        success = true, errorCode = null, errorMessage = null, resultPreview = null,
        latencyMs = 1, traceId = "T"
    )

    @Test
    fun `merges three layers sorted by createdAt ascending`() {
        val merged = mergeTurnRecords(
            llm = listOf(llm(300), llm(100)),
            tool = listOf(tool(200)),
            js = listOf(js(400))
        )
        assertEquals(4, merged.size)
        assertEquals(100L, merged[0].createdAt)
        assertEquals(400L, merged.last().createdAt)
    }

    @Test
    fun `counts per kind correct`() {
        val merged = mergeTurnRecords(
            llm = listOf(llm(100), llm(200)),
            tool = listOf(tool(150), tool(250), tool(350)),
            js = listOf(js(300))
        )
        val counts = countByKind(merged)
        assertEquals(2, counts[TurnKind.LLM])
        assertEquals(3, counts[TurnKind.TOOL])
        assertEquals(1, counts[TurnKind.JS])
    }

    @Test
    fun `empty inputs yield empty`() {
        assertTrue(mergeTurnRecords(emptyList(), emptyList(), emptyList()).isEmpty())
    }
}
