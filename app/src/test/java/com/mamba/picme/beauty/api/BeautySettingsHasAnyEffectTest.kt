package com.mamba.picme.beauty.api

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * [QA] BeautySettings.hasAnyEffect() 参数化测试
 *
 * 每个数据行验证：仅设置某一美颜/调色参数时，hasAnyEffect() 返回 true。
 * color-grade 参数显式清零 lipColor/blush/eyebrow 以隔离被测字段。
 */
@RunWith(Parameterized::class)
class BeautySettingsHasAnyEffectTest(
    private val testName: String,
    private val settings: BeautySettings
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf("smoothing", BeautySettings(smoothing = 1f)),
            arrayOf("whitening", BeautySettings(whitening = 1f)),
            arrayOf("slimFace positive", BeautySettings(slimFace = 1f)),
            arrayOf("slimFace negative", BeautySettings(slimFace = -1f)),
            arrayOf("bigEyes", BeautySettings(bigEyes = 1f)),
            arrayOf("lipColor", BeautySettings(lipColor = 1f)),
            arrayOf("blush", BeautySettings(blush = 1f)),
            arrayOf("eyebrow", BeautySettings(eyebrow = 1f)),
            arrayOf("bodyEnhancement positive", BeautySettings(bodyEnhancement = 1f)),
            arrayOf("bodyEnhancement negative", BeautySettings(bodyEnhancement = -1f)),
            arrayOf("legExtension", BeautySettings(legExtension = 1f)),
            arrayOf("colorFilter", BeautySettings(colorFilter = FilterType.LEICA_CLASSIC, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("styleFilter", BeautySettings(styleFilter = StyleFilter.TOON, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("exposure", BeautySettings(exposure = 1f, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("contrast", BeautySettings(contrast = 60f, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("saturation", BeautySettings(saturation = 90f, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("temperature", BeautySettings(temperature = 5500f, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("tint", BeautySettings(tint = 10f, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("brightness", BeautySettings(brightness = 10f, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("redAdjustment", BeautySettings(redAdjustment = 110f, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("greenAdjustment", BeautySettings(greenAdjustment = 110f, lipColor = 0f, blush = 0f, eyebrow = 0f)),
            arrayOf("blueAdjustment", BeautySettings(blueAdjustment = 110f, lipColor = 0f, blush = 0f, eyebrow = 0f)),
        )
    }

    @Test
    fun `hasAnyEffect returns true when field is set`() {
        assertTrue(testName, settings.hasAnyEffect())
    }
}
