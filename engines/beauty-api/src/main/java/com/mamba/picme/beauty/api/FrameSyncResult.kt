package com.mamba.picme.beauty.api

import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource

/**
 * 帧同步结果
 *
 * @param frameId 关联的帧 ID
 * @param landmarks106 106 点归一化坐标（FloatArray，偶数索引=x，奇数索引=y）
 * @param detectionSource 检测算法来源
 * @param syncStatus 同步状态
 * @param detectionLatencyMs 检测滞后时间（ms）
 * @param predictedOffsetPx 预测补偿的像素位移量
 */
data class FrameSyncResult(
    val frameId: FrameId = FrameId.INVALID,
    val landmarks106: FloatArray? = null,
    val detectionSource: FaceDetectionSource = FaceDetectionSource.NONE,
    val syncStatus: SyncStatus = SyncStatus.MISSING,
    val detectionLatencyMs: Long = 0L,
    val predictedOffsetPx: Float = 0f
) {
    enum class SyncStatus {
        EXACT_MATCH,
        HISTORICAL_FALLBACK,
        PREDICTED,
        MISSING
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FrameSyncResult
        if (frameId != other.frameId) return false
        if (landmarks106 != null) {
            if (other.landmarks106 == null) return false
            if (!landmarks106.contentEquals(other.landmarks106)) return false
        } else if (other.landmarks106 != null) return false
        if (detectionSource != other.detectionSource) return false
        if (syncStatus != other.syncStatus) return false
        if (detectionLatencyMs != other.detectionLatencyMs) return false
        if (predictedOffsetPx != other.predictedOffsetPx) return false
        return true
    }

    override fun hashCode(): Int {
        var result = frameId.hashCode()
        result = 31 * result + (landmarks106?.contentHashCode() ?: 0)
        result = 31 * result + detectionSource.hashCode()
        result = 31 * result + syncStatus.hashCode()
        result = 31 * result + detectionLatencyMs.hashCode()
        result = 31 * result + predictedOffsetPx.hashCode()
        return result
    }

    companion object {
        val MISSING = FrameSyncResult(syncStatus = SyncStatus.MISSING)
    }
}
