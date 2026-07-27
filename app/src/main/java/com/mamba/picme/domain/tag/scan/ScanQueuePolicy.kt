package com.mamba.picme.domain.tag.scan

import com.mamba.picme.data.local.entity.TagScanPass

/**
 * TAG 扫描队列策略
 *
 * 控制自动增量扫描的去重窗口、排序、批次大小与失败重试行为。
 */
data class ScanQueuePolicy(
    /** 跳过最近 N 毫秒内已成功全量扫描的媒体 */
    val skipRecentlyTaggedMs: Long = DEFAULT_SKIP_RECENTLY_TAGGED_MS,

    /** 任务排序方式：默认 newest-first，优先处理新拍摄/新添加的照片 */
    val order: QueueOrder = QueueOrder.NEWEST_FIRST,

    /** 单次自动扫描最大任务数 */
    val maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE,

    /** 是否自动重试失败项 */
    val retryFailed: Boolean = false,

    /** 失败项最小重试间隔 */
    val failedRetryIntervalMs: Long = DEFAULT_FAILED_RETRY_INTERVAL_MS,

    /** 最大重试次数 */
    val maxRetryAttempts: Int = DEFAULT_MAX_RETRY_ATTEMPTS,

    /** 本次扫描第一阶段立即执行的 Pass */
    val passes: List<TagScanPass> = listOf(
        TagScanPass.FACE_DETECTION,
        TagScanPass.DBSCAN
    ),

    /** 第一阶段全量完成后才执行的 Pass（延迟阶段）。
     *  阶段切换由 TagScanOrchestrator.scheduleAutoScan 在第一批次链式耗尽时自动触发。 */
    val deferredPasses: List<TagScanPass> = listOf(
        TagScanPass.IMAGE_TAGGING
    )
) {
    companion object {
        /** 默认 4 小时避重窗口 */
        const val DEFAULT_SKIP_RECENTLY_TAGGED_MS = 4 * 60 * 60 * 1000L

        /** 默认单次自动扫描最多 50 张 */
        const val DEFAULT_MAX_BATCH_SIZE = 50

        /** 失败项默认 24 小时后才能自动重试 */
        const val DEFAULT_FAILED_RETRY_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** 默认最大重试 3 次 */
        const val DEFAULT_MAX_RETRY_ATTEMPTS = 3

        /** 保守策略：小批次、短避重窗口 */
        fun conservative() = ScanQueuePolicy(
            skipRecentlyTaggedMs = 60 * 60 * 1000L,
            maxBatchSize = 20,
            retryFailed = false
        )

        /** 夜间充电策略：大批次、长避重窗口 */
        fun overnight() = ScanQueuePolicy(
            skipRecentlyTaggedMs = 12 * 60 * 60 * 1000L,
            maxBatchSize = 200,
            retryFailed = true
        )
    }
}

enum class QueueOrder {
    /** 优先处理从未扫描或最久未扫描的媒体 */
    OLDEST_FIRST,

    /** 优先处理最新拍摄的媒体 */
    NEWEST_FIRST
}

enum class ScanMode {
    /** 只补缺失（已有结果不覆盖） */
    INCREMENTAL,

    /** 清空指定类别/阶段后重新生成 */
    FULL
}
