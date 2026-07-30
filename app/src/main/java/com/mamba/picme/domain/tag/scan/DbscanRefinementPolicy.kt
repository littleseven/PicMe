package com.mamba.picme.domain.tag.scan

import com.mamba.picme.domain.tag.ClusteringConfig

/**
 * DBSCAN 周期精修触发判定（纯逻辑）。
 *
 * 流式聚类已让人物尽早可见，DBSCAN 降级为「按需精修」：
 * - 末批（整轮扫描结束）必跑一次，保证最终质量；
 * - 中途仅当自上次精修以来流式归类的 embedding 数达 [ClusteringConfig.RE_CLUSTER_THRESHOLD] 才跑。
 */
object DbscanRefinementPolicy {
    fun shouldRunRefinement(embeddingsSinceDbscan: Int, isFinalBatch: Boolean): Boolean {
        if (isFinalBatch) return true
        return embeddingsSinceDbscan >= ClusteringConfig.RE_CLUSTER_THRESHOLD
    }
}
