package com.mamba.picme.agent.core.runtime.execution

/**
 * 执行状态机
 *
 * 表示 Agent 执行计划或批量命令的当前执行状态。
 */
sealed class ExecutionState {

    /**
     * 空闲状态（无执行中任务）
     */
    data object Idle : ExecutionState()

    /**
     * 执行中状态
     *
     * @property totalSteps 总步骤数
     * @property completedSteps 已完成步骤数
     */
    data class Running(
        val totalSteps: Int,
        val completedSteps: Int
    ) : ExecutionState()

    /**
     * 暂停状态（用户手动暂停或等待用户确认）
     */
    data object Paused : ExecutionState()

    /**
     * 已取消状态（用户主动取消或超时取消）
     */
    data object Cancelled : ExecutionState()

    /**
     * 已完成状态
     *
     * @property result 执行结果（成功或失败）
     */
    data class Completed(val result: Result<Unit>) : ExecutionState()
}
