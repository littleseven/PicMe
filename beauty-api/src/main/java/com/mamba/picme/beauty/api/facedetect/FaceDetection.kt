package com.mamba.picme.beauty.api.facedetect

import android.graphics.RectF

/**
 * 单个人脸检测结果（ROI + 5 关键点）
 *
 * 为人脸识别/聚类模型提供对齐所需信息。
 * 5 点 landmarks 顺序遵循 RetinaFace / ArcFace 标准：
 * [左眼 x, 左眼 y, 右眼 x, 右眼 y, 鼻尖 x, 鼻尖 y, 左嘴角 x, 左嘴角 y, 右嘴角 x, 右嘴角 y]
 *
 * @param roi 人脸 ROI 区域（像素坐标，基于原始 Bitmap）
 * @param landmarks5 5 点归一化/像素坐标（FloatArray，长度 10）。
 *                   当前实现使用原始图像像素坐标；若未对齐则可为 null。
 */
data class FaceDetection(
    val roi: RectF,
    val landmarks5: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceDetection
        return roi == other.roi &&
            ((landmarks5 == null && other.landmarks5 == null) ||
                (landmarks5 != null && other.landmarks5 != null &&
                    landmarks5.contentEquals(other.landmarks5)))
    }

    override fun hashCode(): Int {
        var result = roi.hashCode()
        result = 31 * result + (landmarks5?.contentHashCode() ?: 0)
        return result
    }
}
