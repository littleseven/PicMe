package com.mamba.picme.data.remote.picme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSseParserTest {
    @Test
    fun `parses session + assistant_text + done`() {
        val sse = "event: session\ndata: {\"sid\":\"s1\"}\n\n" +
            "event: assistant_text\ndata: {\"delta\":\"hi\"}\n\n" +
            "event: done\ndata: {}\n\n"
        val ev = ClaudeSseParser.parse(sse)
        assertEquals(3, ev.size)
        assertEquals("s1", (ev[0] as ClaudeEvent.Session).sid)
        assertEquals("hi", (ev[1] as ClaudeEvent.AssistantText).delta)
        assertTrue(ev[2] is ClaudeEvent.Done)
    }

    @Test
    fun `parses tool_use and file_change and tool_result`() {
        val sse = "event: tool_use\ndata: {\"tool\":\"Bash\",\"input\":{\"command\":\"ls\"}}\n\n" +
            "event: file_change\ndata: {\"path\":\"a.kt\",\"action\":\"modified\"}\n\n" +
            "event: tool_result\ndata: {\"ok\":true,\"summary\":\"ok\"}\n\n"
        val ev = ClaudeSseParser.parse(sse)
        assertEquals("Bash", (ev[0] as ClaudeEvent.ToolUse).tool)
        assertEquals("a.kt", (ev[1] as ClaudeEvent.FileChange).path)
        assertTrue((ev[2] as ClaudeEvent.ToolResult).ok)
    }

    @Test
    fun `parses error event`() {
        val sse = "event: error\ndata: {\"message\":\"boom\"}\n\n"
        val ev = ClaudeSseParser.parse(sse)
        assertEquals("boom", (ev[0] as ClaudeEvent.Error).message)
    }

    @Test
    fun `parses cost event`() {
        val sse = "event: cost\ndata: {\"turns\":3,\"cents\":12}\n\n"
        val ev = ClaudeSseParser.parse(sse)
        val cost = ev[0] as ClaudeEvent.Cost
        assertEquals(3, cost.turns)
        assertEquals(12, cost.cents)
    }

    @Test
    fun `parse app_tool_request event`() {
        val sse = """
            event: app_tool_request
            data: {"requestId":"abc123","tool":"app_get_logs","args":{"filter":"Tag","lines":100}}

        """.trimIndent()
        val events = ClaudeSseParser.parse(sse)
        assertEquals(1, events.size)
        val ev = events[0] as ClaudeEvent.AppToolRequest
        assertEquals("abc123", ev.requestId)
        assertEquals("app_get_logs", ev.tool)
        assertEquals("Tag", ev.args.optString("filter"))
        assertEquals(100, ev.args.optInt("lines"))
    }

    @Test
    fun `parse app_tool_request with missing args defaults to empty json`() {
        val sse = "event: app_tool_request\ndata: {\"requestId\":\"r1\",\"tool\":\"app_get_crash_trace\"}\n\n"
        val ev = ClaudeSseParser.parse(sse).single() as ClaudeEvent.AppToolRequest
        assertEquals(0, ev.args.length())
    }

    @Test
    fun `app_tool_request with blank requestId is dropped`() {
        // 无 requestId 无法回传 tool result（对齐 session 分支 blank sid 丢弃先例）
        val sse = "event: app_tool_request\ndata: {\"requestId\":\"\",\"tool\":\"app_get_logs\"}\n\n"
        assertTrue(ClaudeSseParser.parse(sse).isEmpty())
        val missing = "event: app_tool_request\ndata: {\"tool\":\"app_get_logs\"}\n\n"
        assertTrue(ClaudeSseParser.parse(missing).isEmpty())
    }

    @Test
    fun `parse existing events not broken`() {
        val sse = "event: assistant_text\ndata: {\"delta\":\"hi\"}\n\n"
        val events = ClaudeSseParser.parse(sse)
        assertTrue(events.single() is ClaudeEvent.AssistantText)
    }

    @Test
    fun `ignores malformed lines`() {
        val sse = "garbage\n\nevent: done\ndata: {}\n\n"
        val ev = ClaudeSseParser.parse(sse)
        assertEquals(1, ev.size)
        assertTrue(ev[0] is ClaudeEvent.Done)
    }

    @Test
    fun `parses done with truncation fields`() {
        val sse = "event: done\ndata: {\"turns\":20,\"truncated\":true,\"reason\":\"max_turns\"}\n\n"
        val ev = ClaudeSseParser.parse(sse).single() as ClaudeEvent.Done
        assertEquals(20, ev.turns)
        assertTrue(ev.truncated)
        assertEquals("max_turns", ev.reason)
    }

    @Test
    fun `parses done without truncation defaults`() {
        val sse = "event: done\ndata: {\"turns\":3}\n\n"
        val ev = ClaudeSseParser.parse(sse).single() as ClaudeEvent.Done
        assertEquals(3, ev.turns)
        assertFalse(ev.truncated)
        assertNull(ev.reason)
    }

    @Test
    fun `parses error with truncation fields`() {
        val sse = "event: error\ndata: {\"message\":\"phase timeout 300s\",\"truncated\":true,\"reason\":\"phase_timeout\"}\n\n"
        val ev = ClaudeSseParser.parse(sse).single() as ClaudeEvent.Error
        assertEquals("phase timeout 300s", ev.message)
        assertTrue(ev.truncated)
        assertEquals("phase_timeout", ev.reason)
    }
}
