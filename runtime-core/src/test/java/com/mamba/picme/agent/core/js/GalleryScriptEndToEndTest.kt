package com.mamba.picme.agent.core.js

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端：JS 调 gallery.summary（mock sync handler）做盘点统计计算。
 * 忠实模拟 ChatViewModel.onRunScript 的 JS↔handler 交互（数据 mock，不读 DB）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryScriptEndToEndTest {

    @Test
    fun `inventory script computes labeled ratio from gallery summary`() = runTest {
        val engine = RhinoJsEngine(scope = this, onLog = {})
        val bridge = JsBridge(this)
        bridge.register(syncHandler("gallery.summary") { _ ->
            JsValue.Obj(
                mapOf(
                    "totalMedia" to JsValue.Num(100.0),
                    "labeledCount" to JsValue.Num(80.0),
                    "unlabeledCount" to JsValue.Num(20.0),
                )
            )
        })
        engine.installBridge(bridge)
        try {
            val result = engine.eval(
                """
                (function () {
                    var s = bridge.call('gallery.summary');
                    var r = s.totalMedia > 0 ? s.labeledCount / s.totalMedia : 0;
                    return { total: s.totalMedia, labeledRatioPct: Math.round(r * 100) };
                })()
                """.trimIndent()
            )
            assertTrue("expected Obj, got $result", result is JsValue.Obj)
            val obj = result as JsValue.Obj
            assertEquals(JsValue.Num(100.0), obj.entries["total"])
            assertEquals(JsValue.Num(80.0), obj.entries["labeledRatioPct"])
        } finally {
            engine.close()
        }
    }
}
