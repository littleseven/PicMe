package com.mamba.picme.agent.core.js

import org.junit.Assert.assertEquals
import org.junit.Test

class JsValueTest {
    @Test
    fun `number holds double`() {
        val v = JsValue.Num(3.0)
        assertEquals(3.0, v.value, 0.0)
    }

    @Test
    fun `object holds entries`() {
        val v = JsValue.Obj(
            mapOf(
                "name" to JsValue.Str("picme"),
                "ok" to JsValue.Bool(true),
            )
        )
        assertEquals(JsValue.Str("picme"), v.entries["name"])
        assertEquals(JsValue.Bool(true), v.entries["ok"])
    }

    @Test
    fun `array holds ordered items`() {
        val v = JsValue.Arr(listOf(JsValue.Num(1.0), JsValue.Num(2.0)))
        assertEquals(2, v.items.size)
        assertEquals(JsValue.Num(2.0), v.items[1])
    }

    @Test
    fun `toJson renders nested structures`() {
        val v = JsValue.Obj(
            mapOf(
                "n" to JsValue.Num(1.0),
                "s" to JsValue.Str("a\"b"),
                "arr" to JsValue.Arr(listOf(JsValue.Bool(true), JsValue.Null)),
            )
        )
        assertEquals("""{"n":1.0,"s":"a\"b","arr":[true,null]}""", v.toJson())
    }
}
