package com.mamba.picme.domain.aesthetic

/** 一个候选封面：媒体 id + 美学分（未评分为 null）。 */
data class CoverCandidate(val mediaId: Long, val score: Float?)

/**
 * 纯逻辑：从候选中选美学分最高的 mediaId。
 * 全部未评分返回 null（调用方回退旧逻辑，如 firstOrNull）。便于 JVM 单测。
 */
object CoverSelector {
    fun bestCoverMediaId(candidates: List<CoverCandidate>): Long? =
        candidates
            .filter { candidate -> candidate.score != null }
            .maxByOrNull { candidate -> candidate.score!! }
            ?.mediaId
}
