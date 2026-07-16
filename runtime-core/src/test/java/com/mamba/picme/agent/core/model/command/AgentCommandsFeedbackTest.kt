package com.mamba.picme.agent.core.model.command

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentCommandsFeedbackTest {
    @Test
    fun `RecordMediaFeedback holds target action queryHint`() {
        val cmd = AgentCommand.RecordMediaFeedback(
            target = FeedbackTarget.Ordinal(3),
            action = FeedbackAction.LIKE,
            queryHint = "海边"
        )
        assertEquals(FeedbackTarget.Ordinal(3), cmd.target)
        assertEquals(FeedbackAction.LIKE, cmd.action)
        assertEquals("海边", cmd.queryHint)
    }

    @Test
    fun `getMethodName maps feedback commands to short names`() {
        assertEquals(
            "feedback",
            AgentCommand.getMethodName(AgentCommand.RecordMediaFeedback(target = FeedbackTarget.LastShown, action = FeedbackAction.LIKE))
        )
        assertEquals(
            "more",
            AgentCommand.getMethodName(AgentCommand.MoreLikeThis(target = FeedbackTarget.LastShown))
        )
        assertEquals(
            "exclude",
            AgentCommand.getMethodName(AgentCommand.ExcludeConstraint(constraint = "夜景"))
        )
    }
}
