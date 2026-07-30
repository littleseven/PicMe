package com.mamba.picme.domain.tag

/**
 * 小簇跨簇合并判定（纯逻辑，无 Android 依赖，便于 JVM 单测）。
 *
 * 供 [FaceClusterEngine.mergeSmallClusters] 复用：对每个小簇找到最近邻后，
 * 用本函数决定「是否合并、谁存活」，把副作用（改派 embedding、删 person）留给引擎。
 */

/** 候选 person 的纯属性快照。 */
data class MergeCandidate(
    val personId: Long,
    val name: String?,
    val isSelf: Boolean,
    val embeddingCount: Int
)

/** 合并结果：[survivor] 存活（保留其 name/isSelf/cover），[absorbed] 被并入后删除。 */
data class MergeDecision(val survivor: MergeCandidate, val absorbed: MergeCandidate)

/**
 * 是否合并小簇 [small] 到其最近邻 [neighbor]，以及幸存者。
 *
 * 优先级：相似度 ≥ [threshold] 才考虑；**双方都已命名则跳过**（尊重用户人工区分）。
 * 幸存者选择（保留 name/isSelf）：
 * 1. 恰一方 isSelf → self 方存活；
 * 2. 否则恰一方命名 → 命名方存活；
 * 3. 否则（都匿名）→ embeddingCount 大者存活，并列取 personId 小者（更早创建）。
 *
 * @return 合并决策；null 表示不合并。
 */
fun decideSmallClusterMerge(
    small: MergeCandidate,
    neighbor: MergeCandidate,
    similarity: Float,
    threshold: Float
): MergeDecision? {
    if (similarity < threshold) return null

    val smallNamed = !small.name.isNullOrBlank()
    val neighborNamed = !neighbor.name.isNullOrBlank()
    // 双方都已命名：可能是用户特意区分的撞脸，不自动合并。
    if (smallNamed && neighborNamed) return null

    val survivor: MergeCandidate
    val absorbed: MergeCandidate
    when {
        small.isSelf && !neighbor.isSelf -> { survivor = small; absorbed = neighbor }
        neighbor.isSelf && !small.isSelf -> { survivor = neighbor; absorbed = small }
        smallNamed && !neighborNamed -> { survivor = small; absorbed = neighbor }
        neighborNamed && !smallNamed -> { survivor = neighbor; absorbed = small }
        else -> {
            // 都匿名：规模大者存活；并列取更早创建（personId 小）
            val neighborSurvives = neighbor.embeddingCount > small.embeddingCount ||
                (neighbor.embeddingCount == small.embeddingCount && neighbor.personId < small.personId)
            if (neighborSurvives) { survivor = neighbor; absorbed = small }
            else { survivor = small; absorbed = neighbor }
        }
    }
    return MergeDecision(survivor, absorbed)
}
