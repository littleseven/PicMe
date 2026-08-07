package com.mamba.picme.beauty.api

import androidx.annotation.StringRes
import com.mamba.picme.beauty.R

/**
 * StyleFilter 扩展：Android 资源映射
 *
 * 将纯数据枚举 [StyleFilter] 与 Android 字符串资源关联。
 */
val StyleFilter.displayNameRes: Int
    @StringRes
    get() = when (this) {
        StyleFilter.NONE -> R.string.style_filter_none
        StyleFilter.TOON -> R.string.style_filter_toon
        StyleFilter.SKETCH -> R.string.style_filter_sketch
        StyleFilter.POSTERIZE -> R.string.style_filter_posterize
        StyleFilter.EMBOSS -> R.string.style_filter_emboss
        StyleFilter.CROSSHATCH -> R.string.style_filter_crosshatch
    }
