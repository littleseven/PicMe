package com.mamba.picme.beauty.api.facedetect

import android.graphics.RectF

/**
 * 人脸检测结果
 *
 * @param landmarks106 106 点归一化坐标（FloatArray，偶数索引=x，奇数索引=y）
 * @param detectionSource 检测算法来源
 * @param roiRect ROI 区域（归一化坐标）
 * @param roiDetectorName ROI 检测器名称（用于调试显示）
 * @param useGpuForRoi 是否使用 GPU 进行 ROI 检测
 * @param landmarkDetectorName 关键点检测器名称（用于调试显示）
 * @param useGpuForLandmark 是否使用 GPU 进行关键点检测
 */
data class FaceDetectionResult(
    val landmarks106: FloatArray,
    val detectionSource: FaceDetectionSource,
    val roiRect: RectF? = null,
    val roiDetectorName: String = "Unknown",
    val useGpuForRoi: Boolean = false,
    val landmarkDetectorName: String = "Unknown",
    val useGpuForLandmark: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FaceDetectionResult

        if (!landmarks106.contentEquals(other.landmarks106)) return false
        if (detectionSource != other.detectionSource) return false
        if (roiRect != other.roiRect) return false
        if (roiDetectorName != other.roiDetectorName) return false
        if (useGpuForRoi != other.useGpuForRoi) return false
        if (landmarkDetectorName != other.landmarkDetectorName) return false
        if (useGpuForLandmark != other.useGpuForLandmark) return false

        return true
    }

    override fun hashCode(): Int {
        var result = landmarks106.contentHashCode()
        result = 31 * result + detectionSource.hashCode()
        result = 31 * result + (roiRect?.hashCode() ?: 0)
        result = 31 * result + roiDetectorName.hashCode()
        result = 31 * result + useGpuForRoi.hashCode()
        result = 31 * result + landmarkDetectorName.hashCode()
        result = 31 * result + useGpuForLandmark.hashCode()
        return result
    }
}
