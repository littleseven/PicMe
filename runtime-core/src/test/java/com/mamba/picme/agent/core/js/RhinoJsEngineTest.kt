package com.mamba.picme.agent.core.js

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoJsEngineTest {
    private fun newEngine(): RhinoJsEngine = RhinoJsEngine(scope = TestScope(), onLog = {})

    @Test
    fun `eval arithmetic`() {
        newEngine().use { engine ->
            assertEquals(JsValue.Num(3.0), engine.eval("1 + 2"))
        }
    }

    @Test
    fun `eval string`() {
        newEngine().use { engine ->
            assertEquals(JsValue.Str("PICME"), engine.eval("'picme'.toUpperCase()"))
        }
    }

    @Test
    fun `sandbox blocks java class access`() {
        newEngine().use { engine ->
            val ex = runCatching { engine.eval("java.lang.Runtime.getRuntime()") }.exceptionOrNull()
            assertTrue("expected sandbox violation, got null", ex != null)
        }
    }

    @Test
    fun `sandbox blocks java object construction`() {
        newEngine().use { engine ->
            // 构造 Java 对象触发 LiveConnect → visibleToScripts=false → 抛异常
            val ex = runCatching { engine.eval("new java.util.HashMap()") }.exceptionOrNull()
            assertTrue("expected sandbox violation", ex != null)
        }
    }

    @Test
    fun `callFunction invokes global fn`() {
        newEngine().use { engine ->
            engine.eval("function add(a, b){ return a + b; }")
            assertEquals(
                JsValue.Num(9.0),
                engine.callFunction("add", JsValue.Num(4.0), JsValue.Num(5.0)),
            )
        }
    }

    @Test
    fun `eval times out on infinite loop`() {
        RhinoJsEngine(scope = TestScope(), onLog = {}, evalTimeoutMs = 200).use { engine ->
            val ex = runCatching { engine.eval("while(true){}") }.exceptionOrNull()
            assertNotNull(ex)
            val jsEx = ex as? JsBridgeException
            assertTrue("expected JsBridgeException, got ${ex?.javaClass?.name}", jsEx != null)
            assertEquals(JsBridgeException.SCRIPT_TIMEOUT, jsEx?.errorCode)
        }
    }

    @Test
    fun `engine recovers after timeout`() {
        RhinoJsEngine(scope = TestScope(), onLog = {}, evalTimeoutMs = 200).use { engine ->
            runCatching { engine.eval("while(true){}") }
            // 熔断重建 executor 后，纯算术（不依赖 bridge）应正常
            assertEquals(JsValue.Num(2.0), engine.eval("1 + 1"))
        }
    }
}
