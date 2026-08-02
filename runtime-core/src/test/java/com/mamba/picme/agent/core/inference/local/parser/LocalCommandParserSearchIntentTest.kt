package com.mamba.picme.agent.core.inference.local.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalCommandParserSearchIntentTest {

    private val context = AgentContext(scene = AgentScene.CHAT)

    @Test
    fun `parse search_media with snake_case intent fields`() {
        // snake_case 是 L2 prompt 协议的主格式（见 LocalPromptBuilder intent 字段说明）
        val json = """[{"method":"search_media","params":{"query":"去年夏天海边","intent":{"time_range":{"start_ms":1704067200000,"end_ms":1735689599999},"keywords":["海边"],"has_faces":true}}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)

        assertEquals(1, commands.size)
        val cmd = commands[0] as AgentCommand.SearchMedia
        assertNotNull(cmd.intent)
        assertEquals(1704067200000L, cmd.intent!!.timeRange!!.startMs)
        assertEquals(listOf("海边"), cmd.intent!!.keywords)
        assertEquals(true, cmd.intent!!.hasFaces)
    }

    @Test
    fun `parse search_media without intent keeps null`() {
        val json = """[{"method":"search_media","params":{"query":"日落"}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)

        assertEquals(1, commands.size)
        val cmd = commands[0] as AgentCommand.SearchMedia
        assertEquals(null, cmd.intent)
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
