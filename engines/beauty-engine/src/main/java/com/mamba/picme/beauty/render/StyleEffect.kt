package com.mamba.picme.beauty.render

/**
 * 风格特效枚举
 *
 * GPUPixel 移植到大美丽的风格特效类型。
 * 每种风格对应一个独立 Shader，互斥使用（一次只激活一种）。
 */
enum class StyleEffect {
    NONE,
    TOON,       // 卡通：Sobel 边缘 + 颜色量化
    SKETCH,     // 素描：灰度 + Sobel 边缘 + 反相
    POSTERIZE,  // 色块：颜色层级量化
    EMBOSS,     // 浮雕：3×3 卷积核
    CROSSHATCH  // 交叉线：基于亮度绘制交叉线图案
}
