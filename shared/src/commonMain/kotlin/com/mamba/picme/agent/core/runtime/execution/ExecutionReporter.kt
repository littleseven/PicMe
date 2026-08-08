package com.mamba.picme.agent.core.runtime.execution

import com.mamba.picme.agent.core.model.plan.ExecutionResult
import com.mamba.picme.agent.core.model.plan.StepResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 执行报告接口
 *
 * 负责报告计划执行过程中的步骤结果和最终执行结果。
 */
interface ExecutionReporter {

    /**
     * 报告计划最终执行结果
     *
     * @param result 执行结果
     */
    fun report(result: ExecutionResult)

    /**
     * 发射步骤执行结果
     *
     * @param stepResult 步骤结果
     */
    suspend fun emitStepResult(stepResult: StepResult)
}

/**
 * 执行报告实现
 *
 * 通过 SharedFlow 提供步骤结果和最终结果的流式通知。
 */
class ExecutionReporterImpl : ExecutionReporter {

    private val _stepResults = MutableSharedFlow<StepResult>(replay = 10, extraBufferCapacity = 64)
    private val _executionResults = MutableSharedFlow<ExecutionResult>(replay = 10, extraBufferCapacity = 16)

    /**
     * 步骤结果流
     */
    val stepResults: SharedFlow<StepResult> = _stepResults.asSharedFlow()

    /**
     * 执行结果流
     */
    val executionResults: SharedFlow<ExecutionResult> = _executionResults.asSharedFlow()

    override fun report(result: ExecutionResult) {
        _executionResults.tryEmit(result)
    }

    override suspend fun emitStepResult(stepResult: StepResult) {
        _stepResults.emit(stepResult)
    }
}
