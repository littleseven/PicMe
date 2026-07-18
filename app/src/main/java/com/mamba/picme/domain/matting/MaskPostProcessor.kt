package com.mamba.picme.domain.matting

/**
 * 掩码后处理：u2netp 输出的概率图 → 二值 Alpha → 上采样到原图 → 可选羽化。
 * 全部基于 FloatArray，可在纯 JVM 单测中验证（不依赖 Bitmap）。
 */
object MaskPostProcessor {

    /** 概率 >= threshold 记为 1（前景），否则 0。 */
    fun binarize(probabilities: FloatArray, threshold: Float): FloatArray =
        FloatArray(probabilities.size) { if (probabilities[it] >= threshold) 1f else 0f }

    /** 双线性上采样 (srcW,srcH) -> (dstW,dstH)，半像素中心 + 边缘钳制（与 TF/PyTorch 默认一致，掩码精确覆盖到图像边缘）。尺寸相同则返回拷贝。 */
    fun upsample(alpha: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        if (srcW == dstW && srcH == dstH) return alpha.copyOf()
        val out = FloatArray(dstW * dstH)
        val xScale = srcW.toFloat() / dstW.coerceAtLeast(1)
        val yScale = srcH.toFloat() / dstH.coerceAtLeast(1)
        val maxSx = (srcW - 1).toFloat()
        val maxSy = (srcH - 1).toFloat()
        for (y in 0 until dstH) {
            val sy = ((y + 0.5f) * yScale - 0.5f).coerceIn(0f, maxSy)
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - y0
            for (x in 0 until dstW) {
                val sx = ((x + 0.5f) * xScale - 0.5f).coerceIn(0f, maxSx)
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
}
