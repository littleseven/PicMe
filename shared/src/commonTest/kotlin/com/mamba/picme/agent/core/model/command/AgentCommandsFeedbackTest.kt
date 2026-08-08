package com.mamba.picme.agent.core.model.command

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentCommandsFeedbackTest {
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
