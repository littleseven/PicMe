package com.mamba.picme.agent.core.js

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JsBridgeTest {
    private fun bridge(scope: TestScope): JsBridge = JsBridge(scope)

    private fun mathAdd() = syncHandler("math.add") { args ->
        val arr = (args as JsValue.Arr).items
        val a = (arr.getOrNull(0) as JsValue.Num).value
        val b = (arr.getOrNull(1) as JsValue.Num).value
        JsValue.Num(a + b)
    }

    @Test
    fun `sync handler returns result`() = runTest {
        val b = bridge(this)
        b.register(mathAdd())
        val r = b.dispatchSync("math.add", JsValue.Arr(listOf(JsValue.Num(1.0), JsValue.Num(2.0))))
        assertEquals(JsValue.Num(3.0), r)
    }

    @Test
    fun `unknown handler throws`() = runTest {
        val b = bridge(this)
        try {
            b.dispatchSync("nope", JsValue.Null)
            error("should throw")
        } catch (e: JsBridgeException) {
            assertEquals(JsBridgeException.HANDLER_NOT_FOUND, e.errorCode)
        }
    }

    @Test
    fun `names lists registered handlers`() = runTest {
        val b = bridge(this)
        b.register(syncHandler("a") { JsValue.Null })
        b.register(syncHandler("b") { JsValue.Null })
        assertEquals(listOf("a", "b"), b.names())
    }

    @Test
    fun `async handler calls back with result`() = runTest {
        val b = bridge(this)
        b.register(asyncHandler("device.info") { _ ->
            JsValue.Obj(mapOf("app" to JsValue.Str("picme")))
        })
        var captured: JsValue? = null
        b.dispatchAsync("device.info", JsValue.Null, JsCallback { _, result -> captured = result })
        advanceUntilIdle()
        assertEquals(JsValue.Obj(mapOf("app" to JsValue.Str("picme"))), captured)
    }

    @Test
    fun `async handler failure calls back with error`() = runTest {
        val b = bridge(this)
        b.register(asyncHandler("boom") { _ -> error("kaboom") })
        var err: JsValue? = null
        b.dispatchAsync("boom", JsValue.Null, JsCallback { error, _ -> err = error })
        advanceUntilIdle()
        assertTrue(err is JsValue.Str)
        assertTrue((err as JsValue.Str).value.contains("kaboom"))
    }

    @Test
    fun `sync handler error is wrapped`() = runTest {
        val b = bridge(this)
        b.register(syncHandler("x") { error("fail") })
        try {
            b.dispatchSync("x", JsValue.Null)
        } catch (e: JsBridgeException) {
            assertEquals(JsBridgeException.HANDLER_ERROR, e.errorCode)
        }
    }

    @Test
    fun `async handler called via sync throws not_async_callable`() = runTest {
        val b = bridge(this)
        b.register(asyncHandler("a") { JsValue.Null })
        try {
            b.dispatchSync("a", JsValue.Null)
        } catch (e: JsBridgeException) {
            assertEquals(JsBridgeException.HANDLER_NOT_ASYNC_CALLABLE, e.errorCode)
        }
    }
}
