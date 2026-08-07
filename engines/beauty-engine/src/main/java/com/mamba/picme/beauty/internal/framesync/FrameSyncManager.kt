package com.mamba.picme.beauty.internal.framesync

import android.os.SystemClock
import com.mamba.picme.beauty.api.Logger
import com.mamba.picme.beauty.api.FrameId
import com.mamba.picme.beauty.api.FrameSyncConfig
import com.mamba.picme.beauty.api.FrameSyncResult
import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * 帧同步管理器
 * - 线程安全：ResultStore 使用 ConcurrentHashMap + ConcurrentLinkedQueue
 * - 轻量级：查询操作 O(1)，无锁读（使用 volatile + copy-on-write 思路）
 */
class FrameSyncManager private constructor(
    initialConfig: FrameSyncConfig = FrameSyncConfig.DEFAULT
) {
    companion object {
        private const val TAG = "FrameSync"

        @Volatile
        private var instance: FrameSyncManager? = null

        fun getInstance(config: FrameSyncConfig = FrameSyncConfig.DEFAULT): FrameSyncManager {
            return instance ?: synchronized(this) {
                instance ?: FrameSyncManager(config).also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance?.clear()
                instance = null
            }
        }
    }

    data class DetectionResult(
        val frameId: FrameId,
        val landmarks106: FloatArray,
        val detectionSource: FaceDetectionSource,
        val detectionLatencyMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DetectionResult
            if (frameId != other.frameId) return false
            if (!landmarks106.contentEquals(other.landmarks106)) return false
            if (detectionSource != other.detectionSource) return false
            if (detectionLatencyMs != other.detectionLatencyMs) return false
            return true
        }

        override fun hashCode(): Int {
            var result = frameId.hashCode()
            result = 31 * result + landmarks106.contentHashCode()
            result = 31 * result + detectionSource.hashCode()
            result = 31 * result + detectionLatencyMs.hashCode()
            return result
        }
    }

    @Volatile
    private var config: FrameSyncConfig = initialConfig

    private val resultStore = ConcurrentHashMap<FrameId, DetectionResult>()
    private val frameHistory = ConcurrentLinkedQueue<FrameId>()
    private val motionTracker = MotionTracker()

    @Volatile
    private var lastQueryResult: FrameSyncResult = FrameSyncResult.MISSING

    // [帧同步] 记录每帧的绑定时间戳，用于计算检测延迟
    private val frameBindTimestamps = ConcurrentHashMap<FrameId, Long>()

    /**
     * 绑定当前渲染帧的 FrameId（由渲染线程调用）
     * 记录帧绑定时间戳，用于后续计算检测-渲染延迟。
     */
    fun bindFrameId(frameId: FrameId, timestampNs: Long) {
        // [GC 优化] 预检查：达到上限时先移除最旧的，再插入新值
        // 避免 put 后再 remove 导致的临时 size 膨胀和二次 hash 操作
        if (frameBindTimestamps.size >= 120) {
            val oldest = frameBindTimestamps.keys.minByOrNull { it.value } ?: return
            frameBindTimestamps.remove(oldest)
        }
        frameBindTimestamps[frameId] = SystemClock.elapsedRealtime()
    }

    /**
     * 计算从帧绑定到检测结果存储的延迟（毫秒）
     */
    fun calculateDetectionLatency(frameId: FrameId): Long {
        val bindTime = frameBindTimestamps[frameId] ?: return 0L
        return SystemClock.elapsedRealtime() - bindTime
    }

    /**
     * 存储检测结果（由检测线程调用）
     */
    fun storeResult(result: DetectionResult) {
        resultStore[result.frameId] = result
        frameHistory.offer(result.frameId)
        motionTracker.update(result.frameId, result.landmarks106)
        trimOldResults()
    }

    /**
     * 查询帧同步结果（由渲染线程调用，每帧一次）
     */
    fun query(currentFrameId: FrameId): FrameSyncResult {
        if (config.syncMode == FrameSyncConfig.SyncMode.OFF) {
            return FrameSyncResult.MISSING
        }

        // 1. 精确匹配
        resultStore[currentFrameId]?.let { result ->
            lastQueryResult = FrameSyncResult(
                frameId = currentFrameId,
                landmarks106 = result.landmarks106,
                detectionSource = result.detectionSource,
                syncStatus = FrameSyncResult.SyncStatus.EXACT_MATCH,
                detectionLatencyMs = result.detectionLatencyMs
            )
            return lastQueryResult
        }

        // 2. 查找最近历史结果
        val historicalResult = findNearestHistoricalResult(currentFrameId)
            ?: return FrameSyncResult.MISSING.also { lastQueryResult = it }

        val frameDiff = currentFrameId.value - historicalResult.frameId.value

        // 3. 严格模式：超过阈值直接隐藏
        if (config.syncMode == FrameSyncConfig.SyncMode.STRICT &&
            frameDiff > config.missingThresholdFrames
        ) {
            lastQueryResult = FrameSyncResult(
                frameId = historicalResult.frameId,
                syncStatus = FrameSyncResult.SyncStatus.MISSING,
                detectionLatencyMs = historicalResult.detectionLatencyMs
            )
            return lastQueryResult
        }

        // 4. 平滑模式：预测补偿（默认启用）
        if (config.syncMode == FrameSyncConfig.SyncMode.SMOOTH) {
            val predicted = motionTracker.predict(
                fromFrameId = historicalResult.frameId,
                toFrameId = currentFrameId,
                maxRatio = config.predictionMaxRatio
            )
            lastQueryResult = FrameSyncResult(
                frameId = historicalResult.frameId,
                landmarks106 = predicted,
                detectionSource = historicalResult.detectionSource,
                syncStatus = FrameSyncResult.SyncStatus.PREDICTED,
                detectionLatencyMs = historicalResult.detectionLatencyMs,
                predictedOffsetPx = calculateOffset(predicted, historicalResult.landmarks106)
            )
            return lastQueryResult
        }

        // 4b. 严格模式且未超阈值：也使用预测补偿（避免妆容甩飞）
        if (frameDiff > 1) {
            val predicted = motionTracker.predict(
                fromFrameId = historicalResult.frameId,
                toFrameId = currentFrameId,
                maxRatio = config.predictionMaxRatio
            )
            lastQueryResult = FrameSyncResult(
                frameId = historicalResult.frameId,
                landmarks106 = predicted,
                detectionSource = historicalResult.detectionSource,
                syncStatus = FrameSyncResult.SyncStatus.PREDICTED,
                detectionLatencyMs = historicalResult.detectionLatencyMs,
                predictedOffsetPx = calculateOffset(predicted, historicalResult.landmarks106)
            )
            return lastQueryResult
        }

        // 5. 严格模式且未超阈值：使用历史结果（无预测）
        lastQueryResult = FrameSyncResult(
            frameId = historicalResult.frameId,
            landmarks106 = historicalResult.landmarks106,
            detectionSource = historicalResult.detectionSource,
            syncStatus = FrameSyncResult.SyncStatus.HISTORICAL_FALLBACK,
            detectionLatencyMs = historicalResult.detectionLatencyMs
        )
        return lastQueryResult
    }

    /**
     * 获取当前存储的检测结果数量（用于调试）
     */
    fun getStoredResultCount(): Int = resultStore.size

    /**
     * 获取存储的所有 FrameId（用于调试）
     */
    fun getStoredFrameIds(): List<Long> = frameHistory.map { it.value }.toList()

    /**
     * 获取最近一次 query 的结果（用于调试浮层）
     */
    fun getLastQueryResult(): FrameSyncResult = lastQueryResult

    private fun findNearestHistoricalResult(currentFrameId: FrameId): DetectionResult? {
        // [GC 优化] 使用 iterator() 代替 toTypedArray()，避免每帧数组分配
        // ConcurrentLinkedQueue.iterator() 是弱一致性且线程安全的。
        // 由于 FrameId 单调递增（front→back: 最旧→最新），正向遍历完整队列
        // 并追踪最近的 <= currentFrameId 等价于原反向遍历语义。
        if (frameHistory.isEmpty()) return null

        var nearestId: FrameId? = null
        val iterator = frameHistory.iterator()
        while (iterator.hasNext()) {
            val frameId = iterator.next()
            if (frameId <= currentFrameId && resultStore.containsKey(frameId)) {
                if (nearestId == null || frameId.value > nearestId.value) {
                    nearestId = frameId
                }
            }
        }
        return nearestId?.let { resultStore[it] }
    }

    private fun trimOldResults() {
        while (frameHistory.size > config.maxStoredResults) {
            val oldId = frameHistory.poll() ?: break
            resultStore.remove(oldId)
        }
    }

    private fun calculateOffset(predicted: FloatArray, historical: FloatArray): Float {
        if (predicted.size < 2 || historical.size < 2) return 0f
        // 取轮廓+中心关键点的平均曼哈顿位移，更能反映全脸偏移
        val keyIndices = listOf(0, 16, 32, 43, 77, 104, 105)
        var totalOffset = 0f
        var validCount = 0
        for (idx in keyIndices) {
            val i = idx * 2
            if (i + 1 < predicted.size) {
                val dx = predicted[i] - historical[i]
                val dy = predicted[i + 1] - historical[i + 1]
                totalOffset += abs(dx) + abs(dy)
                validCount++
            }
        }
        return if (validCount > 0) totalOffset / validCount else 0f
    }

    fun clear() {
        resultStore.clear()
        frameHistory.clear()
        motionTracker.clear()
        frameBindTimestamps.clear()
        lastQueryResult = FrameSyncResult.MISSING
    }

    fun updateConfig(newConfig: FrameSyncConfig) {
        config = newConfig
        Logger.d(TAG, "Config updated: $newConfig")
    }
}
