package com.mamba.picme.domain.usecase

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.analyzer.SceneAnalyzer
import com.mamba.picme.domain.agent.capability.optimize.gacha.CandidateSampler
import com.mamba.picme.domain.agent.capability.optimize.gacha.GachaResult
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeGachaEngine
import com.mamba.picme.domain.agent.capability.optimize.preset.PresetRepository
import com.mamba.picme.domain.agent.capability.optimize.recipe.OptimizeRecipeMapper
import com.mamba.picme.features.editor.EditRecipe

/**
 * AI 一键优化用例
 *
 * 独立于 Capability 的业务逻辑入口，同时服务：
 * - UI 层直接调用（媒体查看器、编辑器、批量优化）
 * - Agent Capability 委托执行
 *
 * 两条路径：
 * - [optimize]：固定预设路径（批量优化、抽卡不可用时的兜底）
 * - [optimizeWithGacha]：抽卡闭环路径（采样 4 候选 → 渲染 → NIMA 评分 → 选优 + 退化守卫）
 */
class AiOptimizeUseCase(
    private val presetRepository: PresetRepository,
    private val sceneAnalyzer: SceneAnalyzer,
    private val gachaEngine: OptimizeGachaEngine? = null,
    private val feedbackLogger: OptimizeFeedbackLogger? = null
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
     * 抽卡优化结果
     *
     * @property result 抽卡结果（Selected / KeepOriginal / Unavailable）
     * @property scene 识别场景
     * @property editRecipe Selected 时为最优卡配方；Unavailable 时为固定预设兜底配方；
     *   KeepOriginal 时为 null（调用方保持原图）
     * @property explanation 场景说明文案
     * @property usedFingerprints 本次已出现的参数指纹（含传入的 exclude），「换一组」时回传去重
     * @property processingTimeMs 处理耗时
     */
    data class GachaOutcome(
        val result: GachaResult,
        val scene: Scene,
        val editRecipe: EditRecipe?,
        val explanation: String,
        val usedFingerprints: Set<String>,
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

    /**
     * 执行抽卡闭环优化（best-of-N + NIMA 评分守卫）。
     *
     * 流程：场景识别 → base preset → [OptimizeGachaEngine] 抽卡选优。
     * 降级链（功能永不阻塞）：
     * - 无引擎 / 引擎返回 Unavailable → 退回固定预设（与 [optimize] 一致）
     * - KeepOriginal → editRecipe 为 null，调用方保持原图
     *
     * 自动选优与 KeepOriginal 均落库反馈（source=auto）；用户手选由 UI 层另行落库。
     *
     * @param imageUri 图片本地 URI
     * @param baseRecipe 基础 Recipe（保留裁剪等既有参数）
     * @param exclude 「换一组」时需排除的参数指纹集合
     */
    suspend fun optimizeWithGacha(
        imageUri: String,
        baseRecipe: EditRecipe? = null,
        exclude: Set<String> = emptySet()
    ): GachaOutcome {
        val startTime = System.currentTimeMillis()
        val scene = sceneAnalyzer.analyze(imageUri)
        val preset = presetRepository.getPreset(scene)
        val base = baseRecipe ?: EditRecipe(sourceUri = imageUri)

        val engine = gachaEngine
        if (engine == null) {
            val recipe = OptimizeRecipeMapper.toEditRecipe(preset, imageUri, base)
            return GachaOutcome(
                result = GachaResult.Unavailable,
                scene = scene,
                editRecipe = recipe,
                explanation = OptimizeRecipeMapper.buildExplanation(scene),
                usedFingerprints = exclude,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }

        val result = engine.run(
            imageUri = imageUri,
            scene = scene,
            basePreset = preset,
            exclude = exclude
        )

        val recipe: EditRecipe? = when (result) {
            is GachaResult.Selected ->
                OptimizeRecipeMapper.toEditRecipe(result.best.candidate.preset, imageUri, base)
            is GachaResult.KeepOriginal -> null
            GachaResult.Unavailable ->
                OptimizeRecipeMapper.toEditRecipe(preset, imageUri, base)
        }

        val allCandidates = when (result) {
            is GachaResult.Selected -> result.all
            is GachaResult.KeepOriginal -> result.all
            GachaResult.Unavailable -> emptyList()
        }
        val usedFingerprints = exclude + allCandidates.map { CandidateSampler.fingerprint(it.candidate.preset) }

        when (result) {
            is GachaResult.Selected ->
                feedbackLogger?.log(imageUri, scene, result.all, result.best.candidate.index, OptimizeFeedbackLogger.SOURCE_AUTO)
            is GachaResult.KeepOriginal ->
                feedbackLogger?.log(imageUri, scene, result.all, -1, OptimizeFeedbackLogger.SOURCE_AUTO)
            GachaResult.Unavailable -> Unit
        }

        val elapsed = System.currentTimeMillis() - startTime
        Logger.i(TAG, "optimizeWithGacha: scene=${scene.name}, result=${result::class.simpleName}, ${elapsed}ms")

        return GachaOutcome(
            result = result,
            scene = scene,
            editRecipe = recipe,
            explanation = OptimizeRecipeMapper.buildExplanation(scene),
            usedFingerprints = usedFingerprints,
            processingTimeMs = elapsed
        )
    }
}
