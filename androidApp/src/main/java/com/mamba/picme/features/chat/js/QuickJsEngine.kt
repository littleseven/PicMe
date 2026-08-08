package com.mamba.picme.features.chat.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.mamba.picme.agent.core.js.JsBridge
import com.mamba.picme.agent.core.js.JsBridgeException
import com.mamba.picme.agent.core.js.JsCallback
import com.mamba.picme.agent.core.js.JsClosable
import com.mamba.picme.agent.core.js.JsEngine
import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.agent.core.platform.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
) : JsEngine, JsClosable {

    private val quickjs: QuickJs = QuickJs.create(Dispatchers.Default)

    override fun eval(script: String): JsValue = eval(script, evalTimeoutMs)

    override fun eval(script: String, timeoutMs: Long): JsValue = runEval(timeoutMs) {
        QuickJsConverter.toJsValue(quickjs.evaluate<Any?>(script))
    }

    /**
     * 两段式 async 执行（dokar3 1.0.5 不会解包顶层 Promise 的值，只返回 Promise 对象本身）：
     *
     * 1. 把 [code] 包成 async IIFE 执行，`.then` 把 resolved value / rejection 写入全局变量。
     *    dokar3 的 evaluate 返回前会 pump 完所有 pending job，此时结果已落定；
     *    第一段自身返回的 Promise 字符串被丢弃。
     * 2. 第二段同步读取全局变量：rejection 转为 throw（调用方拿到真实 JS 错误），
     *    否则返回 resolved value（`undefined` 归一为 `null`）。
     */
    override fun evalAsync(code: String, timeoutMs: Long): JsValue = runEval(timeoutMs) {
        quickjs.evaluate<Any?>(
            ASYNC_WRAPPER_HEAD + code + ASYNC_WRAPPER_TAIL,
        )
        QuickJsConverter.toJsValue(quickjs.evaluate<Any?>(READ_ASYNC_RESULT_JS))
    }

    override fun callFunction(name: String, vararg args: JsValue): JsValue = runEval(evalTimeoutMs) {
        val argsJs = args.joinToString(",") { it.toJson() }
        QuickJsConverter.toJsValue(quickjs.evaluate<Any?>("$name($argsJs)"))
    }

    /**
     * 统一执行入口：runBlocking + withTimeout；异常归一为 [JsBridgeException]——
     * 超时 → [JsBridgeException.SCRIPT_TIMEOUT]，dokar3 JS 执行错误 → [JsBridgeException.SCRIPT_ERROR]
     * （runtime-core 埋点按 errorCode 分类，不可见 dokar3 类型）。
     */
    private fun runEval(timeoutMs: Long, block: suspend () -> JsValue): JsValue = runBlocking {
        try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw JsBridgeException(
                JsBridgeException.SCRIPT_TIMEOUT,
                "script timed out after ${timeoutMs}ms",
                e,
            )
        } catch (e: QuickJsException) {
            throw JsBridgeException(
                JsBridgeException.SCRIPT_ERROR,
                e.message ?: "js evaluation failed",
                e,
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
                    bridge.dispatchAsync(handlerName, jsArgs, JsCallback { err, res ->
                        // handler 失败（含用户拒绝写操作）→ reject Promise，JS 侧可 try/catch
                        if (err != null) {
                            cont.resumeWithException(
                                JsBridgeException(
                                    JsBridgeException.HANDLER_ERROR,
                                    (err as? JsValue.Str)?.value ?: "handler error",
                                )
                            )
                        } else {
                            cont.resume(res)
                        }
                    })
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
        const val DEFAULT_EVAL_TIMEOUT_MS = 5_000L
        private const val TAG = "PoLang:QuickJS"

        /** [evalAsync] 第一段：执行用户代码并把 Promise 落定结果写入全局变量。 */
        private const val ASYNC_WRAPPER_HEAD = """globalThis.__asyncResult = undefined;
globalThis.__asyncError = undefined;
(async function() {
"""

        private const val ASYNC_WRAPPER_TAIL = """
})().then(
  function(r) { globalThis.__asyncResult = r === undefined ? null : r; },
  function(e) { globalThis.__asyncError = String((e && e.stack) || e); }
);
"""

        /** [evalAsync] 第二段：同步读回结果；rejection 转为 throw（暴露真实 JS 错误）。 */
        private const val READ_ASYNC_RESULT_JS = """(function() {
  if (globalThis.__asyncError !== undefined) {
    var m = globalThis.__asyncError;
    globalThis.__asyncError = undefined;
    throw new Error(m);
  }
  var r = globalThis.__asyncResult === undefined ? null : globalThis.__asyncResult;
  globalThis.__asyncResult = undefined;
  return r;
})()
"""

        private const val BOOTSTRAP_JS = """globalThis.bridge = {
  call: function(n, a) { return __bridgeCall(n, a); },
  callAsync: function(n, a) { return __bridgeCallAsync(n, a); },
  list: function() { return __bridgeList(); }
};
globalThis.console = { log: function() { __consoleLog(Array.prototype.slice.call(arguments)); } };"""
    }
}
