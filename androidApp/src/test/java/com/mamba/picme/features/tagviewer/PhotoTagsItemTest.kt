package com.mamba.picme.features.tagviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoTagsItemTest {

    private fun item(parsed: ParsedTags?) = PhotoTagsItem(
        mediaId = 0,
        uri = "",
        fileName = "x",
        parsed = parsed,
        rawJson = ""
    )

    @Test
    fun `labelSummary returns scene when available`() {
        val parsed = ParsedTags(scene = "海滩", tags = listOf("夏天"))
        assertEquals("海滩", item(parsed).labelSummary)
    }

    @Test
    fun `labelSummary falls back to tags when scene is blank`() {
        val parsed = ParsedTags(scene = "", tags = listOf("夏天", "度假"), objects = listOf("人"))
        assertEquals("夏天 · 度假", item(parsed).labelSummary)
    }

    @Test
    fun `labelSummary falls back to objects when scene and tags are blank`() {
        val parsed = ParsedTags(scene = "", objects = listOf("伞", "人"))
        assertEquals("伞 · 人", item(parsed).labelSummary)
    }

    @Test
    fun `labelSummary returns empty when no labels`() {
        assertEquals("", item(null).labelSummary)
    }

    @Test
    fun `labelSummary returns empty when parsed has only blank fields`() {
        val parsed = ParsedTags()
        assertEquals("", item(parsed).labelSummary)
    }
}
