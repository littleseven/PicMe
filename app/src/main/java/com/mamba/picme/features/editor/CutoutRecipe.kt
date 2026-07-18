package com.mamba.picme.features.editor

import com.mamba.picme.domain.matting.MaskSource

/** 抠图（去背景）配方。null 表示未启用去背景。 */
data class CutoutRecipe(
    val maskSource: MaskSource = MaskSource.U2NETP,
    val threshold: Float = 0.5f,
    val bgMode: BgMode = BgMode.TRANSPARENT,
    val bgColor: Int? = null,
    val feather: Int = 0
) {
    enum class BgMode { TRANSPARENT, COLOR, BLUR }
}
