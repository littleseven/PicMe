package com.mamba.picme.domain.aesthetic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * 人脸对齐：用 5 点（双眼/鼻/双嘴角）做相似变换（Umeyama，仅旋转+缩放+平移，无剪切）
 * 把人脸对齐到 eDifFIQA 要的 112×112，参考 ArcFace 标准 5 点模板。
 *
 * 纯数学 + Android [Matrix]，给定输入确定性，便于 JVM 单测（变换矩阵）。
 */
object FaceAligner {
    const val SIZE = 112

    /** ArcFace 112×112 标准 5 点模板 [x0,y0, x1,y1, ...]：左眼、右眼、鼻、左嘴角、右嘴角 */
    private val DST = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * 计算 src 5 点 → ArcFace 模板的相似变换矩阵（9 元素，Android Matrix setValues 用）。
     * landmarks5 长度需 ≥10（5 点 × 2）。退化（src_var=0）返回 null。
     */
    fun similarityMatrix(landmarks5: FloatArray): FloatArray? {
        if (landmarks5.size < 10) return null
        var msx = 0f; var msy = 0f; var mdx = 0f; var mdy = 0f
        for (i in 0 until 5) {
            msx += landmarks5[2 * i]; msy += landmarks5[2 * i + 1]
            mdx += DST[2 * i]; mdy += DST[2 * i + 1]
        }
        msx /= 5f; msy /= 5f; mdx /= 5f; mdy /= 5f

        var c1 = 0f; var c2 = 0f; var srcVar = 0f
        for (i in 0 until 5) {
            val sx = landmarks5[2 * i] - msx
            val sy = landmarks5[2 * i + 1] - msy
            val dx = DST[2 * i] - mdx
            val dy = DST[2 * i + 1] - mdy
            c1 += sx * dx + sy * dy
            c2 += sx * dy - sy * dx
            srcVar += sx * sx + sy * sy
        }
        if (srcVar == 0f) return null

        // A=[[c1,-c2],[c2,c1]]/srcVar；t = μ_dst - A·μ_src
        val m0 = c1 / srcVar
        val m1 = -c2 / srcVar
        val m3 = c2 / srcVar
        val m4 = c1 / srcVar
        val m2 = mdx - (m0 * msx + m1 * msy)
        val m5 = mdy - (m3 * msx + m4 * msy)
        return floatArrayOf(m0, m1, m2, m3, m4, m5, 0f, 0f, 1f)
    }

    /** 把 [src] 按 landmarks5 对齐到 112×112；退化或失败返回 null。 */
    fun align(src: Bitmap, landmarks5: FloatArray): Bitmap? {
        val values = similarityMatrix(landmarks5) ?: return null
        val matrix = Matrix().apply { setValues(values) }
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }
}
