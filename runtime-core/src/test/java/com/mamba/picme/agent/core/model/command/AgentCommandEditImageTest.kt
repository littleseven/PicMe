package com.mamba.picme.agent.core.model.command

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentCommandEditImageTest {

    @Test
    fun `EditImage command has correct method name`() {
        val command = AgentCommand.EditImage(
            imageUri = "file:///test.jpg",
            params = EditParams(brightness = EditParams.Delta(15f)),
            explanation = "调亮一点"
        )
        assertEquals("edit_image", AgentCommand.getMethodName(command))
        assertEquals("file:///test.jpg", command.imageUri)
    }
}
