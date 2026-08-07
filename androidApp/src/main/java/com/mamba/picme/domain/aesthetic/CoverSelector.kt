package com.mamba.picme.domain.aesthetic

/**
 * 一个候选封面：媒体 id + NIMA 美学分(1..10) + eDifFIQA 人脸质量分(~0..1)；未评分为 null。
 */
data class CoverCandidate(
    val mediaId: Long,
    val aestheticScore: Float?,
    val faceQualityScore: Float?
)

/**
 * 封面选择纯逻辑：NIMA 美学 + eDifFIQA 人脸质量**加权组合**（人脸质量为主），取最高者。
 *
 * - 美学归一 (a-1)/9 ∈ [0,1]；人脸质量 q clamp 到 [0,1]。
 * - 两者都有：`W_FACE·q + W_AESTHETIC·aNorm`（人脸质量为主）。
 * - 仅人脸质量：用 q；仅美学：用 aNorm；都无：null（调用方回退旧逻辑，如 firstOrNull）。
 *
 * 权重为可调常量，上线后可据效果调参。便于 JVM 单测。
 */
object CoverSelector {
    const val W_FACE = 0.6f
    const val W_AESTHETIC = 0.4f

    fun combinedScore(aestheticScore: Float?, faceQualityScore: Float?): Float? {
        val aNorm = aestheticScore?.let { ((it - 1f) / 9f).coerceIn(0f, 1f) }
        val q = faceQualityScore?.coerceIn(0f, 1f)
        return when {
            q != null && aNorm != null -> W_FACE * q + W_AESTHETIC * aNorm
            q != null -> q
            aNorm != null -> aNorm
            else -> null
        }
    }

    fun bestCoverMediaId(candidates: List<CoverCandidate>): Long? =
        candidates
            .mapNotNull { c ->
                combinedScore(c.aestheticScore, c.faceQualityScore)?.let { score -> c.mediaId to score }
            }
            .maxByOrNull { pair -> pair.second }
            ?.first
}
