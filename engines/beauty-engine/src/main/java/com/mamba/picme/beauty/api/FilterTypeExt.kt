package com.mamba.picme.beauty.api

import android.graphics.ColorMatrix
import androidx.annotation.StringRes
import com.mamba.picme.beauty.R

/**
 * FilterType 扩展：Android 资源与 ColorMatrix 映射
 *
 * 将纯数据枚举 [FilterType] 与 Android 平台能力关联。
 * 扩展函数保持向后兼容，原有调用方式不变。
 */
val FilterType.displayNameRes: Int
    @StringRes
    get() = when (this) {
        FilterType.NONE -> R.string.filter_none
        FilterType.LEICA_CLASSIC -> R.string.filter_leica_classic
        FilterType.LEICA_VIBRANT -> R.string.filter_leica_vibrant
        FilterType.LEICA_BW -> R.string.filter_leica_bw
        FilterType.FILM_GOLD -> R.string.filter_film_gold
        FilterType.FILM_FUJI -> R.string.filter_film_fuji
        FilterType.VINTAGE -> R.string.filter_vintage
        FilterType.COOL -> R.string.filter_cool
        FilterType.WARM -> R.string.filter_warm
    }

/**
 * 返回 Android 原生的 ColorMatrix（用于预览和拍照后处理）
 */
fun FilterType.toAndroidColorMatrix(): ColorMatrix {
    return when (this) {
        FilterType.NONE -> ColorMatrix()
        FilterType.LEICA_CLASSIC -> ColorMatrix(
            floatArrayOf(
                0.95f, 0f, 0f, 0f, 0f,
                0f, 0.9f, 0f, 0f, 0f,
                0f, 0f, 0.85f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        FilterType.LEICA_VIBRANT -> ColorMatrix().apply {
            setSaturation(1.3f)
        }

        FilterType.LEICA_BW -> ColorMatrix().apply {
            setSaturation(0f)
        }

        FilterType.FILM_GOLD -> ColorMatrix(
            floatArrayOf(
                1.1f, 0.1f, 0f, 0f, 0f,
                0.1f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 0.8f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        FilterType.FILM_FUJI -> ColorMatrix(
            floatArrayOf(
                0.9f, 0f, 0.1f, 0f, 0f,
                0f, 1.1f, 0f, 0f, 0f,
                0.1f, 0f, 1.0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        FilterType.VINTAGE -> ColorMatrix(
            floatArrayOf(
                0.9f, 0f, 0f, 0f, 0f,
                0f, 0.8f, 0f, 0f, 0f,
                0f, 0.5f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        FilterType.COOL -> ColorMatrix(
            floatArrayOf(
                0.8f, 0f, 0f, 0f, 0f,
                0f, 0.9f, 0f, 0f, 0f,
                0f, 0f, 1.2f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        FilterType.WARM -> ColorMatrix(
            floatArrayOf(
                1.15f, 0.05f, 0f, 0f, 0.03f,
                0.02f, 1.05f, 0f, 0f, 0f,
                0f, 0f, 0.85f, 0f, -0.03f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
}
