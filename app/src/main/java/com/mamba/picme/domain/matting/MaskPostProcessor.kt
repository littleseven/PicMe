package com.mamba.picme.domain.matting

/**
 * 掩码后处理：u2netp 输出的概率图 → 二值 Alpha → 上采样到原图 → 可选羽化。
 * 全部基于 FloatArray，可在纯 JVM 单测中验证（不依赖 Bitmap）。
 */
object MaskPostProcessor {

    private const val HALF_PIXEL = 0.5f
    private const val ALPHA_MIDPOINT = 0.5f

    /** 概率 >= threshold 记为 1（前景），否则 0。 */
    fun binarize(probabilities: FloatArray, threshold: Float): FloatArray =
        FloatArray(probabilities.size) { i -> if (probabilities[i] >= threshold) 1f else 0f }

    /** 双线性上采样 (srcW,srcH) -> (dstW,dstH)，半像素中心 + 边缘钳制（与 TF/PyTorch 默认一致，掩码精确覆盖到图像边缘）。尺寸相同则返回拷贝。 */
    fun upsample(alpha: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        if (srcW == dstW && srcH == dstH) return alpha.copyOf()
        val out = FloatArray(dstW * dstH)
        val xScale = srcW.toFloat() / dstW.coerceAtLeast(1)
        val yScale = srcH.toFloat() / dstH.coerceAtLeast(1)
        val maxSx = (srcW - 1).toFloat()
        val maxSy = (srcH - 1).toFloat()
        for (y in 0 until dstH) {
            val sy = ((y + HALF_PIXEL) * yScale - HALF_PIXEL).coerceIn(0f, maxSy)
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - y0
            for (x in 0 until dstW) {
                val sx = ((x + HALF_PIXEL) * xScale - HALF_PIXEL).coerceIn(0f, maxSx)
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val fx = sx - x0
                val v00 = alpha[y0 * srcW + x0]
                val v01 = alpha[y0 * srcW + x1]
                val v10 = alpha[y1 * srcW + x0]
                val v11 = alpha[y1 * srcW + x1]
                val top = v00 + (v01 - v00) * fx
                val bottom = v10 + (v11 - v10) * fx
                out[y * dstW + x] = top + (bottom - top) * fy
            }
        }
        return out
    }

    /** 可分离盒滤波羽化。radius<=0 返回拷贝。 */
    fun feather(alpha: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return alpha.copyOf()
        val tmp = FloatArray(alpha.size)
        // 水平
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (dx in -radius..radius) {
                    val sx = x + dx
                    if (sx in 0 until w) {
                        sum += alpha[y * w + sx]
                        count++
                    }
                }
                tmp[y * w + x] = sum / count
            }
        }
        val out = FloatArray(alpha.size)
        // 垂直
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (dy in -radius..radius) {
                    val sy = y + dy
                    if (sy in 0 until h) {
                        sum += tmp[sy * w + x]
                        count++
                    }
                }
                out[y * w + x] = sum / count
            }
        }
        return out
    }

    /**
     * Alpha 对比度锐化：把软边过渡带收窄，消除抠图边缘的半透明“虚边/光晕”。
     * 仅对中间过渡区做关于 0.5 的对比度拉伸，alpha=0 与 alpha=1 的区域钳制后不变，
     * 因此不会像二值化那样引入锯齿，也不会吃掉发丝（发丝的连续 alpha 被同步增强对比度）。
     * @param contrast 1 = 原样返回；>1 锐化（证件照推荐 2.0 附近，过大会逼近二值化重新出现锯齿）。
     */
    fun sharpenAlpha(alpha: FloatArray, contrast: Float): FloatArray {
        if (contrast == 1f) return alpha.copyOf()
        return FloatArray(alpha.size) { i ->
            val v = (alpha[i] - ALPHA_MIDPOINT) * contrast + ALPHA_MIDPOINT
            when {
                v <= 0f -> 0f
                v >= 1f -> 1f
                else -> v
            }
        }
    }

    /** 腐蚀（收缩前景）：分离式滑动窗口最小值滤波。radius<=0 返回拷贝。 */
    fun erode(alpha: FloatArray, w: Int, h: Int, radius: Int): FloatArray =
        windowPass(windowPass(alpha, w, h, radius, horizontal = true, isMax = false),
            w, h, radius, horizontal = false, isMax = false)

    /** 扩张（扩展前景）：分离式滑动窗口最大值滤波。radius<=0 返回拷贝。 */
    fun dilate(alpha: FloatArray, w: Int, h: Int, radius: Int): FloatArray =
        windowPass(windowPass(alpha, w, h, radius, horizontal = true, isMax = true),
            w, h, radius, horizontal = false, isMax = true)

    /** 单方向滑动窗口 min/max 滤波；越界位置跳过（边缘钳制，与 [feather] 一致）。 */
    private fun windowPass(
        alpha: FloatArray, w: Int, h: Int, radius: Int, horizontal: Boolean, isMax: Boolean
    ): FloatArray {
        if (radius <= 0) return alpha.copyOf()
        val out = FloatArray(alpha.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var best = if (isMax) 0f else 1f
                for (d in -radius..radius) {
                    val sx = if (horizontal) x + d else x
                    val sy = if (horizontal) y else y + d
                    if (sx in 0 until w && sy in 0 until h) {
                        val v = alpha[sy * w + sx]
                        best = if (isMax) maxOf(best, v) else minOf(best, v)
                    }
                }
                out[y * w + x] = best
            }
        }
        return out
    }
}
