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
}
