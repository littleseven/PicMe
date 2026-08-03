package com.mamba.picme.domain.usecase

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.analyzer.SceneAnalyzer
import com.mamba.picme.domain.agent.capability.optimize.preset.PresetRepository
import com.mamba.picme.domain.agent.capability.optimize.recipe.OptimizeRecipeMapper
import com.mamba.picme.features.editor.EditRecipe

/**
 * AI 一键优化用例
 *
 * 独立于 Capability 的业务逻辑入口，同时服务：
 * - UI 层直接调用（媒体查看器、编辑器、批量优化）
 * - Agent Capability 委托执行
 */
class AiOptimizeUseCase(
    private val presetRepository: PresetRepository,
    private val sceneAnalyzer: SceneAnalyzer
) {

    companion object {
        private const val TAG = "PoLang:AiOptimizeUseCase"
    }

    /**
     * 优化结果
     *
     * @property scene 识别场景
     * @property confidence 置信度
     * @property editRecipe 可直接应用的编辑配方
     * @property explanation 一句话说明
     * @property processingTimeMs 处理耗时
     */
    data class Result(
        val scene: Scene,
        val confidence: Float,
        val editRecipe: EditRecipe,
        val explanation: String,
        val processingTimeMs: Long
    )

    /**
     * 执行端侧场景感知优化
     *
     * 通过 [sceneAnalyzer] 端侧识别图片场景，按场景路由本地预设（零网络、隐私合规）。
     *
     * @param imageUri 图片本地 URI
     * @param baseRecipe 基础 Recipe（保留裁剪等既有参数）
     * @return 优化结果
     */
    suspend fun optimize(
        imageUri: String,
        baseRecipe: EditRecipe? = null
    ): Result {
        val startTime = System.currentTimeMillis()
        val scene = sceneAnalyzer.analyze(imageUri)
        val preset = presetRepository.getPreset(scene)
        val elapsed = System.currentTimeMillis() - startTime

        Logger.d(TAG, "Optimize: scene=${scene.name}, ${elapsed}ms")

        return Result(
            scene = scene,
            confidence = 1.0f,
            editRecipe = OptimizeRecipeMapper.toEditRecipe(
                preset = preset,
                sourceUri = imageUri,
                baseRecipe = baseRecipe ?: EditRecipe(sourceUri = imageUri)
            ),
            explanation = OptimizeRecipeMapper.buildExplanation(scene),
            processingTimeMs = elapsed
        )
    }
}
