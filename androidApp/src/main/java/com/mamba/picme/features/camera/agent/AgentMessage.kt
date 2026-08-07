package com.mamba.picme.features.camera.agent

import com.mamba.picme.agent.core.runtime.execution.ExecutionState
import com.mamba.picme.agent.core.model.plan.ExecutionPlan
import com.mamba.picme.agent.core.model.plan.ExecutionResult

/**
 * Agent 消息密封类
 *
 * 支持文本消息和 ExecutionPlan 相关消息类型，用于 AiAgentPanel 的 LazyColumn 渲染。
 */
sealed class AgentMessage {
    abstract val timestamp: Long

    /**
     * 用户文本消息
     */
    data class UserText(
        val content: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentMessage()

    /**
     * Agent 文本回复
     */
    data class AgentText(
        val content: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentMessage()

    /**
     * 计划预览消息（等待用户确认）
     */
    data class PlanPreview(
        val plan: ExecutionPlan,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentMessage()

    /**
     * 计划执行中消息（展示进度）
     */
    data class PlanProgress(
        val plan: ExecutionPlan,
        val state: ExecutionState,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentMessage()

    /**
     * 计划执行结果消息
     */
    data class PlanResult(
        val result: ExecutionResult,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentMessage()
}
