package com.mamba.picme.beauty.api

/**
 * 风格特效滤镜枚举
 *
 * 通过 GPU Shader 实时渲染风格特效。
 * 与 [FilterType]（ColorMatrix 色调滤镜）互为补充：色调滤镜先生效，风格特效后叠加。
 *
 * [NONE] 表示不应用任何风格特效。
 */
enum class StyleFilter {
    NONE,
    TOON,
    SKETCH,
    POSTERIZE,
    EMBOSS,
    CROSSHATCH
}