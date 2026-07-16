package com.mamba.picme.agent.core.inference.remote.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.tool.ToolExecutionRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCallCommandParserFeedbackTest {

    private val context = AgentContext(scene = AgentScene.CHAT)

    private fun request(name: String, arguments: String): ToolExecutionRequest =
        ToolExecutionRequest.builder()
            .id("call_1")
            .name(name)
            .arguments(arguments)
            .build()

    @Test
    fun `parse feedback ordinal like`() {
        val req = request("feedback", """{"target":"ordinal:2","action":"like"}""")
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.RecordMediaFeedback
        assertEquals(FeedbackTarget.Ordinal(2), cmd.target)
        assertEquals(FeedbackAction.LIKE, cmd.action)
    }

    @Test
    fun `parse feedback dislike with default target`() {
        val req = request("feedback", """{"action":"dislike"}""")
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.RecordMediaFeedback
        assertEquals(FeedbackTarget.LastShown, cmd.target)
        assertEquals(FeedbackAction.DISLIKE, cmd.action)
    }

    @Test
    fun `parse more with description target`() {
        val req = request("more", """{"target":"desc:海边"}""")
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.MoreLikeThis
        assertEquals(FeedbackTarget.Description("海边"), cmd.target)
    }

    @Test
    fun `parse more with mediaId target`() {
        val req = request("more", """{"target":"mediaId:img_001"}""")
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.MoreLikeThis
        assertEquals(FeedbackTarget.MediaId("img_001"), cmd.target)
    }

    @Test
    fun `parse exclude constraint`() {
        val req = request("exclude", """{"constraint":"夜景"}""")
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.ExcludeConstraint
        assertEquals("夜景", cmd.constraint)
    }

    @Test
    fun `parseAll keeps feedback commands`() {
        val requests = listOf(
            request("feedback", """{"target":"last","action":"like"}"""),
            request("exclude", """{"constraint":"模糊"}""")
        )
        val commands = ToolCallCommandParser.parseAll(requests, context)
        assertEquals(2, commands.size)
        assertEquals(AgentCommand.RecordMediaFeedback::class.java, commands[0]::class.java)
        assertEquals(AgentCommand.ExcludeConstraint::class.java, commands[1]::class.java)
    }
}
