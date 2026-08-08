package com.mamba.picme.agent.core.js

/**
 * JS 回调的引擎无关抽象。
 *
 * 异步 handler 完成后由 JsBridge 调用：成功时 `error=null, result=<值>`；
 * 失败时 `error=<JsValue.Str>, result=null`。约定与 Node 风格 `(err, result)` 一致。
 */
fun interface JsCallback {
    fun invoke(error: JsValue?, result: JsValue?)
}
