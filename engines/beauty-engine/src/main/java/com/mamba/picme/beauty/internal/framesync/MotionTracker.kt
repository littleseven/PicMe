package com.mamba.picme.beauty.internal.framesync

import android.os.SystemClock
import com.mamba.picme.beauty.api.FrameId
import kotlin.math.abs

/**
 * 轻量级运动跟踪器
 * 基于速度外推的预测算法（Phase 1），后续可替换为 Kalman Filter
 */
class MotionTracker {
    data class FrameState(
        val frameId: FrameId,
        val landmarks106: FloatArray,
        val timestampMs: Long
    )

    private val history = ArrayDeque<FrameState>(3)
    private val historyLock = Any()

    companion object {
        private const val VERTEX_COUNT = 106
        private const val FLOAT_COUNT = VERTEX_COUNT * 2
    }

    // [GC 优化] 双缓冲预测输出，消除每帧 clone() 分配
    // 渲染线程持有 bufferA 引用，下一帧预测写入 bufferB，交替使用
    private val predictedBufferA = FloatArray(FLOAT_COUNT)
    private val predictedBufferB = FloatArray(FLOAT_COUNT)
    private var useBufferA = true

    fun update(frameId: FrameId, landmarks106: FloatArray) {
        synchronized(historyLock) {
            // [GC 优化] 调用方已 clone（CameraFrameAnalyzer 传入前已复制），此处直接引用避免重复分配
            history.addLast(
                FrameState(
                    frameId = frameId,
                    landmarks106 = landmarks106,
                    timestampMs = SystemClock.elapsedRealtime()
                )
            )
            if (history.size > 3) {
                history.removeFirst()
            }
        }
    }

    /**
     * 预测目标帧的人脸关键点位置
     * @return 预测后的 FloatArray（双缓冲，非 clone），如果无法预测则返回历史结果的 clone
     */
    fun predict(fromFrameId: FrameId, toFrameId: FrameId, maxRatio: Float): FloatArray {
        synchronized(historyLock) {
            if (history.size < 2) {
                return history.lastOrNull()?.landmarks106?.clone() ?: FloatArray(FLOAT_COUNT)
            }

            val latest = history.last()
            val previous = history[history.size - 2]

            val frameDiff = (latest.frameId.value - previous.frameId.value).coerceAtLeast(1L)
            val targetDiff = (toFrameId.value - fromFrameId.value).coerceAtLeast(0L)

            // 轮流使用 bufferA/bufferB，避免每帧 clone（CR-P1-1）
            val buffer = if (useBufferA) predictedBufferA else predictedBufferB
            useBufferA = !useBufferA

            for (i in latest.landmarks106.indices) {
                val velocity = (latest.landmarks106[i] - previous.landmarks106[i]) / frameDiff
                val rawPredicted = latest.landmarks106[i] + velocity * targetDiff

                val actualDiff = rawPredicted - latest.landmarks106[i]
                val maxDiff = abs(velocity * frameDiff * maxRatio * 2.0f)
                val clampedDiff = actualDiff.coerceIn(-maxDiff, maxDiff)

                buffer[i] = latest.landmarks106[i] + clampedDiff
            }

            return buffer
        }
    }

    fun clear() {
        synchronized(historyLock) {
            history.clear()
        }
    }
}
