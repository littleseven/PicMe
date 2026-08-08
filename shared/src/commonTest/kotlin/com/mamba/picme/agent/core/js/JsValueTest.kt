package com.mamba.picme.agent.core.js

import kotlin.test.Test
import kotlin.test.assertEquals

class JsValueTest {
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
