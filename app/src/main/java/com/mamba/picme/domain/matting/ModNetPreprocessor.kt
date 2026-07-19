package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** MODNet 输入预处理：256×256 RGB → (x/255-0.5)/0.5 归一化 NCHW。核心 [toNchw] 基于数组，可 JVM 单测。 */
object ModNetPreprocessor {
    const val INPUT_SIZE = 256
    private const val MEAN = 0.5f
    private const val STD = 0.5f

    /** pixels：ARGB IntArray，长度 = size*size。返回 NCHW [3*size*size]。 */
    fun toNchw(pixels: IntArray, size: Int = INPUT_SIZE): FloatArray {
        val plane = size * size
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = (((p shr 16) and 0xFF) / 255f - MEAN) / STD
            val g = (((p shr 8) and 0xFF) / 255f - MEAN) / STD
            val b = ((p and 0xFF) / 255f - MEAN) / STD
            out[i] = r
            out[plane + i] = g
            out[2 * plane + i] = b
        }
        return out
    }

    /** 把 Bitmap 缩放到 size×size，返回 NCHW FloatArray。 */
    fun bitmapToNchw(source: Bitmap, size: Int = INPUT_SIZE): FloatArray {
        val scaled = if (source.width == size && source.height == size) source
        else Bitmap.createScaledBitmap(source, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== source) scaled.recycle()
        return toNchw(pixels, size)
    }
}
