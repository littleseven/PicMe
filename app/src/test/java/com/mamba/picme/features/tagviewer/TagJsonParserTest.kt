package com.mamba.picme.features.tagviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagJsonParserTest {

    @Test
    fun `null or blank labels return null`() {
        assertNull(TagJsonParser.parse(null))
        assertNull(TagJsonParser.parse(""))
        assertNull(TagJsonParser.parse("   "))
    }

    @Test
    fun `invalid json returns null`() {
        assertNull(TagJsonParser.parse("not a json"))
        assertNull(TagJsonParser.parse("{broken"))
    }

    @Test
    fun `full well-formed json parses all fields`() {
        val json = """
            {"face":{"count":2,"selfie":false,"groupPhoto":true,"personIds":[10,20]},
             "scene":"海滩","activity":"游泳",
             "objects":["人","伞"],"tags":["夏天","度假"],
             "qwenSummary":"海边游泳的人"}
        """.trimIndent()

        val parsed = TagJsonParser.parse(json)
        assertNotNull(parsed)
        val tags = parsed!!
        assertEquals("海滩", tags.scene)
        assertEquals("游泳", tags.activity)
        assertEquals(listOf("人", "伞"), tags.objects)
        assertEquals(listOf("夏天", "度假"), tags.tags)
        assertEquals("海边游泳的人", tags.summary)
        assertEquals(2, tags.face?.count)
        assertEquals(false, tags.face?.selfie)
        assertEquals(true, tags.face?.groupPhoto)
        assertEquals(listOf(10L, 20L), tags.face?.personIds)
    }

    @Test
    fun `missing fields default to empty`() {
        val json = """{"scene":"餐厅"}"""

        val parsed = TagJsonParser.parse(json)
        assertNotNull(parsed)
        val tags = parsed!!
        assertEquals("餐厅", tags.scene)
        assertEquals("", tags.activity)
        assertTrue(tags.objects.isEmpty())
        assertTrue(tags.tags.isEmpty())
        assertEquals("", tags.summary)
        assertNull(tags.face)
    }

    @Test
    fun `old pass1-only format with face only parses without crash`() {
        val json = """{"face":{"count":0}}"""

        val parsed = TagJsonParser.parse(json)
        assertNotNull(parsed)
        val tags = parsed!!
        assertEquals("", tags.scene)
        assertEquals(0, tags.face?.count)
    }

    @Test
    fun `blank string values are treated as absent`() {
        val json = """{"scene":"  ","activity":""}"""

        val parsed = TagJsonParser.parse(json)
        assertNotNull(parsed)
        val tags = parsed!!
        assertEquals("", tags.scene)
        assertEquals("", tags.activity)
    }

    @Test
    fun `legacy json array labels are parsed into tags`() {
        val json = """["猫","户外","食物"]"""

        val parsed = TagJsonParser.parse(json)
        assertNotNull(parsed)
        val tags = parsed!!
        assertEquals("", tags.scene)
        assertEquals(emptyList<String>(), tags.objects)
        assertEquals(listOf("猫", "户外", "食物"), tags.tags)
    }

    @Test
    fun `empty json array returns null`() {
        assertNull(TagJsonParser.parse("[]"))
    }
}
