package com.mamba.picme.features.camera

import androidx.camera.core.CameraSelector
import com.mamba.picme.beauty.api.BeautySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * [QA] 相机算法纯函数单元测试
 *
 * §3 resolveNextBeautySettings — 美颜参数启用/禁用自动联动（CameraScreenActions）
 * §4 nextLensFacing            — 前/后置摄像头切换（参数化）
 * §5 toCameraAspectRatio       — 内部比例常量 → CameraX 比例常量映射（参数化）
 */

class CameraAlgorithmTest {

    // ================================================================
    // §3 resolveNextBeautySettings() 测试
    // ================================================================

    @Test
    fun `resolveNextBeautySettings - only toggle changed returns updated as-is`() {
        val current = BeautySettings(enabled = false, smoothing = 50f)
        val updated = current.copy(enabled = true)
        val result = resolveNextBeautySettings(current, updated)
        assertTrue("Only toggle changed: enabled should be true", result.enabled)
        assertEquals("Smoothing should not change", 50f, result.smoothing, 0.001f)
    }

    @Test
    fun `resolveNextBeautySettings - any effect changed and non-zero auto enables`() {
        val current = BeautySettings(enabled = false, smoothing = 0f)
        val updated = current.copy(smoothing = 50f)
        val result = resolveNextBeautySettings(current, updated)
        assertTrue("Should auto-enable when effect is set", result.enabled)
        assertEquals("Smoothing should be 50", 50f, result.smoothing, 0.001f)
    }

    @Test
    fun `resolveNextBeautySettings - all effects zero auto disables`() {
        val current = BeautySettings(enabled = true, smoothing = 50f)
        val updated = BeautySettings(enabled = true, smoothing = 0f, lipColor = 0f, blush = 0f)
        val result = resolveNextBeautySettings(current, updated)
        assertFalse("Should auto-disable when all effects are zero", result.enabled)
    }

    @Test
    fun `resolveNextBeautySettings - toggle off with effects just disables enabled flag`() {
        val current = BeautySettings(enabled = true, smoothing = 50f)
        val updated = current.copy(enabled = false)
        val result = resolveNextBeautySettings(current, updated)
        assertFalse("Should be disabled", result.enabled)
        assertEquals("Smoothing should be preserved", 50f, result.smoothing, 0.001f)
    }
}

// ================================================================
// §4 nextLensFacing() — 参数化
// ================================================================

@RunWith(Parameterized::class)
class NextLensFacingTest(
    private val testName: String,
    private val initial: Int,
    private val toggles: Int,
    private val expected: Int
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf("from back returns front", CameraSelector.LENS_FACING_BACK, 1, CameraSelector.LENS_FACING_FRONT),
            arrayOf("from front returns back", CameraSelector.LENS_FACING_FRONT, 1, CameraSelector.LENS_FACING_BACK),
            arrayOf("toggled twice returns original", CameraSelector.LENS_FACING_BACK, 2, CameraSelector.LENS_FACING_BACK),
        )
    }

    @Test
    fun `nextLensFacing toggle result`() {
        var result = initial
        repeat(toggles) { result = nextLensFacing(result) }
        assertEquals(testName, expected, result)
    }
}

// ================================================================
// §5 toCameraAspectRatio() — 参数化
// ================================================================

@RunWith(Parameterized::class)
class ToCameraAspectRatioTest(
    private val testName: String,
    private val input: Int,
    private val expected: Int
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf("RATIO_4_3 maps to CameraX RATIO_4_3", AspectRatio.RATIO_4_3, AspectRatio.RATIO_4_3),
            arrayOf("RATIO_16_9 maps to CameraX RATIO_16_9", AspectRatio.RATIO_16_9, AspectRatio.RATIO_16_9),
            arrayOf("RATIO_FULL maps to CameraX RATIO_16_9", AspectRatio.RATIO_FULL, AspectRatio.RATIO_16_9),
            arrayOf("unknown ratio falls back to RATIO_4_3", 999, AspectRatio.RATIO_4_3),
        )
    }

    @Test
    fun `toCameraAspectRatio maps to expected CameraX ratio`() {
        assertEquals(testName, expected, toCameraAspectRatio(input))
    }
}
