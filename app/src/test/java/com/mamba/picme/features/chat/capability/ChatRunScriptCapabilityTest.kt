package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentScene
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRunScriptCapabilityTest {

    private val context = AgentContext(scene = AgentScene.CHAT)
    private val capability = ChatRunScriptCapability.getInstance()

    @After
    fun tearDown() {
        capability.unbindDelegate()
    }

    @Test
    fun `execute returns TextReply with delegate result`() = runBlocking {
        capability.bindDelegate(object : ChatRunScriptCapability.Delegate {
            override suspend fun onRunScript(code: String): String = "RESULT:$code"
        })
        val result = capability.execute(
            AgentCommand.ExecuteScript(code = "abc"),
            context,
            null,
        ).getOrNull()
        assertTrue("expected TextReply, got $result", result is AgentAction.TextReply)
        assertEquals("RESULT:abc", (result as AgentAction.TextReply).message)
    }

    @Test
    fun `execute reports unavailable when no delegate`() = runBlocking {
        capability.unbindDelegate()
        val result = capability.execute(
            AgentCommand.ExecuteScript(code = "x"),
            context,
            null,
        ).getOrNull()
        assertTrue("expected Error, got $result", result is AgentAction.Error)
        assertEquals(
            AgentErrorCode.CAPABILITY_UNAVAILABLE,
            (result as AgentAction.Error).errorCode,
        )
    }

    @Test
    fun `unsupported command reports method not found`() = runBlocking {
        capability.bindDelegate(object : ChatRunScriptCapability.Delegate {
            override suspend fun onRunScript(code: String): String = ""
        })
        val result = capability.execute(
            AgentCommand.TextReply(message = "hi"),
            context,
            null,
        ).getOrNull()
        assertTrue(result is AgentAction.Error)
        assertEquals(
            AgentErrorCode.METHOD_NOT_FOUND,
            (result as AgentAction.Error).errorCode,
        )
    }
}
