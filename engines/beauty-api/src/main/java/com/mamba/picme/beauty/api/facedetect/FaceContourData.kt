package com.mamba.picme.beauty.api.facedetect

import android.graphics.PointF

/**
 * 存储所有人脸 Contour 点的数据类
 */
data class FaceContourData(
    val faceOval: List<PointF> = emptyList(),
    val leftEyebrowTop: List<PointF> = emptyList(),
    val leftEyebrowBottom: List<PointF> = emptyList(),
    val rightEyebrowTop: List<PointF> = emptyList(),
    val rightEyebrowBottom: List<PointF> = emptyList(),
    val leftEye: List<PointF> = emptyList(),
    val rightEye: List<PointF> = emptyList(),
    val upperLipTop: List<PointF> = emptyList(),
    val upperLipBottom: List<PointF> = emptyList(),
    val lowerLipTop: List<PointF> = emptyList(),
    val lowerLipBottom: List<PointF> = emptyList(),
    val noseBridge: List<PointF> = emptyList(),
    val noseBottom: List<PointF> = emptyList(),
    val leftCheek: List<PointF> = emptyList(),
    val rightCheek: List<PointF> = emptyList()
) {
    fun totalPointCount(): Int {
        return faceOval.size + leftEyebrowTop.size + leftEyebrowBottom.size +
            rightEyebrowTop.size + rightEyebrowBottom.size +
            leftEye.size + rightEye.size +
            upperLipTop.size + upperLipBottom.size +
            lowerLipTop.size + lowerLipBottom.size +
            noseBridge.size + noseBottom.size +
            leftCheek.size + rightCheek.size
    }
}
