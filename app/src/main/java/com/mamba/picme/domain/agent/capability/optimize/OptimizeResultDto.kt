package com.mamba.picme.domain.agent.capability.optimize

import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset

/**
 * AI 优化执行结果 DTO，用于 Capability 与 UI 之间传递优化后的编辑配方。
 *
 * 序列化为 JSON 后存放在 [com.mamba.picme.agent.core.model.command.AgentCommand.AiOptimize.resultRecipe]
 * 中，UI 层解析后重建 [com.mamba.picme.features.editor.EditRecipe]。
 */
data class OptimizeResultDto(
    val sourceUri: String,
    val scene: String,
    val explanation: String,
    val preset: OptimizePreset
)
