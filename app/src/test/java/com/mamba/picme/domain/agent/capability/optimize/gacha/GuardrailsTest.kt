package com.mamba.picme.domain.agent.capability.optimize.gacha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardrailsTest {

    private fun pixel(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `highlightClipRatio counts near-white pixels`() {
        // 4 个采样点中 1 个纯白（step=1 全采样）
        val px = intArrayOf(
            pixel(255, 255, 255), pixel(100, 100, 100),
            pixel(50, 50, 50), pixel(200, 200, 200)
        )
        assertEquals(0.25f, Guardrails.highlightClipRatio(px, step = 1), 0.001f)
    }

    @Test
    fun `highlightClipRatio returns 0 for empty array`() {
        assertEquals(0f, Guardrails.highlightClipRatio(intArrayOf()), 0.001f)
    }

    @Test
    fun `meanLuminance of pure white is 1 and pure black is 0`() {
        val white = IntArray(16) { pixel(255, 255, 255) }
        val black = IntArray(16) { pixel(0, 0, 0) }
        assertEquals(1f, Guardrails.meanLuminance(white), 0.001f)
        assertEquals(0f, Guardrails.meanLuminance(black), 0.001f)
    }

    @Test
    fun `check rejects candidate exceeding highlight clip limit`() {
        // 全白图：裁剪率 1.0 > 0.05
        val px = IntArray(64) { pixel(255, 255, 255) }
        val reason = Guardrails.check(px, originalMeanLuminance = 1.0f)
        assertNotNull(reason)
        assertTrue(reason!!.startsWith("highlight_clip"))
    }

    @Test
    fun `check rejects candidate with excessive luminance drift`() {
        // 亮灰图（240 未达 250 裁剪阈值，裁剪率 0），原图亮度 0.5，漂移约 88% > 15%
        val px = IntArray(64) { pixel(240, 240, 240) }
        val reason = Guardrails.check(px, originalMeanLuminance = 0.5f)
        assertNotNull(reason)
        assertTrue(reason!!.startsWith("luminance_drift"))
    }

    @Test
    fun `check passes candidate within guardrails`() {
        // 中灰图 vs 原图亮度 0.5：裁剪率 0，漂移约 0.4%（0.502 vs 0.5）
        val px = IntArray(64) { pixel(128, 128, 128) }
        assertNull(Guardrails.check(px, originalMeanLuminance = 0.5f))
    }
}
