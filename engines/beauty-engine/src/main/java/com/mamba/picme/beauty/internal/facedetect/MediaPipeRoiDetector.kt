package com.mamba.picme.beauty.internal.facedetect

import android.graphics.Bitmap
import android.os.SystemClock
import com.mamba.picme.beauty.api.Logger
import com.google.mediapipe.framework.image.BitmapImageBuilder
import android.graphics.RectF

/**
 * 基于 MediaPipe 的 ROI 检测器
 * 优势: 快速、精确、支持多帧跟踪
 */
class MediaPipeRoiDetector(
    private val faceDetector: MediaPipeFaceDetector
) : RoiDetector {
    companion object {
        private const val TAG = "MediaPipeRoi"
    }

    override fun detectRoi(bitmap: Bitmap): RectF? {
        val startTime = SystemClock.elapsedRealtime()
        val mpImage = BitmapImageBuilder(bitmap).build()
        return try {
            Logger.d(TAG, "[Perf] MediaPipe ROI START: bitmap=${bitmap.width}x${bitmap.height}")

            val inferenceStart = SystemClock.elapsedRealtime()
            val result = faceDetector.videoLandmarker?.detectForVideo(
                mpImage,
                SystemClock.uptimeMillis()
            )
            val inferenceTime = SystemClock.elapsedRealtime() - inferenceStart

            result?.faceLandmarks()?.firstOrNull()?.let { landmarks ->
                var minX = 1f
                var maxX = 0f
                var minY = 1f
                var maxY = 0f

                landmarks.forEach { landmark ->
                    val x = landmark.x()
                    val y = landmark.y()
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }

                val roi = RectF(
                    minX * bitmap.width.toFloat(),
                    minY * bitmap.height.toFloat(),
                    maxX * bitmap.width.toFloat(),
                    maxY * bitmap.height.toFloat()
                )

                val totalTime = SystemClock.elapsedRealtime() - startTime
                Logger.d(TAG, "[Perf] MediaPipe ROI DONE: total=${totalTime}ms, inference=${inferenceTime}ms, roi=$roi")
                roi
            } ?: run {
                val totalTime = SystemClock.elapsedRealtime() - startTime
                Logger.w(TAG, "[Perf] MediaPipe ROI DONE: total=${totalTime}ms, no face detected")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "MediaPipe ROI detection failed", e)
            null
        } finally {
            runCatching { mpImage.close() }
        }
    }

    override fun release() {
        faceDetector.release()
    }
}
