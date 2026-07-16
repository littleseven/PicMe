package com.mamba.picme.agent.core.model.command

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackActionTest {
    @Test
    fun `FeedbackAction has LIKE DISLIKE MORE_LIKE_THIS`() {
        assertEquals(3, FeedbackAction.entries.size)
        assertEquals("LIKE", FeedbackAction.LIKE.name)
        assertEquals("DISLIKE", FeedbackAction.DISLIKE.name)
        assertEquals("MORE_LIKE_THIS", FeedbackAction.MORE_LIKE_THIS.name)
    }
}
