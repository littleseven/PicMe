package com.mamba.picme.domain.search

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.tag.MobileClipEngine
import com.mamba.picme.domain.tag.MobileClipTokenizer
import com.mamba.picme.domain.tag.i18n.ChineseQueryTranslator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 验证 MobileCLIP 语义搜索引擎的过滤行为。
 *
 * 关键测试：filter.keywords 用于 SQL 标签/文件名搜索，
 * 在语义召回层必须被忽略，否则跨语言/跨词汇搜索会永远为空。
 */
class SemanticSearchEngineTest {

    private val context: Context = mockk(relaxed = true)
    private val mediaDao: MediaDao = mockk(relaxed = true)
    private val mobileClipEngine: MobileClipEngine = mockk(relaxed = true)
    private val tokenizer: MobileClipTokenizer = mockk(relaxed = true)
    private val queryTranslator: ChineseQueryTranslator = mockk(relaxed = true)

    @Before
    fun setup() {
        // 屏蔽 Android Log/Base64，避免 JVM 测试崩溃
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @Test
    fun `filter keywords should not exclude semantic candidates`() = runBlocking {
        // Given: 一张图片，标签是中文且不含 "woman"，但有有效的 semanticEmbedding
        val embedding = FloatArray(512) { 0.1f }
        val entity = MediaEntity(
            id = 1L,
            uri = "content://test/1",
            type = MediaType.PHOTO,
            captureDate = System.currentTimeMillis(),
            fileName = "IMG_0001.jpg",
            labels = "[\"人物\",\"室内\"]",
            semanticEmbedding = encodeEmbeddingBase64(embedding)
        )

        // 语义引擎已就绪
        every { mobileClipEngine.isInitialized } returns true
        every { mobileClipEngine.isTextLoaded } returns true
        every { tokenizer.isReady() } returns true

        // 查询扩展："woman" -> ["woman"]
        every { queryTranslator.expandForClip("woman") } returns listOf("woman")

        // tokenizer 编码
        every { tokenizer.encode("woman") } returns longArrayOf(1L, 2L, 3L)

        // 文本编码返回一个归一化向量
        val textEmbedding = FloatArray(512) { 0.05f }.also { normalize(it) }
        every { mobileClipEngine.encodeText(any()) } returns textEmbedding

        // 相似度：固定 0.8
        every { mobileClipEngine.cosineSimilarity(any(), any()) } returns 0.8f

        // DAO 返回该候选（这是核心：它会被 filter.keywords 排除，但语义层应保留）
        // 使用 ID-based 方法避免 OOM
        coEvery { mediaDao.getMediaWithSemanticEmbeddingIds() } returns listOf(1L)
        coEvery { mediaDao.getMediaByIds(listOf(1L)) } returns listOf(entity)

        val engine = SemanticSearchEngine(
            context = context,
            mediaDao = mediaDao,
            mobileClipEngine = mobileClipEngine,
            queryTranslator = queryTranslator,
            mobileClipTokenizer = tokenizer
        )

        // 关键：filter.keywords = ["woman"] 用于 SQL 层时，这张中文标签图片会被排除；
        // 但语义召回必须忽略 keywords，只基于向量相似度。
        val filter = StructuredFilter(keywords = listOf("woman"))

        // When
        val results = engine.searchByText("woman", filter = filter, topK = 50)

        // Then
        assertEquals("语义召回不应被 filter.keywords 过滤掉", 1, results.size)
        assertEquals(1L, results.first().media.id)
    }

    /**
     * FloatArray → Base64（大端序 float32，与生产代码一致）
     */
    private fun encodeEmbeddingBase64(embedding: FloatArray): String {
        val bytes = ByteArray(embedding.size * 4)
        for (i in embedding.indices) {
            val bits = java.lang.Float.floatToIntBits(embedding[i])
            bytes[i * 4] = (bits shr 24 and 0xFF).toByte()
            bytes[i * 4 + 1] = (bits shr 16 and 0xFF).toByte()
            bytes[i * 4 + 2] = (bits shr 8 and 0xFF).toByte()
            bytes[i * 4 + 3] = (bits and 0xFF).toByte()
        }
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun normalize(array: FloatArray) {
        var norm = 0f
        for (v in array) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) {
            for (i in array.indices) array[i] /= norm
        }
    }
}
