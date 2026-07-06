package com.mamba.picme.domain.tag

import android.graphics.Bitmap
import com.mamba.picme.domain.model.AppLanguage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun `classify returns Chinese labels for CHINESE and English labels for ENGLISH`() {
        val engine = mockk<MobileClipEngine>(relaxed = true)
        val tokenizer = mockk<MobileClipTokenizer>(relaxed = true)
        val vocab = ControlledVocab(
            scene = listOf("室内"),
            sceneEn = listOf("indoor"),
            objects = listOf("猫"),
            objectsEn = listOf("cat"),
            people = listOf("男性"),
            peopleEn = listOf("male")
        )

        every { engine.initializeWithFallback() } returns true
        every { tokenizer.load() } returns true
        every { tokenizer.encode(any()) } returns longArrayOf(1L, 2L, 3L)
        every { engine.encodeText(any()) } returns floatArrayOf(1f, 0f)
        every { engine.encodeImage(any()) } returns floatArrayOf(1f, 0f)

        val classifier = MobileClipTagClassifier(engine, tokenizer, vocab)
        assertEquals(true, classifier.warmUp())

        val bitmap = mockk<Bitmap>(relaxed = true)
        val zh = classifier.classify(bitmap, AppLanguage.CHINESE)
        val en = classifier.classify(bitmap, AppLanguage.ENGLISH)

        assertNotNull(zh)
        assertEquals("室内", zh?.scene)
        assertEquals(listOf("猫"), zh?.objects)
        assertEquals(listOf("男性"), zh?.tags)

        assertNotNull(en)
        assertEquals("indoor", en?.scene)
        assertEquals(listOf("cat"), en?.objects)
        assertEquals(listOf("male"), en?.tags)
    }

    @Test
    fun `classify returns null for unsupported language`() {
        val engine = mockk<MobileClipEngine>(relaxed = true)
        val tokenizer = mockk<MobileClipTokenizer>(relaxed = true)
        val vocab = ControlledVocab(
            scene = listOf("室内"),
            sceneEn = listOf("indoor")
        )

        every { engine.initializeWithFallback() } returns true
        every { tokenizer.load() } returns true
        every { tokenizer.encode(any()) } returns longArrayOf(1L)
        every { engine.encodeText(any()) } returns floatArrayOf(1f, 0f)
        every { engine.encodeImage(any()) } returns floatArrayOf(1f, 0f)

        val classifier = MobileClipTagClassifier(engine, tokenizer, vocab)
        assertEquals(true, classifier.warmUp())

        val bitmap = mockk<Bitmap>(relaxed = true)
        assertNull(classifier.classify(bitmap, AppLanguage.SYSTEM))
        assertNull(classifier.classify(bitmap, AppLanguage.TRADITIONAL_CHINESE))
    }
}
