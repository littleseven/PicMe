package com.mamba.picme.beauty.api

import org.junit.Assert.*
import org.junit.Test

/**
 * [QA] BeautySettings 单元测试
 *
 * 测试目标：验证 hasAnyEffect() 状态判断逻辑与边界行为。
 * hasAnyEffect() returns-true 的逐字段覆盖见 [BeautySettingsHasAnyEffectTest]。
 */
class BeautySettingsTest {

    // ==================== hasAnyEffect() 基线 ====================

    @Test
    fun `hasAnyEffect returns false for default settings`() {
        val settings = BeautySettings()
        assertFalse(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns false when only lipColorIndex is set and other effects are zero`() {
        // lipColorIndex 不影响 hasAnyEffect 结果
        val settings = BeautySettings(
            lipColorIndex = 5,
            lipColor = 0f,
            blush = 0f
        )

        assertFalse(settings.hasAnyEffect())
    }

    // ==================== 边界值测试 ====================

    @Test
    fun `hasAnyEffect with smoothing at boundary values`() {
        assertFalse(BeautySettings(smoothing = 0f, lipColor = 0f, blush = 0f).hasAnyEffect())
        assertTrue(BeautySettings(smoothing = 0.1f, lipColor = 0f, blush = 0f).hasAnyEffect())
        assertTrue(BeautySettings(smoothing = 100f, lipColor = 0f, blush = 0f).hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect with slimFace at boundary values`() {
        assertFalse(BeautySettings(slimFace = 0f, lipColor = 0f, blush = 0f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = 0.1f, lipColor = 0f, blush = 0f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = -0.1f, lipColor = 0f, blush = 0f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = 50f, lipColor = 0f, blush = 0f).hasAnyEffect())
        assertTrue(BeautySettings(slimFace = -50f, lipColor = 0f, blush = 0f).hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect with multiple effects`() {
        val settings = BeautySettings(
            smoothing = 10f,
            whitening = 20f,
            bigEyes = 30f
        )

        assertTrue(settings.hasAnyEffect())
    }

    @Test
    fun `hasAnyEffect returns false when all color grade params are at default`() {
        val settings = BeautySettings(
            lipColor = 0f, blush = 0f,
            colorFilter = FilterType.NONE,
            styleFilter = StyleFilter.NONE,
            exposure = 0f, contrast = 50f, saturation = 100f,
            temperature = 5000f, tint = 0f, brightness = 0f,
            redAdjustment = 100f, greenAdjustment = 100f, blueAdjustment = 100f
        )
        assertFalse(settings.hasAnyEffect())
    }
}
