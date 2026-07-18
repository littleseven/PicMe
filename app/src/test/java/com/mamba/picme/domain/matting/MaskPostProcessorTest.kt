package com.mamba.picme.domain.matting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MaskPostProcessorTest {

    @Test
    fun `binarize above threshold is 1 below is 0`() {
        val probs = floatArrayOf(0.2f, 0.6f, 0.5f, 0.9f)
        val out = MaskPostProcessor.binarize(probs, threshold = 0.5f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f), out, 0.0001f)
    }

    @Test
    fun `upsample 2x2 to 4x4 bilinear interpolates`() {
        // corners: 0 1
        //          0 1   -> right column stays 1, left stays 0
        val alpha = floatArrayOf(0f, 1f, 0f, 1f)
        val out = MaskPostProcessor.upsample(alpha, srcW = 2, srcH = 2, dstW = 4, dstH = 4)
        assertEquals(16, out.size)
        // top-right corner stays 1, top-left stays 0
        assertEquals(1f, out[3], 0.01f)
        assertEquals(0f, out[0], 0.01f)
    }

    @Test
    fun `upsample same size returns copy`() {
        val alpha = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val out = MaskPostProcessor.upsample(alpha, 2, 2, 2, 2)
        assertArrayEquals(alpha, out, 0.0001f)
    }

    @Test
    fun `feather radius 0 returns copy`() {
        val alpha = floatArrayOf(1f, 0f, 0f, 1f)
        val out = MaskPostProcessor.feather(alpha, w = 2, h = 2, radius = 0)
        assertArrayEquals(alpha, out, 0.0001f)
    }

    @Test
    fun `feather smooths hard edge`() {
        // 1x4 strip: 1 1 0 0 ; radius 1 box blur -> middle values between 0 and 1
        val alpha = floatArrayOf(1f, 1f, 0f, 0f)
        val out = MaskPostProcessor.feather(alpha, w = 4, h = 1, radius = 1)
        // index 1 window {1,1,0} avg = 2/3 ; index 2 window {1,0,0} avg = 1/3
        assertEquals(2f / 3f, out[1], 0.01f)
        assertEquals(1f / 3f, out[2], 0.01f)
    }
}
