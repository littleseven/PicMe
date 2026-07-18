package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** Alpha → 合成到不透明纯色背景。核心 [composeOnColor] 基于数组，可 JVM 单测。 */
object BackgroundComposer {

    /** alpha（0..1）混合前景像素与 bgColor，输出不透明 ARGB IntArray。 */
    fun composeOnColor(pixels: IntArray, alpha: FloatArray, bgColor: Int): IntArray {
        val out = IntArray(pixels.size)
        val br = (bgColor shr 16) and 0xFF
        val bg = (bgColor shr 8) and 0xFF
        val bb = bgColor and 0xFF
        for (i in pixels.indices) {
            val a = alpha[i].coerceIn(0f, 1f)
            val p = pixels[i]
            val r = (((p shr 16) and 0xFF) * a + br * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) * a + bg * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
            val b = ((p and 0xFF) * a + bb * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    fun apply(source: Bitmap, alpha: FloatArray, width: Int, height: Int, bgColor: Int): Bitmap {
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = composeOnColor(pixels, alpha, bgColor)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, width, 0, 0, width, height)
        return result
    }
}
