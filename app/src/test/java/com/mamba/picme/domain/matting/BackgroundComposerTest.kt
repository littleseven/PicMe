package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundComposerTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `full alpha keeps foreground pixel opaque`() {
        val fg = argb(255, 100, 50, 25)
        val out = BackgroundComposer.composeOnColor(intArrayOf(fg), floatArrayOf(1f), bgColor = argb(255, 0, 0, 255))
        assertEquals(argb(255, 100, 50, 25), out[0])
    }

    @Test
    fun `zero alpha yields background color opaque`() {
        val fg = argb(255, 100, 50, 25)
        val out = BackgroundComposer.composeOnColor(intArrayOf(fg), floatArrayOf(0f), bgColor = argb(255, 0, 0, 255))
        assertEquals(argb(255, 0, 0, 255), out[0])
    }

    @Test
    fun `half alpha blends foreground and background`() {
        val fg = argb(255, 200, 0, 0)
        val bg = argb(255, 0, 0, 0)
        val out = BackgroundComposer.composeOnColor(intArrayOf(fg), floatArrayOf(0.5f), bg)
        val r = (out[0] shr 16) and 0xFF
        assertEquals(100, r) // 200*0.5 + 0*0.5 = 100
    }
}
