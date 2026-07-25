package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.withTimeout

/**
 * 命令执行器
 *
 * 负责命令执行、超时控制和异常处理。
 *
 * chat / 飞书 / batch 等全部 tool 执行都汇聚于此，因此在此统一上报执行指标
 * （见 [recorder]），只记录纯指标，不含命令参数与业务内容。
 */
class CommandExecutor(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val ERROR_CODE_TIMEOUT = -32002
        const val ERROR_CODE_EXECUTION_FAILED = -32005

        private const val TAG = "PoLang:CommandExecutor"

        /**
         * tool 执行指标接收端，由 :app 在 Application 启动时注入（全构建注入）。
         * 为 null 时不记录。
         */
        @Volatile
        var recorder: CommandExecutionRecorder? = null
    }

    /**
     * 执行命令（带超时）
     */
    suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        capability: Capability
    ): Result<AgentAction> {
        val startMs = System.currentTimeMillis()
        val commandType = AgentCommand.getMethodName(command)
        return try {
            val result = withTimeout(timeoutMs) {
                capability.execute(command, context, pageContext)
            }
            notifyRecorder(
                capability.name, commandType, startMs,
                success = result.isSuccess,
                errorCode = null,
                errorMessage = result.exceptionOrNull()?.message
            )
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val ex = CapabilityExecutionException(
                "Command execution timed out after ${timeoutMs}ms",
                ERROR_CODE_TIMEOUT,
                e
            )
            notifyRecorder(capability.name, commandType, startMs, false, ex.errorCode, ex.message)
            Result.failure(ex)
        } catch (e: Exception) {
            val ex = CapabilityExecutionException(
                "Command execution failed: ${e.message}",
                ERROR_CODE_EXECUTION_FAILED,
                e
            )
            notifyRecorder(capability.name, commandType, startMs, false, ex.errorCode, ex.message)
            Result.failure(ex)
        }
    }

    /** 上报执行指标；recorder 自身异常吞掉，绝不影响命令执行主流程。 */
    private fun notifyRecorder(
        capability: String,
        commandType: String,
        startMs: Long,
        success: Boolean,
        errorCode: Int?,
        errorMessage: String?
    ) {
        try {
            recorder?.record(
                capability = capability,
                commandType = commandType,
                latencyMs = System.currentTimeMillis() - startMs,
                success = success,
                errorCode = errorCode,
                errorMessage = errorMessage
            )
        } catch (e: Exception) {
            Logger.w(TAG, "recorder.record failed", e)
        }
    }

    class CapabilityExecutionException(
        message: String,
        val errorCode: Int,
        cause: Throwable?
    ) : Exception(message, cause)
}
