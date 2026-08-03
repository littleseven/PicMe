package com.mamba.picme.domain.usecase

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.consent.CloudOptimizeConsentManager
import com.mamba.picme.domain.agent.capability.optimize.preset.PresetRepository
import com.mamba.picme.domain.agent.capability.optimize.recipe.OptimizeRecipeMapper
import com.mamba.picme.domain.agent.capability.optimize.smart.SmartOptimizeEngine
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
    private val consentManager: CloudOptimizeConsentManager,
    private val smartEngine: SmartOptimizeEngine? = null
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
     * @property usedCloud 是否使用了云端模型
     * @property processingTimeMs 处理耗时
     */
    data class Result(
        val scene: Scene,
        val confidence: Float,
        val editRecipe: EditRecipe,
        val explanation: String,
        val usedCloud: Boolean,
        val processingTimeMs: Long
    )

    /**
     * 执行本地快速优化
     *
     * @param imageUri 图片本地 URI
     * @param baseRecipe 基础 Recipe（保留裁剪等既有参数）
     * @return 优化结果
     */
    suspend fun fastOptimize(
        imageUri: String,
        baseRecipe: EditRecipe? = null
    ): Result {
        val startTime = System.currentTimeMillis()
        val preset = presetRepository.getPreset(Scene.GENERAL)
        val elapsed = System.currentTimeMillis() - startTime

        Logger.d(TAG, "Fast optimize: scene=GENERAL, ${elapsed}ms")

        return Result(
            scene = Scene.GENERAL,
            confidence = 1.0f,
            editRecipe = OptimizeRecipeMapper.toEditRecipe(
                preset = preset,
                sourceUri = imageUri,
                baseRecipe = baseRecipe ?: EditRecipe(sourceUri = imageUri)
            ),
            explanation = OptimizeRecipeMapper.buildExplanation(Scene.GENERAL),
            usedCloud = false,
            processingTimeMs = elapsed
        )
    }

    /**
     * 执行云端智能推荐
     *
     * 若未授权或引擎不可用，自动降级为 fastOptimize。
     *
     * @param imageUri 图片本地 URI
     * @param baseRecipe 基础 Recipe
     * @return 优化结果
     */
    suspend fun smartOptimize(
        imageUri: String,
        baseRecipe: EditRecipe? = null
    ): Result {
        if (!consentManager.isCloudOptimizeAllowed()) {
            Logger.i(TAG, "Cloud optimize not allowed, fallback to fast")
            return fastOptimize(imageUri, baseRecipe)
        }

        val engine = smartEngine
        if (engine == null) {
            Logger.w(TAG, "Smart engine not available, fallback to fast")
            return fastOptimize(imageUri, baseRecipe)
        }

        return try {
            val startTime = System.currentTimeMillis()
            val preset = engine.optimize(imageUri)
            val elapsed = System.currentTimeMillis() - startTime

            Result(
                scene = Scene.entries.find { it.name.equals(preset.scene, ignoreCase = true) }
                    ?: Scene.GENERAL,
                confidence = 0.85f,
                editRecipe = OptimizeRecipeMapper.toEditRecipe(
                    preset = preset,
                    sourceUri = imageUri,
                    baseRecipe = baseRecipe ?: EditRecipe(sourceUri = imageUri)
                ),
                explanation = OptimizeRecipeMapper.buildExplanation(
                    Scene.entries.find { it.name.equals(preset.scene, ignoreCase = true) }
                        ?: Scene.GENERAL
                ),
                usedCloud = true,
                processingTimeMs = elapsed
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Smart optimize failed, fallback to fast", e)
            fastOptimize(imageUri, baseRecipe)
        }
    }
}
