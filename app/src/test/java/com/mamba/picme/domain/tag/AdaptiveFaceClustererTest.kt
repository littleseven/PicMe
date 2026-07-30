package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 密度自适应 k-NN 人脸聚类单测。
 *
 * 核心验证：建边阈值不能太松，否则会把外貌相近的不同人错误连成大簇；
 * 也不能太紧，否则会把同一人拆碎。
 */
class AdaptiveFaceClustererTest {

    companion object {
        private const val DIM = 512
    }

    /**
     * 构造确定性的两组 embedding：
     * - 组内两两相似度 ≈ withinSim
     * - 组间两两相似度 ≈ acrossSim
     *
     * 实现：A/B 共享一个“公共方向”（控制组间相似度），各自再加一个正交差异方向
     * （控制组内紧密度）。权重按 ws² = acrossSim、ws² + wd² = withinSim 计算。
     */
    private fun buildTwoPersonEmbeddings(
        perPerson: Int = 10,
        withinSim: Float,
        acrossSim: Float
    ): Map<Long, List<FloatArray>> {
        require(withinSim > acrossSim) { "withinSim must be greater than acrossSim" }

        val shared = deterministicUnitVector(seed = 1)
        val diffA = deterministicUnitVectorOrthogonalTo(shared, seed = 2)
        val diffB = deterministicUnitVectorOrthogonalTo(shared, seed = 3)

        val ws = sqrt(acrossSim)
        val wd = sqrt(withinSim - acrossSim)
        val noiseScale = 0.04f

        val embeddings = mutableMapOf<Long, MutableList<FloatArray>>()
        repeat(perPerson) { idx ->
            embeddings.getOrPut(100L) { mutableListOf() }
                .add(sampleVector(shared, diffA, ws, wd, noiseScale, seed = 1000L + idx))
            embeddings.getOrPut(101L) { mutableListOf() }
                .add(sampleVector(shared, diffB, ws, wd, noiseScale, seed = 2000L + idx))
        }
        return embeddings
    }

    private fun flatIndex(embeddings: Map<Long, List<FloatArray>>): List<Pair<Long, Int>> {
        return embeddings.flatMap { (mediaId, list) ->
            list.indices.map { mediaId to it }
        }
    }

    @Test
    fun `current config separates two different persons`() {
        // 外貌相差较大的两个人：组间 0.35，组内 0.80
        val embeddings = buildTwoPersonEmbeddings(
            perPerson = 10,
            withinSim = 0.80f,
            acrossSim = 0.35f
        )
        val flat = flatIndex(embeddings)

        val clusters = AdaptiveFaceClusterer.cluster(
            embeddingsMap = embeddings,
            flatIndex = flat,
            k = ClusteringConfig.KNN_K,
            minSimilarity = ClusteringConfig.KNN_MIN_SIMILARITY,
            minClusterSize = ClusteringConfig.KNN_MIN_CLUSTER_SIZE
        )

        val validClusters = clusters.filterKeys { it != -1 }
        assertEquals(
            "两个不同人应被分为两个簇，但当前配置产生了 ${validClusters.size} 个簇",
            2,
            validClusters.size
        )
        validClusters.values.forEach { members ->
            assertEquals(10, members.size)
        }
    }

    @Test
    fun `current config separates lookalikes`() {
        // 外貌相近的两个人（兄妹/明星撞脸）：组间 0.50，组内 0.80
        val embeddings = buildTwoPersonEmbeddings(
            perPerson = 12,
            withinSim = 0.80f,
            acrossSim = 0.50f
        )
        val flat = flatIndex(embeddings)

        val clusters = AdaptiveFaceClusterer.cluster(
            embeddingsMap = embeddings,
            flatIndex = flat,
            k = ClusteringConfig.KNN_K,
            minSimilarity = ClusteringConfig.KNN_MIN_SIMILARITY,
            minClusterSize = ClusteringConfig.KNN_MIN_CLUSTER_SIZE
        )

        val validClusters = clusters.filterKeys { it != -1 }
        assertEquals(
            "外貌相近的两个人也应被分开，实际产生了 ${validClusters.size} 个簇",
            2,
            validClusters.size
        )
    }

    @Test
    fun `loose 0_40 threshold creates fewer but wrong clusters`() {
        // 用 0.40 阈值跑同样数据，会建立大量跨组弱边，导致簇结构不稳定。
        // 这里不断言具体簇数，只验证 0.40 比 0.65 产生更少的有效簇（更多合并/噪声）。
        val embeddings = buildTwoPersonEmbeddings(
            perPerson = 12,
            withinSim = 0.80f,
            acrossSim = 0.50f
        )
        val flat = flatIndex(embeddings)

        val looseClusters = AdaptiveFaceClusterer.cluster(
            embeddingsMap = embeddings,
            flatIndex = flat,
            k = 3,
            minSimilarity = 0.40f,
            minClusterSize = 2
        ).filterKeys { it != -1 }

        val strictClusters = AdaptiveFaceClusterer.cluster(
            embeddingsMap = embeddings,
            flatIndex = flat,
            k = ClusteringConfig.KNN_K,
            minSimilarity = ClusteringConfig.KNN_MIN_SIMILARITY,
            minClusterSize = ClusteringConfig.KNN_MIN_CLUSTER_SIZE
        ).filterKeys { it != -1 }

        assertTrue(
            "0.40 阈值应比当前配置产生更少的有效簇（更多错误合并），" +
                "loose=${looseClusters.size}, strict=${strictClusters.size}",
            looseClusters.size <= strictClusters.size
        )
    }

    @Test
    fun `strict threshold does not over-split one person`() {
        val embeddings = buildTwoPersonEmbeddings(
            perPerson = 10,
            withinSim = 0.80f,
            acrossSim = 0.35f
        )
        val singlePerson = embeddings.filterKeys { it == 100L }
        val flat = flatIndex(singlePerson)

        val clusters = AdaptiveFaceClusterer.cluster(
            embeddingsMap = singlePerson,
            flatIndex = flat,
            k = ClusteringConfig.KNN_K,
            minSimilarity = ClusteringConfig.KNN_MIN_SIMILARITY,
            minClusterSize = ClusteringConfig.KNN_MIN_CLUSTER_SIZE
        )

        val validClusters = clusters.filterKeys { it != -1 }
        assertEquals(
            "同一人 10 张相似照片不应被拆成多个簇",
            1,
            validClusters.size
        )
        assertEquals(10, validClusters.values.first().size)
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    private fun sampleVector(
        shared: FloatArray,
        diff: FloatArray,
        ws: Float,
        wd: Float,
        noiseScale: Float,
        seed: Long
    ): FloatArray {
        val arr = FloatArray(DIM)
        for (i in arr.indices) {
            val noise = (deterministicNoise(seed + i) - 0.5f) * 2 * noiseScale
            arr[i] = ws * shared[i] + wd * diff[i] + noise
        }
        return normalize(arr)
    }

    /** 确定性随机单位向量：seed 相同则结果相同，保证测试可复现。 */
    private fun deterministicUnitVector(seed: Long): FloatArray {
        val arr = FloatArray(DIM) { deterministicNoise(seed + it) - 0.5f }
        return normalize(arr)
    }

    private fun deterministicUnitVectorOrthogonalTo(base: FloatArray, seed: Long): FloatArray {
        val random = FloatArray(DIM) { deterministicNoise(seed + it) - 0.5f }
        val dot = random.zip(base).sumOf { (a, b) -> (a * b).toDouble() }.toFloat()
        val arr = FloatArray(DIM) { i -> random[i] - dot * base[i] }
        val norm = sqrt(arr.sumOf { (it * it).toDouble() }).toFloat()
        if (norm < 1e-6f) return deterministicUnitVectorOrthogonalTo(base, seed + 1)
        return FloatArray(DIM) { i -> arr[i] / norm }
    }

    /** 简单确定性伪随机 [0,1)。 */
    private fun deterministicNoise(seed: Long): Float {
        var x = seed * 747796405L + 2891336453L
        x = (x xor (x ushr 13)) * 1274126177L
        x = x xor (x ushr 16)
        return (x and 0xFFFFFFFFL).toFloat() / 0xFFFFFFFFL
    }

    private fun normalize(arr: FloatArray): FloatArray {
        var sum = 0.0
        for (v in arr) sum += v * v
        val norm = sqrt(sum).toFloat()
        return if (norm > 0f) FloatArray(arr.size) { i -> arr[i] / norm } else arr
    }
}
