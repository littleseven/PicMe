package com.mamba.picme.agent.core.model.command

import org.junit.Assert.assertEquals
import org.junit.Test

class EditParamsTest {

    @Test
    fun `default EditParams has no changes`() {
        val params = EditParams()
        assertEquals(EditParams.Unchanged, params.smoothing)
        assertEquals(EditParams.Unchanged, params.filterName)
        assertEquals(null, params.filterIntensity)
    }

    @Test
    fun `absolute value is preserved`() {
        val params = EditParams(smoothing = EditParams.Absolute(35f))
        assertEquals(35f, (params.smoothing as EditParams.Absolute).value, 0.001f)
    }

    @Test
    fun `delta value is preserved`() {
        val params = EditParams(brightness = EditParams.Delta(15f))
        assertEquals(15f, (params.brightness as EditParams.Delta).value, 0.001f)
    }

    @Test
    fun `absolute string value is preserved`() {
        val params = EditParams(filterName = EditParams.AbsoluteString("FILM_GOLD"))
        assertEquals("FILM_GOLD", (params.filterName as EditParams.AbsoluteString).value)
    }

    @Test
    fun `fromJson parses absolute delta and string values`() {
        val params = EditParams.fromJson(
            org.json.JSONObject(
                """{"smoothing":30,"filter_name":"FILM_GOLD","filter_intensity":70,"brightness_delta":20}"""
            )
        )
        assertEquals(30f, (params.smoothing as EditParams.Absolute).value, 0.001f)
        assertEquals("FILM_GOLD", (params.filterName as EditParams.AbsoluteString).value)
        assertEquals(70f, params.filterIntensity!!, 0.001f)
        assertEquals(20f, (params.brightness as EditParams.Delta).value, 0.001f)
        assertEquals(EditParams.Unchanged, params.whitening)
    }

    @Test
    fun `fromJson parses snake_case keys and empty object`() {
        val params = EditParams.fromJson(
            org.json.JSONObject("""{"slim_face":-15,"big_eyes_delta":10}""")
        )
        assertEquals(-15f, (params.slimFace as EditParams.Absolute).value, 0.001f)
        assertEquals(10f, (params.bigEyes as EditParams.Delta).value, 0.001f)

        val empty = EditParams.fromJson(org.json.JSONObject("{}"))
        assertEquals(EditParams.Unchanged, empty.smoothing)
        assertEquals(null, empty.filterIntensity)
    }
}
