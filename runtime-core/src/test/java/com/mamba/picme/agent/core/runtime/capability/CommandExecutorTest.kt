package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.PageContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `success branch records metrics with no error`() = runTest {
        installRecorder()
        val capability = fakeCapability { command ->
            Result.success(AgentAction.Success(command.commandId, command))
        }

        val result = CommandExecutor().execute(AgentCommand.FlipCamera(), context, null, capability)

        assertTrue(result.isSuccess)
        assertEquals(1, recorded.size)
        val r = recorded.first()
        assertEquals("fake", r.capability)
        assertEquals("flip_camera", r.commandType)
        assertTrue(r.success)
        assertTrue(r.latencyMs >= 0)
        assertNull(r.errorCode)
        assertNull(r.errorMessage)
    }

    @Test
    fun `failure branch records error code and message`() = runTest {
        installRecorder()
        val capability = fakeCapability { throw IllegalStateException("boom") }

        val result = CommandExecutor().execute(AgentCommand.FlipCamera(), context, null, capability)

        assertFalse(result.isSuccess)
        val ex = result.exceptionOrNull()
        assertTrue(ex is CommandExecutor.CapabilityExecutionException)
        assertEquals(1, recorded.size)
        val r = recorded.first()
        assertFalse(r.success)
        assertEquals(CommandExecutor.ERROR_CODE_EXECUTION_FAILED, r.errorCode)
        assertNotNull(r.errorMessage)
        assertTrue(r.errorMessage!!.contains("boom"))
    }

    @Test
    fun `timeout branch records TIMEOUT error code`() = runTest {
        installRecorder()
        val capability = fakeCapability {
            delay(60_000)
            Result.success(AgentAction.Success(it.commandId, it))
        }

        val result = CommandExecutor(timeoutMs = 50)
            .execute(AgentCommand.FlipCamera(), context, null, capability)

        assertFalse(result.isSuccess)
        assertEquals(1, recorded.size)
        val r = recorded.first()
        assertFalse(r.success)
        assertEquals(CommandExecutor.ERROR_CODE_TIMEOUT, r.errorCode)
        assertNotNull(r.errorMessage)
    }

    @Test
    fun `error action inside success result records as failure with error message`() = runTest {
        installRecorder()
        // Capability 业务失败以 Result.success(AgentAction.Error) 返回（如引导性错误）
        val capability = fakeCapability { command ->
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = "还没有标记哪个人物是你本人"
                )
            )
        }

        val result = CommandExecutor().execute(AgentCommand.FlipCamera(), context, null, capability)

        assertTrue(result.isSuccess)
        assertEquals(1, recorded.size)
        val r = recorded.first()
        assertFalse("AgentAction.Error 必须记 success=0", r.success)
        assertEquals(AgentErrorCode.INVALID_PARAMS, r.errorCode)
        assertEquals("还没有标记哪个人物是你本人", r.errorMessage)
    }

    @Test
    fun `text reply action records as success`() = runTest {
        installRecorder()
        val capability = fakeCapability { command ->
            Result.success(AgentAction.TextReply(command.commandId, "已记住"))
        }

        CommandExecutor().execute(AgentCommand.FlipCamera(), context, null, capability)

        val r = recorded.single()
        assertTrue(r.success)
        assertNull(r.errorCode)
        assertNull(r.errorMessage)
    }

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

        val r = recorded.single()
        assertEquals("trace-123", r.traceId)
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
