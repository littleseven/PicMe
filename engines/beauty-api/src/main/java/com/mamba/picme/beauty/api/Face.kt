package com.mamba.picme.beauty.api

import android.graphics.PointF
import android.graphics.Rect

/**
 * 人脸检测结果数据类（替代 ML Kit 的 com.google.mlkit.vision.face.Face）
 *
 * 仅保留 CPU 美颜路径所需的最小 API 表面：
 * - boundingBox
 * - getLandmark(type) -> FaceLandmark?
 * - getContour(type) -> FaceContour?
 */
data class Face(
    val boundingBox: Rect,
    val landmarks: Map<Int, PointF> = emptyMap(),
    val contours: Map<Int, List<PointF>> = emptyMap()
) {
    fun getLandmark(type: Int): FaceLandmark? {
        val position = landmarks[type] ?: return null
        return FaceLandmark(position)
    }

    fun getContour(type: Int): FaceContour? {
        val points = contours[type] ?: return null
        return FaceContour(points)
    }
}

data class FaceContour(val points: List<PointF>) {
    companion object {
        const val FACE = 1
        const val UPPER_LIP_TOP = 2
        const val UPPER_LIP_BOTTOM = 3
        const val LOWER_LIP_TOP = 4
        const val LOWER_LIP_BOTTOM = 5
        const val LEFT_CHEEK = 6
        const val RIGHT_CHEEK = 7
    }
}

data class FaceLandmark(val position: PointF) {
    companion object {
        const val LEFT_EYE = 1
        const val RIGHT_EYE = 2
        const val MOUTH_LEFT = 3
        const val MOUTH_RIGHT = 4
        const val MOUTH_BOTTOM = 5
    }
}
