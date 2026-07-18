package com.mamba.picme.agent.core.model.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回归：媒体命令历史回退原始 JSON（消除「[method] X」摘要对 LLM 输出格式的诱导）。
 */
class MemorySummaryTest {

    @Test
    fun `TextReply returns its message`() {
        assertEquals(
            "你好呀",
            summarizeCommandsForMemory(listOf(AgentCommand.TextReply(message = "你好呀")))
        )
    }

    @Test
    fun `media commands return null to fall back to raw JSON`() {
        assertNull(summarizeCommandsForMemory(listOf(AgentCommand.SearchMedia(query = "海边"))))
        assertNull(summarizeCommandsForMemory(listOf(AgentCommand.RefineMediaSearch(constraint = "日落"))))
        assertNull(summarizeCommandsForMemory(listOf(AgentCommand.ExcludeConstraint(constraint = "夜景"))))
        assertNull(
            summarizeCommandsForMemory(listOf(AgentCommand.MoreLikeThis(target = FeedbackTarget.LastShown)))
        )
    }

    @Test
    fun `multiple media commands return null`() {
        assertNull(
            summarizeCommandsForMemory(
                listOf(
                    AgentCommand.SearchMedia(query = "海边"),
                    AgentCommand.RefineMediaSearch(constraint = "日落")
                )
            )
        )
    }

    @Test
    fun `non-text non-media commands return null`() {
        assertNull(
            summarizeCommandsForMemory(listOf(AgentCommand.NavigateTo(destination = "gallery")))
        )
    }

    @Test
    fun `empty commands return null`() {
        assertNull(summarizeCommandsForMemory(emptyList()))
    }
}
