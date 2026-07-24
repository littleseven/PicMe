package com.mamba.picme.features.chat.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.mamba.picme.agent.core.js.JsBridge
import com.mamba.picme.agent.core.js.JsBridgeException
import com.mamba.picme.agent.core.js.JsCallback
import com.mamba.picme.agent.core.js.JsEngine
import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.agent.core.platform.logging.Logger
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * dokar3/quickjs-kt 1.0.5（com.dokar.quickjs.*）实现的 JS 引擎，实现 [JsEngine] 契约。
 *
 * - dokar3 的 evaluate 是 suspend，这里用 runBlocking 适配同步的 [JsEngine.eval]；超时用协程 [withTimeout]
 *   （1.0.5 未暴露 evaluationTimeoutMillis）。dokar3 的协程取消能**真正中断** C 死循环。
 * - **沙箱**：QuickJS 无 LiveConnect，JS 碰不到 Java/反射；唯一 native 通道是注入的 bridge。
 *   native 库 16KB page 对齐（满足 Google Play 16KB 合规）。
 * - async：dokar3 是 Promise/await 模型；`bridge.callAsync` 返回 Promise，JS 侧 `await`。
 * - bridge 注入采用「全局函数 + bootstrap JS 包装」，定义 JS 侧 API（bridge.call/callAsync/list）。
 *
 * @param onLog `console.log` 输出回调。
 * @param evalTimeoutMs evaluate 超时（withTimeout）。
 */
class QuickJsEngine(
    private val onLog: (String) -> Unit = {},
    private val evalTimeoutMs: Long = DEFAULT_EVAL_TIMEOUT_MS,
) : JsEngine, Closeable {

    private val quickjs: QuickJs = QuickJs.create(Dispatchers.Default)

    override fun eval(script: String): JsValue = runBlocking {
        try {
            val result = withTimeout(evalTimeoutMs) { quickjs.evaluate<Any?>(script) }
            QuickJsConverter.toJsValue(result)
        } catch (e: TimeoutCancellationException) {
            throw JsBridgeException(
                JsBridgeException.SCRIPT_TIMEOUT,
                "script timed out after ${evalTimeoutMs}ms",
            )
        }
    }

    override fun callFunction(name: String, vararg args: JsValue): JsValue = runBlocking {
        val argsJs = args.joinToString(",") { it.toJson() }
        try {
            val result = withTimeout(evalTimeoutMs) { quickjs.evaluate<Any?>("$name($argsJs)") }
            QuickJsConverter.toJsValue(result)
        } catch (e: TimeoutCancellationException) {
            throw JsBridgeException(
                JsBridgeException.SCRIPT_TIMEOUT,
                "script timed out after ${evalTimeoutMs}ms",
            )
        }
    }

    override fun installBridge(bridge: JsBridge) {
        quickjs.defineBinding("__bridgeCall", FunctionBinding<Any?> { args ->
            val handlerName = args.getOrNull(0)?.toString() ?: ""
            val jsArgs = QuickJsConverter.toJsValue(args.getOrNull(1))
            QuickJsConverter.toQuickJS(bridge.dispatchSync(handlerName, jsArgs))
        })
        quickjs.defineBinding("__bridgeCallAsync", object : AsyncFunctionBinding<Any?> {
            override suspend fun invoke(args: Array<Any?>): Any? {
                val handlerName = args.getOrNull(0)?.toString() ?: ""
                val jsArgs = QuickJsConverter.toJsValue(args.getOrNull(1))
                val result = suspendCoroutine<JsValue?> { cont ->
                    bridge.dispatchAsync(handlerName, jsArgs, JsCallback { _, res -> cont.resume(res) })
                }
                return result?.let { QuickJsConverter.toQuickJS(it) }
            }
        })
        quickjs.defineBinding("__bridgeList", FunctionBinding<List<String>> { _ -> bridge.names() })
        quickjs.defineBinding("__consoleLog", FunctionBinding<Unit> { args ->
            onLog(args.joinToString(" ") { QuickJsConverter.toJsValue(it).toJson() })
        })
        // bootstrap：把全局函数包装成 bridge/console 对象
        runBlocking { quickjs.evaluate<Any?>(BOOTSTRAP_JS) }
        Logger.i(TAG, "QuickJsEngine bridge installed (handlers=${bridge.names()})")
    }

    override fun close() {
        quickjs.close()
        Logger.i(TAG, "QuickJsEngine closed")
    }

    companion object {
        const val DEFAULT_EVAL_TIMEOUT_MS = 3_000L
        private const val TAG = "PoLang:QuickJS"
        private const val BOOTSTRAP_JS = """globalThis.bridge = {
  call: function(n, a) { return __bridgeCall(n, a); },
  callAsync: function(n, a) { return __bridgeCallAsync(n, a); },
  list: function() { return __bridgeList(); }
};
globalThis.console = { log: function() { __consoleLog(Array.prototype.slice.call(arguments)); } };"""
    }
}
