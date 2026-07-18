package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class CutoutComposerTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `composeTransparent keeps rgb and sets alpha from mask`() {
        // opaque red pixel, full-alpha mask -> fully opaque
        val pixels = intArrayOf(argb(255, 255, 0, 0))
        val alpha = floatArrayOf(1f)
        val out = CutoutComposer.composeTransparent(pixels, alpha)
        assertEquals(argb(255, 255, 0, 0), out[0])
    }

    @Test
    fun `composeTransparent zero alpha makes pixel fully transparent`() {
        val pixels = intArrayOf(argb(255, 10, 20, 30))
        val out = CutoutComposer.composeTransparent(pixels, floatArrayOf(0f))
        // alpha channel 0, rgb preserved
        assertEquals(0, (out[0] ushr 24) and 0xFF)
        assertEquals(10, (out[0] shr 16) and 0xFF)
    }

    @Test
    fun `composeTransparent fractional alpha quantizes to 0_255`() {
        val pixels = intArrayOf(argb(255, 0, 0, 0))
        val out = CutoutComposer.composeTransparent(pixels, floatArrayOf(0.5f))
        val a = (out[0] ushr 24) and 0xFF
        assertEquals(128, a) // 0.5*255 = 127.5 -> +0.5 -> 128
    }
}
