package com.mamba.picme.domain.chat.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownSegmenterTest {

    @Test
    fun `splits prose and fenced code block`() {
        val md = "intro\n```kotlin\nval x = 1\nval y = 2\n```\noutro"
        val segs = segmentMarkdown(md)
        assertEquals(3, segs.size)
        assertEquals(SegmentType.MARKDOWN, segs[0].type)
        assertEquals("intro", segs[0].text)
        assertEquals(SegmentType.CODE, segs[1].type)
        assertTrue(segs[1].text.contains("```kotlin"))
        assertEquals(SegmentType.MARKDOWN, segs[2].type)
        assertEquals("outro", segs[2].text)
    }

    @Test
    fun `code fence lines belong to code segment`() {
        val md = "```\nfoo\nbar\n```"
        val segs = segmentMarkdown(md)
        assertEquals(1, segs.size)
        assertEquals(SegmentType.CODE, segs[0].type)
        assertEquals("foo\nbar", extractCodeBody(segs[0].text))
        assertEquals(2, codeLineCount(extractCodeBody(segs[0].text)))
    }

    @Test
    fun `unterminated fence streams remaining lines as code`() {
        val md = "text\n```python\nprint(1)"
        val segs = segmentMarkdown(md)
        assertEquals(2, segs.size)
        assertEquals(SegmentType.CODE, segs[1].type)
        assertEquals("print(1)", extractCodeBody(segs[1].text))
    }

    @Test
    fun `pipe inside code fence is not treated as table`() {
        val md = "```\na|b\nc|d\n```"
        val segs = segmentMarkdown(md)
        assertEquals(1, segs.size)
        assertEquals(SegmentType.CODE, segs[0].type)
    }

    @Test
    fun `table segment still recognized outside fence`() {
        val md = "| h1 | h2 |\n| --- | --- |\n| a | b |"
        val segs = segmentMarkdown(md)
        assertEquals(1, segs.size)
        assertEquals(SegmentType.TABLE, segs[0].type)
    }

    @Test
    fun `parse table strips delimiter row and trims cells`() {
        val table = parseMarkdownTable("| h1 | h2 |\n| --- | ---: |\n| a | b |\n| c | d |")
        assertEquals(listOf("h1", "h2"), table.header)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), table.rows)
    }

    @Test
    fun `parse table without edge pipes`() {
        val table = parseMarkdownTable("h1 | h2\n--- | ---\na | b")
        assertEquals(listOf("h1", "h2"), table.header)
        assertEquals(listOf(listOf("a", "b")), table.rows)
    }

    @Test
    fun `parse table keeps escaped pipe inside cell`() {
        val table = parseMarkdownTable("| h1 | h2 |\n| --- | --- |\n| a \\| b | c |")
        assertEquals(listOf(listOf("a | b", "c")), table.rows)
    }

    @Test
    fun `parse table pads short rows and strips inline markers`() {
        val table = parseMarkdownTable("| h1 | h2 | h3 |\n| --- | --- | --- |\n| **a** | `b` |")
        assertEquals(listOf(listOf("a", "b", "")), table.rows)
    }

    @Test
    fun `parse malformed table returns empty`() {
        val table = parseMarkdownTable("only one line")
        assertTrue(table.header.isEmpty())
        assertTrue(table.rows.isEmpty())
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
