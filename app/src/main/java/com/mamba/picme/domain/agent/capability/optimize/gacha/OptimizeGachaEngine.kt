package com.mamba.picme.domain.agent.capability.optimize.gacha

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.aesthetic.AestheticScorer

/**
 * 抽卡编排引擎：采样 → 渲染 → 评分 → 选优/退化守卫。
 *
 * 所有媒体处理 100% 端侧（[PRIVACY] 红线）：小图解码、GPU 渲染、NIMA 评分均不出设备。
 */
class OptimizeGachaEngine(
    private val sampler: CandidateSampler,
    private val renderer: CandidateRenderer,
    private val optimizeScorer: OptimizeScorer,
    private val aestheticScorer: AestheticScorer
) {

    companion object {
        private const val TAG = "PoLang:OptimizeGacha"
    }

    /**
     * 执行一次抽卡。
     *
     * @param imageUri 原图 URI
     * @param scene 场景（决定采样方向池）
     * @param basePreset 锚点 preset（卡 0 原样使用）
     * @param count 候选总数（含锚点卡）
     * @param exclude 「换一组」时需排除的 fingerprint 集合
     */
    suspend fun run(
        imageUri: String,
        scene: Scene,
        basePreset: OptimizePreset,
        count: Int = CandidateSampler.DEFAULT_COUNT,
        exclude: Set<String> = emptySet()
    ): GachaResult {
        if (!aestheticScorer.initialize()) {
            Logger.w(TAG, "aesthetic scorer unavailable, gacha skipped")
            return GachaResult.Unavailable
        }
        val base = renderer.decodeDownscaled(imageUri) ?: return GachaResult.Unavailable
        val originalPx = renderer.extractPixels(base)
        val originalLuminance = Guardrails.meanLuminance(originalPx)
        val originalScore = aestheticScorer.score(base)

        val candidates = sampler.sample(basePreset, scene, count, exclude)
        val scored = candidates.mapNotNull { candidate ->
            val rendered = renderer.render(candidate, base, imageUri) ?: return@mapNotNull null
            val px = renderer.extractPixels(rendered)
            optimizeScorer.scoreCandidate(candidate, rendered, px, originalLuminance)
        }
        if (scored.size < OptimizeScorer.MIN_VALID_CARDS) {
            Logger.w(TAG, "only ${scored.size} cards rendered, gacha unavailable")
            return GachaResult.Unavailable
        }

        val result = optimizeScorer.select(scored, originalScore)
        val resultName = when (result) {
            is GachaResult.Selected -> "Selected"
            is GachaResult.KeepOriginal -> "KeepOriginal"
            GachaResult.Unavailable -> "Unavailable"
        }
        Logger.i(
            TAG,
            "gacha done: scene=${scene.name}, cards=${scored.size}, " +
                "original=$originalScore, result=$resultName"
        )
        return result
    }
}
