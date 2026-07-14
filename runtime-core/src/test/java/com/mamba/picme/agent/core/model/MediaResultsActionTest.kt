package com.mamba.picme.agent.core.model

import com.mamba.picme.agent.core.model.context.AgentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaResultsActionTest {
    @Test
    fun `getCommandId resolves MediaResults`() {
        val action = AgentAction.MediaResults(
            commandId = 42,
            query = "海边",
            mediaIds = listOf(1L, 2L, 3L),
            totalCount = 3,
            isRefinement = true
        )
        assertEquals(42, AgentAction.getCommandId(action))
        assertEquals(listOf(1L, 2L, 3L), action.mediaIds)
        assertEquals(true, action.isRefinement)
    }

    @Test
    fun `MediaResults is considered success`() {
        val action = AgentAction.MediaResults(
            commandId = 1, query = "x", mediaIds = emptyList(), totalCount = 0, isRefinement = false
        )
        assertTrue(action.isSuccess)
    }
}
