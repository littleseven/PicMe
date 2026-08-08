package com.mamba.picme.spike.facerestore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * 人脸对齐到 512×512（Phase-0 spike）。
 *
 * 复用 [com.mamba.picme.domain.aesthetic.FaceAligner] 的 Umeyama 相似变换数学（仅旋转+缩放+平移，
 * 无剪切），但把目标模板从 ArcFace 112 缩放到 512，输出尺寸改为 512×512。
 *
 * 与 FaceAligner 的关键区别：本类 **保留 forwardMatrix**（原图坐标 → 512 对齐坐标），
 * 供 [FacePasteBack] 反向变换贴回使用。FaceAligner 丢弃了它，本 spike 不能重蹈覆辙。
 *
 * 纯数学（[similarityMatrix512]）基于 FloatArray，确定性，可在 JVM 单测中验证。
 */
object FaceAlign512 {
    const val SIZE = 512

    private const val BASE_SIZE = 112f
    private const val SCALE = SIZE / BASE_SIZE

    /** ArcFace 112×112 标准 5 点模板（左眼、右眼、鼻、左嘴角、右嘴角），与 FaceAligner 完全一致。 */
    private val DST_112 = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    // TODO(production): switch to canonical FFHQ/CodeFormer 5-pt template for 512 — verify coords from facexlib
    private val DST = FloatArray(DST_112.size) { i -> DST_112[i] * SCALE }

    /**
     * 计算 src 5 点 → 512 缩放模板的相似变换矩阵（9 元素，Android Matrix setValues 用）。
     * landmarks5 长度需 ≥10（5 点 × 2）。退化（src_var=0）返回 null。
     */
    fun similarityMatrix512(landmarks5: FloatArray): FloatArray? {
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

    /**
     * 把 [src] 按 landmarks5 对齐到 512×512；退化或失败返回 null。
     * 返回的 [AlignedFace.forwardMatrix] 把原图坐标映射到 512 对齐坐标，贴回时取逆使用。
     */
    fun align(src: Bitmap, landmarks5: FloatArray): AlignedFace? {
        val values = similarityMatrix512(landmarks5) ?: return null
        val matrix = Matrix().apply { setValues(values) }
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return AlignedFace(out, values)
    }
}

/** 对齐后的 512×512 人脸 + 正向变换矩阵（原图 → 512）。 */
data class AlignedFace(
    val bitmap: Bitmap,
    val forwardMatrix: FloatArray /* 9 elems, Android Matrix setValues order */
)
