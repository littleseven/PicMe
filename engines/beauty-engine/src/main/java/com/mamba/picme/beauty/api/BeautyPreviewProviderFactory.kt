package com.mamba.picme.beauty.api

import android.content.Context

/**
 * BeautyPreviewProvider 工厂接口
 *
 * 职责：
 * 1. 集中管理 BeautyPreviewProvider 的创建
 * 2. 支持按策略返回不同实现
 * 3. 便于单元测试时替换为 Mock
 *
 * App 层禁止直接实例化 `GlBeautyPreviewProvider`，应通过此工厂获取接口实例。
 *
 * @since Phase 3（库化）
 */
interface BeautyPreviewProviderFactory {

    /**
     * 创建美颜预览提供者实例
     *
     * @param context 应用上下文
     * @return BeautyPreviewProvider 接口实例
     */
    fun create(context: Context): BeautyPreviewProvider
}
