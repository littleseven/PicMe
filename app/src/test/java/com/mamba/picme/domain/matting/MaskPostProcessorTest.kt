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

    @Test
    fun `sharpenAlpha narrows soft edge and keeps 0 and 1 ends`() {
        // contrast=2 关于 0.5 拉伸：0->0, 0.25->0, 0.5->0.5, 0.75->1, 1->1
        val alpha = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val out = MaskPostProcessor.sharpenAlpha(alpha, contrast = 2f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.5f, 1f, 1f), out, 1e-5f)
    }

    @Test
    fun `sharpenAlpha contrast 1 returns copy`() {
        val alpha = floatArrayOf(0.2f, 0.8f)
        val out = MaskPostProcessor.sharpenAlpha(alpha, contrast = 1f)
        assertArrayEquals(alpha, out, 1e-6f)
    }

    @Test
    fun `erode radius 0 returns copy`() {
        val alpha = floatArrayOf(0f, 1f, 1f, 0f)
        val out = MaskPostProcessor.erode(alpha, w = 4, h = 1, radius = 0)
        assertArrayEquals(alpha, out, 0.0001f)
    }

    @Test
    fun `dilate radius 0 returns copy`() {
        val alpha = floatArrayOf(0f, 1f, 0f, 1f)
        val out = MaskPostProcessor.dilate(alpha, w = 4, h = 1, radius = 0)
        assertArrayEquals(alpha, out, 0.0001f)
    }

    @Test
    fun `erode shrinks foreground strip`() {
        // 1x5: 0 1 1 1 0 ; radius 1 min-filter -> 0 0 1 0 0
        val alpha = floatArrayOf(0f, 1f, 1f, 1f, 0f)
        val out = MaskPostProcessor.erode(alpha, w = 5, h = 1, radius = 1)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f), out, 0.0001f)
    }

    @Test
    fun `dilate grows foreground strip`() {
        // 1x5: 0 0 1 0 0 ; radius 1 max-filter -> 0 1 1 1 0
        val alpha = floatArrayOf(0f, 0f, 1f, 0f, 0f)
        val out = MaskPostProcessor.dilate(alpha, w = 5, h = 1, radius = 1)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f, 0f), out, 0.0001f)
    }

    @Test
    fun `dilate at image edge clamps window`() {
        // 1x3: 1 0 0 ; radius 1 -> 1 1 0（左边缘不外溢）
        val alpha = floatArrayOf(1f, 0f, 0f)
        val out = MaskPostProcessor.dilate(alpha, w = 3, h = 1, radius = 1)
        assertArrayEquals(floatArrayOf(1f, 1f, 0f), out, 0.0001f)
    }

    @Test
    fun `erode never increases foreground area on 2d mask`() {
        // 3x3 全 1，中心一个 0 空洞；radius 1 的 3x3 窗口对每个像素都覆盖中心 0，腐蚀后全 0
        val alpha = floatArrayOf(
            1f, 1f, 1f,
            1f, 0f, 1f,
            1f, 1f, 1f
        )
        val out = MaskPostProcessor.erode(alpha, w = 3, h = 3, radius = 1)
        assertArrayEquals(FloatArray(9) { 0f }, out, 0.0001f)
    }

    @Test
    fun `adjustEdges with default params equals sharpen 2_5 only`() {
        val alpha = floatArrayOf(0f, 0.3f, 0.7f, 1f)
        val out = MaskPostProcessor.adjustEdges(alpha, w = 4, h = 1, params = EdgeParams())
        val expected = MaskPostProcessor.sharpenAlpha(alpha, contrast = EdgeParams.DEFAULT_CONTRAST)
        assertArrayEquals(expected, out, 1e-5f)
    }

    @Test
    fun `adjustEdges positive shrinkExpand dilates`() {
        val alpha = floatArrayOf(0f, 0f, 1f, 0f, 0f)
        val out = MaskPostProcessor.adjustEdges(
            alpha, w = 5, h = 1,
            params = EdgeParams(contrast = 1f, shrinkExpandPx = 1)
        )
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f, 0f), out, 0.0001f)
    }

    @Test
    fun `adjustEdges negative shrinkExpand erodes`() {
        val alpha = floatArrayOf(0f, 1f, 1f, 1f, 0f)
        val out = MaskPostProcessor.adjustEdges(
            alpha, w = 5, h = 1,
            params = EdgeParams(contrast = 1f, shrinkExpandPx = -1)
        )
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f), out, 0.0001f)
    }

    @Test
    fun `adjustEdges applies contrast when morph and feather disabled`() {
        // 只验证对比度透传（0.4 关于 0.5 压到 0.3）+ morph/feather 默认值短路（0.3 不被当作前景改变）
        val alpha = floatArrayOf(0.4f, 0.4f, 0.4f)
        val out = MaskPostProcessor.adjustEdges(
            alpha, w = 3, h = 1,
            params = EdgeParams(contrast = 2f, shrinkExpandPx = 0, featherRadiusPx = 0)
        )
        assertArrayEquals(floatArrayOf(0.3f, 0.3f, 0.3f), out, 1e-5f)
    }

    @Test
    fun `adjustEdges with featherRadius delegates to feather`() {
        // 1x4 硬边 1 1 0 0，contrast=1 短路，feather radius 1 -> 过渡带被软化
        val alpha = floatArrayOf(1f, 1f, 0f, 0f)
        val out = MaskPostProcessor.adjustEdges(
            alpha, w = 4, h = 1,
            params = EdgeParams(contrast = 1f, featherRadiusPx = 1)
        )
        assertEquals(2f / 3f, out[1], 0.01f)
        assertEquals(1f / 3f, out[2], 0.01f)
    }
}
