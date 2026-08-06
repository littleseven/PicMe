package com.mamba.picme.domain.matting

/**
 * 证件照边缘调整参数（参数层）。默认值 = 2026-08 前融合管线内固定行为
 * （sharpen 2.5，无缩扩/羽化），即默认输出与旧版本逐像素一致。
 */
data class EdgeParams(
    val contrast: Float = DEFAULT_CONTRAST,
    val shrinkExpandPx: Int = 0,
    val featherRadiusPx: Int = 0
) {
    companion object {
        const val DEFAULT_CONTRAST = 2.5f
        const val MIN_CONTRAST = 1.0f
        const val MAX_CONTRAST = 4.0f
        const val MAX_SHRINK_EXPAND_PX = 20
        const val MAX_FEATHER_PX = 20
    }
}
