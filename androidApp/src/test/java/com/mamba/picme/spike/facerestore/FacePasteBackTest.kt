package com.mamba.picme.spike.facerestore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纯数学单测：验证 [FacePasteBack] 贴回合成的正确性。
 *
 * 核心验证：恒等修复 + 全 1 alpha → 贴回后原图无变化（数学上无接缝）。
 * 部分半透明 → 精确等于 `fg*a + bg*(1-a)`。
 */
class FacePasteBackTest {

    private val size = FaceAlign512.SIZE

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    // ── invertAffine3x3 ──

    @Test
    fun `invertAffine3x3 of identity is identity`() {
        val inv = FacePasteBack.invertAffine3x3(identity)!!
        for (i in 0 until 9) {
            assertEquals(identity[i], inv[i], 1e-5f)
        }
    }

    @Test
    fun `invertAffine3x3 round-trip recovers original transform`() {
        val m = floatArrayOf(2f, 0.5f, 10f, -0.3f, 1.5f, 20f, 0f, 0f, 1f)
        val inv = FacePasteBack.invertAffine3x3(m)
        assertNotNull(inv)
        // m * inv 应为单位矩阵（仅验证左上 2×2 + 平移列）
        val matrix = m
        val inverse = inv!!
        val r0 = matrix[0] * inverse[0] + matrix[1] * inverse[3]
        val r1 = matrix[0] * inverse[1] + matrix[1] * inverse[4]
        val r2 = matrix[0] * inverse[2] + matrix[1] * inverse[5] + matrix[2]
        val r3 = matrix[3] * inverse[0] + matrix[4] * inverse[3]
        val r4 = matrix[3] * inverse[1] + matrix[4] * inverse[4]
        val r5 = matrix[3] * inverse[2] + matrix[4] * inverse[5] + matrix[5]
        assertEquals(1f, r0, 1e-4f)
        assertEquals(0f, r1, 1e-4f)
        assertEquals(0f, r2, 1e-4f)
        assertEquals(0f, r3, 1e-4f)
        assertEquals(1f, r4, 1e-4f)
        assertEquals(0f, r5, 1e-4f)
    }

    @Test
    fun `invertAffine3x3 returns null for singular matrix`() {
        // rank-1 行 → det=0
        val singular = floatArrayOf(2f, 4f, 0f, 1f, 2f, 0f, 0f, 0f, 1f)
        assertNull(FacePasteBack.invertAffine3x3(singular))
    }

    // ── ellipseAlphaMask ──

    @Test
    fun `ellipseAlphaMask center is inside and corner is outside`() {
        val w = 10
        val h = 10
        val mask = FacePasteBack.ellipseAlphaMask(w, h, centerX = 5f, centerY = 5f, radiusX = 3f, radiusY = 3f)
        // center pixel (5,5): pixel-center (5.5,5.5), dx=0.5 dy=0.5 → d ≈ 0.056 ≤ 1
        assertEquals(1f, mask[5 * w + 5], 0f)
        // corner pixel (0,0): pixel-center (0.5,0.5), dx=-4.5 dy=-4.5 → d=4.5 > 1
        assertEquals(0f, mask[0], 0f)
    }

    // ── pasteBack: the crux ──

    @Test
    fun `zero alpha leaves original pixel unchanged`() {
        val origPixel = argb(255, 100, 50, 25)
        val restoredColor = argb(255, 200, 200, 200)
        val restored = IntArray(size * size) { restoredColor }
        val alpha = FloatArray(size * size) { 0f }
        val out = FacePasteBack.pasteBack(intArrayOf(origPixel), 1, 1, restored, identity, alpha)
        assertEquals(origPixel, out[0])
    }

    @Test
    fun `full alpha with identity restore yields restored pixel`() {
        // 恒等修复：restored 填入与 orig 相同的像素 + alpha=1 → 结果 == orig（无可见痕迹）
        val origPixel = argb(255, 100, 50, 25)
        val restored = IntArray(size * size) { origPixel }
        val alpha = FloatArray(size * size) { 1f }
        val out = FacePasteBack.pasteBack(intArrayOf(origPixel), 1, 1, restored, identity, alpha)
        assertEquals(origPixel, out[0])
    }

    @Test
    fun `half alpha blends foreground and background exactly`() {
        // 精确验证 fg*a + bg*(1-a)：R = 200*0.5 + 0*0.5 = 100
        val origPixel = argb(255, 0, 0, 0)
        val restoredPixel = argb(255, 200, 0, 0)
        val restored = IntArray(size * size) { restoredPixel }
        val alpha = FloatArray(size * size) { 0.5f }
        val out = FacePasteBack.pasteBack(intArrayOf(origPixel), 1, 1, restored, identity, alpha)
        val r = (out[0] shr 16) and 0xFF
        assertEquals(100, r)
    }

    @Test
    fun `identity restore full alpha recovers entire small image without seams`() {
        // spike 核心验证：恒等修复 + 全 1 alpha → 整个小图贴回后逐像素等于原图（无接缝）
        val origW = 4
        val origH = 4
        val origPixels = IntArray(origW * origH) { i -> argb(255, i * 10, 255 - i * 10, 128) }

        // 恒等修复：在 512 对齐空间的 (0..3, 0..3) 位置放入与原图一致的像素
        val restored = IntArray(size * size) { argb(255, 0, 0, 0) }
        for (oy in 0 until origH) {
            for (ox in 0 until origW) {
                restored[oy * size + ox] = origPixels[oy * origW + ox]
            }
        }
        val alpha = FloatArray(size * size) { 1f }

        val out = FacePasteBack.pasteBack(origPixels, origW, origH, restored, identity, alpha)
        for (i in origPixels.indices) {
            assertEquals("pixel $i should be unchanged (seamless)", origPixels[i], out[i])
        }
    }

    @Test
    fun `singular inverse matrix returns original unchanged`() {
        val origPixels = intArrayOf(argb(255, 10, 20, 30))
        val restored = IntArray(size * size) { argb(255, 200, 200, 200) }
        val alpha = FloatArray(size * size) { 1f }
        val singular = floatArrayOf(2f, 4f, 0f, 1f, 2f, 0f, 0f, 0f, 1f)
        val out = FacePasteBack.pasteBack(origPixels, 1, 1, restored, singular, alpha)
        assertEquals(origPixels[0], out[0])
    }

    @Test
    fun `scaled forward matrix maps original region into 512 and back correctly`() {
        // 非单位变换：forward = scale 2 + translate (1,1)
        // 原图 (ox,oy) → 512 (2*ox+1, 2*oy+1)
        // inverse: 512 (u,v) → 原图 ((u-1)/2, (v-1)/2)
        val origW = 2
        val origH = 2
        val origPixels = intArrayOf(
            argb(255, 10, 10, 10), argb(255, 20, 20, 20),
            argb(255, 30, 30, 30), argb(255, 40, 40, 40)
        )

        // inverse maps 512→orig: u'=(u-1)/2, v'=(v-1)/2
        // [0.5, 0, -0.5, 0, 0.5, -0.5, 0, 0, 1]
        val inverse = floatArrayOf(0.5f, 0f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0f, 1f)

        // 恒等修复：把与 orig 对应的 512 像素放好
        // orig (0,0) → 512 (1,1); orig (1,0) → 512 (3,1); orig (0,1) → 512 (1,3); orig (1,1) → 512 (3,3)
        val restored = IntArray(size * size) { argb(255, 0, 0, 0) }
        restored[1 * size + 1] = origPixels[0] // (0,0)
        restored[1 * size + 3] = origPixels[1] // (1,0)
        restored[3 * size + 1] = origPixels[2] // (0,1)
        restored[3 * size + 3] = origPixels[3] // (1,1)

        val alpha = FloatArray(size * size) { 1f }
        val out = FacePasteBack.pasteBack(origPixels, origW, origH, restored, inverse, alpha)
        for (i in origPixels.indices) {
            assertEquals("pixel $i", origPixels[i], out[i])
        }
    }
}
