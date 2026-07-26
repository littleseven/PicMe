package com.mamba.picme.domain.tag.florence2

/**
 * Florence-2 图像预处理的纯函数部分（无 Android 依赖，JVM 可单测）：
 * ARGB_8888 像素 → ImageNet 归一化的 CHW float planes。
 *
 * 性能关键：归一化用 256 项 LUT 查表替代逐像素浮点除法。
 * (v/255 - mean)/std 对 8bit 通道只有 256 种取值，预计算一次即可把
 * 768×768×3 ≈ 177 万次浮点除法降为等次数组查表。
 */

/** 单通道 256 项归一化查找表：lut[v] = (v/255f - mean) / std。 */
internal fun buildNormalizeLut(mean: Float, std: Float): FloatArray {
    val invStd = 1f / std
    return FloatArray(256) { v -> (v / 255f - mean) * invStd }
}

/**
 * ARGB int 像素 → CHW float planes（R/G/B 平面顺序），经 LUT 归一化。
 * alpha 通道忽略。
 *
 * @param pixels width×height 个 ARGB_8888 像素
 * @param out 输出缓冲，长度 ≥ 3×pixels.size，布局 [R plane | G plane | B plane]
 */
internal fun normalizePixelsToPlanes(
    pixels: IntArray,
    out: FloatArray,
    rLut: FloatArray,
    gLut: FloatArray,
    bLut: FloatArray
) {
    val plane = pixels.size
    for (i in 0 until plane) {
        val px = pixels[i]
        out[i] = rLut[(px shr 16) and 0xFF]
        out[plane + i] = gLut[(px shr 8) and 0xFF]
        out[2 * plane + i] = bLut[px and 0xFF]
    }
}
