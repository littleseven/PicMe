package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.PageContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CommandExecutor 边界用例：recorder 异常不影响执行、traceId 透传、无 recorder 正常执行。
 */
class CommandExecutorTest {

    private data class Recorded(
        val capability: String,
        val commandType: String,
        val latencyMs: Long,
        val success: Boolean,
        val errorCode: Int?,
        val errorMessage: String?,
        val traceId: String?
    )

    private val recorded = mutableListOf<Recorded>()

    @After
    fun tearDown() {
        CommandExecutor.recorder = null
    }

    private fun installRecorder() {
        CommandExecutor.recorder = CommandExecutionRecorder { capability, commandType, latencyMs, success, errorCode, errorMessage, traceId ->
            recorded += Recorded(capability, commandType, latencyMs, success, errorCode, errorMessage, traceId)
        }
    }

    private fun fakeCapability(
        block: suspend (AgentCommand) -> Result<AgentAction>
    ) = object : BaseCapability() {
        override val name = "fake"
        override val description = "fake capability"
        override fun supportedCommands(): List<String> = listOf("flip_camera")
        override suspend fun execute(
            command: AgentCommand,
            context: AgentContext,
            pageContext: PageContext?
        ): Result<AgentAction> = block(command)
    }

    private val context = AgentContext(scene = AgentScene.CHAT)

    @Test
    fun `recorder exception does not affect command execution`() = runTest {
        CommandExecutor.recorder = CommandExecutionRecorder { _, _, _, _, _, _, _ ->
            error("boom in recorder")
        }
        val capability = fakeCapability { command ->
            Result.success(AgentAction.Success(command.commandId, command))
        }

        val result = CommandExecutor().execute(AgentCommand.FlipCamera(), context, null, capability)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `context traceId is passed to recorder`() = runTest {
        installRecorder()
        val capability = fakeCapability { command ->
            Result.success(AgentAction.Success(command.commandId, command))
        }
        val ctxWithTrace = AgentContext(scene = AgentScene.CHAT, traceId = "trace-123")

        CommandExecutor().execute(AgentCommand.FlipCamera(), ctxWithTrace, null, capability)

        assertEquals("trace-123", recorded.single().traceId)
    }

    @Test
    fun `null traceId passes through when context has none`() = runTest {
        installRecorder()
        val capability = fakeCapability { command ->
            Result.success(AgentAction.Success(command.commandId, command))
        }

        CommandExecutor().execute(AgentCommand.FlipCamera(), context, null, capability)

        assertNull(recorded.single().traceId)
    }

    @Test
    fun `null recorder executes normally`() = runTest {
        CommandExecutor.recorder = null
        val capability = fakeCapability { command ->
            Result.success(AgentAction.Success(command.commandId, command))
        }

        val result = CommandExecutor().execute(AgentCommand.FlipCamera(), context, null, capability)

        assertTrue(result.isSuccess)
        assertTrue(recorded.isEmpty())
    }
}
