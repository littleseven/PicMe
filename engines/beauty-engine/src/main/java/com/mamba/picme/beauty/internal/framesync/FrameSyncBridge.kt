package com.mamba.picme.beauty.internal.framesync

import com.mamba.picme.beauty.api.FrameId
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 帧同步桥接器
 * 线程安全地共享渲染线程的最新 FrameId 给分析线程。
 *
 * 解决 CR-P0-1：FrameId 来源不一致问题。
 * - 渲染线程（CameraPreviewRenderer）在每帧渲染时设置 latestFrameId
 * - 分析线程（CameraFrameAnalyzer）读取该 FrameId，将检测结果绑定到对应帧
 * - 确保检测-渲染链路使用同一套 FrameId 序列
 *
 * [CR-P0-3 修复] 新增 timestamp -> FrameId 映射，解决检测线程 FrameId 绑定错误。
 * CameraX ImageProxy 与 SurfaceTexture 共享同一时间基准（来自相机硬件）。
 * 通过时间戳精确关联检测输入帧与渲染帧，避免检测结果绑定到错误的 FrameId。
 */
object FrameSyncBridge {
    private val latestFrameIdRef = AtomicReference(FrameId.INVALID)
    private val latestTimestampNsRef = AtomicLong(0L)

    /**
     * [CR-P0-3] 时间戳到 FrameId 的映射表。
     * 渲染线程每帧记录 SurfaceTexture.timestamp -> FrameId。
     * 检测线程根据 ImageProxy.imageInfo.timestamp 查询对应的 FrameId。
     * 由于两路流来自同一相机硬件，时间戳在同一基准上。
     */
    // [GC 优化] 使用 ConcurrentSkipListMap 代替 ConcurrentHashMap，
    // getFrameIdByTimestamp 的最近邻查找复杂度从 O(n) 降为 O(log n)，
    // 消除每帧遍历 120 条 Map.Entry 产生的 Entry/Boxing 对象分配。
    private val timestampToFrameIdMap = ConcurrentSkipListMap<Long, FrameId>()
    private const val MAX_TIMESTAMP_MAP_SIZE = 120

    /**
     * 设置当前渲染帧的 FrameId（由渲染线程调用）
     */
    fun setLatestFrameId(frameId: FrameId, timestampNs: Long = 0L) {
        latestFrameIdRef.set(frameId)
        latestTimestampNsRef.set(timestampNs)

        // [CR-P0-3] 记录时间戳到 FrameId 的映射，供检测线程精确查询
        if (timestampNs > 0) {
            timestampToFrameIdMap[timestampNs] = frameId
            trimTimestampMap()
        }
    }

    /**
     * 获取最新渲染帧的 FrameId（由分析线程调用）
     */
    fun getLatestFrameId(): FrameId = latestFrameIdRef.get()

    /**
     * 获取最新渲染帧的时间戳
     */
    fun getLatestTimestampNs(): Long = latestTimestampNsRef.get()

    /**
     * [CR-P0-3] 根据时间戳查询对应的 FrameId。
     * 由于 CameraX 两路流的时间戳可能有微小偏差，使用最近邻匹配。
     *
     * @param timestampNs ImageProxy.imageInfo.timestamp（纳秒）
     * @param toleranceNs 容差范围（默认 5ms = 5_000_000ns）
     * @return 匹配的 FrameId，若未找到则返回 INVALID
     */
    fun getFrameIdByTimestamp(timestampNs: Long, toleranceNs: Long = 5_000_000L): FrameId {
        if (timestampNs <= 0) return FrameId.INVALID

        // 精确匹配
        timestampToFrameIdMap[timestampNs]?.let { return it }

        // [GC 优化] ConcurrentSkipListMap.floorEntry/ceilingEntry 实现 O(log n) 最近邻查找，
        // 替代原 O(n) 全表遍历，消除每帧 120 次迭代的 Entry/Boxing 对象分配。
        val floorEntry = timestampToFrameIdMap.floorEntry(timestampNs)
        val ceilingEntry = timestampToFrameIdMap.ceilingEntry(timestampNs)

        val floorDiff = floorEntry?.let { kotlin.math.abs(it.key - timestampNs) }
            ?: Long.MAX_VALUE
        val ceilingDiff = ceilingEntry?.let { kotlin.math.abs(it.key - timestampNs) }
            ?: Long.MAX_VALUE

        return when {
            floorDiff <= toleranceNs && floorDiff <= ceilingDiff -> floorEntry!!.value
            ceilingDiff <= toleranceNs -> ceilingEntry!!.value
            else -> FrameId.INVALID
        }
    }

    private fun trimTimestampMap() {
        while (timestampToFrameIdMap.size > MAX_TIMESTAMP_MAP_SIZE) {
            // ConcurrentSkipListMap.firstKey() 是 O(1) 操作
            val oldestKey = timestampToFrameIdMap.firstKey()
            timestampToFrameIdMap.remove(oldestKey)
        }
    }

    /**
     * 重置状态（相机释放时调用）
     */
    fun reset() {
        latestFrameIdRef.set(FrameId.INVALID)
        latestTimestampNsRef.set(0L)
        timestampToFrameIdMap.clear()
    }
}
