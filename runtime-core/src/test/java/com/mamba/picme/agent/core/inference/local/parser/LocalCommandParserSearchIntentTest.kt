package com.mamba.picme.agent.core.inference.local.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandParserSearchIntentTest {

    private val context = AgentContext(scene = AgentScene.CHAT)

    @Test
    fun `parse search_media with time range and keywords`() {
        val json = """[{"method":"search_media","params":{"query":"近半年小孩的照片","intent":{"time_range":{"start_ms":1735689600000,"end_ms":1751327999999},"keywords":["小孩"],"has_faces":true}}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)

        assertEquals(1, commands.size)
        val cmd = commands[0] as AgentCommand.SearchMedia
        assertEquals("近半年小孩的照片", cmd.query)
        assertNotNull(cmd.intent)
        assertEquals(1735689600000L, cmd.intent!!.timeRange!!.startMs)
        assertEquals(1751327999999L, cmd.intent.timeRange!!.endMs)
        assertEquals(listOf("小孩"), cmd.intent.keywords)
        assertEquals(true, cmd.intent.hasFaces)
    }

    @Test
    fun `parse refine_media_search with time only intent`() {
        val json = """[{"method":"refine_media_search","params":{"constraint":"只要近半年的","intent":{"time_range":{"start_ms":1735689600000,"end_ms":1751327999999}}}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)

        assertEquals(1, commands.size)
        val cmd = commands[0] as AgentCommand.RefineMediaSearch
        assertEquals("只要近半年的", cmd.constraint)
        assertNotNull(cmd.intent)
        assertEquals(1735689600000L, cmd.intent!!.timeRange!!.startMs)
        assertTrue(cmd.intent.keywords.isEmpty())
    }

    @Test
    fun `parse search_media without intent keeps null`() {
        val json = """[{"method":"search_media","params":{"query":"猫的照片"}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)

        assertEquals(1, commands.size)
        val cmd = commands[0] as AgentCommand.SearchMedia
        assertEquals("猫的照片", cmd.query)
        assertNull(cmd.intent)
    }

    @Test
    fun `parse search_media with empty intent returns null intent`() {
        val json = """[{"method":"search_media","params":{"query":"随便看看","intent":{}}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)

        assertEquals(1, commands.size)
        val cmd = commands[0] as AgentCommand.SearchMedia
        assertEquals("随便看看", cmd.query)
        assertNull(cmd.intent)
    }

    @Test
    fun `parse search_media with camelCase intent fields`() {
        val json = """[{"method":"search_media","params":{"query":"上海合照","intent":{"timeRange":{"startMs":1704067200000,"endMs":1735689599999},"locationKeywords":["上海"],"hasFaces":true}}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)

        assertEquals(1, commands.size)
        val cmd = commands[0] as AgentCommand.SearchMedia
        assertNotNull(cmd.intent)
        assertEquals(1704067200000L, cmd.intent!!.timeRange!!.startMs)
        assertEquals(listOf("上海"), cmd.intent.locationKeywords)
        assertEquals(true, cmd.intent.hasFaces)
    }
}
