package com.mamba.picme.agent.core.js

import org.junit.Assert.assertEquals
import org.junit.Test

class RhinoConverterTest {
    @Test
    fun `toJsValue handles primitives`() {
        assertEquals(JsValue.Null, RhinoConverter.toJsValue(null))
        assertEquals(JsValue.Bool(true), RhinoConverter.toJsValue(true))
        assertEquals(JsValue.Num(3.0), RhinoConverter.toJsValue(3))
        assertEquals(JsValue.Num(3.5), RhinoConverter.toJsValue(3.5))
        assertEquals(JsValue.Str("hi"), RhinoConverter.toJsValue("hi"))
    }

    @Test
    fun `toJsValue falls back to string for unknown types`() {
        assertEquals(JsValue.Str("xyz"), RhinoConverter.toJsValue(StringBuilder("xyz")))
    }
}
