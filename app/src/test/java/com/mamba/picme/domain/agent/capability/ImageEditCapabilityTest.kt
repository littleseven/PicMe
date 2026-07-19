package com.mamba.picme.domain.agent.capability

import android.content.Context
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.EditParams
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.domain.usecase.ChatEditProcessor
import com.mamba.picme.features.chat.ChatEditStateHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditCapabilityTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `execute returns success with output uri and updates state holder`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val processor = mockk<ChatEditProcessor>(relaxed = true)
        val stateHolder = ChatEditStateHolder()
        val capability = ImageEditCapability(context, processor, stateHolder)

        val sessionId = "chat-123"
        val agentContext = AgentContext(scene = AgentScene.CHAT, memorySessionId = sessionId)
        val command = AgentCommand.EditImage(
            imageUri = "file:///input.jpg",
            params = EditParams(brightness = EditParams.Absolute(10f))
        )

        coEvery { processor.execute(any(), any(), any()) } returns Result.success("file:///output.jpg")

        val result = capability.execute(command, agentContext, null)

        assertTrue(result.isSuccess)
        val action = result.getOrThrow() as AgentAction.Success
        assertEquals(command.commandId, action.commandId)
        val returnedCommand = action.command as AgentCommand.EditImage
        assertEquals("file:///output.jpg", returnedCommand.imageUri)

        coVerify { processor.execute(context, "file:///input.jpg", any()) }

        val storedRecipe = stateHolder.get(sessionId)
        assertEquals("file:///input.jpg", storedRecipe.sourceUri)
        assertEquals(10f, storedRecipe.adjustments.brightness, 0.001f)
    }

    @Test
    fun `execute falls back to state holder source uri when command image uri is blank`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val processor = mockk<ChatEditProcessor>(relaxed = true)
        val stateHolder = ChatEditStateHolder()
        stateHolder.update("chat-456", com.mamba.picme.features.editor.EditRecipe(sourceUri = "file:///state.jpg"))
        val capability = ImageEditCapability(context, processor, stateHolder)

        val agentContext = AgentContext(scene = AgentScene.CHAT, memorySessionId = "chat-456")
        val command = AgentCommand.EditImage(
            imageUri = "",
            params = EditParams(contrast = EditParams.Delta(5f))
        )

        coEvery { processor.execute(any(), any(), any()) } returns Result.success("content://output")

        capability.execute(command, agentContext, null)

        coVerify { processor.execute(context, "file:///state.jpg", any()) }
    }

    @Test
    fun `execute returns error when no target uri available`() = runTest {
        val capability = ImageEditCapability(mockk(relaxed = true), mockk(relaxed = true), ChatEditStateHolder())
        val agentContext = AgentContext(scene = AgentScene.CHAT, memorySessionId = "chat-empty")
        val command = AgentCommand.EditImage(imageUri = "", params = EditParams())

        val result = capability.execute(command, agentContext, null)

        assertTrue(result.isSuccess)
        val action = result.getOrThrow() as AgentAction.Error
        assertEquals(command.commandId, action.commandId)
        assertEquals(AgentErrorCode.INVALID_PARAMS, action.errorCode)
    }

    @Test
    fun `execute returns error for unsupported command`() = runTest {
        val capability = ImageEditCapability(mockk(relaxed = true), mockk(relaxed = true), ChatEditStateHolder())
        val agentContext = AgentContext(scene = AgentScene.CHAT)
        val command = AgentCommand.NavigateTo(destination = "chat")

        val result = capability.execute(command, agentContext, null)

        assertTrue(result.isSuccess)
        val action = result.getOrThrow() as AgentAction.Error
        assertEquals(command.commandId, action.commandId)
        assertEquals(AgentErrorCode.METHOD_NOT_FOUND, action.errorCode)
    }

    @Test
    fun `execute returns error when processor fails`() = runTest {
        val processor = mockk<ChatEditProcessor>(relaxed = true)
        coEvery { processor.execute(any(), any(), any()) } returns Result.failure(RuntimeException("渲染失败"))

        val capability = ImageEditCapability(mockk<Context>(relaxed = true), processor, ChatEditStateHolder())
        val agentContext = AgentContext(scene = AgentScene.CHAT, memorySessionId = "chat-fail")
        val command = AgentCommand.EditImage(
            imageUri = "file:///input.jpg",
            params = EditParams()
        )

        val result = capability.execute(command, agentContext, null)

        assertTrue(result.isSuccess)
        val action = result.getOrThrow() as AgentAction.Error
        assertEquals(command.commandId, action.commandId)
        assertEquals(AgentErrorCode.INTERNAL_ERROR, action.errorCode)
    }

    @Test
    fun `supportedCommands returns edit_image only`() {
        val capability = ImageEditCapability(mockk(relaxed = true), mockk(relaxed = true), ChatEditStateHolder())
        assertEquals(listOf("edit_image"), capability.supportedCommands())
    }

    @Test
    fun `activeScenes returns CHAT only`() {
        val capability = ImageEditCapability(mockk(relaxed = true), mockk(relaxed = true), ChatEditStateHolder())
        assertEquals(listOf(com.mamba.picme.agent.core.runtime.state.SceneManager.Scene.CHAT), capability.activeScenes())
    }

    @Test
    fun `execute returns TextReply for unsupported erase`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.chat_edit_unsupported_erase) } returns " erase unsupported "
        val capability = ImageEditCapability(context, mockk(relaxed = true), ChatEditStateHolder())
        val command = AgentCommand.EditImage(
            imageUri = "file:///input.jpg",
            params = EditParams(),
            explanation = "[unsupported:erase]"
        )

        val result = capability.execute(command, AgentContext(scene = AgentScene.CHAT), null)

        assertTrue(result.isSuccess)
        val action = result.getOrThrow() as AgentAction.TextReply
        assertEquals(command.commandId, action.commandId)
        assertEquals(" erase unsupported ", action.message)
    }

    @Test
    fun `execute returns TextReply for unsupported local beauty`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.chat_edit_unsupported_local_beauty) } returns " local beauty unsupported "
        val capability = ImageEditCapability(context, mockk(relaxed = true), ChatEditStateHolder())
        val command = AgentCommand.EditImage(
            imageUri = "file:///input.jpg",
            params = EditParams(),
            explanation = "[unsupported:local_beauty]"
        )

        val result = capability.execute(command, AgentContext(scene = AgentScene.CHAT), null)

        assertTrue(result.isSuccess)
        val action = result.getOrThrow() as AgentAction.TextReply
        assertEquals(" local beauty unsupported ", action.message)
    }
}
