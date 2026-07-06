package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileClipTagClassifierTest {

    @Test
    fun `topK respects k and threshold`() {
        // 模拟 3 个候选标签的 embedding，与查询向量点积分别为 0.5, 0.2, 0.1
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf("high", "medium", "low")
        val embeddings = mapOf(
            "high" to floatArrayOf(0.5f, 0f),
            "medium" to floatArrayOf(0.2f, 0f),
            "low" to floatArrayOf(0.1f, 0f)
        )

        val result = topKForTest(k = 2, threshold = 0.15f, candidates, query, embeddings)

        assertEquals(listOf("high", "medium"), result)
    }

    @Test
    fun `topK filters below threshold`() {
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf("a", "b")
        val embeddings = mapOf(
            "a" to floatArrayOf(0.5f, 0f),
            "b" to floatArrayOf(0.1f, 0f)
        )

        val result = topKForTest(k = 5, threshold = 0.2f, candidates, query, embeddings)

        assertEquals(listOf("a"), result)
    }

    @Test
    fun `topK returns empty when no candidate meets threshold`() {
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf("x", "y")
        val embeddings = mapOf(
            "x" to floatArrayOf(0.1f, 0f),
            "y" to floatArrayOf(0.05f, 0f)
        )

        val result = topKForTest(k = 3, threshold = 0.2f, candidates, query, embeddings)

        assertEquals(emptyList<String>(), result)
    }

    private fun topKForTest(
        k: Int,
        threshold: Float,
        candidates: List<String>,
        query: FloatArray,
        embeddings: Map<String, FloatArray>
    ): List<String> {
        return candidates.mapNotNull { label ->
            val emb = embeddings[label] ?: return@mapNotNull null
            val sim = cosineSimilarityForTest(query, emb)
            if (sim >= threshold) label to sim else null
        }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    private fun cosineSimilarityForTest(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
