package com.mamba.picme.beauty.api

import com.mamba.picme.beauty.log.BeautyLogProxy

/**
 * Beauty Engine 模块统一日志入口
 *
 * 外观与 app 模块的 [com.mamba.picme.core.common.Logger] 完全一致，
 * 便于代码在不同模块间移动时无需修改 import 和调用方式。
 *
 * 底层通过 [com.mamba.picme.beauty.log.BeautyLogProxy] 委托到 app 模块的 Logger（支持模块开关控制），
 * 未绑定或反射失败时 fallback 到 android.util.Log。
 *
 * 使用方式：
 * ```kotlin
 * private const val TAG = "BeautyRenderer"
 * Logger.d(TAG, "Preview started")
 * Logger.i(TAG, "Shader compiled")
 * Logger.w(TAG, "Frame dropped")
 * Logger.e(TAG, "GL error", exception)
 * ```
 */
object Logger {

    /**
     * 限流 Debug 日志：同一 key 最多每 [intervalMs] 毫秒打印一次（默认 1000ms）。
     * 适用于渲染循环、帧分析等高频场景，避免日志洪水。
     *
     * @param tag 模块标签
     * @param key 限流 key，建议用 "tag:消息类型" 格式
     * @param message 日志内容
     * @param intervalMs 最短打印间隔，默认 1000ms
     */
    fun dThrottled(tag: String, key: String, message: String, intervalMs: Long = 1_000L) {
        if (!isLogEnabled(tag)) return
        BeautyLogProxy.dThrottled(tag, key, message, intervalMs)
    }

    /**
     * Debug 级别日志
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     */
    fun d(tag: String, message: String) {
        if (!isLogEnabled(tag)) return
        BeautyLogProxy.d(tag, message)
    }

    /**
     * Info 级别日志
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     */
    fun i(tag: String, message: String) {
        if (!isLogEnabled(tag)) return
        BeautyLogProxy.i(tag, message)
    }

    /**
     * Warning 级别日志
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     */
    fun w(tag: String, message: String) {
        if (!isLogEnabled(tag)) return
        BeautyLogProxy.w(tag, message)
    }

    /**
     * Warning 级别日志（带异常）
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     * @param throwable 异常对象
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        if (!isLogEnabled(tag)) return
        BeautyLogProxy.w(tag, message, throwable)
    }

    /**
     * Error 级别日志
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     * @param throwable 异常对象（可选）
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!isLogEnabled(tag)) return
        if (throwable != null) {
            BeautyLogProxy.e(tag, message, throwable)
        } else {
            BeautyLogProxy.e(tag, message)
        }
    }

    /**
     * 检查指定标签的日志是否允许输出
     *
     * @param tag 模块标签
     * @return 如果模块开关开启或无法判断则返回 true
     */
    fun isLogEnabled(tag: String): Boolean {
        return BeautyLogProxy.isLogEnabled(tag)
    }
}
