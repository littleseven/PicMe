package com.mamba.picme.agent.core.js

import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecord
import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock

/**
 * JS 运行时门面：装配引擎 + bridge + 内置 handler，提供 [eval]/[evalAsync]/[callFunction]。
 *
 * 引擎由调用方注入（[engine]）：app 层注入 QuickJsEngine（生产）。
 * 这解决了「JsRuntime hardcode 某个引擎实现」的耦合——bridge/handler/JsValue 全引擎无关，
 * 换引擎只换注入的实现。
 *
 * **运行感知（Agent 终端运行感知层）**：每次执行产生一条 [JsRunEvent] 经 [recorder] 上报
 * （成功/失败/超时全覆盖），绝不影响执行语义——结果原样返回、错误原样重抛、recorder 异常被吞。
 *
 * 典型用法：
 * ```
 * val rt = JsRuntime(engine = QuickJsEngine(onLog = { msg -> Log.i("PoLang:Js", msg) }), scope = appScope, source = "chat")
 * rt.eval("bridge.call('math.add', [1, 2])")
 * rt.close()
 * ```
 *
 * @param engine JS 引擎实现（eval/callFunction/installBridge；通常也实现 [JsClosable]）。
 * @param scope 异步 handler 协程作用域（建议绑定 App / 页面生命周期）。
 * @param source 运行来源标签（chat / debug_page），落入 [JsRunEvent.source] 便于归因。
 */
class JsRuntime(
    private val engine: JsEngine,
    scope: CoroutineScope,
    private val source: String = "unknown",
) : JsEngine, JsClosable {

    private val tag = "JsRuntime"
    private val bridge: JsBridge = JsBridge(scope)

    init {
        BuiltInHandlers.registerAll(bridge)
        engine.installBridge(bridge)
        Logger.i(tag, "JsRuntime ready (handlers=${bridge.names()}, source=$source)")
    }

    /** 追加自定义 handler。 */
    fun register(handler: NativeHandler) {
        bridge.register(handler)
    }

    /** 已注册 handler 名（含内置 + 自定义）。 */
    fun handlerNames(): List<String> = bridge.names()

    override fun eval(script: String): JsValue =
        runRecorded(JsRunEvent.KIND_EVAL, script) { engine.eval(script) }

    override fun eval(script: String, timeoutMs: Long): JsValue =
        runRecorded(JsRunEvent.KIND_EVAL, script) { engine.eval(script, timeoutMs) }

    override fun evalAsync(code: String, timeoutMs: Long): JsValue =
        runRecorded(JsRunEvent.KIND_EVAL_ASYNC, code) { engine.evalAsync(code, timeoutMs) }

    override fun callFunction(name: String, vararg args: JsValue): JsValue =
        runRecorded(
            JsRunEvent.KIND_CALL_FUNCTION,
            name + "(" + args.joinToString(",") { it.toJson() } + ")",
        ) { engine.callFunction(name, *args) }

    // —— traceId 携带重载：chat 等会话来源透传 context.traceId,落入 JsRunEvent.traceId ——

    fun eval(script: String, traceId: String?): JsValue =
        runRecorded(JsRunEvent.KIND_EVAL, script, traceId) { engine.eval(script) }

    fun eval(script: String, timeoutMs: Long, traceId: String?): JsValue =
        runRecorded(JsRunEvent.KIND_EVAL, script, traceId) { engine.eval(script, timeoutMs) }

    fun evalAsync(code: String, timeoutMs: Long, traceId: String?): JsValue =
        runRecorded(JsRunEvent.KIND_EVAL_ASYNC, code, traceId) { engine.evalAsync(code, timeoutMs) }

    fun callFunction(name: String, traceId: String?, vararg args: JsValue): JsValue =
        runRecorded(
            JsRunEvent.KIND_CALL_FUNCTION,
            name + "(" + args.joinToString(",") { it.toJson() } + ")",
            traceId
        ) { engine.callFunction(name, *args) }

    override fun installBridge(bridge: JsBridge) {
        engine.installBridge(bridge)
    }

    /**
     * 执行并记录一条 [JsRunEvent]。执行语义不变：结果原样返回、错误原样重抛。
     */
    private inline fun runRecorded(kind: String, script: String, traceId: String? = null, block: () -> JsValue): JsValue {
        val start = Clock.System.now().toEpochMilliseconds()
        var event: JsRunEvent? = null
        try {
            val result = block()
            event = JsRunEvent(
                createdAt = start,
                source = source,
                kind = kind,
                script = if (captureContent) LlmCallRecord.cap(script, JsRunEvent.SCRIPT_MAX_CHARS) else null,
                scriptLength = script.length,
                success = true,
                errorCode = null,
                errorMessage = null,
                resultPreview = if (captureContent) {
                    LlmCallRecord.cap(result.toJson(), JsRunEvent.RESULT_MAX_CHARS)
                } else {
                    null
                },
                latencyMs = Clock.System.now().toEpochMilliseconds() - start,
                traceId = traceId,
            )
            return result
        } catch (t: Throwable) {
            event = JsRunEvent(
                createdAt = start,
                source = source,
                kind = kind,
                script = if (captureContent) LlmCallRecord.cap(script, JsRunEvent.SCRIPT_MAX_CHARS) else null,
                scriptLength = script.length,
                success = false,
                errorCode = (t as? JsBridgeException)?.errorCode ?: JsRunEvent.ERROR_UNKNOWN,
                errorMessage = LlmCallRecord.cap(t.message ?: t::class.simpleName ?: "unknown", JsRunEvent.ERROR_MAX_CHARS),
                resultPreview = null,
                latencyMs = Clock.System.now().toEpochMilliseconds() - start,
                traceId = traceId,
            )
            throw t
        } finally {
            // 上报是旁观行为：recorder 异常绝不冒泡到执行链路
            event?.let { e -> runCatching { recorder?.record(e) } }
        }
    }

    override fun close() {
        (engine as? JsClosable)?.close()
        Logger.i(tag, "JsRuntime closed")
    }

    companion object {
        /**
         * 全局 [JsRunRecorder]（:app 启动时注入，null 则不记录）。
         * 镜像 `RemoteModelFactory.recorder` 既定模式。
         */
        @kotlin.concurrent.Volatile
        var recorder: JsRunRecorder? = null

        /** true 时事件含脚本文本与结果预览（DEBUG）；false 仅落指标（release，隐私红线）。 */
        @kotlin.concurrent.Volatile
        var captureContent: Boolean = false
    }
}
