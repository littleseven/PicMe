package com.mamba.picme.beauty.internal.facedetect.adapter

import com.mamba.picme.beauty.api.Logger
import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource

/**
 * 人脸关键点适配器注册表
 *
 * 管理所有检测器到统一 106 点标准的适配器，支持运行时扩展。
 * 各适配器按 [FaceDetectionSource] 注册，消费层通过检测源获取对应适配器。
 *
 * 线程安全：当前为单线程使用（主线程初始化 + 检测线程读取），
 * 若未来需要多线程并发访问，需加读写锁。
 */
object FaceLandmarkAdapterRegistry {

    private const val TAG = "LandmarkAdapter"

    private val adapters = mutableMapOf<FaceDetectionSource, FaceLandmarkAdapter>()

    /**
     * 注册适配器
     *
     * @param source 检测源标识
     * @param adapter 适配器实例
     */
    fun register(source: FaceDetectionSource, adapter: FaceLandmarkAdapter) {
        val existing = adapters.put(source, adapter)
        if (existing != null) {
            Logger.w(TAG, "Adapter for $source was overwritten")
        } else {
            Logger.d(TAG, "Adapter registered: ${adapter::class.simpleName} for $source")
        }
    }

    /**
     * 获取指定检测源的适配器
     *
     * @param source 检测源标识
     * @return 适配器实例，未注册时返回 null
     */
    fun getAdapter(source: FaceDetectionSource): FaceLandmarkAdapter? {
        return adapters[source]
    }

    /**
     * 检查指定检测源是否已注册适配器
     */
    fun hasAdapter(source: FaceDetectionSource): Boolean {
        return adapters.containsKey(source)
    }

    /**
     * 获取已注册的所有检测源
     */
    fun getRegisteredSources(): Set<FaceDetectionSource> {
        return adapters.keys.toSet()
    }

    /**
     * 清空所有注册（主要用于测试）
     */
    fun clear() {
        adapters.clear()
        Logger.d(TAG, "All adapters cleared")
    }

    /**
     * 初始化默认适配器集合
     *
     * 应在 Application 初始化或 DI 模块中调用一次。
     */
    fun initDefaults() {
        if (adapters.isNotEmpty()) {
            Logger.d(TAG, "Default adapters already initialized, skipping")
            return
        }

        register(FaceDetectionSource.MEDIAPIPE, MediaPipe468Adapter())
        register(FaceDetectionSource.MNN, MnnLandmarkAdapter())  // [性能优化] MNN GPU 检测器

        Logger.i(TAG, "Default adapters initialized: ${adapters.size} registered")
    }
}
