package com.mamba.picme.agent.core.inference.remote.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
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
class ToolCallCommandParserRunScriptTest {

    private val context = AgentContext(scene = AgentScene.CHAT)

    private fun request(name: String, arguments: String): ToolExecutionRequest =
        ToolExecutionRequest.builder()
            .id("call_1")
            .name(name)
            .arguments(arguments)
            .build()

    @Test
    fun `parse run_gallery_script with simple code`() {
        val req = request("run_gallery_script", """{"code":"return 1 + 2;"}""")
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.ExecuteScript
        assertEquals("return 1 + 2;", cmd.code)
    }

    @Test
    fun `parse run_gallery_script with double-quoted js code`() {
        // JS code 含双引号/点/括号；验证 JSONObject 健壮往返（手写正则会在此截断）
        val code = """var s = bridge.call("gallery.summary"); return s.totalMedia;"""
        val escaped = code.replace("\\", "\\\\").replace("\"", "\\\"")
        val req = request("run_gallery_script", """{"code":"$escaped"}""")
        val cmd = ToolCallCommandParser.parse(req, context) as AgentCommand.ExecuteScript
        assertEquals(code, cmd.code)
    }
}
