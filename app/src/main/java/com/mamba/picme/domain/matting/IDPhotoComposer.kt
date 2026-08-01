package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** 证件照合成：把「Alpha 抠图 + 纯色背景」按目标尺寸 cover-crop。核心 [coverCropRect] 可 JVM 单测。 */
object IDPhotoComposer {

    private const val SUBJECT_ALPHA_THRESHOLD = 0.5f

    /** 头顶留白占成片高度的比例（证件照惯例约 5%~10%）。 */
    private const val HEADROOM_RATIO = 0.08f

    data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 主体包围信息（源图像素坐标，来自 alpha 蒙版）：[top] 头顶所在行，[centerX] 主体水平中心。 */
    data class SubjectBounds(val top: Int, val centerX: Int)

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

    /** 从 alpha 蒙版（width×height，0..1）提取主体头顶行与水平中心；无主体返回 null。 */
    fun subjectBounds(alpha: FloatArray, width: Int, height: Int): SubjectBounds? {
        var top = -1
        var sumX = 0L
        var count = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (alpha[y * width + x] >= SUBJECT_ALPHA_THRESHOLD) {
                    if (top < 0) top = y
                    sumX += x
                    count++
                }
            }
        }
        if (count == 0L) return null
        return SubjectBounds(top = top, centerX = (sumX / count).toInt())
    }

    /** 构图参数：主体位置（智能定位）+ 用户拖拽偏移（裁剪窗口尺寸的归一化值）+ [zoom] 缩放倍数（≥1，1=cover 填满）。 */
    data class CropFraming(
        val subject: SubjectBounds? = null,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val zoom: Float = 1f
    )

    /** 裁剪窗口：尺寸（cover 窗口 / zoom）与智能定位后的自动起点（未加用户偏移）。 */
    private data class CropWindow(val cropW: Int, val cropH: Int, val autoLeft: Int, val autoTop: Int)

    private fun cropWindow(srcW: Int, srcH: Int, dstW: Int, dstH: Int, framing: CropFraming): CropWindow {
        val centered = coverCropRect(srcW, srcH, dstW, dstH)
        val zoom = framing.zoom.coerceAtLeast(1f)
        val cropW = ((centered.right - centered.left) / zoom).toInt().coerceIn(1, srcW)
        val cropH = ((centered.bottom - centered.top) / zoom).toInt().coerceIn(1, srcH)
        val autoLeft = framing.subject?.let { subject -> subject.centerX - cropW / 2 } ?: (srcW - cropW) / 2
        val autoTop = framing.subject?.let { subject -> subject.top - (cropH * HEADROOM_RATIO).toInt() }
            ?: (srcH - cropH) / 2
        return CropWindow(cropW, cropH, autoLeft, autoTop)
    }

    /**
     * 把 [framing] 的拖拽偏移 clamp 到当前合法裁剪范围内并返回新实例。
     * 状态层（手势累加）必须经此收敛，否则拖过边界后偏移持续累积，回拖时出现「拖了没反应」的死区。
     */
    fun clampFraming(srcW: Int, srcH: Int, dstW: Int, dstH: Int, framing: CropFraming): CropFraming {
        val window = cropWindow(srcW, srcH, dstW, dstH, framing)
        return framing.copy(
            zoom = framing.zoom.coerceAtLeast(1f),
            offsetX = framing.offsetX.coerceIn(
                -window.autoLeft.toFloat() / window.cropW,
                (srcW - window.cropW - window.autoLeft).toFloat() / window.cropW
            ),
            offsetY = framing.offsetY.coerceIn(
                -window.autoTop.toFloat() / window.cropH,
                (srcH - window.cropH - window.autoTop).toFloat() / window.cropH
            )
        )
    }

    /**
     * 主体感知 cover-crop：窗口尺寸 = cover 窗口 / [CropFraming.zoom]（zoom>1 即放大画面）。
     * 定位：纵向按头顶留白（避免居中裁剪「砍头」）、横向按主体居中；无主体回退居中。
     * [CropFraming.offsetX]/[CropFraming.offsetY] 为用户拖拽微调，取值为裁剪窗口尺寸的
     * 归一化偏移（拖满一个预览高度 = 偏移 1.0），最终位置 clamp 到合法范围。
     */
    fun subjectAwareCropRect(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        framing: CropFraming
    ): CropRect {
        val window = cropWindow(srcW, srcH, dstW, dstH, framing)
        val left = (window.autoLeft + (framing.offsetX * window.cropW).toInt()).coerceIn(0, srcW - window.cropW)
        val top = (window.autoTop + (framing.offsetY * window.cropH).toInt()).coerceIn(0, srcH - window.cropH)
        return CropRect(left, top, left + window.cropW, top + window.cropH)
    }

    /** 从已合成底图按 [framing] 裁剪并缩放到目标尺寸（UI 预览/保存共用，底图可跨手势缓存）。 */
    fun cropAndScale(composited: Bitmap, targetW: Int, targetH: Int, framing: CropFraming): Bitmap {
        val cr = subjectAwareCropRect(composited.width, composited.height, targetW, targetH, framing)
        val cropped = Bitmap.createBitmap(
            composited, cr.left, cr.top, cr.right - cr.left, cr.bottom - cr.top
        )
        val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        // createBitmap 在裁剪窗口覆盖整张位图且源不可变时会返回源对象自身，不得误 recycle 调用方持有的底图
        if (cropped !== composited && cropped !== scaled) cropped.recycle()
        return scaled
    }
}
