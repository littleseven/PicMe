package com.mamba.picme.agent.core.js

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JsRuntimeTest {

    private fun newRuntime(scope: TestScope): JsRuntime =
        JsRuntime(scope = scope, onLog = {})

    @Test
    fun `builtin math add works via runtime`() = runTest {
        newRuntime(this).use { rt ->
            assertEquals(JsValue.Num(42.0), rt.eval("bridge.call('math.add', [40, 2])"))
        }
    }

    @Test
    fun `string upper works`() = runTest {
        newRuntime(this).use { rt ->
            assertEquals(JsValue.Str("HELLO"), rt.eval("bridge.call('string.upper', 'hello')"))
        }
    }

    @Test
    fun `echo returns input`() = runTest {
        newRuntime(this).use { rt ->
            val r = rt.eval("bridge.call('echo', {a: 1, b: 'x'})")
            assertTrue(r is JsValue.Obj)
        }
    }

    @Test
    fun `async device info calls back`() = runTest {
        newRuntime(this).use { rt ->
            rt.eval(
                """
                var info = null;
                bridge.callAsync('device.info', null, function(err, res){ info = res; });
                """.trimIndent()
            )
            advanceUntilIdle()
            val info = rt.eval("info")
            assertTrue(info is JsValue.Obj)
            assertEquals(JsValue.Str("rhino"), (info as JsValue.Obj).entries["engine"])
        }
    }

    @Test
    fun `handlerNames includes builtins`() = runTest {
        newRuntime(this).use { rt ->
            val names = rt.handlerNames()
            assertTrue(names.contains("math.add"))
            assertTrue(names.contains("device.info"))
        }
    }

    @Test
    fun `custom handler can be registered`() = runTest {
        newRuntime(this).use { rt ->
            rt.register(syncHandler("greet") { name ->
                val who = (name as? JsValue.Str)?.value ?: "world"
                JsValue.Str("hi $who")
            })
            assertEquals(JsValue.Str("hi polang"), rt.eval("bridge.call('greet', 'polang')"))
        }
    }
}
