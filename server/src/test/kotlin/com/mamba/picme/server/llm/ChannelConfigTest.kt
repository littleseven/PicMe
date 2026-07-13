package com.mamba.picme.server.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelConfigTest {
    @Test
    fun `parseModelMap parses json object to map`() {
        val map = parseModelMap("""{"deepseek-chat":"glm-5.2","kimi-k2.6":"glm-5.2"}""")
        assertEquals("glm-5.2", map["deepseek-chat"])
        assertEquals("glm-5.2", map["kimi-k2.6"])
    }

    @Test
    fun `parseModelMap empty or bad json returns empty map`() {
        assertTrue(parseModelMap("").isEmpty())
        assertTrue(parseModelMap("not json").isEmpty())
    }

    @Test
    fun `serializeModelMap round trips through parseModelMap`() {
        val original = mapOf("a" to "b", "c" to "d")
        val json = serializeModelMap(original)
        assertEquals(original, parseModelMap(json))
    }

    @Test
    fun `parseModelMapLines parses key=value lines ignoring blanks and comments`() {
        val text = """
            # 注释行
            deepseek-chat=glm-5.2

            kimi-k2.6=glm-5.2
        """.trimIndent()
        val map = parseModelMapLines(text)
        assertEquals("glm-5.2", map["deepseek-chat"])
        assertEquals("glm-5.2", map["kimi-k2.6"])
        assertEquals(2, map.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseModelMapLines throws on line without equals`() {
        parseModelMapLines("deepseek-chat glm-5.2")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseModelMapLines throws on empty value`() {
        parseModelMapLines("deepseek-chat=")
    }

    @Test
    fun `renderModelMapLines produces key=value per line`() {
        val text = renderModelMapLines(mapOf("a" to "b"))
        assertEquals("a=b", text)
    }
}
