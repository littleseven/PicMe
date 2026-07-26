package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** u2netp 输入预处理：320×320 RGB → ImageNet 归一化 NCHW。核心 [toNchw] 基于数组，可 JVM 单测。 */
object U2NetPreprocessor {
    const val INPUT_SIZE = 320
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    private const val CHANNEL_MASK = 0xFF

    /** pixels：ARGB IntArray，长度 = size*size。返回 NCHW [3*size*size]。 */
    fun toNchw(pixels: IntArray, size: Int = INPUT_SIZE): FloatArray {
        val plane = size * size
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and CHANNEL_MASK) / 255f
            val g = ((p shr 8) and CHANNEL_MASK) / 255f
            val b = (p and CHANNEL_MASK) / 255f
            out[i] = (r - MEAN[0]) / STD[0]
            out[plane + i] = (g - MEAN[1]) / STD[1]
            out[2 * plane + i] = (b - MEAN[2]) / STD[2]
        }
        return out
    }

    /** 把 Bitmap 缩放到 size×size（接受轻微长宽比失真，掩码可整体映射回原图），返回 NCHW FloatArray。 */
    fun bitmapToNchw(source: Bitmap, size: Int = INPUT_SIZE): FloatArray {
        val scaled = if (source.width == size && source.height == size) source
        else Bitmap.createScaledBitmap(source, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== source) scaled.recycle()
        return toNchw(pixels, size)
    }
}
