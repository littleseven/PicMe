package com.mamba.picme.features.camera

import android.graphics.RectF
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 人脸检测结果缓存
 *
 * 用于在预览和拍照之间共享人脸检测数据，减少重复检测导致的效果差异。
 * 使用 MediaPipe 106 点格式。
 */
object FaceDetectionCache {
    private val cachedLandmarks106 = AtomicReference<FloatArray?>(null)
    private val cachedRoiNormalized = AtomicReference<RectF?>(null)
    private val lastLandmarksUpdateTimeMs = AtomicLong(0L)
    private val lastRoiUpdateTimeMs = AtomicLong(0L)
    private const val LANDMARK_CACHE_VALIDITY_MS = 500L
    private const val ROI_CACHE_VALIDITY_MS = 1200L

    /**
     * 更新 MediaPipe 106 点数据缓存
     */
    fun updateLandmarks106(landmarks: FloatArray) {
        cachedLandmarks106.set(landmarks.copyOf())
        lastLandmarksUpdateTimeMs.set(System.currentTimeMillis())
    }

    /**
     * 更新归一化 ROI（0~1）缓存。
     */
    fun updateRoiNormalized(roiNormalized: RectF) {
        cachedRoiNormalized.set(
            RectF(
                roiNormalized.left.coerceIn(0f, 1f),
                roiNormalized.top.coerceIn(0f, 1f),
                roiNormalized.right.coerceIn(0f, 1f),
                roiNormalized.bottom.coerceIn(0f, 1f)
            )
        )
        lastRoiUpdateTimeMs.set(System.currentTimeMillis())
    }

    /**
     * 获取缓存的 MediaPipe 106 点数据
     * @return 如果缓存有效则返回 106 点 FloatArray，否则返回 null
     */
    fun getCachedLandmarks106(): FloatArray? {
        val now = System.currentTimeMillis()
        return if (now - lastLandmarksUpdateTimeMs.get() <= LANDMARK_CACHE_VALIDITY_MS) {
            cachedLandmarks106.get()?.copyOf()
        } else {
            null
        }
    }

    /**
     * 获取缓存的归一化 ROI。
     */
    fun getCachedRoiNormalized(): RectF? {
        val now = System.currentTimeMillis()
        return if (now - lastRoiUpdateTimeMs.get() <= ROI_CACHE_VALIDITY_MS) {
            cachedRoiNormalized.get()?.let { RectF(it) }
        } else {
            null
        }
    }

    /**
     * 清除缓存
     */
    fun clear() {
        cachedLandmarks106.set(null)
        cachedRoiNormalized.set(null)
        lastLandmarksUpdateTimeMs.set(0L)
        lastRoiUpdateTimeMs.set(0L)
    }

    /**
     * 检查缓存是否有效
     */
    fun isValid(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastLandmarksUpdateTimeMs.get() <= LANDMARK_CACHE_VALIDITY_MS &&
            cachedLandmarks106.get() != null
    }

    fun isRoiValid(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastRoiUpdateTimeMs.get() <= ROI_CACHE_VALIDITY_MS &&
            cachedRoiNormalized.get() != null
    }
}
