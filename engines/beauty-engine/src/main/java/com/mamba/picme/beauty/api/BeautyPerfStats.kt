package com.mamba.picme.beauty.api

/**
 * GL 美颜预览性能统计数据（beauty-engine 公开 API）
 *
 * 通过 [BeautyPreviewProvider.getPerfStats] 获取，
 * 供调试浮层实时展示 FPS、处理耗时、预览延迟、CPU 占用、空帧计数，以及最近一次
 * `PoLang:BeautyRenderer` 渲染失败的分类与原因。
 *
 * @param fps 渲染帧率（帧/秒）
 * @param processingMs 单帧美颜处理耗时（毫秒）
 * @param delayMs 预览延迟（毫秒）
 * @param cpuUsage CPU 占用率（0~100）
 * @param nullFrames 空帧（未渲染帧）计数
 * @param errorCategory 最近一次渲染异常分类；无异常时为空字符串
 * @param errorReason 最近一次渲染异常摘要；无异常时为空字符串
 */
data class BeautyPerfStats(
    val fps: Float = 0f,
    val processingMs: Int = 0,
    val delayMs: Int = 0,
    val cpuUsage: Float = 0f,
    val nullFrames: Int = 0,
    val errorCategory: String = "",
    val errorReason: String = "",
    val detectionLatencyMs: Long = 0L,
    val syncStatus: String = "",
    val predictedOffsetPx: Float = 0f
) {
    companion object {
        val EMPTY = BeautyPerfStats()
    }
}

