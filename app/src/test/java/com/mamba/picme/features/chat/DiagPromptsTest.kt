package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagPromptsTest {

    @Test
    fun `reply without marker is not ready`() {
        val r = DiagPrompts.parseDiagReply("请问在哪个页面遇到的？")
        assertFalse(r.ready)
        assertEquals("请问在哪个页面遇到的？", r.displayText)
        assertNull(r.summary)
    }

    @Test
    fun `marker splits display text and summary`() {
        val r = DiagPrompts.parseDiagReply("信息够了，可以提交。\n[DIAG_READY]\n问题现象：打开相册崩溃\n复现步骤：必现")
        assertTrue(r.ready)
        assertEquals("信息够了，可以提交。", r.displayText)
        assertEquals("问题现象：打开相册崩溃\n复现步骤：必现", r.summary)
    }

    @Test
    fun `marker without summary degrades to manual submit with null summary`() {
        // 解析失败兜底：ready=true、summary=null，用户仍可手动提交（退化为现状）
        val r = DiagPrompts.parseDiagReply("可以提交了 [DIAG_READY]")
        assertTrue(r.ready)
        assertNull(r.summary)
        assertEquals("可以提交了", r.displayText)
    }

    @Test
    fun `empty display text falls back to summary`() {
        val r = DiagPrompts.parseDiagReply("[DIAG_READY]\n问题现象：崩溃")
        assertTrue(r.ready)
        assertEquals("问题现象：崩溃", r.displayText)
    }

    @Test
    fun `summary is truncated to the server limit`() {
        val r = DiagPrompts.parseDiagReply("[DIAG_READY]\n" + "x".repeat(5000))
        assertEquals(DiagPrompts.MAX_SUMMARY_LEN, r.summary!!.length)
    }
}
