package com.mamba.picme.agent.core.inference.remote.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.tool.ToolExecutionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCallCommandParserSearchIntentTest {

    private val context = AgentContext(scene = AgentScene.CHAT)

    private fun request(name: String, arguments: String): ToolExecutionRequest =
        ToolExecutionRequest.builder()
            .id("call_1")
            .name(name)
            .arguments(arguments)
            .build()

    @Test
    fun `parse search_media with intent`() {
        val req = request(
            "search_media",
            """{"query":"近半年小孩的照片","intent":{"time_range":{"start_ms":1735689600000,"end_ms":1751327999999},"keywords":["小孩"],"has_faces":true}}"""
        )
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.SearchMedia

        assertEquals("近半年小孩的照片", cmd.query)
        assertNotNull(cmd.intent)
        assertEquals(1735689600000L, cmd.intent!!.timeRange!!.startMs)
        assertEquals(1751327999999L, cmd.intent.timeRange!!.endMs)
        assertEquals(listOf("小孩"), cmd.intent.keywords)
        assertEquals(true, cmd.intent.hasFaces)
    }

    @Test
    fun `parse refine_media_search with time only intent`() {
        val req = request(
            "refine_media_search",
            """{"constraint":"只要近半年的","intent":{"time_range":{"start_ms":1735689600000,"end_ms":1751327999999}}}"""
        )
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.RefineMediaSearch

        assertEquals("只要近半年的", cmd.constraint)
        assertNotNull(cmd.intent)
        assertEquals(1735689600000L, cmd.intent!!.timeRange!!.startMs)
        assertTrue(cmd.intent.keywords.isEmpty())
    }

    @Test
    fun `parse search_media without intent keeps null`() {
        val req = request("search_media", """{"query":"猫的照片"}""")
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.SearchMedia

        assertEquals("猫的照片", cmd.query)
        assertNull(cmd.intent)
    }

    @Test
    fun `parse search_media with empty intent returns null intent`() {
        val req = request("search_media", """{"query":"随便看看","intent":{}}""")
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.SearchMedia

        assertEquals("随便看看", cmd.query)
        assertNull(cmd.intent)
    }
}
