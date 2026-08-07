package com.mamba.picme.di

/**
 * RD 美颜引擎运行时状态
 *
 * 职责：记录 R Plan 运行时回退原因，供 UI 层消费并展示提示。
 * 设计为单次消费（One-Shot），避免同一原因重复展示。
 *
 * 从 AppContainer 中独立出来，便于：
 * 1. 单独进行单元测试
 * 2. Phase 2+ 中迁移到 beauty-core 模块后仍可独立使用
 */
object BeautyEngineRuntimeState {
    @Volatile
    private var fallbackReason: String? = null

    fun markGlEngineFallback(reason: String) {
        fallbackReason = reason
    }

    fun consumeGlEngineFallbackReason(): String? {
        val reason = fallbackReason
        fallbackReason = null
        return reason
    }
}

