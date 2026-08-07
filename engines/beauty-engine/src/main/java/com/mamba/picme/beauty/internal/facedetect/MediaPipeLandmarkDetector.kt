package com.mamba.picme.beauty.internal.facedetect

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.Image
import com.mamba.picme.beauty.api.Logger

/**
 * 基于 MediaPipe 的关键点检测器
 * 优势: 468 点高密度、实时性强
 */
class MediaPipeLandmarkDetector(
    private val faceDetector: MediaPipeFaceDetector
) : LandmarkDetector {
    companion object {
        private const val TAG = "MediaPipeLandmark"
    }

    /**
     * 预览路径 landmark 检测（Image 零拷贝输入）
     *
     * MediaPipe 不需要 ROI，直接全图检测。
     * 使用 [MediaPipeFaceDetector.detect] 的 Image 重载。
     */
    fun detectLandmarks(
        mediaImage: Image,
        lensFacing: Int,
        rotationDegrees: Int,
        roi: RectF?
    ): FloatArray? {
        return try {
            val result = faceDetector.detect(mediaImage, rotationDegrees, lensFacing)

            if (result != null) {
                Logger.d(TAG, "MediaPipe landmarks detected (Image): ${result.size / 2} points")
            }
            result
        } catch (e: Exception) {
            Logger.e(TAG, "MediaPipe landmark detection (Image) failed", e)
            null
        }
    }

    override fun detectLandmarks(
        bitmap: Bitmap,
        lensFacing: Int,
        roi: RectF?
    ): FloatArray? {
        return try {
            // MediaPipe 不需要 ROI,直接检测全图
            val result = faceDetector.detectForPhoto(bitmap, lensFacing)

            if (result != null) {
                Logger.d(TAG, "MediaPipe landmarks detected: ${result.size / 2} points")
            }

            result
        } catch (e: Exception) {
            Logger.e(TAG, "MediaPipe landmark detection failed", e)
            null
        }
    }

    override fun release() {
        faceDetector.release()
    }
}
