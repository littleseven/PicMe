package com.mamba.picme.agent.core.inference.local.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.EditParams
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandParserEditImageTest {

    private val context = AgentContext(scene = AgentScene.CHAT)

    @Test
    fun `parse edit_image with absolute smoothing and filter name`() {
        val json = """
            {"method":"edit_image","args":{"image_uri":"file:///test.jpg","smoothing":35,"filter_name":"FILM_GOLD","explanation":"磨皮并换成胶片风"}}
        """.trimIndent()
        val command = LocalCommandParser.parseLlmResponse(json, context)
        assertTrue(command is AgentCommand.EditImage)
        val edit = command as AgentCommand.EditImage
        assertEquals("file:///test.jpg", edit.imageUri)
        assertEquals(35f, (edit.params.smoothing as EditParams.Absolute).value, 0.001f)
        assertEquals("FILM_GOLD", (edit.params.filterName as EditParams.AbsoluteString).value)
        assertEquals("磨皮并换成胶片风", edit.explanation)
    }

    @Test
    fun `parse edit_image with delta brightness`() {
        val json = """
            {"method":"edit_image","args":{"brightness_delta":15}}
        """.trimIndent()
        val command = LocalCommandParser.parseLlmResponse(json, context)
        assertTrue(command is AgentCommand.EditImage)
        val edit = command as AgentCommand.EditImage
        assertEquals(15f, (edit.params.brightness as EditParams.Delta).value, 0.001f)
    }

    @Test
    fun `parse edit_image with filter intensity`() {
        val json = """
            {"method":"edit_image","args":{"filter_name":"COOL","filter_intensity":0.4}}
        """.trimIndent()
        val command = LocalCommandParser.parseLlmResponse(json, context)
        assertTrue(command is AgentCommand.EditImage)
        val edit = command as AgentCommand.EditImage
        assertEquals(EditParams.AbsoluteString("COOL"), edit.params.filterName)
        assertEquals(0.4f, edit.params.filterIntensity)
    }
}
