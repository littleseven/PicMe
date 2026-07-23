package com.mamba.picme.agent.core.js

/**
 * 内置 handler 集合（纯计算 / 只读信息，无原生危险副作用、不触网）。
 * 用于演示 JSBridge 通路与作为应用层 handler 的参考实现。
 */
object BuiltInHandlers {

    /** math.add([a, b]) -> number */
    val mathAdd = syncHandler("math.add") { args ->
        val items = (args as? JsValue.Arr)?.items ?: emptyList()
        val a = (items.getOrNull(0) as? JsValue.Num)?.value ?: 0.0
        val b = (items.getOrNull(1) as? JsValue.Num)?.value ?: 0.0
        JsValue.Num(a + b)
    }

    /** string.upper(s) -> string */
    val stringUpper = syncHandler("string.upper") { args ->
        val s = (args as? JsValue.Str)?.value ?: args.toJson()
        JsValue.Str(s.uppercase())
    }

    /** echo(obj) -> obj（原样返回，调试用） */
    val echo = syncHandler("echo") { args -> args }

    /** device.info() -> { app, engine }（只读，不取隐私、不触网） */
    val deviceInfo = asyncHandler("device.info") { _ ->
        JsValue.Obj(
            mapOf(
                "app" to JsValue.Str("picme"),
                "engine" to JsValue.Str("rhino"),
            )
        )
    }

    /** 注册全部内置 handler。 */
    fun registerAll(bridge: JsBridge) {
        bridge.register(mathAdd)
        bridge.register(stringUpper)
        bridge.register(echo)
        bridge.register(deviceInfo)
    }
}
