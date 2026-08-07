package com.mamba.picme.features.common.chat

import androidx.compose.ui.graphics.vector.ImageVector
import com.mamba.picme.agent.core.model.plan.ExecutionPlan

/**
 * Agent 消息类型定义
 *
 * 支持的消息类型：
 * - UserText: 用户输入的文字消息
 * - AgentText: AI Agent 回复的文字消息
 * - CommandExecution: 单个命令的执行状态展示（用于多命令执行过程可视化）
 * - PlanPreview: 计划预览（显示将要执行的操作）
 * - PlanProgress: 计划执行进度
 * - PlanResult: 计划执行结果
 */
sealed class AgentMessage {
    data class UserText(val content: String) : AgentMessage()
    data class AgentText(val content: String) : AgentMessage()

    /**
     * 命令执行状态消息
     *
     * 用于展示单个命令的名称、执行状态和结果，支持多命令批量执行时的过程可视化。
     *
     * @property commandName 命令的友好名称（如"切换滤镜"、"调整美颜"）
     * @property commandIcon 命令对应的图标（如 [androidx.compose.material.icons.rounded.Face]），
     *                       优先用于 UI 展示以减少纯文字标识
     * @property status 当前执行状态
     * @property detail 命令的详细参数信息（如"磨皮: 80%"）
     * @property index 在批量命令中的序号（从 1 开始，单命令为 0）
     * @property total 批量命令总数（单命令为 1）
     */
    data class CommandExecution(
        val commandName: String,
        val commandIcon: ImageVector? = null,
        val status: Status = Status.PENDING,
        val detail: String = "",
        val index: Int = 0,
        val total: Int = 1
    ) : AgentMessage() {
        enum class Status {
            PENDING,    // 等待执行
            RUNNING,    // 执行中
            SUCCESS,    // 执行成功
            FAILED      // 执行失败
        }
    }

    data class PlanPreview(val content: String, val plan: ExecutionPlan? = null) : AgentMessage()
    data class PlanProgress(val content: String) : AgentMessage()
    data class PlanResult(val content: String) : AgentMessage()
}
