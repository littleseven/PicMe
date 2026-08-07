package com.mamba.picme.beauty.internal.facedetect

import android.graphics.PointF
import com.mamba.picme.beauty.api.facedetect.FaceContourData
import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource
import com.mamba.picme.beauty.api.facedetect.FaceWarpParams
import com.mamba.picme.beauty.api.facedetect.GpuPixelLandmarks

/**
 * 将 106 点 FloatArray 转换为 FaceWarpParams
 *
 * [GC 优化] 所有内部计算直接操作 FloatArray 索引，彻底消除 PointF 对象分配。
 * 相比原实现，每次 convert() 调用减少约 13 个短生命周期 PointF 对象。
 *
 * 106点索引定义（字节火山引擎标准，画面视角，前置摄像头镜像后）：
 *   画面左侧 = 实际右脸，画面右侧 = 实际左脸
 *
 * - 0-32:   脸部轮廓 33点（开放曲线：右鬓角0 → 下巴16 → 左鬓角32）
 * - 33-37:  右眉上部 5点（画面左侧=实际右脸，从眉头到眉尾）
 * - 38-42:  左眉上部 5点（画面右侧=实际左脸，从眉头到眉尾）
 * - 43:     眉心
 * - 44-46:  鼻梁 3点（从上到下）
 * - 47-51:  鼻尖 5点（从左到右：左鼻翼上→鼻尖左→鼻尖中心→鼻尖右→右鼻翼上）
 * - 52-57:  右眼外轮廓 6点（画面左侧=实际右脸，从外角→内角顺时针）
 * - 58-63:  左眼外轮廓 6点（画面右侧=实际左脸，从外角→内角顺时针）
 * - 64-67:  右眉下部 4点（画面左侧，从眉头到眉尾）
 * - 68-71:  左眉下部 4点（画面右侧，从眉头到眉尾）
 * - 72-74:  右眼内/下 3点（72=右眼内角，73=右眼下眼睑中，74=右瞳孔）
 * - 75-77:  左眼内/下 3点（75=左眼内角，76=左眼下眼睑中，77=左瞳孔）
 * - 78-79:  山根 2点（鼻梁起点左右：78=山根左，79=山根右）
 * - 80-83:  鼻孔 4点（80=左鼻孔左，81=左鼻孔右，82=右鼻孔左，83=右鼻孔右）
 * - 84-95:  嘴巴外轮廓 12点（84=左嘴角，90=唇珠，94=右嘴角）
 * - 96-103: 嘴巴内轮廓 8点（96=左内角，100=右内角）
 * - 104:    右瞳孔（与74同位置）
 * - 105:    左瞳孔（与77同位置）
 */
object Face106ToWarpParams {

    const val POINT_COUNT = 106
    const val CONTOUR_POINT_COUNT = 33
    const val NON_CONTOUR_POINT_COUNT = 73

    fun convert(
        landmarks106: FloatArray,
        detectionSource: FaceDetectionSource = FaceDetectionSource.NONE
    ): FaceWarpParams {
        require(landmarks106.size >= POINT_COUNT * 2)

        // [GC 优化] 使用原始浮点计算，避免 PointF 对象分配
        val faceCenterX = sumRange(landmarks106, 0, 33) / 33f
        val faceCenterY = sumRange(landmarks106, 1, 33) / 33f
        val faceRadius = calculateFaceRadius(landmarks106, faceCenterX, faceCenterY)

        // 左眼中心 (索引 52-57 外轮廓 + 72-73 内眼角)
        val leftEyeX = (sumRange(landmarks106, 52 * 2, 6) +
                sumX(landmarks106, 72) + sumX(landmarks106, 73)) / 8f
        val leftEyeY = (sumRange(landmarks106, 52 * 2 + 1, 6) +
                sumY(landmarks106, 72) + sumY(landmarks106, 73)) / 8f

        // 右眼中心 (索引 58-63 外轮廓 + 75-76 内眼角)
        val rightEyeX = (sumRange(landmarks106, 58 * 2, 6) +
                sumX(landmarks106, 75) + sumX(landmarks106, 76)) / 8f
        val rightEyeY = (sumRange(landmarks106, 58 * 2 + 1, 6) +
                sumY(landmarks106, 75) + sumY(landmarks106, 76)) / 8f

        // 嘴巴中心
        val mouthCenterX = (sumRange(landmarks106, 84 * 2, 12) +
                sumRange(landmarks106, 96 * 2, 8)) / 20f
        val mouthCenterY = (sumRange(landmarks106, 84 * 2 + 1, 12) +
                sumRange(landmarks106, 96 * 2 + 1, 8)) / 20f

        val contourPoints = extractContourPoints(landmarks106)
        val leftEyeContour = extractEyeContour(landmarks106, isLeft = true)
        val rightEyeContour = extractEyeContour(landmarks106, isLeft = false)
        val mouthContour = extractMouthContour(landmarks106)
        val leftCheekContour = extractCheekContour(landmarks106, isLeft = true)
        val rightCheekContour = extractCheekContour(landmarks106, isLeft = false)

        val bigBeautyLandmarks = GpuPixelLandmarks.fromFloatArray(landmarks106)

        return FaceWarpParams(
            faceCenterX = faceCenterX,
            faceCenterY = faceCenterY,
            leftEyeX = leftEyeX,
            leftEyeY = leftEyeY,
            rightEyeX = rightEyeX,
            rightEyeY = rightEyeY,
            mouthCenterX = mouthCenterX,
            mouthCenterY = mouthCenterY,
            mouthLeftX = landmarks106[84 * 2],
            mouthLeftY = landmarks106[84 * 2 + 1],
            mouthRightX = landmarks106[90 * 2],
            mouthRightY = landmarks106[90 * 2 + 1],
            upperLipCenterX = landmarks106[87 * 2],
            upperLipCenterY = landmarks106[87 * 2 + 1],
            lowerLipCenterX = landmarks106[93 * 2],
            lowerLipCenterY = landmarks106[93 * 2 + 1],
            faceRadius = faceRadius,
            hasFace = true,
            contourPoints = contourPoints,
            leftEyeContourPoints = leftEyeContour,
            rightEyeContourPoints = rightEyeContour,
            lipOuterContourPoints = mouthContour,
            lipInnerContourPoints = emptyList(),
            leftCheekContourPoints = leftCheekContour,
            rightCheekContourPoints = rightCheekContour,
            allContours = FaceContourData(),
            bigBeautyLandmarks = bigBeautyLandmarks,
            detectionSource = detectionSource
        )
    }

    /** 对 landmarks 从 startIdx 处连续取 count 个 x 坐标求和 */
    private fun sumRange(landmarks: FloatArray, startIdx: Int, count: Int): Float {
        var sum = 0f
        for (i in 0 until count) {
            sum += landmarks[startIdx + i * 2]
        }
        return sum
    }

    /** 取第 index 个点的 x 坐标 */
    private fun sumX(landmarks: FloatArray, index: Int): Float = landmarks[index * 2]
    /** 取第 index 个点的 y 坐标 */
    private fun sumY(landmarks: FloatArray, index: Int): Float = landmarks[index * 2 + 1]

    private fun calculateFaceRadius(landmarks: FloatArray, centerX: Float, centerY: Float): Float {
        var maxDist = 0f
        for (i in 0..32) {
            val dx = landmarks[i * 2] - centerX
            val dy = landmarks[i * 2 + 1] - centerY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            maxDist = kotlin.math.max(maxDist, dist)
        }
        return maxDist.coerceIn(0.12f, 0.45f)
    }

    private fun extractContourPoints(landmarks: FloatArray): List<PointF> {
        return (0..32).map { PointF(landmarks[it * 2], landmarks[it * 2 + 1]) }
    }

    private fun extractEyeContour(landmarks: FloatArray, isLeft: Boolean): List<PointF> {
        val outerStart = if (isLeft) 52 else 58
        val innerStart = if (isLeft) 72 else 75
        val result = mutableListOf<PointF>()
        for (i in outerStart until outerStart + 6) {
            result.add(PointF(landmarks[i * 2], landmarks[i * 2 + 1]))
        }
        for (i in innerStart until innerStart + 3) {
            result.add(PointF(landmarks[i * 2], landmarks[i * 2 + 1]))
        }
        return result
    }

    private fun extractMouthContour(landmarks: FloatArray): List<PointF> {
        val result = mutableListOf<PointF>()
        for (i in 84..95) {
            result.add(PointF(landmarks[i * 2], landmarks[i * 2 + 1]))
        }
        for (i in 96..103) {
            result.add(PointF(landmarks[i * 2], landmarks[i * 2 + 1]))
        }
        return result
    }

    private fun extractCheekContour(landmarks: FloatArray, isLeft: Boolean): List<PointF> {
        return if (isLeft) {
            (2..6).map { PointF(landmarks[it * 2], landmarks[it * 2 + 1]) }
        } else {
            (27..31).map { PointF(landmarks[it * 2], landmarks[it * 2 + 1]) }
        }
    }
}
