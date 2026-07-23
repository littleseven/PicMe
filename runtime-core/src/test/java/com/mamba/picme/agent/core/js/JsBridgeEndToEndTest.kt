package com.mamba.picme.agent.core.js

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JsBridgeEndToEndTest {

    @Test
    fun `js calls native sync handler via bridge`() = runTest {
        val engine = RhinoJsEngine(scope = this, onLog = {})
        val bridge = JsBridge(this)
        bridge.register(
            syncHandler("math.add") { args ->
                val items = (args as JsValue.Arr).items
                val a = (items[0] as JsValue.Num).value
                val b = (items[1] as JsValue.Num).value
                JsValue.Num(a + b)
            }
        )
        engine.installBridge(bridge)
        try {
            val result = engine.eval("(function(){ return bridge.call('math.add', [10, 32]); })()")
            assertEquals(JsValue.Num(42.0), result)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `js async handler calls back`() = runTest {
        val engine = RhinoJsEngine(scope = this, onLog = {})
        val bridge = JsBridge(this)
        bridge.register(asyncHandler("device.info") { _ ->
            JsValue.Obj(mapOf("app" to JsValue.Str("picme"), "v" to JsValue.Num(1.0)))
        })
        engine.installBridge(bridge)
        try {
            engine.eval(
                """
                var captured = null;
                bridge.callAsync('device.info', null, function(err, res) {
                    captured = res;
                });
                """.trimIndent()
            )
            // 推进协程：handler 在测试线程跑完 → 回调 submitJsCallback 到空闲的 JS 线程执行
            advanceUntilIdle()
            val res = engine.eval("captured")
            assertEquals(
                JsValue.Obj(mapOf("app" to JsValue.Str("picme"), "v" to JsValue.Num(1.0))),
                res,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `console log reaches onLog`() = runTest {
        val logs = mutableListOf<String>()
        val engine = RhinoJsEngine(scope = this, onLog = { msg -> logs += msg })
        engine.installBridge(JsBridge(this)) // console 随 bridge 一起安装
        try {
            engine.eval("console.log('hello', 42);")
        } finally {
            engine.close()
        }
        assertEquals(1, logs.size)
        assertEquals("\"hello\" 42.0", logs[0])
    }

    @Test
    fun `bridge list returns registered names`() = runTest {
        val engine = RhinoJsEngine(scope = this, onLog = {})
        val bridge = JsBridge(this)
        bridge.register(syncHandler("math.add") { JsValue.Null })
        engine.installBridge(bridge)
        try {
            val names = engine.eval("bridge.list()")
            // 返回数组，首项为 "math.add"
            val arr = names as JsValue.Arr
            assertEquals(JsValue.Str("math.add"), arr.items.first())
        } finally {
            engine.close()
        }
    }
}
