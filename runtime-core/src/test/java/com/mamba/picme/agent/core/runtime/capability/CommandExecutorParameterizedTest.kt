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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * CommandExecutor 核心分支参数化测试。
 *
 * 参数化覆盖：success / failure / timeout / error-action 四条执行路径，
 * 统一验证 recorder 记录的 success/errorCode/errorMessage 契约。
 */
@RunWith(Parameterized::class)
class CommandExecutorParameterizedTest(
    private val testName: String,
    private val scenario: Scenario
) {

    companion object {
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

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf("success", Scenario.Success()),
            arrayOf("failure", Scenario.Failure()),
            arrayOf("timeout", Scenario.Timeout),
            arrayOf("error-action", Scenario.ErrorAction())
        )
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
    fun `execute records correct metrics`() = runTest {
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
