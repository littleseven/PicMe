package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class U2NetPreprocessorTest {

    @Test
    fun `toNchw normalizes rgb with imagenet mean std in NCHW layout`() {
        // 单像素纯白图 1x1
        val white = intArrayOf(0xFFFFFFFF.toInt())
        val out = U2NetPreprocessor.toNchw(white, size = 1)
        assertEquals(3, out.size)
        // (1.0 - 0.485) / 0.229
        val expectedR = (1f - 0.485f) / 0.229f
        assertEquals(expectedR, out[0], 0.001f)
        // G plane at index 1
        val expectedG = (1f - 0.456f) / 0.224f
        assertEquals(expectedG, out[1], 0.001f)
        val expectedB = (1f - 0.406f) / 0.225f
        assertEquals(expectedB, out[2], 0.001f)
    }

    @Test
    fun `toNchw 320 has expected length`() {
        val pixels = IntArray(320 * 320) { 0xFF000000.toInt() }
        val out = U2NetPreprocessor.toNchw(pixels, size = 320)
        assertEquals(3 * 320 * 320, out.size)
    }
}
