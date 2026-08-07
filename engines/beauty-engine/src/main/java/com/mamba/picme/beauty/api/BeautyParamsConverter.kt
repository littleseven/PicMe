package com.mamba.picme.beauty.api

import com.mamba.picme.beauty.render.StyleEffect

/**
 * BeautySettings -> BeautyParams 转换扩展
 *
 * 将领域模型转换为引擎所需的参数格式。
 * 保留所有比例映射逻辑，确保美颜效果与重构前一致。
 *
 * colorFilter 字段会被转换为 4x5 colorMatrix（FloatArray，行主序），
 * 传入大美丽引擎的 OpenGL Shader 进行实时色调变换。
 * FilterType.NONE 时 colorMatrix 为 null，Shader 直通输出。
 */
fun BeautySettings.toBeautyParams(): BeautyParams {
    // 色调滤镜矩阵：取 Android ColorMatrix 的底层 float 数组（20个元素，4行×5列）
    val matrix: FloatArray? = colorFilter
        .takeIf { it != FilterType.NONE }
        ?.toAndroidColorMatrix()
        ?.array

    // 专业调色参数映射（大美丽引擎路径）
    // 对比度: UI 0-200，50=原始 → Shader 0.0-4.0，1.0=原始 (÷50)
    val shaderContrast = (contrast / 50f).coerceIn(0f, 4f)
    // 饱和度: UI 0-200，100=原始 → Shader 0.0-2.0，1.0=原始 (÷100)
    val shaderSaturation = (saturation / 100f).coerceIn(0f, 2f)
    // 色温: UI 2000-8000K，5000=原始 → Shader -1.0~+1.0
    val shaderTemperature = ((temperature - 5000f) / 3000f).coerceIn(-1f, 1f)
    // 色调: UI -100~+100，0=原始 → Shader -1.0~+1.0 (÷100)
    val shaderTint = (tint / 100f).coerceIn(-1f, 1f)
    // 亮度: UI -100~+100，0=原始 → Shader -1.0~+1.0 (÷100)
    val shaderBrightness = (brightness / 100f).coerceIn(-1f, 1f)
    // 曝光: UI -100~+100，0=原始 → Shader EV -2.0~+2.0 (÷50)，与 Apple Photos 一致
    val shaderExposure = (exposure / 50f).coerceIn(-2f, 2f)
    // RGB 通道: UI 0-200，100=原始 → Shader 0.0-2.0，1.0=原始 (÷100)
    val shaderRed = (redAdjustment / 100f).coerceIn(0f, 2f)
    val shaderGreen = (greenAdjustment / 100f).coerceIn(0f, 2f)
    val shaderBlue = (blueAdjustment / 100f).coerceIn(0f, 2f)

    // 风格特效映射（大美丽引擎路径）
    val styleEffect = styleFilter.toStyleEffect()

    if (!enabled || !hasAnyEffect()) {
        // 美颜关闭时仍需携带色调矩阵、调色参数和风格特效（滤镜/调色/风格独立于美颜开关）
        return BeautyParams.EMPTY.copy(
            colorMatrix = matrix,
            exposure = shaderExposure,
            contrast = shaderContrast,
            saturation = shaderSaturation,
            temperature = shaderTemperature,
            tint = shaderTint,
            brightness = shaderBrightness,
            redAdjustment = shaderRed,
            greenAdjustment = shaderGreen,
            blueAdjustment = shaderBlue,
            styleEffect = styleEffect
        )
    }
    return BeautyParams(
        enabled = true,
        smoothing = (smoothing / 100f).coerceIn(0f, 1f),
        whitening = (whitening / 100f).coerceIn(0f, 1f),
        bigEyes = (bigEyes / 100f).coerceIn(0f, 1f),
        // UI 层面 slimFace 正值表示瘦脸；引擎层面负值表示瘦脸，因此转换时取反
        slimFace = -(slimFace / 50f * 1.35f).coerceIn(-1f, 1f),
        lipColor = (lipColor / 100f).coerceIn(0f, 1f),
        lipColorIndex = lipColorIndex.coerceIn(0, 11),
        blush = (blush / 100f).coerceIn(0f, 1f),
        blushColorFamily = blushColorFamily.coerceIn(0, 2),
        exposure = shaderExposure,
        contrast = shaderContrast,
        saturation = shaderSaturation,
        temperature = shaderTemperature,
        tint = shaderTint,
        brightness = shaderBrightness,
        redAdjustment = shaderRed,
        greenAdjustment = shaderGreen,
        blueAdjustment = shaderBlue,
        colorMatrix = matrix,
        styleEffect = styleEffect
    )
}

/**
 * StyleFilter（UI 枚举）→ StyleEffect（引擎内部枚举）映射
 */
private fun StyleFilter.toStyleEffect(): StyleEffect {
    return when (this) {
        StyleFilter.NONE -> StyleEffect.NONE
        StyleFilter.TOON -> StyleEffect.TOON
        StyleFilter.SKETCH -> StyleEffect.SKETCH
        StyleFilter.POSTERIZE -> StyleEffect.POSTERIZE
        StyleFilter.EMBOSS -> StyleEffect.EMBOSS
        StyleFilter.CROSSHATCH -> StyleEffect.CROSSHATCH
    }
}
