package com.mamba.picme.core.common

import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * 纯 Kotlin 去重核心：MD5（流式）/ 64-bit pHash（DCT）/ 汉明距离 / 并查集聚类。
 * 零 Android 依赖，可纯 JVM 单测。端侧执行，不上传任何数据。
 */
object PerceptualHash {

    /** pHash 灰度矩阵边长（32×32 → DCT → 取左上 8×8）。 */
    const val PHASH_SIZE = 32

    /** 近似判定阈值（汉明距离）。保守值，少误报。 */
    const val SIMILAR_HAMMING_THRESHOLD = 5

    /**
     * 流式 MD5。**不关闭** [input]（由调用方 `use` 管理）。返回 32 位小写十六进制。
     */
    fun md5Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("MD5")
        val dis = DigestInputStream(input, md)
        val buf = ByteArray(8 * 1024)
        while (dis.read(buf) > 0) {
            // 排空：每读一块同步更新 digest
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 64-bit pHash。[gray] 长度须为 [size]×[size]。
     * 流程：灰度矩阵 → 2D DCT-II → 左上 8×8 系数 → 以中位数阈值化。
     */
    fun phash(gray: DoubleArray, size: Int = PHASH_SIZE): Long {
        require(gray.size == size * size) { "gray size ${gray.size} != ${size * size}" }
        val dct = dct2(gray, size)
        val block = Array(8) { r -> DoubleArray(8) { c -> dct[r][c] } }
        // 标准 pHash：阈值用 AC 系数中位数，排除 DC（DC 编码亮度，纳入会使阈值随曝光漂移，
        // 破坏对「同图不同曝光」这类近似重复的鲁棒性）。
        val coeffs = ArrayList<Double>(63)
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (r == 0 && c == 0) continue
                coeffs.add(block[r][c])
            }
        }
        coeffs.sort()
        val median = coeffs[31] // 63 个 AC 系数的中位数
        var hash = 0L
        var bit = 0
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (block[r][c] > median) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    /** 64-bit 汉明距离（popcount of XOR）。 */
    fun hammingDistance(a: Long, b: Long): Int {
        var xor = a xor b
        var d = 0
        while (xor != 0L) {
            d++
            xor = xor and (xor - 1)
        }
        return d
    }

    /**
     * 并查集按汉明距离聚类。返回每组成员在 [hashes] 中的下标（仅保留 size≥2 的组）。
     */
    fun clusterByHamming(
        hashes: List<Long>,
        threshold: Int = SIMILAR_HAMMING_THRESHOLD
    ): List<List<Int>> {
        val n = hashes.size
        if (n < 2) {
            return emptyList()
        }
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (hammingDistance(hashes[i], hashes[j]) <= threshold) {
                    parent[find(i)] = find(j)
                }
            }
        }
        return parent.indices
            .groupBy { find(it) }
            .values
            .filter { it.size >= 2 }
    }

    private fun dct2(input: DoubleArray, size: Int): Array<DoubleArray> {
        val rowDct = Array(size) { r ->
            dct1(DoubleArray(size) { c -> input[r * size + c] })
        }
        val out = Array(size) { DoubleArray(size) }
        for (c in 0 until size) {
            val col = DoubleArray(size) { r -> rowDct[r][c] }
            val colDct = dct1(col)
            for (r in 0 until size) {
                out[r][c] = colDct[r]
            }
        }
        return out
    }

    private fun dct1(x: DoubleArray): DoubleArray {
        val n = x.size
        val out = DoubleArray(n)
        val factor = PI / (2.0 * n)
        for (k in 0 until n) {
            var sum = 0.0
            for (m in 0 until n) {
                sum += x[m] * cos(factor * (2 * m + 1) * k)
            }
            val c0 = if (k == 0) sqrt(1.0 / n) else sqrt(2.0 / n)
            out[k] = c0 * sum
        }
        return out
    }
}
