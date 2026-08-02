package com.mamba.picme.domain.aesthetic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NimaScorerTest {

    @Test
    fun preprocessIsNhwcInterleaved() {
        // red(255,0,0) + green(0,255,0)：二者值非对称，可区分 NHWC 交错 vs NCHW 三 plane
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val out = NimaScorer.preprocessPixels(intArrayOf(red, green))
        // NHWC：[p0.R,p0.G,p0.B, p1.R,p1.G,p1.B] = [1,-1,-1, -1,1,-1]
        // 若错成 NCHW plane 会得到 [1,-1,-1,1,-1,-1]（index3/4 不同）
        val expected = floatArrayOf(1f, -1f, -1f, -1f, 1f, -1f)
        assertArrayEquals(expected, out, 1e-5f)
    }

    @Test
    fun preprocessNormalizationRange() {
        // 灰 128 → (128-127.5)/127.5 ≈ +0.0039，接近 0；纯白 255 → +1；纯黑 0 → -1
        val gray = 0xFF808080.toInt()
        val g = NimaScorer.preprocessPixels(intArrayOf(gray))
        assertTrue("gray ~0", kotlin.math.abs(g[0]) < 0.01f)

        val white = 0xFFFFFFFF.toInt()
        val w = NimaScorer.preprocessPixels(intArrayOf(white))
        assertEquals(1f, w[0], 1e-5f)
        assertEquals(1f, w[1], 1e-5f)
        assertEquals(1f, w[2], 1e-5f)

        val black = 0xFF000000.toInt()
        val b = NimaScorer.preprocessPixels(intArrayOf(black))
        assertEquals(-1f, b[0], 1e-5f)
    }

    @Test
    fun expectedScoreUniformIsMidpoint() {
        // 均匀 softmax 10-bin → Σ 0.1·(i+1) = 0.1·55 = 5.5
        assertEquals(5.5f, NimaScorer.expectedScore(List(10) { 0.1f }), 1e-4f)
    }

    @Test
    fun expectedScoreExtremes() {
        // 全质量落在 bin0(rating 1) → 1；bin9(rating 10) → 10
        assertEquals(1f, NimaScorer.expectedScore(listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)), 1e-5f)
        assertEquals(10f, NimaScorer.expectedScore(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)), 1e-5f)
    }

    @Test
    fun expectedScoreStaysInRange() {
        // 任意分布（不必归一）期望分仍在 [1,10]
        val s = NimaScorer.expectedScore(listOf(0.05f, 0.1f, 0.2f, 0.25f, 0.2f, 0.1f, 0.05f, 0.03f, 0.015f, 0.005f))
        assertTrue("in [1,10]: $s", s in 1f..10f)
    }
}
