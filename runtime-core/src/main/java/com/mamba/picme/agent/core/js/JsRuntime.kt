package com.mamba.picme.agent.core.js

import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.CoroutineScope

/**
 * JS 运行时门面：装配引擎（Rhino）+ bridge + 内置 handler，提供 [eval]/[callFunction]。
 *
 * 典型用法：
 * ```
 * val rt = JsRuntime(scope = appScope, onLog = { msg -> Log.i("PoLang:Js", msg) })
 * rt.eval("bridge.call('math.add', [1, 2])")
 * rt.close()
 * ```
 *
 * @param scope 异步 handler 协程作用域（建议绑定 App / 页面生命周期）。
 * @param onLog `console.log` 输出（App 端注入 android Log）。
 */
class JsRuntime(
    scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
    evalTimeoutMs: Long = RhinoJsEngine.DEFAULT_EVAL_TIMEOUT_MS,
) : JsEngine, AutoCloseable {

    private val tag = "JsRuntime"
    private val engine: RhinoJsEngine = RhinoJsEngine(scope, onLog, evalTimeoutMs)
    private val bridge: JsBridge = JsBridge(scope)

    init {
        BuiltInHandlers.registerAll(bridge)
        engine.installBridge(bridge)
        Logger.i(tag, "JsRuntime ready (engine=rhino, handlers=${bridge.names()})")
    }

    /** 追加自定义 handler。 */
    fun register(handler: NativeHandler) {
        bridge.register(handler)
    }

    /** 已注册 handler 名（含内置 + 自定义）。 */
    fun handlerNames(): List<String> = bridge.names()

    override fun eval(script: String): JsValue = engine.eval(script)

    override fun callFunction(name: String, vararg args: JsValue): JsValue =
        engine.callFunction(name, *args)

    override fun installBridge(bridge: JsBridge) {
        engine.installBridge(bridge)
    }

    override fun close() {
        engine.close()
        Logger.i(tag, "JsRuntime closed")
    }
}
