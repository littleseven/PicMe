package com.mamba.picme.agent.core.js

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Rhino 实现的 JS 引擎。
 *
 * - 所有 JS 执行串行在单守护线程（Rhino Context 非线程安全）。
 * - [Sandbox] 的 [ClassShutter.visibleToScripts] 对所有 Java 类返回 false；
 *   JS 只能通过注入的 `bridge` 对象（NativeObject + BaseFunction，JS 原生类型）
 *   间接访问原生，构成 Google Play「解释器间接访问」豁免 + 防逃逸沙箱。
 * - 解释模式（optimization -1），不生成可被滥用的字节码类。
 *
 * @param scope 异步 handler 回调所用协程作用域。
 * @param onLog `console.log` 输出回调（App 端注入 android Log；测试可注入捕获器）。
 */
class RhinoJsEngine(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onLog: (String) -> Unit = {},
    private val evalTimeoutMs: Long = DEFAULT_EVAL_TIMEOUT_MS,
) : JsEngine, Closeable {

    companion object {
        const val DEFAULT_EVAL_TIMEOUT_MS = 3_000L
    }

    private var executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PoLang-JsEngine").apply { isDaemon = true }
    }

    @Volatile
    private var rootScope: Scriptable? = null
    private var installedBridge: JsBridge? = null

    private fun <T> onJsThread(block: (cx: Context, sc: Scriptable) -> T): T {
        val future = executor.submit<T> {
            val cx = Context.enter()
            try {
                cx.setOptimizationLevel(-1)
                cx.setClassShutter(Sandbox)
                val sc = rootScope ?: cx.initStandardObjects().also { rootScope = it }
                block(cx, sc)
            } finally {
                Context.exit()
            }
        }
        return try {
            future.get(evalTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            // 死循环/超时：丢弃卡死的 JS 线程与可能不一致的 rootScope，重建 executor 使引擎可继续使用。
            // 注：被中断的旧线程若仍占用 CPU（Rhino 解释模式未必响应中断），属异常路径下的已知代价。
            resetEngine()
            throw JsBridgeException(
                JsBridgeException.SCRIPT_TIMEOUT,
                "script timed out after ${evalTimeoutMs}ms",
            )
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * 超时熔断：关停卡死的 JS 线程、丢弃 rootScope、重建 executor。
     * 熔断后 bridge 不会自动重装（需重新 installBridge）；onRunScript 用法下熔断即 close，不受影响。
     */
    private fun resetEngine() {
        runCatching { executor.shutdownNow() }
        rootScope = null
        executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "PoLang-JsEngine").apply { isDaemon = true }
        }
    }

    override fun eval(script: String): JsValue = onJsThread { cx, sc ->
        val result = cx.evaluateString(sc, script, "picme-js", 1, null)
        RhinoConverter.toJsValue(result)
    }

    override fun callFunction(name: String, vararg args: JsValue): JsValue = onJsThread { cx, sc ->
        val fn = sc.get(name, sc)
        if (fn is Function) {
            val raw = args.map { RhinoConverter.toRhino(it, sc) }.toTypedArray()
            RhinoConverter.toJsValue(fn.call(cx, sc, sc, raw))
        } else {
            throw JsBridgeException(
                JsBridgeException.FUNCTION_NOT_FOUND,
                "JS function not found: $name",
            )
        }
    }

    override fun installBridge(bridge: JsBridge) {
        installedBridge = bridge
        onJsThread { _, sc ->
            val bridgeObj = NativeObject()

            ScriptableObject.putProperty(bridgeObj, "call", object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any?>,
                ): Any? {
                    val handlerName = args.getOrNull(0)?.toString() ?: ""
                    val jsArgs = RhinoConverter.toJsValue(args.getOrNull(1))
                    val result = bridge.dispatchSync(handlerName, jsArgs)
                    return RhinoConverter.toRhino(result, scope)
                }
            })

            ScriptableObject.putProperty(bridgeObj, "callAsync", object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any?>,
                ): Any? {
                    val handlerName = args.getOrNull(0)?.toString() ?: ""
                    val jsArgs = RhinoConverter.toJsValue(args.getOrNull(1))
                    val rawFn = args.getOrNull(2)
                    val cb: JsCallback = if (rawFn is Function) {
                        JsCallback { err, result -> submitJsCallback(rawFn, err, result) }
                    } else {
                        JsCallback { _, _ -> }
                    }
                    bridge.dispatchAsync(handlerName, jsArgs, cb)
                    return Context.getUndefinedValue()
                }
            })

            ScriptableObject.putProperty(bridgeObj, "list", object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any?>,
                ): Any? {
                    return NativeArray(bridge.names().toTypedArray())
                }
            })

            sc.put("bridge", sc, bridgeObj)

            // console.log —— 纯日志，无原生副作用
            val console = NativeObject()
            ScriptableObject.putProperty(console, "log", object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any?>,
                ): Any? {
                    val msg = args.joinToString(" ") { RhinoConverter.toJsValue(it).toJson() }
                    onLog(msg)
                    return Context.getUndefinedValue()
                }
            })
            sc.put("console", sc, console)
        }
    }

    /** 在 JS 线程上回调 JS 函数（异步 handler 完成后）。 */
    private fun submitJsCallback(fn: Function, err: JsValue?, result: JsValue?) {
        onJsThread { cx, sc ->
            val errArg = RhinoConverter.toRhino(err ?: JsValue.Null, sc)
            val resArg = RhinoConverter.toRhino(result ?: JsValue.Null, sc)
            fn.call(cx, sc, sc, arrayOf(errArg, resArg))
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    /**
     * 沙箱：对一切 Java 类返回 false（deny-all）。
     *
     * 注入的 bridge 是 NativeObject + BaseFunction（JS 原生类型），调用 JS 函数不触发
     * ClassShutter（它只在 LiveConnect 解析 Java 类名时被调用），故 deny-all 不会影响
     * bridge 工作；同时彻底封死 `Packages.java.*` / `android.*` 等逃逸路径。
     */
    private object Sandbox : ClassShutter {
        override fun visibleToScripts(fullClassName: String?): Boolean = false
    }
}
