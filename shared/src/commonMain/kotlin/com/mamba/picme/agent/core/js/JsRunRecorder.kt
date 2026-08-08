package com.mamba.picme.agent.core.js

/**
 * JS 沙盒运行事件上报口（Agent 终端运行感知层）。
 *
 * 由 :androidApp 在 Application 启动时注入到 [JsRuntime.recorder]（镜像
 * `RemoteModelFactory.recorder` / `CommandExecutor.recorder` 既定模式）。
 *
 * 实现要求：**fire-and-forget**——异步落库、异常自吞，绝不阻塞或影响 JS 执行链路
 * （[JsRuntime] 侧另有 runCatching 双保险）。
 */
interface JsRunRecorder {
    fun record(event: JsRunEvent)
}
