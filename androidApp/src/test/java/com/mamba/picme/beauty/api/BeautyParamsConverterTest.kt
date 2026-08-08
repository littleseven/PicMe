package com.mamba.picme.beauty.api

import com.mamba.picme.beauty.render.StyleEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QA] BeautySettings → BeautyParams 转换单元测试
 *
 * 测试目标：验证 toBeautyParams() 的转换逻辑，特别是：
 * 1. enabled = false 时是否走 EMPTY.copy 分支（人脸参数被丢弃）
 * 2. enabled = true 且 hasAnyEffect() = true 时是否返回完整 BeautyParams
 * 3. 调色参数（exposure/contrast/saturation 等）的正确映射
 * 4. colorFilter / styleFilter 的转换
 */
class BeautyParamsConverterTest {

    // ================================================================
    // enabled 开关分支测试（关键：之前 bug 的根因）
    // ================================================================

    @Test
    fun `toBeautyParams when enabled is false returns EMPTY and discards face effects`() {
        val settings = BeautySettings(
            enabled = false,
            smoothing = 50f // 非零，但 enabled=false 应该被忽略
        )

        val params = settings.toBeautyParams()

        // enabled=false 时走 EMPTY.copy 分支，人脸参数应为默认值 0
        assertFalse("params.enabled should be false when settings.enabled is false", params.enabled)
        assertEquals("smoothing should be 0 when enabled is false", 0f, params.smoothing, 0.001f)
        assertEquals("whitening should be 0 when enabled is false", 0f, params.whitening, 0.001f)
        assertEquals("bigEyes should be 0 when enabled is false", 0f, params.bigEyes, 0.001f)
    }

    @Test
    fun `toBeautyParams when enabled is true returns full params`() {
        val settings = BeautySettings(
            enabled = true,
            smoothing = 50f,
            whitening = 30f,
            bigEyes = 40f,
            slimFace = 20f,
            lipColor = 60f,
            lipColorIndex = 5,
            blush = 25f,
            blushColorFamily = 1
        )

        val params = settings.toBeautyParams()

        assertTrue("params.enabled should be true", params.enabled)
        assertEquals(0.5f, params.smoothing, 0.001f) // 50/100
        assertEquals(0.3f, params.whitening, 0.001f) // 30/100
        assertEquals(0.4f, params.bigEyes, 0.001f)   // 40/100
        assertEquals(-0.54f, params.slimFace, 0.001f) // 20/50*1.35，UI 正值瘦脸对应引擎负值
        assertEquals(0.6f, params.lipColor, 0.001f)  // 60/100
        assertEquals(5, params.lipColorIndex)
        assertEquals(0.25f, params.blush, 0.001f)    // 25/100
        assertEquals(1, params.blushColorFamily)
    }

    @Test
    fun `toBeautyParams when enabled is true but no effects returns EMPTY`() {
        val settings = BeautySettings(
            enabled = true,
            lipColor = 0f, blush = 0f // 覆盖默认值，使 hasAnyEffect() = false
        )

        val params = settings.toBeautyParams()

        // enabled=true 但 hasAnyEffect()=false，仍然走 EMPTY.copy 分支
        assertFalse("params.enabled should be false when no effects", params.enabled)
    }

    // ================================================================
    // 调色参数映射测试
    // ================================================================

    @Test
    fun `toBeautyParams exposure is mapped correctly`() {
        val settings = BeautySettings(exposure = 50f, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(1.0f, params.exposure, 0.001f) // 50/50 = 1.0 EV
    }

    @Test
    fun `toBeautyParams contrast default maps to shader 1_0`() {
        val settings = BeautySettings(contrast = 50f, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(1.0f, params.contrast, 0.001f) // 50/50 = 1.0
    }

    @Test
    fun `toBeautyParams contrast 100 maps to shader 2_0`() {
        val settings = BeautySettings(contrast = 100f, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(2.0f, params.contrast, 0.001f) // 100/50 = 2.0
    }

    @Test
    fun `toBeautyParams saturation default maps to shader 1_0`() {
        val settings = BeautySettings(saturation = 100f, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(1.0f, params.saturation, 0.001f) // 100/100 = 1.0
    }

    @Test
    fun `toBeautyParams temperature default 5000 maps to shader 0`() {
        val settings = BeautySettings(temperature = 5000f, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(0f, params.temperature, 0.001f) // (5000-5000)/3000 = 0
    }

    @Test
    fun `toBeautyParams temperature 8000 maps to shader 1_0`() {
        val settings = BeautySettings(temperature = 8000f, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(1.0f, params.temperature, 0.001f) // (8000-5000)/3000 = 1.0
    }

    @Test
    fun `toBeautyParams brightness default maps to shader 0`() {
        val settings = BeautySettings(brightness = 0f, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(0f, params.brightness, 0.001f) // 0/100 = 0
    }

    @Test
    fun `toBeautyParams redAdjustment default maps to shader 1_0`() {
        val settings = BeautySettings(redAdjustment = 100f, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(1.0f, params.redAdjustment, 0.001f) // 100/100 = 1.0
    }

    // ================================================================
    // 滤镜/风格特效测试
    // ================================================================

    @Test
    fun `toBeautyParams styleFilter NONE maps to StyleEffect NONE`() {
        val settings = BeautySettings(styleFilter = StyleFilter.NONE, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(StyleEffect.NONE, params.styleEffect)
    }

    @Test
    fun `toBeautyParams styleFilter TOON maps to StyleEffect TOON`() {
        val settings = BeautySettings(styleFilter = StyleFilter.TOON, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(StyleEffect.TOON, params.styleEffect)
    }

    @Test
    fun `toBeautyParams styleFilter SKETCH maps to StyleEffect SKETCH`() {
        val settings = BeautySettings(styleFilter = StyleFilter.SKETCH, enabled = true)
        val params = settings.toBeautyParams()
        assertEquals(StyleEffect.SKETCH, params.styleEffect)
    }

    // ================================================================
    // 边界值/降级测试
    // ================================================================

    @Test
    fun `toBeautyParams default settings returns EMPTY`() {
        val settings = BeautySettings()
        val params = settings.toBeautyParams()

        // 默认 enabled=false，走 EMPTY.copy 分支
        assertFalse(params.enabled)
        assertEquals(0f, params.smoothing, 0.001f)
        assertNull(params.colorMatrix)
        assertEquals(StyleEffect.NONE, params.styleEffect)
    }

    @Test
    fun `toBeautyParams slimFace out of range is coerced`() {
        val settings = BeautySettings(
            enabled = true,
            slimFace = 100f // 超出范围
        )
        val params = settings.toBeautyParams()
        assertEquals(-1.0f, params.slimFace, 0.001f) // 被限制到 -1.0（UI 正值瘦脸对应引擎负值）
    }

    @Test
    fun `toBeautyParams lipColorIndex out of range is coerced`() {
        val settings = BeautySettings(
            enabled = true,
            lipColor = 50f, // 必须 > 0 才能使 hasAnyEffect() = true，进入主分支
            lipColorIndex = 20 // 超出 0-11
        )
        val params = settings.toBeautyParams()
        assertEquals(11, params.lipColorIndex) // 被限制到 11
    }
}
