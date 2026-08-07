package com.mamba.picme.beauty.internal.facedetect.adapter

import android.os.SystemClock
import com.mamba.picme.beauty.api.Logger
import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource

// CameraSelector.LENS_FACING_FRONT = 0, LENS_FACING_BACK = 1
private const val LENS_FACING_FRONT = 0

/**
 * MNN Landmark 适配器
 *
 * MNN 模型 (2d106det.mnn) 是从 InsightFace ONNX 模型转换而来，
 * 其输出点序与 InsightFace 原始输出一致，**不是**统一 106 格式。
 * 因此需要复用 InsightFaceAdapter 的 FULL_REMAP 映射表进行点序重排。
 *
 * 映射完成后，再处理前置摄像头的水平镜像。
 */
class MnnLandmarkAdapter : FaceLandmarkAdapter {

    override val detectionSource: FaceDetectionSource = FaceDetectionSource.MNN

    companion object {
        private const val POINT_COUNT = 106

        /**
         * 复用 InsightFaceAdapter 的完整映射表
         * 统一 106 索引 → MNN/InsightFace 索引
         */
        private val FULL_REMAP = intArrayOf(
            // ===== 轮廓点 0-32 =====
            // 统一 0-15（左太阳穴→下巴左侧）→ InsightFace 1, 9-16, 2-8
            1, 9, 10, 11, 12, 13, 14, 15, 16, 2, 3, 4, 5, 6, 7, 8,
            // 统一 16 → InsightFace 0（下巴中心）
            0,
            // 统一 17-32（下巴右侧→右太阳穴）→ InsightFace 24-18, 32-25, 17（倒排）
            24, 23, 22, 21, 20, 19, 18, 32, 31, 30, 29, 28, 27, 26, 25, 17,

            // ===== 非轮廓点 33-105 =====
            // 眉毛上部：统一 33-37（右眉，画面左侧从眉头到眉尾）→ InsightFace 43, 48, 49, 51, 50
            43, 48, 49, 51, 50,
            // 眉毛上部：统一 38-42（左眉，画面右侧从眉头到眉尾）→ InsightFace 101, 105, 104, 103, 102
            101, 105, 104, 103, 102,
            // 眉心：统一 43 → InsightFace 72
            72,
            // 鼻梁：统一 44-46（从上到下）→ InsightFace 73, 74, 86
            73, 74, 86,
            // 鼻尖：统一 47-51（从上到下）→ InsightFace 78, 79, 80, 85, 84
            78, 79, 80, 85, 84,
            // 右眼外轮廓：统一 52-57（画面左侧）→ InsightFace 35, 41, 42, 39, 37, 36
            35, 41, 42, 39, 37, 36,
            // 左眼外轮廓：统一 58-63（画面右侧）→ InsightFace 89, 95, 96, 93, 91, 90
            89, 95, 96, 93, 91, 90,
            // 眉毛下部：统一 64-67（右眉，画面左侧从眉头到眉尾）→ InsightFace 44, 45, 47, 46
            44, 45, 47, 46,
            // 眉毛下部：统一 68-71（左眉，画面右侧从眉头到眉尾）→ InsightFace 100, 99, 98, 97
            100, 99, 98, 97,
            // 右眼补充：统一 72-74（画面左侧）→ InsightFace 40, 33, 34
            40, 33, 34,
            // 左眼补充：统一 75-77（画面右侧）→ InsightFace 94, 87, 92
            94, 87, 92,
            // 山根：统一 78-79（从上到下）→ InsightFace 75, 81
            75, 81,
            // 鼻孔：统一 80-83（从左到右）→ InsightFace 76, 82, 77, 83
            76, 82, 77, 83,
            // 嘴巴外轮廓：统一 84-95 → InsightFace 52, 64, 63, 71, 67, 68, 61, 58, 59, 53, 56, 55
            52, 64, 63, 71, 67, 68, 61, 58, 59, 53, 56, 55,
            // 嘴巴内轮廓：统一 96-103 → InsightFace 65, 66, 62, 70, 69, 57, 60, 54
            65, 66, 62, 70, 69, 57, 60, 54,
            // 瞳孔：统一 104-105 → InsightFace 38, 88
            38, 88
        )
    }

    override fun adapt(nativeLandmarks: FloatArray, lensFacing: Int): Result<FloatArray> {
        if (nativeLandmarks.size < POINT_COUNT * 2) {
            return Result.failure(
                IllegalArgumentException(
                    "MNN landmarks size ${nativeLandmarks.size} < required ${POINT_COUNT * 2}"
                )
            )
        }

        val startTime = SystemClock.elapsedRealtime()
        val isFrontCamera = lensFacing == LENS_FACING_FRONT
        val unified = FloatArray(POINT_COUNT * 2)

        // [关键修复] 复用 InsightFace 的 FULL_REMAP 映射表
        // MNN 模型输出点序与 InsightFace 原始输出一致
        for (unifiedIdx in 0 until POINT_COUNT) {
            val srcIdx = FULL_REMAP[unifiedIdx]
            val srcX = nativeLandmarks[srcIdx * 2]
            val srcY = nativeLandmarks[srcIdx * 2 + 1]
            unified[unifiedIdx * 2] = if (isFrontCamera) 1f - srcX else srcX
            unified[unifiedIdx * 2 + 1] = srcY
        }

        val elapsed = SystemClock.elapsedRealtime() - startTime
        Logger.d("MnnLandmarkAdapter", "[Perf] adapt DONE: ${elapsed}ms (InsightFace remap)")

        return Result.success(unified)
    }
}
