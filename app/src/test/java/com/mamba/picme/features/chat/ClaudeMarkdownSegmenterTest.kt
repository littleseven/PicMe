package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeMarkdownSegmenterTest {

    @Test
    fun `splits prose and fenced code block`() {
        val md = "intro\n```kotlin\nval x = 1\nval y = 2\n```\noutro"
        val segs = segmentMarkdown(md)
        assertEquals(3, segs.size)
        assertEquals(AgentSegmentType.MARKDOWN, segs[0].type)
        assertEquals("intro", segs[0].text)
        assertEquals(AgentSegmentType.CODE, segs[1].type)
        assertTrue(segs[1].text.contains("```kotlin"))
        assertEquals(AgentSegmentType.MARKDOWN, segs[2].type)
        assertEquals("outro", segs[2].text)
    }

    @Test
    fun `code fence lines belong to code segment`() {
        val md = "```\nfoo\nbar\n```"
        val segs = segmentMarkdown(md)
        assertEquals(1, segs.size)
        assertEquals(AgentSegmentType.CODE, segs[0].type)
        assertEquals("foo\nbar", extractCodeBody(segs[0].text))
        assertEquals(2, codeLineCount(extractCodeBody(segs[0].text)))
    }

    @Test
    fun `unterminated fence streams remaining lines as code`() {
        val md = "text\n```python\nprint(1)"
        val segs = segmentMarkdown(md)
        assertEquals(2, segs.size)
        assertEquals(AgentSegmentType.CODE, segs[1].type)
        assertEquals("print(1)", extractCodeBody(segs[1].text))
    }

    @Test
    fun `pipe inside code fence is not treated as table`() {
        val md = "```\na|b\nc|d\n```"
        val segs = segmentMarkdown(md)
        assertEquals(1, segs.size)
        assertEquals(AgentSegmentType.CODE, segs[0].type)
    }

    @Test
    fun `table segment still recognized outside fence`() {
        val md = "| h1 | h2 |\n| --- | --- |\n| a | b |"
        val segs = segmentMarkdown(md)
        assertEquals(1, segs.size)
        assertEquals(AgentSegmentType.TABLE, segs[0].type)
    }

    @Test
    fun `preview code takes first n lines`() {
        val code = "1\n2\n3\n4\n5"
        assertEquals("1\n2\n3", previewCode(code, 3))
    }

    @Test
    fun `extract body with no fence returns as-is`() {
        assertEquals("plain", extractCodeBody("plain"))
    }
}
