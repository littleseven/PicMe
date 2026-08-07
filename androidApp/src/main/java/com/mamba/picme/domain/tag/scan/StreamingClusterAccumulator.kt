package com.mamba.picme.domain.tag.scan

import com.mamba.picme.domain.tag.ClusteringConfig

/**
 * 流式攒批聚类计数器：Pass1 每处理一张含人脸图调用 [onFacePhoto]，
 * 累计达到 [batchSize] 时返回 true（触发一次流式聚类）并自动清零。
 *
 * 纯逻辑、无副作用，便于 JVM 单测；状态由持有方（[TagScanOrchestrator]）跨批次保留。
 */
class StreamingClusterAccumulator(
    private val batchSize: Int = ClusteringConfig.STREAMING_CLUSTER_BATCH
) {
    private var pending: Int = 0

    /** 记录一张含人脸图；返回 true 表示已达阈值、应触发流式聚类（触发后自动清零）。 */
    fun onFacePhoto(): Boolean {
        pending++
        if (pending >= batchSize) {
            pending = 0
            return true
        }
        return false
    }

    /** 手动清零（全量重扫等场景）。 */
    fun reset() {
        pending = 0
    }
}
