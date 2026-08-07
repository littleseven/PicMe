package com.mamba.picme.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * [QA] PerceptualHash 纯 Kotlin 去重核心单测：MD5（流式）/ pHash(DCT) / 汉明距离 / 并查集聚类。
 */
class PerceptualHashTest {

    // ── MD5（流式）──

    @Test
    fun `md5Hex same bytes gives same 32-hex hash`() {
        val h1 = PerceptualHash.md5Hex(ByteArrayInputStream("test content".toByteArray()))
        val h2 = PerceptualHash.md5Hex(ByteArrayInputStream("test content".toByteArray()))
        assertNotNull(h1)
        assertEquals(32, h1.length)
        assertEquals(h1, h2)
    }

    @Test
    fun `md5Hex different bytes gives different hash`() {
        val h1 = PerceptualHash.md5Hex(ByteArrayInputStream("content A".toByteArray()))
        val h2 = PerceptualHash.md5Hex(ByteArrayInputStream("content B".toByteArray()))
        assertNotEquals(h1, h2)
    }

    // ── 汉明距离 ──

    @Test
    fun `hammingDistance same hash is 0`() {
        val h = 0x123456789ABCDEF0L
        assertEquals(0, PerceptualHash.hammingDistance(h, h))
    }

    @Test
    fun `hammingDistance one bit is 1`() {
        assertEquals(1, PerceptualHash.hammingDistance(0L, 0x0000000000000001L))
    }

    @Test
    fun `hammingDistance all 64 bits is 64`() {
        assertEquals(64, PerceptualHash.hammingDistance(0L, -1L))
    }

    @Test
    fun `hammingDistance is symmetric`() {
        val a = 0x123456789ABCDEF0L
        val b = 0x0FEDCBA987654321L
        assertEquals(
            PerceptualHash.hammingDistance(a, b),
            PerceptualHash.hammingDistance(b, a)
        )
    }

    // ── pHash：确定性 + 稳定性 + 敏感性 ──

    private fun gradient(size: Int, horizontal: Boolean): DoubleArray {
        val g = DoubleArray(size * size)
        for (r in 0 until size) {
            for (c in 0 until size) {
                g[r * size + c] = if (horizontal) c.toDouble() else r.toDouble()
            }
        }
        return g
    }

    /**
     * 确定性的伪随机「图像」场（LCG，不依赖 Math.random()）。
     *
     * 用于亮度鲁棒性测试：其低频 DCT 系数分散（非退化）。避免使用抛物面/梯度这类
     * 数学曲面——它们的频谱退化，63 个低频系数里大量聚集在中位数附近，使严格 `>`
     * 比较对 ~1e-12 量级的浮点噪声敏感而乱翻转（实测抛物面 49/63 系数落在中位数
     * 1e-6 邻域内，亮度偏移后翻转 6~12 位，并非实现缺陷而是夹具退化）。
     */
    private fun deterministicRandomField(size: Int, seed: Int = 12345): DoubleArray {
        val g = DoubleArray(size * size)
        var s = seed.toLong() and 0x7fffffffL
        for (i in g.indices) {
            s = (1103515245L * s + 12345L) and 0x7fffffffL
            g[i] = (s % 256).toDouble() // 0..255，模拟自然图像像素强度
        }
        return g
    }

    @Test
    fun `phash is deterministic for identical input`() {
        val g = gradient(32, horizontal = true)
        assertEquals(PerceptualHash.phash(g), PerceptualHash.phash(g.toList().toDoubleArray()))
    }

    @Test
    fun `phash robust to global brightness shift`() {
        // pHash 以 AC 系数中位数阈值化：常数亮度偏移只改变 DC（已排除出中位数），
        // AC 系数与中位数不变 → 哈希近似不变。这是「同图不同曝光」近似重复判定的理论基础。
        // 用确定性的伪随机场（非退化、低频系数分散）作夹具，避免抛物面这类曲面在频域
        // 把大量系数聚集到中位数附近、使严格 `>` 比较对浮点噪声敏感而乱翻转。
        val g = deterministicRandomField(32)
        val gBrighter = DoubleArray(g.size) { i -> g[i] + 50.0 }
        val d = PerceptualHash.hammingDistance(PerceptualHash.phash(g), PerceptualHash.phash(gBrighter))
        assertTrue("brightness shift should barely change pHash, got $d", d <= 4)
    }

    @Test
    fun `phash distinguishes clearly different patterns`() {
        val horiz = PerceptualHash.phash(gradient(32, horizontal = true))
        val vert = PerceptualHash.phash(gradient(32, horizontal = false))
        val d = PerceptualHash.hammingDistance(horiz, vert)
        assertTrue("expected large hamming distance, got $d", d > 10)
    }

    // ── 并查集聚类 ──

    @Test
    fun `clusterByHamming merges within threshold, splits beyond it`() {
        // 0 与 0x1F 距离 5；0 与 0x3F 距离 6
        val hashes = listOf(0L, 0x000000000000001FL, 0x000000000000003FL)
        val t5 = PerceptualHash.clusterByHamming(hashes, threshold = 5)
        assertEquals(1, t5.size) // 仅 [0,1] 成组；0x3F 单独被过滤
        assertTrue(t5[0].containsAll(listOf(0, 1)))

        val t6 = PerceptualHash.clusterByHamming(hashes, threshold = 6)
        assertEquals(1, t6.size) // [0,1,2] 全合并
        assertEquals(3, t6[0].size)
    }

    @Test
    fun `clusterByHamming empty or single yields no groups`() {
        assertTrue(PerceptualHash.clusterByHamming(emptyList()).isEmpty())
        assertTrue(PerceptualHash.clusterByHamming(listOf(1L)).isEmpty())
    }
}
