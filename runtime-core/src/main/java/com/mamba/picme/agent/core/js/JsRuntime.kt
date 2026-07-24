package com.mamba.picme.agent.core.js

import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.CoroutineScope
import java.io.Closeable

/**
 * JS 运行时门面：装配引擎 + bridge + 内置 handler，提供 [eval]/[callFunction]。
 *
 * 引擎由调用方注入（[engine]）：app 层注入 QuickJsEngine（生产），测试可注入 RhinoJsEngine（纯 JVM 可单测）。
 * 这解决了「JsRuntime hardcode 某个引擎实现」的耦合——bridge/handler/JsValue 全引擎无关，
 * 换引擎只换注入的实现。
 *
 * 典型用法：
 * ```
 * val rt = JsRuntime(engine = QuickJsEngine(onLog = { msg -> Log.i("PoLang:Js", msg) }), scope = appScope)
 * rt.eval("bridge.call('math.add', [1, 2])")
 * rt.close()
 * ```
 *
 * @param engine JS 引擎实现（eval/callFunction/installBridge；通常也实现 [Closeable]）。
 * @param scope 异步 handler 协程作用域（建议绑定 App / 页面生命周期）。
 */
class JsRuntime(
    private val engine: JsEngine,
    scope: CoroutineScope,
) : JsEngine, AutoCloseable {

    private val tag = "JsRuntime"
    private val bridge: JsBridge = JsBridge(scope)

    init {
        BuiltInHandlers.registerAll(bridge)
        engine.installBridge(bridge)
        Logger.i(tag, "JsRuntime ready (handlers=${bridge.names()})")
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
        (engine as? Closeable)?.close()
        Logger.i(tag, "JsRuntime closed")
    }
}
