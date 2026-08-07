package com.mamba.picme.beauty.render

import com.mamba.picme.beauty.api.Logger

/**
 * Framebuffer 对象池
 *
 * 管理多 Pass 渲染中的离屏 FBO 资源，支持乒乓切换。
 * 避免每帧创建/销毁 FBO 的开销。
 */
class FramebufferPool {
    companion object {
        private const val TAG = "FramebufferPool"
    }

    private val pool = mutableMapOf<String, Framebuffer>()
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0

    /**
     * 获取或创建指定名称的 FBO
     * @param name FBO 名称（如 "ping", "pong"）
     * @param width 宽度
     * @param height 高度
     */
    fun acquire(name: String, width: Int, height: Int): Framebuffer {
        val existing = pool[name]
        if (existing != null && existing.isInitialized) {
            if (existing.getWidth() == width && existing.getHeight() == height) {
                return existing
            }
            // 尺寸变化，释放旧的
            existing.release()
        }

        val fbo = Framebuffer(width, height)
        if (fbo.initialize()) {
            pool[name] = fbo
            Logger.d(TAG, "Created FBO '$name': ${width}x${height}")
        } else {
            Logger.e(TAG, "Failed to create FBO '$name'")
        }
        return fbo
    }

    /**
     * 获取已存在的 FBO（不创建新的）
     */
    fun get(name: String): Framebuffer? = pool[name]

    /**
     * 释放所有 FBO
     */
    fun releaseAll() {
        pool.values.forEach { it.release() }
        pool.clear()
        Logger.d(TAG, "All FBOs released")
    }

    /**
     * 检查是否有指定名称的 FBO
     */
    fun has(name: String): Boolean = pool.containsKey(name) && pool[name]?.isInitialized == true
}
