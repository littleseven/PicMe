package com.mamba.picme.agent.core.js

/**
 * 可关闭资源的引擎无关抽象（commonMain 无 java.io.Closeable / AutoCloseable）。
 * 引擎实现（如 QuickJsEngine）实现本接口即可被 [JsRuntime.close] 级联关闭。
 */
interface JsClosable {
    fun close()
}

/**
 * JS 引擎抽象（引擎无关）。当前实现 QuickJsEngine（app 层，dokar3/quickjs-kt）。
 *
 * 引擎负责：执行脚本、调用全局函数、注入 bridge 全局对象。
 * bridge 的语义（call/callAsync/list）由 [JsBridge] 定义，引擎只做"桥接翻译"。
 */
interface JsEngine {
    /** 执行脚本，返回结果（JsValue 投影）。 */
    fun eval(script: String): JsValue

    /**
     * 执行脚本并自定义超时（如脚本内含 `capability.dispatch` 需等用户确认时放宽）。
     * 默认实现退化为 [eval]（引擎自身默认超时）。
     */
    fun eval(script: String, timeoutMs: Long): JsValue = eval(script)

    /**
     * 以「async 函数体」语义执行 [code]：允许顶层 `await` / `return`；
     * 若求值结果是 Promise，等待其 settle 并返回 resolved value（rejected 则抛出 JS 错误）。
     *
     * 默认实现：包 async IIFE 后走 [eval]，适用于能自动解包 Promise 的引擎；
     * 不能解包的引擎（如 dokar3 QuickJS）必须覆写，否则调用方拿到的只是 Promise 对象的字符串。
     */
    fun evalAsync(code: String, timeoutMs: Long): JsValue =
        eval("(async function() {\n$code\n})()", timeoutMs)

    /** 调用全局函数。 */
    fun callFunction(name: String, vararg args: JsValue): JsValue

    /** 注入 bridge 全局对象（封装引擎自己的 host-object 装配）。 */
    fun installBridge(bridge: JsBridge)
}
