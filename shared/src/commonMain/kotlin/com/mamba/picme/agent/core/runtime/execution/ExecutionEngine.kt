package com.mamba.picme.agent.core.runtime.execution

import com.mamba.picme.agent.core.capability.FaceDetectionProvider
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.plan.ExecutionPlan
import com.mamba.picme.agent.core.model.plan.ExecutionResult
import com.mamba.picme.agent.core.model.plan.PlanStep
import com.mamba.picme.agent.core.model.plan.StepResult
import com.mamba.picme.agent.core.model.plan.WaitCondition
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.concurrent.Volatile

/**
 * 命令分发器接口
 *
 * 抽象 CapabilityRegistry 的分发能力，便于测试时注入 Fake。
 */
interface CommandDispatcher {

    /**
     * 分发命令并返回执行结果
     */
    suspend fun dispatch(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext? = null
    ): Result<AgentAction>
}

/**
 * 执行引擎
 *
 * 负责顺序执行 ExecutionPlan 中的步骤，支持：
 * - 条件评估（跳过不满足条件的步骤）
 * - 步骤间延迟
 * - 暂停 / 恢复 / 取消操作
 * - 失败回退（fallbackAction）
 * - 状态管理（StateFlow）
 */
class ExecutionEngine(
    private val dispatcher: CommandDispatcher,
    private val reporter: ExecutionReporter
) {

    /**
     * 通过 CapabilityRegistry 构造执行引擎
     */
    constructor(
        capabilityRegistry: CapabilityRegistry,
        reporter: ExecutionReporter
    ) : this(
        dispatcher = object : CommandDispatcher {
            override suspend fun dispatch(
                command: AgentCommand,
                context: AgentContext,
                pageContext: PageContext?
            ): Result<AgentAction> {
                return capabilityRegistry.dispatch(command, context, pageContext)
            }
        },
        reporter = reporter
    )

    private val tag = "ExecutionEngine"

    private val _stateFlow = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val stateFlow: StateFlow<ExecutionState> = _stateFlow.asStateFlow()

    @Volatile
    private var isPaused = false

    @Volatile
    private var isCancelled = false

    /**
     * 执行计划
     *
     * @param plan 执行计划
     * @return 执行结果
     */
    suspend fun execute(plan: ExecutionPlan): ExecutionResult {
        Logger.d(tag, "Starting execution of plan: ${plan.planId}, steps: ${plan.steps.size}")

        isCancelled = false
        isPaused = false
        _stateFlow.value = ExecutionState.Running(
            totalSteps = plan.steps.size,
            completedSteps = 0
        )

        val stepResults = mutableListOf<StepResult>()

        try {
            for (planStep in plan.steps) {
                if (isCancelled) {
                    Logger.d(tag, "Execution cancelled at step ${planStep.step}")
                    _stateFlow.value = ExecutionState.Cancelled
                    val result = ExecutionResult(planId = plan.planId, stepResults = stepResults)
                    reporter.report(result)
                    return result
                }

                // 等待暂停恢复
                while (isPaused && !isCancelled) {
                    delay(100)
                }

                if (isCancelled) {
                    Logger.d(tag, "Execution cancelled after pause at step ${planStep.step}")
                    _stateFlow.value = ExecutionState.Cancelled
                    val result = ExecutionResult(planId = plan.planId, stepResults = stepResults)
                    reporter.report(result)
                    return result
                }

                val stepResult = executeStep(planStep)
                stepResults.add(stepResult)
                reporter.emitStepResult(stepResult)

                // 更新状态
                _stateFlow.value = ExecutionState.Running(
                    totalSteps = plan.steps.size,
                    completedSteps = stepResults.size
                )

                // 步骤间延迟
                if (planStep.delayMs > 0) {
                    Logger.d(tag, "Delaying ${planStep.delayMs}ms after step ${planStep.step}")
                    delay(planStep.delayMs)
                }
            }
        } catch (exception: CancellationException) {
            Logger.d(tag, "Execution cancelled via CancellationException")
            _stateFlow.value = ExecutionState.Cancelled
            val result = ExecutionResult(planId = plan.planId, stepResults = stepResults)
            reporter.report(result)
            return result
        } catch (throwable: Throwable) {
            Logger.e(tag, "Execution failed with unexpected error", throwable)
            _stateFlow.value = ExecutionState.Completed(Result.failure(throwable))
            val result = ExecutionResult(planId = plan.planId, stepResults = stepResults)
            reporter.report(result)
            return result
        }

        val result = ExecutionResult(planId = plan.planId, stepResults = stepResults)
        _stateFlow.value = ExecutionState.Completed(
            Result.success(Unit).takeIf { result.isSuccess }
                ?: Result.failure(RuntimeException("Plan execution completed with failures"))
        )
        reporter.report(result)

        Logger.d(tag, "Plan execution completed: ${plan.planId}, success=${result.isSuccess}")
        return result
    }

    /**
     * 执行单个步骤
     *
     * 支持：条件评估、等待条件、循环重复、失败回退。
     * 保留 AgentAction 用于追踪每步的实际执行结果。
     */
    private suspend fun executeStep(planStep: PlanStep): StepResult {
        // 条件评估
        if (!evaluateCondition(planStep.condition)) {
            Logger.d(tag, "Step ${planStep.step} skipped: condition '${planStep.condition}' is false")
            return StepResult.Skipped(
                step = planStep,
                reason = "Condition '${planStep.condition}' evaluated to false"
            )
        }

        // 等待条件评估
        val waitResult = evaluateWaitCondition(planStep.waitCondition)
        if (waitResult != null) {
            Logger.d(tag, "Step ${planStep.step} wait condition failed: $waitResult")
            return StepResult.Skipped(step = planStep, reason = waitResult)
        }

        Logger.d(tag, "Executing step ${planStep.step}: ${planStep.description}, repeat=${planStep.repeatCount}")

        return try {
            // 循环重复执行
            var lastAction: AgentAction? = null
            var hasFailure = false
            repeat(planStep.repeatCount.coerceAtLeast(1)) { index ->
                if (index > 0) {
                    Logger.d(tag, "Step ${planStep.step} repeat ${index + 1}/${planStep.repeatCount}")
                }
                val dispatchResult = dispatcher.dispatch(
                    command = planStep.action,
                    context = AgentContext(
                        scene = AgentScene.CAMERA
                    )
                )
                lastAction = dispatchResult.getOrNull()
                if (dispatchResult.isFailure || lastAction?.isSuccess == false) {
                    hasFailure = true
                    return@repeat
                }
            }

            if (!hasFailure && lastAction != null) {
                Logger.d(tag, "Step ${planStep.step} executed successfully (x${planStep.repeatCount})")
                StepResult.Executed(step = planStep, action = lastAction!!)
            } else {
                val errorMessage = lastAction?.let {
                    when (it) {
                        is AgentAction.Error -> it.message
                        else -> "Step ${planStep.step} failed"
                    }
                } ?: "Step ${planStep.step} failed without action"
                Logger.e(tag, "Step ${planStep.step} failed: $errorMessage")
                tryFallback(planStep, errorMessage, planStep.action.commandId)
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            Logger.e(tag, "Step ${planStep.step} threw exception: ${throwable.message}", throwable)
            tryFallback(
                planStep,
                throwable.message ?: "Step ${planStep.step} threw exception",
                planStep.action.commandId
            )
        }
    }

    /**
     * 评估等待条件
     *
     * @param waitCondition 等待条件
     * @return null 表示条件已满足，非 null 表示等待失败的原因
     */
    private suspend fun evaluateWaitCondition(waitCondition: WaitCondition?): String? {
        if (waitCondition == null) return null

        return when (waitCondition) {
            is WaitCondition.SmileDetected -> {
                // 微笑检测预留，暂未实现具体逻辑
                Logger.w(tag, "Smile detection not implemented yet, skipping wait")
                "微笑检测暂未实现"
            }
            is WaitCondition.FaceDetected -> {
                Logger.d(tag, "Waiting for face detection, timeout=${waitCondition.timeoutMs}ms")
                val startTime = Clock.System.now().toEpochMilliseconds()
                while (Clock.System.now().toEpochMilliseconds() - startTime < waitCondition.timeoutMs) {
                    if (FaceDetectionProvider.get()?.isFaceValid() == true) {
                        Logger.d(tag, "Face detected")
                        return null
                    }
                    delay(200)
                }
                "等待人脸检测超时"
            }
            is WaitCondition.Duration -> {
                Logger.d(tag, "Waiting for duration: ${waitCondition.delayMs}ms")
                delay(waitCondition.delayMs)
                null
            }
            is WaitCondition.UserConfirm -> {
                // 用户确认需要 UI 交互，暂不支持自动等待
                Logger.w(tag, "User confirm wait not supported in auto mode")
                "用户确认需手动操作"
            }
        }
    }

    /**
     * 尝试执行 fallback 动作
     */
    private suspend fun tryFallback(
        planStep: PlanStep,
        errorMessage: String,
        commandId: Int
    ): StepResult {
        val fallbackAction = planStep.fallbackAction
        return if (fallbackAction != null) {
            Logger.d(tag, "Trying fallback for step ${planStep.step}")
            try {
                val fallbackResult = dispatcher.dispatch(
                    command = fallbackAction,
                    context = AgentContext(
                        scene = AgentScene.CAMERA
                    )
                )
                val fallbackAction = fallbackResult.getOrNull()
                if (fallbackResult.isSuccess && fallbackAction?.isSuccess == true) {
                    Logger.d(tag, "Fallback for step ${planStep.step} succeeded")
                    StepResult.Executed(step = planStep, action = fallbackAction)
                } else {
                    val fallbackErrorMsg = (fallbackAction as? AgentAction.Error)?.message
                        ?: "Fallback failed"
                    Logger.e(tag, "Fallback for step ${planStep.step} failed: $fallbackErrorMsg")
                    StepResult.Failed(
                        step = planStep,
                        action = AgentAction.Error(
                            commandId = commandId,
                            errorCode = AgentErrorCode.INTERNAL_ERROR,
                            message = errorMessage,
                            detail = "Fallback also failed: $fallbackErrorMsg"
                        )
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                Logger.e(tag, "Fallback for step ${planStep.step} threw exception", throwable)
                StepResult.Failed(
                    step = planStep,
                    action = AgentAction.Error(
                        commandId = commandId,
                        errorCode = AgentErrorCode.INTERNAL_ERROR,
                        message = errorMessage,
                        detail = "Fallback threw: ${throwable.message}"
                    )
                )
            }
        } else {
            StepResult.Failed(
                step = planStep,
                action = AgentAction.Error(
                    commandId = commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = errorMessage
                )
            )
        }
    }

    /**
     * 评估条件表达式
     *
     * - "false" -> false
     * - "true" -> true
     * - null -> true（无条件执行）
     *
     * @param condition 条件表达式
     * @return 是否满足条件
     */
    private fun evaluateCondition(condition: String?): Boolean {
        return when (condition) {
            null -> true
            "false" -> false
            "true" -> true
            else -> true
        }
    }

    /**
     * 暂停执行
     */
    fun pause() {
        if (_stateFlow.value is ExecutionState.Running) {
            Logger.d(tag, "Execution paused")
            isPaused = true
            _stateFlow.value = ExecutionState.Paused
        }
    }

    /**
     * 恢复执行
     */
    fun resume() {
        if (_stateFlow.value is ExecutionState.Paused) {
            Logger.d(tag, "Execution resumed")
            isPaused = false
            // 状态会在 execute 循环中自动更新回 Running
        }
    }

    /**
     * 取消执行
     */
    fun cancel() {
        Logger.d(tag, "Execution cancel requested")
        isCancelled = true
        isPaused = false
    }

    /**
     * 重置引擎状态
     */
    fun reset() {
        Logger.d(tag, "Execution engine reset")
        isCancelled = false
        isPaused = false
        _stateFlow.value = ExecutionState.Idle
    }
}
