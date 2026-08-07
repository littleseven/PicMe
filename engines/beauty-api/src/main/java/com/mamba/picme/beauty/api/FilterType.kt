package com.mamba.picme.beauty.api

/**
 * 色调滤镜枚举
 *
 * 通过 ColorMatrix 实现实时色调变换。
 * 与 [com.mamba.picme.beauty.api.StyleFilter]（GPU Shader 风格特效）互为补充：色调滤镜先生效，风格特效后叠加。
 *
 * [NONE] 表示不应用任何色调滤镜。
 */
enum class FilterType {
    NONE,
    LEICA_CLASSIC,
    LEICA_VIBRANT,
    LEICA_BW,
    FILM_GOLD,
    FILM_FUJI,
    VINTAGE,
    COOL,
    WARM
}
