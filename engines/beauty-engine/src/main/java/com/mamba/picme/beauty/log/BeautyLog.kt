package com.mamba.picme.beauty.log

/**
 * Beauty Engine 日志接口
 *
 * 设计原则：
 * - 不直接依赖 app 模块的 Logger，避免反向依赖
 * - 通过反射代理模式在运行时绑定到 app 的 Logger
 * - 未绑定前 fallback 到 android.util.Log
 * - 所有 TAG 由调用方传入，禁止硬编码
 */
interface BeautyLog {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable)
    fun e(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable)
}
