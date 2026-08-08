package com.mamba.picme.agent.core.capability

import kotlin.concurrent.Volatile

/**
 * 人脸检测状态提供者接口
 *
 * 用于解耦 ExecutionEngine 对具体人脸检测实现的依赖。
 * app 模块提供实际实现。
 */
interface FaceDetectionProvider {
    /**
     * 当前是否检测到有效人脸
     */
    fun isFaceValid(): Boolean

    companion object {
        @Volatile
        private var instance: FaceDetectionProvider? = null

        fun set(provider: FaceDetectionProvider) {
            instance = provider
        }

        fun get(): FaceDetectionProvider? = instance
    }
}
