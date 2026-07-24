package com.mamba.picme.agent.core.js

/**
 * JS 引擎抽象（引擎无关）。当前实现 QuickJsEngine（app 层，dokar3/quickjs-kt）。
 *
 * 引擎负责：执行脚本、调用全局函数、注入 bridge 全局对象。
 * bridge 的语义（call/callAsync/list）由 [JsBridge] 定义，引擎只做"桥接翻译"。
 */
interface JsEngine {
    /** 执行脚本，返回结果（JsValue 投影）。 */
    fun eval(script: String): JsValue

    /** 调用全局函数。 */
    fun callFunction(name: String, vararg args: JsValue): JsValue

    /** 注入 bridge 全局对象（封装引擎自己的 host-object 装配）。 */
    fun installBridge(bridge: JsBridge)
}
