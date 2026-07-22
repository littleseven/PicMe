package com.mamba.picme.agent.core.js

import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * JS ↔ Native 路由（引擎无关）。
 *
 * 引擎层（如 RhinoJsEngine）将 JS 的 `bridge.call(name,args)` 翻译为 [dispatchSync]，
 * 将 `bridge.callAsync(name,args,cb)` 翻译为 [dispatchAsync]。
 *
 * @param scope 用于异步 handler 的协程作用域（建议绑定到 App 或页面生命周期）。
 */
class JsBridge(
    private val scope: CoroutineScope,
) {
    private val tag = "JsBridge"
    private val handlers = LinkedHashMap<String, NativeHandler>()

    /** 注册 handler（同名覆盖）。 */
    fun register(handler: NativeHandler) {
        handlers[handler.name] = handler
        Logger.i(tag, "Registered handler: ${handler.name}")
    }

    /** 已注册 handler 名列表（供 JS `bridge.list()` 调用）。 */
    fun names(): List<String> = handlers.keys.toList()

    /** 同步分发：查 handler → 执行 → 返回。 */
    fun dispatchSync(name: String, args: JsValue): JsValue {
        val handler = handlers[name]
            ?: throw JsBridgeException(
                JsBridgeException.HANDLER_NOT_FOUND,
                "handler not registered: $name",
            )
        return when (handler) {
            is NativeHandler.Sync -> runCatching { handler.invoke(args) }.getOrElse { cause ->
                Logger.w(tag, "Handler $name threw: ${cause.message}")
                throw JsBridgeException(JsBridgeException.HANDLER_ERROR, cause.message ?: "error", cause)
            }
            is NativeHandler.Async -> throw JsBridgeException(
                JsBridgeException.HANDLER_NOT_ASYNC_CALLABLE,
                "$name is async; use callAsync",
            )
        }
    }

    /** 异步分发：在 [scope] 内执行，完成后回调 [cb]（成功 result；失败 error）。 */
    fun dispatchAsync(name: String, args: JsValue, cb: JsCallback) {
        val handler = handlers[name]
        if (handler == null) {
            cb.invoke(JsValue.Str("handler not registered: $name"), null)
            return
        }
        scope.launch {
            val result = runCatching {
                when (handler) {
                    is NativeHandler.Async -> handler.invoke(args)
                    is NativeHandler.Sync -> handler.invoke(args)
                }
            }
            result.fold(
                onSuccess = { value -> cb.invoke(null, value) },
                onFailure = { err ->
                    Logger.w(tag, "Async handler $name threw: ${err.message}")
                    cb.invoke(JsValue.Str(err.message ?: "error"), null)
                },
            )
        }
    }
}
