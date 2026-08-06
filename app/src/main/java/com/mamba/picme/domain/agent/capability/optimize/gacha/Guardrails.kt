package com.mamba.picme.domain.agent.capability.optimize.gacha

import kotlin.math.abs

/**
 * 候选渲染结果的技术护栏（纯函数，操作像素数组，可 JVM 单测）。
 *
 * NIMA 偏好高对比高饱和，护栏用于淘汰过曝/亮度异常漂移的候选（见 spec §5.1）。
 * 阈值均为初始值，按离线样张验证结果调整。
 */
object Guardrails {

    /** 高光裁剪增量上限：候选裁剪率相对原图的增量超过该值则淘汰（防候选把高光推爆，不惩罚天然偏亮的照片） */
    const val HIGHLIGHT_CLIP_DELTA_LIMIT = 0.05f

    /** 平均亮度漂移上限：候选均亮度相对原图漂移超过该比例则淘汰 */
    const val LUMINANCE_DRIFT_LIMIT = 0.15f

    /** 高光裁剪率，∈[0,1]；[step] 为采样步长（默认每 4 像素采 1 个）。 */
    fun highlightClipRatio(px: IntArray, step: Int = 4): Float {
        if (px.isEmpty()) return 0f
        var clipped = 0
        var sampled = 0
        for (i in px.indices step step) {
            sampled++
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r >= 250 && g >= 250 && b >= 250) clipped++
        }
        return if (sampled == 0) 0f else clipped.toFloat() / sampled
    }

    /** 平均亮度（Rec.601 luma 归一化到 [0,1]）。 */
    fun meanLuminance(px: IntArray, step: Int = 4): Float {
        if (px.isEmpty()) return 0f
        var sum = 0.0
        var sampled = 0
        for (i in px.indices step step) {
            sampled++
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        }
        return if (sampled == 0) 0f else (sum / sampled).toFloat()
    }

    /**
     * 护栏检查。
     *
     * @param candidatePx 候选渲染结果像素
     * @param originalMeanLuminance 原图平均亮度
     * @param originalClipRatio 原图高光裁剪率（增量判定基准）
     * @return null 表示通过；否则为淘汰原因（日志与落库用）
     */
    fun check(candidatePx: IntArray, originalMeanLuminance: Float, originalClipRatio: Float): String? {
        val clip = highlightClipRatio(candidatePx)
        if (clip - originalClipRatio > HIGHLIGHT_CLIP_DELTA_LIMIT) return "highlight_clip:$clip"
        val lum = meanLuminance(candidatePx)
        if (originalMeanLuminance > 0f &&
            abs(lum - originalMeanLuminance) / originalMeanLuminance > LUMINANCE_DRIFT_LIMIT
        ) {
            return "luminance_drift:$lum"
        }
        return null
    }
}
