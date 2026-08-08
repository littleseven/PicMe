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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CommandExecutor 核心分支测试。
 *
 * 覆盖：success / failure / timeout / error-action 四条执行路径，
 * 统一验证 recorder 记录的 success/errorCode/errorMessage 契约。
 * （原 JUnit4 Parameterized runner 版，迁 commonTest 后改为每分支一个独立 @Test）
 */
class CommandExecutorParameterizedTest {

    sealed class Scenario {
        abstract val capabilityBlock: suspend (AgentCommand) -> Result<AgentAction>
        abstract val expectSuccess: Boolean
        abstract val expectRecordedSuccess: Boolean
        abstract val expectErrorCode: Int?
        abstract val expectErrorMessageContains: String?

        data class Success(
            override val capabilityBlock: suspend (AgentCommand) -> Result<AgentAction> = { cmd ->
                Result.success(AgentAction.Success(cmd.commandId, cmd))
            }
        ) : Scenario() {
            override val expectSuccess = true
            override val expectRecordedSuccess = true
            override val expectErrorCode: Int? = null
            override val expectErrorMessageContains: String? = null
        }

        data class Failure(val message: String = "boom") : Scenario() {
            override val capabilityBlock: suspend (AgentCommand) -> Result<AgentAction> = { throw IllegalStateException(message) }
            override val expectSuccess = false
            override val expectRecordedSuccess = false
            override val expectErrorCode = CommandExecutor.ERROR_CODE_EXECUTION_FAILED
            override val expectErrorMessageContains = message
        }

        data object Timeout : Scenario() {
            override val capabilityBlock: suspend (AgentCommand) -> Result<AgentAction> = {
                delay(60_000)
                Result.success(AgentAction.Success(it.commandId, it))
            }
            override val expectSuccess = false
            override val expectRecordedSuccess = false
            override val expectErrorCode = CommandExecutor.ERROR_CODE_TIMEOUT
            override val expectErrorMessageContains: String? = null // 只验证 non-null
        }

        data class ErrorAction(
            val errorCode: Int = AgentErrorCode.INVALID_PARAMS,
            val message: String = "还没有标记哪个人物是你本人"
        ) : Scenario() {
            override val capabilityBlock: suspend (AgentCommand) -> Result<AgentAction> = { cmd ->
                Result.success(
                    AgentAction.Error(
                        commandId = cmd.commandId,
                        errorCode = errorCode,
                        message = message
                    )
                )
            }
            override val expectSuccess = true // Result.success wrapping AgentAction.Error
            override val expectRecordedSuccess = false
            override val expectErrorCode = AgentErrorCode.INVALID_PARAMS
            override val expectErrorMessageContains = message
        }
    }

    private val context = AgentContext(scene = AgentScene.CHAT)

    private data class Recorded(
        val capability: String,
        val commandType: String,
        val latencyMs: Long,
        val success: Boolean,
        val errorCode: Int?,
        val errorMessage: String?
    )

    @Test
    fun `success path records correct metrics`() = runTest {
        verify(Scenario.Success())
    }

    @Test
    fun `failure path records correct metrics`() = runTest {
        verify(Scenario.Failure())
    }

    @Test
    fun `timeout path records correct metrics`() = runTest {
        verify(Scenario.Timeout)
    }

    @Test
    fun `error-action path records correct metrics`() = runTest {
        verify(Scenario.ErrorAction())
    }

    private suspend fun verify(scenario: Scenario) {
        val recorded = mutableListOf<Recorded>()
        CommandExecutor.recorder = CommandExecutionRecorder { capability, commandType, latencyMs, success, errorCode, errorMessage, _ ->
            recorded += Recorded(capability, commandType, latencyMs, success, errorCode, errorMessage)
        }
        try {
            val executor = if (scenario is Scenario.Timeout) {
                CommandExecutor(timeoutMs = 50)
            } else {
                CommandExecutor()
            }

            val capability = object : BaseCapability() {
                override val name = "fake"
                override val description = "fake capability"
                override fun supportedCommands(): List<String> = listOf("flip_camera")
                override suspend fun execute(
                    command: AgentCommand,
                    context: AgentContext,
                    pageContext: PageContext?
                ): Result<AgentAction> = scenario.capabilityBlock(command)
            }

            val result = executor.execute(AgentCommand.FlipCamera(), context, null, capability)

            assertEquals(scenario.expectSuccess, result.isSuccess)
            assertEquals(1, recorded.size)
            val r = recorded.first()
            assertEquals("fake", r.capability)
            assertEquals("flip_camera", r.commandType)
            assertTrue(r.latencyMs >= 0)
            assertEquals(scenario.expectRecordedSuccess, r.success)
            assertEquals(scenario.expectErrorCode, r.errorCode)
            val expectedMessage = scenario.expectErrorMessageContains
            when {
                expectedMessage != null -> {
                    assertNotNull(r.errorMessage)
                    assertTrue(r.errorMessage!!.contains(expectedMessage))
                }
                scenario.expectRecordedSuccess -> assertNull(r.errorMessage)
                else -> assertNotNull(r.errorMessage)
            }
        } finally {
            CommandExecutor.recorder = null
        }
    }
}
