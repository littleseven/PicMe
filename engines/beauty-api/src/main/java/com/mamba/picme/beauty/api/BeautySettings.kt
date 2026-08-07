package com.mamba.picme.beauty.api

/**
 * 美颜设置数据类
 * 包含所有美颜功能的参数配置
 */
data class BeautySettings(
    val enabled: Boolean = false,
    val smoothing: Float = 0f,
    val whitening: Float = 0f,
    val slimFace: Float = 0f,
    val bigEyes: Float = 0f,
    val lipColor: Float = DEFAULT_LIP_COLOR,
    val lipColorIndex: Int = 0,
    val blush: Float = DEFAULT_BLUSH,
    val blushColorFamily: Int = 0,
    val eyebrow: Float = DEFAULT_EYEBROW,
    val bodyEnhancement: Float = 0f,
    val legExtension: Float = 0f,
    val exposure: Float = 0f,
    val contrast: Float = 50f,
    val saturation: Float = 100f,
    val temperature: Float = 5000f,
    val tint: Float = 0f,
    val brightness: Float = 0f,
    val redAdjustment: Float = 100f,
    val greenAdjustment: Float = 100f,
    val blueAdjustment: Float = 100f,
    val colorFilter: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter = StyleFilter.NONE
) {
    fun hasAnyEffect(): Boolean {
        return smoothing > 0 || whitening > 0 || slimFace != 0f || bigEyes > 0 ||
            lipColor > 0 || blush > 0 || eyebrow > 0 ||
            bodyEnhancement != 0f || legExtension > 0 ||
            colorFilter != FilterType.NONE || styleFilter != StyleFilter.NONE ||
            exposure != 0f || contrast != 50f || saturation != 100f ||
            temperature != 5000f || tint != 0f || brightness != 0f ||
            redAdjustment != 100f || greenAdjustment != 100f || blueAdjustment != 100f
    }

    companion object {
        const val DEFAULT_LIP_COLOR: Float = 0f
        const val DEFAULT_BLUSH: Float = 0f
        const val DEFAULT_EYEBROW: Float = 0f
    }
}
