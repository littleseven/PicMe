package com.mamba.picme.agent.core.inference.local.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地 L2 解析器的 feedback/more/exclude 分支是独立实现
 * （LocalCommandParser.parseCommandByMethod + parseFeedbackTarget），
 * 远程 ToolCallCommandParser 的同名测试保护不到这条路径。
 */
class LocalCommandParserFeedbackTest {

    private val context = AgentContext(scene = AgentScene.CHAT)

    @Test
    fun `parse feedback ordinal like`() {
        val json = """[{"method":"feedback","params":{"target":"ordinal:3","action":"like"}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)
        assertEquals(1, commands.size)
        val cmd = commands[0] as AgentCommand.RecordMediaFeedback
        assertEquals(FeedbackTarget.Ordinal(3), cmd.target)
        assertEquals(FeedbackAction.LIKE, cmd.action)
    }

    @Test
    fun `parse more with description target`() {
        val json = """[{"method":"more","params":{"target":"desc:海边"}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)
        val cmd = commands[0] as AgentCommand.MoreLikeThis
        assertEquals(FeedbackTarget.Description("海边"), cmd.target)
    }

    @Test
    fun `parse exclude constraint`() {
        val json = """[{"method":"exclude","params":{"constraint":"夜景"}}]"""
        val commands = LocalCommandParser.parseL2BatchResponse(json, context)
        val cmd = commands[0] as AgentCommand.ExcludeConstraint
        assertEquals("夜景", cmd.constraint)
    }
}
