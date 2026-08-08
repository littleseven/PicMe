package com.mamba.picme.agent.core.model.plan

import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.SceneContext

/**
 * 交互模式枚举
 */
enum class InteractionMode {
    /**
     * 自动模式（无需用户确认直接执行）
     */
    AUTO,

    /**
     * 预览模式（展示计划，用户一键确认后执行）
     */
    PREVIEW,

    /**
     * 逐步模式（每步执行前需用户确认）
     */
    STEP_BY_STEP
}

/**
 * 等待条件密封类
 *
 * 支持执行计划中的条件等待步骤。
 * 目前仅定义接口，具体条件检测逻辑在 ExecutionEngine 中实现。
 */
sealed class WaitCondition {
    /**
     * 等待检测到微笑（预留，暂未实现具体检测逻辑）
     */
    data class SmileDetected(val timeoutMs: Long = 15000) : WaitCondition()

    /**
     * 等待检测到人脸
     */
    data class FaceDetected(val timeoutMs: Long = 10000) : WaitCondition()

    /**
     * 等待固定时长
     */
    data class Duration(val delayMs: Long) : WaitCondition()

    /**
     * 等待用户确认（需要 UI 交互）
     */
    data class UserConfirm(val prompt: String = "") : WaitCondition()
}

/**
 * 执行计划（L3 Plan-and-Execute 模式）
 *
 * @property planId 计划唯一标识
 * @property steps 执行步骤列表
 * @property description 计划描述
 * @property interactionMode 交互模式
 * @property sceneContext 场景上下文（可选，用于场景感知计划生成）
 * @property recommendationReason 推荐原因（可选，向用户解释为何生成此计划）
 */
data class ExecutionPlan(
    val planId: String,
    val steps: List<PlanStep>,
    val description: String = "",
    val interactionMode: InteractionMode = InteractionMode.AUTO,
    val sceneContext: SceneContext? = null,
    val recommendationReason: String? = null
)

/**
 * 计划执行步骤
 *
 * @property step 步骤序号（从 1 开始）
 * @property action 要执行的命令
 * @property condition 执行条件（可选，为 null 时无条件执行）
 * @property waitCondition 等待条件（可选，设置时会在执行前等待条件满足）
 * @property repeatCount 重复执行次数（默认 1，大于 1 时重复执行 action）
 * @property description 步骤描述
 * @property delayMs 执行后延迟（毫秒，给 UI 反应时间）
 * @property fallbackAction 失败回退动作（可选，当此步骤执行失败时执行）
 */
data class PlanStep(
    val step: Int,
    val action: AgentCommand,
    val condition: String? = null,
    val waitCondition: WaitCondition? = null,
    val repeatCount: Int = 1,
    val description: String = "",
    val delayMs: Long = 0,
    val fallbackAction: AgentCommand? = null
)

/**
 * 步骤执行结果
 *
 * 保留 AgentAction 用于追踪每步的实际执行结果。
 */
sealed class StepResult {
    /**
     * 步骤已执行
     *
     * @property step 计划步骤
     * @property action 执行后返回的 AgentAction（含 commandId、成功/失败状态）
     */
    data class Executed(val step: PlanStep, val action: AgentAction) : StepResult()

    /**
     * 步骤被跳过（条件不满足）
     */
    data class Skipped(val step: PlanStep, val reason: String) : StepResult()

    /**
     * 步骤执行失败
     *
     * @property step 计划步骤
     * @property action 失败时返回的 AgentAction.Error（含结构化 errorCode）
     */
    data class Failed(val step: PlanStep, val action: AgentAction.Error) : StepResult()
}

/**
 * 计划执行结果
 *
 * @property planId 计划 ID
 * @property stepResults 各步骤执行结果
 * @property actions 各步骤实际返回的 AgentAction 列表（便于快速访问）
 */
data class ExecutionResult(
    val planId: String,
    val stepResults: List<StepResult>
) {
    /**
     * 所有步骤实际返回的 AgentAction 列表
     */
    val actions: List<AgentAction>
        get() = stepResults.mapNotNull {
            when (it) {
                is StepResult.Executed -> it.action
                is StepResult.Failed -> it.action
                is StepResult.Skipped -> null
            }
        }

    /**
     * 是否全部成功（Executed 成功 或 Skipped）
     */
    val isSuccess: Boolean
        get() = stepResults.all {
            when (it) {
                is StepResult.Executed -> it.action.isSuccess
                is StepResult.Skipped -> true
                is StepResult.Failed -> false
            }
        }

    /**
     * 执行成功的步骤数
     */
    val successCount: Int
        get() = stepResults.count {
            it is StepResult.Executed && it.action.isSuccess
        }

    /**
     * 被跳过的步骤数
     */
    val skippedCount: Int
        get() = stepResults.count { it is StepResult.Skipped }

    /**
     * 失败的步骤数
     */
    val failedCount: Int
        get() = stepResults.count {
            it is StepResult.Failed || (it is StepResult.Executed && !it.action.isSuccess)
        }
}
