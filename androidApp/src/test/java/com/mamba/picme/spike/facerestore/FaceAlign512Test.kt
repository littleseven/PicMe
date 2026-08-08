package com.mamba.picme.spike.facerestore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纯数学单测：验证 [FaceAlign512.similarityMatrix512] 的 Umeyama 相似变换正确性。
 */
class FaceAlign512Test {

    /** ArcFace 112 标准 5 点模板（与 FaceAligner 一致），仅用于测试中重建期望值。 */
    private val base112 = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )
    private val scale = FaceAlign512.SIZE.toFloat() / 112f

    @Test
    fun `similarityMatrix512 maps template points to themselves yielding identity`() {
        // 源点 == 缩放后的模板点 → 最优相似变换为单位矩阵
        val src = FloatArray(base112.size) { i -> base112[i] * scale }
        val m = FaceAlign512.similarityMatrix512(src)
        assertNotNull(m)
        val tol = 0.01f
        val matrix = m!!
        assertEquals(1f, matrix[0], tol)
        assertEquals(0f, matrix[1], tol)
        assertEquals(0f, matrix[2], tol)
        assertEquals(0f, matrix[3], tol)
        assertEquals(1f, matrix[4], tol)
        assertEquals(0f, matrix[5], tol)
    }

    @Test
    fun `similarityMatrix512 maps arbitrary landmarks onto scaled template`() {
        // 源点 = 模板缩放 2 倍 + 偏移 (10, 20) → 矩阵应把每个源点映射回缩放模板
        val offsetX = 10f
        val offsetY = 20f
        val srcScale = 2f
        val src = FloatArray(base112.size) { i ->
            val base = base112[i] * scale * srcScale
            if (i % 2 == 0) base + offsetX else base + offsetY
        }
        val m = FaceAlign512.similarityMatrix512(src)
        assertNotNull(m)
        val matrix = m!!
        for (i in 0 until 5) {
            val sx = src[2 * i]
            val sy = src[2 * i + 1]
            val ux = matrix[0] * sx + matrix[1] * sy + matrix[2]
            val uy = matrix[3] * sx + matrix[4] * sy + matrix[5]
            val expectedX = base112[2 * i] * scale
            val expectedY = base112[2 * i + 1] * scale
            assertEquals("point $i x", expectedX, ux, 0.1f)
            assertEquals("point $i y", expectedY, uy, 0.1f)
        }
    }

    @Test
    fun `similarityMatrix512 returns null for degenerate landmarks`() {
        // 5 点重合 → srcVar=0 → 退化
        val src = floatArrayOf(
            100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f
        )
        assertNull(FaceAlign512.similarityMatrix512(src))
    }

    @Test
    fun `similarityMatrix512 returns null for fewer than 5 points`() {
        val src = floatArrayOf(1f, 2f, 3f, 4f) // only 2 points
        assertNull(FaceAlign512.similarityMatrix512(src))
    }

    @Test
    fun `matrix is 9 elements in Android Matrix setValues order`() {
        val src = FloatArray(base112.size) { i -> base112[i] * scale }
        val m = FaceAlign512.similarityMatrix512(src)
        assertNotNull(m)
        assertEquals(9, m!!.size)
        // 最后三个元素固定为 [0, 0, 1]（仿射齐次坐标）
        assertEquals(0f, m[6], 0f)
        assertEquals(0f, m[7], 0f)
        assertEquals(1f, m[8], 0f)
    }
}
