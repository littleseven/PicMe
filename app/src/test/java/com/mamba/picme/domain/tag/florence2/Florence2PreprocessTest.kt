package com.mamba.picme.domain.tag.florence2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验 [buildNormalizeLut] 与 [normalizePixelsToPlanes]：
 * LUT 取值与参考公式 (v/255 - mean)/std 一致；CHW 平面布局与逐像素参考实现一致。
 */
class Florence2PreprocessTest {

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** 参考实现：逐像素浮点除法（即优化前的算法）。 */
    private fun reference(pixels: IntArray): FloatArray {
        val plane = pixels.size
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val px = pixels[i]
            out[i] = (((px shr 16) and 0xFF) / 255f - mean[0]) / std[0]
            out[plane + i] = (((px shr 8) and 0xFF) / 255f - mean[1]) / std[1]
            out[2 * plane + i] = ((px and 0xFF) / 255f - mean[2]) / std[2]
        }
        return out
    }

    private fun luts() = Triple(
        buildNormalizeLut(mean[0], std[0]),
        buildNormalizeLut(mean[1], std[1]),
        buildNormalizeLut(mean[2], std[2])
    )

    @Test
    fun lut_matches_reference_formula_for_all_256_values() {
        for (c in 0 until 3) {
            val lut = buildNormalizeLut(mean[c], std[c])
            assertEquals(256, lut.size)
            for (v in 0 until 256) {
                val expected = (v / 255f - mean[c]) / std[c]
                assertEquals("channel=$c v=$v", expected, lut[v], 1e-6f)
            }
        }
    }

    @Test
    fun normalize_matches_reference_for_sampled_pixels() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), // 纯黑
            0xFFFFFFFF.toInt(), // 纯白
            0xFFFF0000.toInt(), // 纯红
            0xFF00FF00.toInt(), // 纯绿
            0xFF0000FF.toInt(), // 纯蓝
            0xFF7F8081.toInt(), // 中间灰（非对称通道值）
            0xFF123456.toInt(), // 任意色
            0x00A1B2C3.toInt()  // alpha=0（应被忽略，只取 RGB）
        )
        val (rLut, gLut, bLut) = luts()
        val out = FloatArray(3 * pixels.size)
        normalizePixelsToPlanes(pixels, out, rLut, gLut, bLut)

        val expected = reference(pixels)
        for (i in expected.indices) {
            assertEquals("idx=$i", expected[i], out[i], 1e-6f)
        }
    }

    @Test
    fun normalize_writes_chw_planar_layout() {
        // 两个不同颜色的像素，验证 R/G/B 三个平面的偏移布局
        val pixels = intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt())
        val (rLut, gLut, bLut) = luts()
        val out = FloatArray(6)
        normalizePixelsToPlanes(pixels, out, rLut, gLut, bLut)

        assertEquals(rLut[0x10], out[0], 1e-7f)
        assertEquals(rLut[0x40], out[1], 1e-7f)
        assertEquals(gLut[0x20], out[2], 1e-7f)
        assertEquals(gLut[0x50], out[3], 1e-7f)
        assertEquals(bLut[0x30], out[4], 1e-7f)
        assertEquals(bLut[0x60], out[5], 1e-7f)
    }
}
