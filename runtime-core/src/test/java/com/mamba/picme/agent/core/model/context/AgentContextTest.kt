package com.mamba.picme.agent.core.model.context

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentContextTest {
    @Test
    fun `AgentContext can carry recent search snapshots`() {
        val snapshot = SearchResultSnapshot(
            query = "海边",
            results = listOf(ResultItem("m1", listOf("海", "日落"))),
            totalCount = 1,
            isRefinement = false,
            timestamp = 1234L
        )
        val ctx = AgentContext(
            scene = AgentScene.CHAT,
            recentSearchResults = listOf(snapshot)
        )
        assertEquals(1, ctx.recentSearchResults.size)
        assertEquals("海边", ctx.recentSearchResults[0].query)
        assertEquals("m1", ctx.recentSearchResults[0].results[0].mediaId)
    }
}
