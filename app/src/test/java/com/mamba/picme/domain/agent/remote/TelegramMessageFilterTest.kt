package com.mamba.picme.domain.agent.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMessageFilterTest {

    @Test
    fun allowed_blank_refuses_all_fail_closed() {
        assertFalse(TelegramMessageFilter.shouldAccept("123", "  "))
        assertFalse(TelegramMessageFilter.shouldAccept("123", ""))
    }

    @Test
    fun matches_allowed_chat_id() {
        assertTrue(TelegramMessageFilter.shouldAccept("123", "123"))
    }

    @Test
    fun mismatch_rejected() {
        assertFalse(TelegramMessageFilter.shouldAccept("123", "999"))
    }

    @Test
    fun null_chat_id_rejected() {
        assertFalse(TelegramMessageFilter.shouldAccept(null, "123"))
    }
}
