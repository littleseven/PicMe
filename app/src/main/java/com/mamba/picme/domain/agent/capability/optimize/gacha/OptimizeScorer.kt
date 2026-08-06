package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.graphics.Bitmap
import com.mamba.picme.domain.aesthetic.AestheticScorer

/**
 * 抽卡评分器：技术护栏 → NIMA 打分 → 选优 + 退化守卫。
 */
class OptimizeScorer(private val scorer: AestheticScorer) {

    companion object {
        /** 退化守卫阈值：最优候选相对原图的最小 NIMA 提升（初始值，离线样张校准） */
        const val MIN_IMPROVEMENT = 0.05f

        /** 有效候选卡下限，低于则判定抽卡不可用 */
        const val MIN_VALID_CARDS = 2
    }

    /**
     * 给单张渲染结果评分：先护栏后 NIMA（护栏淘汰的卡不再打分）。
     *
     * @param rendered 候选渲染结果（同时作为 thumbnail 带回）
     * @param renderedPx [rendered] 的像素数组（护栏计算用）
     * @param originalMeanLuminance 原图平均亮度
     */
    fun scoreCandidate(
        candidate: OptimizeCandidate,
        rendered: Bitmap,
        renderedPx: IntArray,
        originalMeanLuminance: Float
    ): ScoredCandidate {
        val rejectReason = Guardrails.check(renderedPx, originalMeanLuminance)
        if (rejectReason != null) {
            return ScoredCandidate(
                candidate = candidate,
                nimaScore = null,
                rejected = true,
                rejectReason = rejectReason,
                thumbnail = rendered
            )
        }
        val score = scorer.score(rendered)
        return ScoredCandidate(
            candidate = candidate,
            nimaScore = score,
            rejected = score == null,
            rejectReason = if (score == null) "nima_failed" else null,
            thumbnail = rendered
        )
    }

    /**
     * 选优 + 退化守卫。
     *
     * - 有效卡（未淘汰且有分）< [MIN_VALID_CARDS] → [GachaResult.Unavailable]
     * - 原图分可用且最优卡提升 ≤ [MIN_IMPROVEMENT] → [GachaResult.KeepOriginal]
     * - 原图分不可用 → 跳过守卫直接选优（spec §9）
     */
    fun select(all: List<ScoredCandidate>, originalScore: Float?): GachaResult {
        val valid = all.filter { !it.rejected && it.nimaScore != null }
        if (valid.size < MIN_VALID_CARDS) return GachaResult.Unavailable
        val best = valid.maxBy { it.nimaScore!! }
        return if (originalScore != null && best.nimaScore!! <= originalScore + MIN_IMPROVEMENT) {
            GachaResult.KeepOriginal(all = all, originalScore = originalScore)
        } else {
            GachaResult.Selected(best = best, all = all, originalScore = originalScore)
        }
    }
}
