package com.mamba.picme.beauty.api

import android.view.Surface

/**
 * 美颜预览提供者接口（beauty-engine 模块公开 API）
 *
 * 设计原则：
 * 1. 单一职责：只负责预览表面的提供和滤镜更新
 * 2. 依赖倒置：上层模块依赖抽象接口，而非具体实现
 * 3. 开闭原则：对扩展开放（新增实现类），对修改关闭（不改动调用方）
 *
 * 注意：参数使用本模块专用 [BeautyParams]，避免对 app domain 层的反向依赖。
 *
 * @since Phase 3（库化）
 */
interface BeautyPreviewProvider {

    /**
     * 初始化引擎（离屏 EGL + Shader 编译）
     */
    fun initialize()

    /**
     * 创建预览 Surface，CameraX 将帧输出到此 Surface
     */
    fun createPreviewSurface(): Surface

    /**
     * 更新美颜滤镜参数
     */
    fun updateFilters(params: BeautyParams)

    /**
     * 释放资源，在 ViewModel 销毁时调用
     */
    fun release()

    /**
     * 是否已准备好
     */
    fun isReady(): Boolean

    /**
     * 获取实时性能统计数据（用于调试浮层展示）
     * 若引擎不支持或尚未初始化，返回 [BeautyPerfStats.EMPTY]
     */
    fun getPerfStats(): BeautyPerfStats = BeautyPerfStats.EMPTY
}

