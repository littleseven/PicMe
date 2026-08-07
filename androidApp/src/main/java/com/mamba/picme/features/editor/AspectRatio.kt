package com.mamba.picme.features.editor

import com.mamba.picme.R

enum class AspectRatio(val labelRes: Int, val ratio: Float?) {
    FREE(R.string.aspect_ratio_free, null),
    ORIGINAL(R.string.aspect_ratio_original, -1f),
    SQUARE(R.string.aspect_ratio_square, 1f),
    RATIO_4_3(R.string.aspect_ratio_4_3, 4f / 3f),
    RATIO_3_4(R.string.aspect_ratio_3_4, 3f / 4f),
    RATIO_16_9(R.string.aspect_ratio_16_9, 16f / 9f),
    RATIO_9_16(R.string.aspect_ratio_9_16, 9f / 16f)
}
