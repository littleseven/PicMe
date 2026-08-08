package com.mamba.picme.spike.facerestore

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import com.mamba.picme.domain.matting.MaskPostProcessor

/**
 * 把修复后的 512×512 人脸贴回原图（Phase-0 spike 的核心验证点）。
 *
 * 数学流程：
 * 1. [forwardMatrix]（原图 → 512）取逆得 [inverseMatrix]（512 → 原图）。
 * 2. 在 512 空间构建椭圆 alpha 蒙版覆盖人脸区域，再羽化软化边缘。
 * 3. 对原图受影响区域的每个像素，用正向变换映射到 512 空间，双线性采样修复图与蒙版，
 *    按 `out = restored*a + original*(1-a)` 逐通道合成（镜像 BackgroundComposer 的数学）。
 *
 * 用「迭代原图像素 + 正向采样 512」而非「迭代 512 + 散射到原图」，保证无空洞、全覆盖。
 *
 * 核心合成 [pasteBack] 基于 IntArray/FloatArray，确定性，可在 JVM 单测中验证。
 */
object FacePasteBack {

    private const val TAG = "PoLang:SpikeFaceRestore"
    private const val CHANNEL_MASK = 0xFF
    private const val ROUND_HALF = 0.5f

    /** 蒙版羽化半径（像素），约为 512 的 3%。 */
    private const val FEATHER_RADIUS = 16

    /**
     * 求逆 3×3 仿射矩阵（Android Matrix setValues 顺序 [m0,m1,m2,m3,m4,m5,0,0,1]）。
     * 退化（det=0）返回 null。纯数学，可 JVM 单测。
     */
    fun invertAffine3x3(m: FloatArray): FloatArray? {
        val det = m[0] * m[4] - m[1] * m[3]
        if (det == 0f) return null
        val invDet = 1f / det
        val r0 = m[4] * invDet
        val r1 = -m[1] * invDet
        val r2 = (m[1] * m[5] - m[2] * m[4]) * invDet
        val r3 = -m[3] * invDet
        val r4 = m[0] * invDet
        val r5 = (m[2] * m[3] - m[0] * m[5]) * invDet
        return floatArrayOf(r0, r1, r2, r3, r4, r5, 0f, 0f, 1f)
    }

    /** 仿射变换 applied to point (x, y): returns [x', y']。 */
    private fun applyAffine(m: FloatArray, x: Float, y: Float): FloatArray =
        floatArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

    /**
     * 在 (w×h) 上构建椭圆 alpha 蒙版（圆内 = 1，圆外 = 0）。
     * 使用像素中心约定 (x+0.5, y+0.5)。纯数学，可 JVM 单测。
     */
    fun ellipseAlphaMask(
        w: Int, h: Int, centerX: Float, centerY: Float, radiusX: Float, radiusY: Float
    ): FloatArray {
        val mask = FloatArray(w * h)
        val rx2 = radiusX * radiusX
        val ry2 = radiusY * radiusY
        if (rx2 <= 0f || ry2 <= 0f) return mask
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x + 0.5f - centerX
                val dy = y + 0.5f - centerY
                mask[y * w + x] = if ((dx * dx) / rx2 + (dy * dy) / ry2 <= 1f) 1f else 0f
            }
        }
        return mask
    }

    /**
     * 纯核心：把修复后的 512² 人脸合成回原图。
     *
     * @param origPixels 原图像素 ARGB IntArray（不会被修改，返回拷贝）
     * @param origW 原图宽
     * @param origH 原图高
     * @param restoredPixels 修复后的 512² 像素 ARGB IntArray
     * @param inverseMatrix 512 对齐坐标 → 原图坐标 的逆矩阵（9 元素）
     * @param alphaMask 512² 蒙版 FloatArray（0..1，已羽化）
     * @return 合成后的原图像素 IntArray（尺寸 = origW × origH）
     */
    fun pasteBack(
        origPixels: IntArray, origW: Int, origH: Int,
        restoredPixels: IntArray,
        inverseMatrix: FloatArray,
        alphaMask: FloatArray
    ): IntArray {
        val out = origPixels.copyOf()
        // inverseMatrix 映射 512→原图；正向采样需要 原图→512，故再求一次逆。
        val forward = invertAffine3x3(inverseMatrix) ?: return out

        // 原图受影响区域：把 512 四角经 inverseMatrix 映射回原图，取包围盒。
        val sizeF = (FaceAlign512.SIZE - 1).toFloat()
        val corners = arrayOf(
            applyAffine(inverseMatrix, 0f, 0f),
            applyAffine(inverseMatrix, sizeF, 0f),
            applyAffine(inverseMatrix, 0f, sizeF),
            applyAffine(inverseMatrix, sizeF, sizeF)
        )
        var minX = corners[0][0]; var maxX = corners[0][0]
        var minY = corners[0][1]; var maxY = corners[0][1]
        for (cornerIndex in corners.indices) {
            val cx = corners[cornerIndex][0]
            val cy = corners[cornerIndex][1]
            if (cx < minX) minX = cx
            if (cx > maxX) maxX = cx
            if (cy < minY) minY = cy
            if (cy > maxY) maxY = cy
        }
        val xStart = minX.toInt().coerceIn(0, origW - 1)
        val xEnd = maxX.toInt().coerceIn(0, origW - 1)
        val yStart = minY.toInt().coerceIn(0, origH - 1)
        val yEnd = maxY.toInt().coerceIn(0, origH - 1)

        val maxSrc = sizeF
        for (oy in yStart..yEnd) {
            for (ox in xStart..xEnd) {
                // 原图坐标 → 512 对齐坐标（正向变换）
                val u = forward[0] * ox + forward[1] * oy + forward[2]
                val v = forward[3] * ox + forward[4] * oy + forward[5]
                if (u < 0f || u > maxSrc || v < 0f || v > maxSrc) continue

                val uc = if (u < 0f) 0f else if (u > maxSrc) maxSrc else u
                val vc = if (v < 0f) 0f else if (v > maxSrc) maxSrc else v
                val x0 = uc.toInt()
                val x1 = (x0 + 1).coerceAtMost(FaceAlign512.SIZE - 1)
                val y0 = vc.toInt()
                val y1 = (y0 + 1).coerceAtMost(FaceAlign512.SIZE - 1)
                val fu = uc - x0
                val fv = vc - y0

                // 双线性采样 alpha 蒙版
                val a00 = alphaMask[y0 * FaceAlign512.SIZE + x0]
                val a01 = alphaMask[y0 * FaceAlign512.SIZE + x1]
                val a10 = alphaMask[y1 * FaceAlign512.SIZE + x0]
                val a11 = alphaMask[y1 * FaceAlign512.SIZE + x1]
                val aTop = a00 + (a01 - a00) * fu
                val aBot = a10 + (a11 - a10) * fu
                val a = (aTop + (aBot - aTop) * fv).coerceIn(0f, 1f)
                if (a <= 0f) continue

                // 双线性采样修复图（逐通道）
                val p00 = restoredPixels[y0 * FaceAlign512.SIZE + x0]
                val p01 = restoredPixels[y0 * FaceAlign512.SIZE + x1]
                val p10 = restoredPixels[y1 * FaceAlign512.SIZE + x0]
                val p11 = restoredPixels[y1 * FaceAlign512.SIZE + x1]

                val sr = bilinearChannel(p00, p01, p10, p11, fu, fv, SHIFT_R)
                val sg = bilinearChannel(p00, p01, p10, p11, fu, fv, SHIFT_G)
                val sb = bilinearChannel(p00, p01, p10, p11, fu, fv, SHIFT_B)

                val idx = oy * origW + ox
                val orig = out[idx]
                val oR = (orig shr SHIFT_R) and CHANNEL_MASK
                val oG = (orig shr SHIFT_G) and CHANNEL_MASK
                val oB = (orig shr SHIFT_B) and CHANNEL_MASK

                val invA = 1f - a
                val r = ((sr * a + oR * invA) + ROUND_HALF).toInt().coerceIn(0, CHANNEL_MASK)
                val g = ((sg * a + oG * invA) + ROUND_HALF).toInt().coerceIn(0, CHANNEL_MASK)
                val b = ((sb * a + oB * invA) + ROUND_HALF).toInt().coerceIn(0, CHANNEL_MASK)

                out[idx] = (CHANNEL_MASK shl 24) or (r shl SHIFT_R) or (g shl SHIFT_G) or b
            }
        }
        return out
    }

    private const val SHIFT_R = 16
    private const val SHIFT_G = 8
    private const val SHIFT_B = 0

    /** 对 4 个角像素的指定通道做双线性插值（fu: x 方向权重, fv: y 方向权重）。 */
    private fun bilinearChannel(p00: Int, p01: Int, p10: Int, p11: Int, fu: Float, fv: Float, shift: Int): Float {
        val v00 = (p00 shr shift) and CHANNEL_MASK
        val v01 = (p01 shr shift) and CHANNEL_MASK
        val v10 = (p10 shr shift) and CHANNEL_MASK
        val v11 = (p11 shr shift) and CHANNEL_MASK
        val top = v00 + (v01 - v00) * fu
        val bottom = v10 + (v11 - v10) * fu
        return top + (bottom - top) * fv
    }

    /**
     * Bitmap 胶水：把修复后的 [restored512] 贴回 [original]。
     *
     * @param forwardMatrix 原图 → 512 的正向矩阵（来自 [AlignedFace.forwardMatrix]）
     * @param roi 人脸 ROI（原图坐标，仅用于约束蒙版范围 / 调试）
     * @return 合成后的 Bitmap（尺寸同 [original]）
     */
    fun pasteBack(
        original: Bitmap,
        restored512: Bitmap,
        forwardMatrix: FloatArray,
        @Suppress("UNUSED_PARAMETER") roi: RectF
    ): Bitmap {
        val origW = original.width
        val origH = original.height
        val origPixels = IntArray(origW * origH)
        original.getPixels(origPixels, 0, origW, 0, 0, origW, origH)

        val restoredPixels = IntArray(FaceAlign512.SIZE * FaceAlign512.SIZE)
        restored512.getPixels(restoredPixels, 0, FaceAlign512.SIZE, 0, 0, FaceAlign512.SIZE, FaceAlign512.SIZE)

        // forward(原图→512) 取逆得 inverse(512→原图)
        val inverse = invertAffine3x3(forwardMatrix)
            ?: run {
                Log.w(TAG, "forwardMatrix not invertible, returning original unchanged")
                return original
            }

        // 构建 512 椭圆蒙版并羽化
        val half = FaceAlign512.SIZE / 2f
        val mask = ellipseAlphaMask(
            FaceAlign512.SIZE, FaceAlign512.SIZE,
            centerX = half, centerY = half,
            radiusX = FaceAlign512.SIZE * 0.4f,
            radiusY = FaceAlign512.SIZE * 0.48f
        )
        val feathered = MaskPostProcessor.feather(mask, FaceAlign512.SIZE, FaceAlign512.SIZE, FEATHER_RADIUS)

        val out = pasteBack(origPixels, origW, origH, restoredPixels, inverse, feathered)
        val result = Bitmap.createBitmap(origW, origH, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, origW, 0, 0, origW, origH)
        return result
    }

    /** 供外部用 android.graphics.Matrix 求逆的便利方法（与纯 [invertAffine3x3] 等价）。 */
    fun invertViaAndroidMatrix(forwardMatrix: FloatArray): FloatArray? {
        val matrix = Matrix()
        matrix.setValues(forwardMatrix)
        val inverse = Matrix()
        if (!matrix.invert(inverse)) return null
        val out = FloatArray(9)
        inverse.getValues(out)
        return out
    }
}
