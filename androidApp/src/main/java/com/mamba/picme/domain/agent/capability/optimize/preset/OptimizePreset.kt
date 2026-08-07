package com.mamba.picme.domain.agent.capability.optimize.preset

/**
 * AI 一键优化预设配方
 *
 * 每个场景对应一套美颜、滤镜、调节参数。
 */
data class OptimizePreset(
    val scene: String,
    val beauty: BeautyPreset,
    val filter: FilterPreset,
    val adjustment: AdjustmentPreset
)

/**
 * 美颜预设参数
 *
 * 所有数值使用与 [com.mamba.picme.beauty.api.BeautySettings] 一致的单位和范围。
 */
data class BeautyPreset(
    val enabled: Boolean = true,
    val smoothing: Float = 0f,      // 0..100
    val whitening: Float = 0f,      // 0..100
    val slimFace: Float = 0f,       // -50..50
    val bigEyes: Float = 0f,        // 0..100
    val lipColor: Float = 0f,       // 0..100
    val blush: Float = 0f,          // 0..100
    val eyebrow: Float = 0f         // 0..100
)

/**
 * 滤镜预设参数
 *
 * colorFilter / styleFilter 使用字符串名称，在映射时解析为 beauty-api 枚举。
 */
data class FilterPreset(
    val colorFilter: String = "NONE",
    val styleFilter: String = "NONE"
)

/**
 * 调节预设参数
 *
 * 所有数值使用与 [com.mamba.picme.features.editor.AdjustmentRecipe] 一致的单位和范围。
 */
data class AdjustmentPreset(
    val brightness: Float = 0f,     // -100..100
    val exposure: Float = 0f,       // -100..100
    val contrast: Float = 50f,      // 0..200
    val saturation: Float = 100f,   // 0..200
    val temperature: Float = 5000f, // 2000..8000
    val tint: Float = 0f            // -100..100
)
