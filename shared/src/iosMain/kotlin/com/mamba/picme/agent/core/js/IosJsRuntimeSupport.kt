package com.mamba.picme.agent.core.js

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * iOS JS 运行时工厂：用后台 [Dispatchers.Default] 构造协程 scope，装配 [JsRuntime]。
 *
 * **dispatcher 选择（死锁规避）**：`JsCoreEngine.__bridgeCallAsync` 在 evaluateScript 线程
 * 同步 `DispatchSemaphore.wait()` 等待 handler 完成；handler 由 [JsBridge.dispatchAsync]
 * 在本 scope 内 `launch` 执行。两者必须跑在不同线程——evaluateScript 线程被信号量硬阻塞、
 * 无法执行被 launch 的 handler，故 Default 线程池（≥2 线程）必派发到另一 worker，无死锁。
 * （切勿用 Main / 与 evaluateScript 调用方相同的单线程 dispatcher。）
 *
 * Swift 调用：`IosJsRuntimeSupportKt.createIosJsRuntime(engine: JsCoreEngine, source: "chat")`。
 *
 * @param engine JS 引擎（iOS 侧为 Swift `JsCoreEngine`，经 SharedKit 暴露为 `JsEngine`）。
 * @param source 运行来源标签（chat / debug），落入 JsRunEvent.source。
 */
fun createIosJsRuntime(engine: JsEngine, source: String = "chat"): JsRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    return JsRuntime(engine = engine, scope = scope, source = source)
}
