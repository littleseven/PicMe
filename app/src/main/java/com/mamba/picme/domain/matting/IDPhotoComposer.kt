package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** 证件照合成：把「Alpha 抠图 + 纯色背景」按目标尺寸 cover-crop。核心 [coverCropRect] 可 JVM 单测。 */
object IDPhotoComposer {

    data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 计算 src 尺寸按 dst 宽高比 cover（填满）所需裁掉的源矩形（居中）。 */
    fun coverCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CropRect {
        val srcRatio = srcW.toFloat() / srcH.toFloat()
        val dstRatio = dstW.toFloat() / dstH.toFloat()
        return if (srcRatio > dstRatio) {
            // 源更宽：裁左右
            val cropW = (srcH * dstRatio).toInt()
            val left = (srcW - cropW) / 2
            CropRect(left, 0, left + cropW, srcH)
        } else {
            // 源更高：裁上下
            val cropH = (srcW / dstRatio).toInt()
            val top = (srcH - cropH) / 2
            CropRect(0, top, srcW, top + cropH)
        }
    }

    /** 合成：original+alpha → bgColor（原图尺寸）→ cover-crop → 缩放到 (targetW, targetH)。 */
    fun compose(
        original: Bitmap,
        alpha: FloatArray,
        bgColor: Int,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        val w = original.width
        val h = original.height
        val composited = BackgroundComposer.apply(original, alpha, w, h, bgColor)
        val cr = coverCropRect(w, h, targetW, targetH)
        val cropped = Bitmap.createBitmap(
            composited, cr.left, cr.top, cr.right - cr.left, cr.bottom - cr.top
        )
        val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        if (cropped !== composited && cropped !== scaled) cropped.recycle()
        if (composited !== scaled) composited.recycle()
        return scaled
    }
}
