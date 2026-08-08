package com.mamba.picme.agent.core.model.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditParamsTest {

    @Test
    fun `fromJson parses absolute delta and string values`() {
        val params = EditParams.fromJson(
            """{"smoothing":30,"filter_name":"FILM_GOLD","filter_intensity":70,"brightness_delta":20}"""
        )
        assertEquals(30f, (params.smoothing as EditParams.Absolute).value, 0.001f)
        assertEquals("FILM_GOLD", (params.filterName as EditParams.AbsoluteString).value)
        assertEquals(70f, params.filterIntensity!!, 0.001f)
        assertEquals(20f, (params.brightness as EditParams.Delta).value, 0.001f)
        assertEquals(EditParams.Unchanged, params.whitening)
    }

    @Test
    fun `fromJson parses snake_case keys and empty object`() {
        val params = EditParams.fromJson("""{"slim_face":-15,"big_eyes_delta":10}""")
        assertEquals(-15f, (params.slimFace as EditParams.Absolute).value, 0.001f)
        assertEquals(10f, (params.bigEyes as EditParams.Delta).value, 0.001f)

        val empty = EditParams.fromJson("{}")
        assertEquals(EditParams.Unchanged, empty.smoothing)
        assertNull(empty.filterIntensity)
    }
}
