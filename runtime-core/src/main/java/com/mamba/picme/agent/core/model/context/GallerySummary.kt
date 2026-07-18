package com.mamba.picme.agent.core.model.context

/**
 * 本地相册摘要，供 LLM 在 Chat 中感知相册状态。
 *
 * 所有数字均为计数；不包含任何媒体 URI 或 embedding 等大字段，保持轻量。
 */
data class GallerySummary(
    val totalPhotos: Int,
    val totalVideos: Int,
    val totalMedia: Int,
    val hasFaceCount: Int,
    val personClusterCount: Int,
    val namedPersonCount: Int,
    val labeledCount: Int,
    val unlabeledCount: Int,
    val mlKitLabeledCount: Int,
    val semanticEncodedCount: Int,
    val remainingPass1: Int,
    val remainingPass3: Int,
    val remainingMlKit: Int,
    val isScanning: Boolean,
    val currentPass: String? = null,
    val scanProgressText: String? = null,
    val recommendation: ScanRecommendation,
    /** 命令请求时是否包含 details；仅影响格式化输出，不影响统计口径。 */
    val includeDetails: Boolean = false
) {
    enum class ScanRecommendation {
        NONE,
        INCREMENTAL,
        PASS3_FULL,
        PASS1_FIRST
    }
}
