package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsBridge
import com.mamba.picme.agent.core.js.JsEngine
import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.agent.core.js.JsValue
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [JsEngine.evalAsync] 契约测试（纯 JVM）。
 *
 * 真实引擎（QuickJsEngine）的两段式 Promise 解包依赖 native 库，无法单测，走真机验证；
 * 这里锁定接口默认实现的包装语义与 JsRuntime 的委托行为。
 */
class EvalAsyncContractTest {

    /** 记录 eval 收到的脚本并返回固定值的假引擎。 */
    private class RecordingEngine : JsEngine {
        var lastScript: String? = null
        var lastTimeoutMs: Long? = null

        override fun eval(script: String): JsValue {
            lastScript = script
            return JsValue.Str("ok")
        }

        override fun eval(script: String, timeoutMs: Long): JsValue {
            lastScript = script
            lastTimeoutMs = timeoutMs
            return JsValue.Str("ok")
        }

        override fun callFunction(name: String, vararg args: JsValue): JsValue = JsValue.Null
        override fun installBridge(bridge: JsBridge) = Unit
    }

    @Test
    fun `default evalAsync wraps code in async iife and forwards timeout`() {
        val engine = RecordingEngine()

        val result = engine.evalAsync("return 1;", 1234)

        assertEquals(JsValue.Str("ok"), result)
        assertEquals("(async function() {\nreturn 1;\n})()", engine.lastScript)
        assertEquals(1234L, engine.lastTimeoutMs)
    }

    @Test
    fun `JsRuntime delegates evalAsync to engine`() {
        val engine = RecordingEngine()
        val runtime = JsRuntime(engine = engine, scope = TestScope())

        val result = runtime.evalAsync("return 2;", 99)

        assertEquals(JsValue.Str("ok"), result)
        assertEquals("(async function() {\nreturn 2;\n})()", engine.lastScript)
        assertEquals(99L, engine.lastTimeoutMs)
    }
}
