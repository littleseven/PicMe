package com.mamba.picme.agent.core.js

/**
 * 原生能力 handler 契约。JS 通过 `bridge.call(name, args)` 触发。
 *
 * - [Sync]：同步返回结果。
 * - [Async]：挂起完成后通过 [JsCallback] 回调（用于耗时/协程操作）。
 *
 * 引擎无关：handler 只处理 [JsValue]，不感知引擎特定类型。
 * 用 [syncHandler] / [asyncHandler] 工厂构造实例。
 */
sealed interface NativeHandler {
    val name: String

    /** 同步 handler：直接返回结果。 */
    interface Sync : NativeHandler {
        fun invoke(args: JsValue): JsValue
    }

    /** 异步 handler：挂起执行，完成后由 JsBridge 回调 JS。 */
    interface Async : NativeHandler {
        suspend fun invoke(args: JsValue): JsValue
    }
}

/** 创建同步 handler。 */
fun syncHandler(name: String, block: (JsValue) -> JsValue): NativeHandler.Sync =
    object : NativeHandler.Sync {
        override val name = name
        override fun invoke(args: JsValue): JsValue = block(args)
    }

/** 创建异步 handler。 */
fun asyncHandler(name: String, block: suspend (JsValue) -> JsValue): NativeHandler.Async =
    object : NativeHandler.Async {
        override val name = name
        override suspend fun invoke(args: JsValue): JsValue = block(args)
    }
