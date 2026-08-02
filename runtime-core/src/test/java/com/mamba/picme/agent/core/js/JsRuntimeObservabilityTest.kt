package com.mamba.picme.agent.core.js

import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * JsRuntime 运行感知埋点测试（Agent 终端运行感知层）。
 *
 * 契约：eval/evalAsync/callFunction 每次执行都产生一条 [JsRunEvent] 经 [JsRunRecorder] 上报；
 * 记录绝不影响执行语义（结果原样返回、错误原样重抛、recorder 自身异常被吞）。
 */
class JsRuntimeObservabilityTest {

    private class FakeEngine(
        private val behavior: (String) -> JsValue = { JsValue.Null },
    ) : JsEngine {
        override fun eval(script: String): JsValue = behavior(script)
        override fun callFunction(name: String, vararg args: JsValue): JsValue = JsValue.Num(1.0)
        override fun installBridge(bridge: JsBridge) = Unit
    }

    private class FakeRecorder : JsRunRecorder {
        val events = mutableListOf<JsRunEvent>()
        override fun record(event: JsRunEvent) {
            events.add(event)
        }
    }

    private lateinit var recorder: FakeRecorder

    @Before
    fun setUp() {
        recorder = FakeRecorder()
        JsRuntime.recorder = recorder
        JsRuntime.captureContent = true
    }

    @After
    fun tearDown() {
        JsRuntime.recorder = null
        JsRuntime.captureContent = false
    }

    private fun runtime(engine: JsEngine, source: String = "chat") =
        JsRuntime(engine = engine, scope = TestScope(), source = source)

    @Test
    fun `eval success records event with kind source and script`() {
        val rt = runtime(FakeEngine { JsValue.Str("done") })

        val result = rt.eval("return 1;")

        assertEquals(JsValue.Str("done"), result)
        assertEquals(1, recorder.events.size)
        val e = recorder.events[0]
        assertEquals("eval", e.kind)
        assertEquals("chat", e.source)
        assertEquals("return 1;", e.script)
        assertEquals(9, e.scriptLength)
        assertTrue(e.success)
        assertNull(e.errorCode)
        assertNull(e.errorMessage)
        assertNotNull(e.resultPreview)
        assertTrue(e.latencyMs >= 0)
        assertTrue(e.createdAt > 0)
    }

    @Test
    fun `evalAsync records kind evalAsync`() {
        val rt = runtime(FakeEngine { JsValue.Null }, source = "debug_page")

        rt.evalAsync("return 1;", 1000)

        assertEquals(1, recorder.events.size)
        assertEquals("evalAsync", recorder.events[0].kind)
        assertEquals("debug_page", recorder.events[0].source)
    }

    @Test
    fun `callFunction records kind callFunction`() {
        val rt = runtime(FakeEngine())

        rt.callFunction("Chart.bar", JsValue.Str("{}"))

        assertEquals(1, recorder.events.size)
        assertEquals("callFunction", recorder.events[0].kind)
    }

    @Test
    fun `traceId is correctly stamped on eval and evalAsync`() {
        // eval with traceId → event carries it
        runtime(FakeEngine { JsValue.Str("ok") }).eval("return 1;", traceId = "trace-js")
        assertEquals("trace-js", recorder.events[0].traceId)

        // eval without traceId → null
        runtime(FakeEngine { JsValue.Str("ok") }).eval("return 1;")
        assertNull(recorder.events[1].traceId)

        // evalAsync with traceId → event carries it
        runtime(FakeEngine { JsValue.Null }).evalAsync("return 1;", 1000, traceId = "trace-js-async")
        assertEquals("trace-js-async", recorder.events[2].traceId)
    }

    @Test
    fun `bridge exception failure records its errorCode and rethrows`() {
        val rt = runtime(FakeEngine { throw JsBridgeException(JsBridgeException.SCRIPT_ERROR, "boom: at line 1") })

        try {
            rt.eval("bad js")
            fail("should rethrow")
        } catch (e: JsBridgeException) {
            assertEquals(JsBridgeException.SCRIPT_ERROR, e.errorCode)
        }
        assertEquals(1, recorder.events.size)
        val e = recorder.events[0]
        assertTrue(!e.success)
        assertEquals(JsBridgeException.SCRIPT_ERROR, e.errorCode)
        assertTrue(e.errorMessage!!.contains("boom"))
        assertNull(e.resultPreview)
    }

    @Test
    fun `non bridge exception is classified UNKNOWN`() {
        val rt = runtime(FakeEngine { throw IllegalStateException("native crash") })

        try {
            rt.eval("x")
            fail("should rethrow")
        } catch (e: IllegalStateException) {
            // expected: 原样重抛
        }
        assertEquals("UNKNOWN", recorder.events[0].errorCode)
    }

    @Test
    fun `captureContent false omits script and resultPreview but keeps metrics`() {
        JsRuntime.captureContent = false
        val rt = runtime(FakeEngine { JsValue.Str("done") })

        rt.eval("return 1;")

        val e = recorder.events[0]
        assertNull(e.script)
        assertNull(e.resultPreview)
        assertEquals(9, e.scriptLength)
        assertTrue(e.success)
    }

    @Test
    fun `recorder throwing does not affect execution`() {
        JsRuntime.recorder = object : JsRunRecorder {
            override fun record(event: JsRunEvent) = throw RuntimeException("db down")
        }
        val rt = runtime(FakeEngine { JsValue.Str("ok") })

        val result = rt.eval("return 1;")

        assertEquals(JsValue.Str("ok"), result)
    }

    @Test
    fun `no recorder installed runs without recording`() {
        JsRuntime.recorder = null
        val rt = runtime(FakeEngine { JsValue.Str("ok") })

        val result = rt.eval("return 1;")

        assertEquals(JsValue.Str("ok"), result)
    }
}
