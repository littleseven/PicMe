package com.mamba.picme.data

/**
 * Swift → Kotlin 的端侧 JS 脚本执行桥协议。
 *
 * Swift 实现（iosApp `RunScriptBridge`/NSObject）持有一个 commonMain `JsRuntime`
 * （引擎 = `JsCoreEngine`：JavaScriptCore 实现 `JsEngine`），执行用户脚本
 * （`evalAsync`，可 `await bridge.callAsync('gallery.summary', {})` 等取数），
 * 把结果 JSON 经 [onResult] 回传给能力层，作为远程 LLM 的 observation（做文字总结）。
 *
 * SharedBridge 铁律（同 [IosChartBridge] / [IosChatSearchBridge]）：
 * - Swift 实现侧绝不抛异常跨边界（逃逸会 signal 6 / SIGABRT）；脚本异常时回传错误文案。
 * - [onResult] **必须**被调用（成功或失败），否则 Kotlin 侧 suspendCancellableCoroutine
 *   永久挂起；失败时回传可读错误信息（不泄露内部栈/路径）。
 *
 * 与 Android `ChatRunScriptCapability.Delegate.onRunScript` 的差异：iOS 能力在组合根
 * 构造（早于 ChatViewModel），不走 Delegate 回调，JsRuntime 的生命周期由 Swift 桥持有。
 *
 * [PRIVACY]：脚本仅触发端侧只读 handler（gallery.summary/tags、tag.scan_status 等盘点），
 * 不上传任何媒体文件；结果只含计数/标签等聚合信息。
 */
interface IosRunScriptBridge {

    /**
     * 端侧执行一段 JS 脚本。
     *
     * @param code JS 源码（async 语义，可顶层 `await`/`return`；用
     *   `await bridge.callAsync(name, args)` 取端侧数据）。
     * @param onResult 结果回调（回传 LLM）：成功为结果 JSON 文本；
     *   脚本异常/超时时回传可读错误信息（如「脚本执行失败：<reason>」）。
     */
    fun runScript(code: String, onResult: (result: String) -> Unit)
}
