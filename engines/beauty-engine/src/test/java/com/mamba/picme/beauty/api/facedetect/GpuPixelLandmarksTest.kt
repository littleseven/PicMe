package com.mamba.picme.beauty.api.facedetect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GpuPixelLandmarks 数据转换测试
 *
 * 验证 FloatArray → GpuPixelLandmarks 的转换正确性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpuPixelLandmarksTest {

    // ── valid / extra / partial / single / coords ──────────────

    @Test
    fun fromFloatArray_validInputs_extractsExpectedPointCount() {
        // 标准输入：106 个点 → 提取 106
        assertValidExtraction(FloatArray(106 * 2) { it / 212f }, expectedPoints = 106)
        // 多余输入：150 个点 → 仍只取 106
        assertValidExtraction(FloatArray(150 * 2) { it / 300f }, expectedPoints = 106)
        // 不足输入：50 个点 → 取 50
        assertValidExtraction(FloatArray(50 * 2) { it / 100f }, expectedPoints = 50)
        // 最小输入：1 个点 → 取 1
        assertValidExtraction(floatArrayOf(0.5f, 0.6f), expectedPoints = 1)
    }

    @Test
    fun fromFloatArray_preservesCoordinateValues() {
        val floats = FloatArray(106 * 2)
        for (i in 0 until 106) {
            floats[i * 2] = i * 0.001f
            floats[i * 2 + 1] = i * 0.002f
        }

        val result = GpuPixelLandmarks.fromFloatArray(floats)

        assertTrue("hasFace should be true", result.hasFace)
        for (i in 0 until 106) {
            assertEquals("Point $i x", i * 0.001f, result.rawPoints[i * 2], 0.0001f)
            assertEquals("Point $i y", i * 0.002f, result.rawPoints[i * 2 + 1], 0.0001f)
        }
    }

    @Test
    fun fromFloatArray_zeroCoordinates_preservedAsZero() {
        val result = GpuPixelLandmarks.fromFloatArray(FloatArray(106 * 2) { 0f })

        assertTrue("hasFace should be true", result.hasFace)
        for (i in 0 until 106) {
            assertEquals("Point $i x should be 0", 0f, result.rawPoints[i * 2], 0.0001f)
            assertEquals("Point $i y should be 0", 0f, result.rawPoints[i * 2 + 1], 0.0001f)
        }
    }

    // ── null / empty ───────────────────────────────────────────

    @Test
    fun fromFloatArray_nullAndEmptyInput_returnsEmptyResult() {
        assertEmptyResult(GpuPixelLandmarks.fromFloatArray(null))
        assertEmptyResult(GpuPixelLandmarks.fromFloatArray(FloatArray(0)))
    }

    // ── odd-length input ───────────────────────────────────────

    @Test
    fun fromFloatArray_oddLength_ignoresLastFloat() {
        // 213 floats = 106.5 points → should extract exactly 106 complete points
        val result = GpuPixelLandmarks.fromFloatArray(FloatArray(213) { it / 213f })

        assertTrue("hasFace should be true", result.hasFace)
        assertEquals("Should extract 106 complete points", 106, result.rawPoints.size / 2)
    }

    // ── helpers ────────────────────────────────────────────────

    private fun assertValidExtraction(floats: FloatArray, expectedPoints: Int) {
        val result = GpuPixelLandmarks.fromFloatArray(floats)
        assertTrue("hasFace should be true", result.hasFace)
        assertEquals("Should extract $expectedPoints points", expectedPoints, result.rawPoints.size / 2)
    }

    private fun assertEmptyResult(result: GpuPixelLandmarks) {
        assertFalse("hasFace should be false", result.hasFace)
        assertEquals("points should be empty", 0, result.points.size)
    }
}
