package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class ModNetPreprocessorTest {

    @Test
    fun `toNchw maps white to plus one and black to minus one`() {
        val white = intArrayOf(0xFFFFFFFF.toInt()) // 1x1
        val out = ModNetPreprocessor.toNchw(white, size = 1)
        assertEquals(3, out.size)
        // (1.0 - 0.5) / 0.5 = 1.0
        assertEquals(1.0f, out[0], 0.001f)
        assertEquals(1.0f, out[1], 0.001f)
        assertEquals(1.0f, out[2], 0.001f)

        val black = intArrayOf(0xFF000000.toInt())
        val ob = ModNetPreprocessor.toNchw(black, size = 1)
        // (0 - 0.5) / 0.5 = -1.0
        assertEquals(-1.0f, ob[0], 0.001f)
    }

    @Test
    fun `toNchw 256 has expected length`() {
        val pixels = IntArray(256 * 256) { 0xFF000000.toInt() }
        val out = ModNetPreprocessor.toNchw(pixels, size = 256)
        assertEquals(3 * 256 * 256, out.size)
    }
}
