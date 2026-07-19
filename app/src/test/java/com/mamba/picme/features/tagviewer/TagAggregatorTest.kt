package com.mamba.picme.features.tagviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class TagAggregatorTest {

    private fun item(parsed: ParsedTags?): PhotoTagsItem =
        PhotoTagsItem(mediaId = 0, uri = "", fileName = "x", parsed = parsed, rawJson = "")

    @Test
    fun `empty input returns empty groups`() {
        val result = TagAggregator.aggregate(emptyList())
        assertEquals(0, result.scenes.size)
        assertEquals(0, result.objects.size)
        assertEquals(0, result.tags.size)
    }

    @Test
    fun `unparsed items are skipped`() {
        val result = TagAggregator.aggregate(listOf(item(null), item(null)))
        assertEquals(0, result.scenes.size)
    }

    @Test
    fun `counts are accumulated across photos`() {
        val p1 = ParsedTags(scene = "海滩", objects = listOf("伞"), tags = listOf("夏天"))
        val p2 = ParsedTags(scene = "海滩", objects = listOf("伞", "人"), tags = listOf("夏天", "度假"))
        val result = TagAggregator.aggregate(listOf(item(p1), item(p2)))

        assertEquals(listOf(TagCount("海滩", 2)), result.scenes)
        assertEquals(TagCount("伞", 2), result.objects[0])
        assertEquals(TagCount("人", 1), result.objects[1])
        assertEquals(TagCount("夏天", 2), result.tags[0])
        assertEquals(TagCount("度假", 1), result.tags[1])
    }

    @Test
    fun `results are sorted by count descending`() {
        val p = ParsedTags(tags = listOf("稀有", "常见", "常见", "常见", "中等", "中等"))
        val result = TagAggregator.aggregate(listOf(item(p)))

        assertEquals("常见", result.tags[0].label)
        assertEquals(3, result.tags[0].count)
        assertEquals("中等", result.tags[1].label)
        assertEquals(2, result.tags[1].count)
        assertEquals("稀有", result.tags[2].label)
        assertEquals(1, result.tags[2].count)
    }

    @Test
    fun `blank labels are ignored`() {
        val p = ParsedTags(scene = "  ", tags = listOf("有效", ""))
        val result = TagAggregator.aggregate(listOf(item(p)))

        assertEquals(0, result.scenes.size)
        assertEquals(1, result.tags.size)
        assertEquals("有效", result.tags[0].label)
    }
}
