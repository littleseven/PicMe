package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** Alpha → 透明 ARGB。核心 [composeTransparent] 基于数组，可 JVM 单测。 */
object CutoutComposer {

    private const val ROUND_HALF = 0.5f
    private const val RGB_MASK = 0x00FFFFFF

    /** 保留原 RGB，按 alpha（0..1）重写 Alpha 通道，返回透明 ARGB IntArray。 */
    fun composeTransparent(pixels: IntArray, alpha: FloatArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val a = (alpha[i].coerceIn(0f, 1f) * 255f + ROUND_HALF).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (pixels[i] and RGB_MASK)
        }
        return out
    }

    /** 包装：把 source bitmap 按 alpha（width×height）合成成透明 Bitmap。 */
    fun apply(source: Bitmap, alpha: FloatArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = composeTransparent(pixels, alpha)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setHasAlpha(true)
        result.setPixels(out, 0, width, 0, 0, width, height)
        return result
    }
}
