package com.mamba.picme.domain.tag

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 验证 MobileClipTokenizer 对常见英文/中文查询的编码行为。
 *
 * 这些测试用于定位 MobileCLIP 文本编码链路问题：
 * 1. 英文词（如 "woman"）是否能编码为有效 token IDs。
 * 2. 中文词在 vocab 未命中时是否会退化为空或字节级 token。
 */
class MobileClipTokenizerTest {

    private val context: Context = mockk(relaxed = true)
    private val assets: AssetManager = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0

        every { context.assets } returns assets
        // 提供一个真实临时目录作为 filesDir，避免 ModelPathConfig.getModelsBaseDir 空指针
        every { context.filesDir } returns kotlin.io.path.createTempDirectory("mobileclip_test_").toFile()
    }

    @Test
    fun `英文 woman 应编码为包含 BOS 和 EOS 的有效序列`() {
        val tokenizerJson = buildMinimalTokenizerJson(
            vocab = mapOf(
                "<|startoftext|>" to 49406L,
                "<|endoftext|>" to 49407L,
                "woman</w>" to 100L,
                "w" to 200L,
                "o" to 201L,
                "m" to 202L,
                "a" to 203L,
                "n" to 204L
            ),
            merges = listOf("w o", "wo m", "wom a", "woma n")
        )
        every { assets.open("tokenizer.json") } returns ByteArrayInputStream(tokenizerJson.toByteArray())

        val tokenizer = MobileClipTokenizer(context)
        assertTrue(tokenizer.load())

        val tokenIds = tokenizer.encode("woman")
        assertNotNull(tokenIds)
        assertEquals(77, tokenIds!!.size)
        assertEquals(49406L, tokenIds[0]) // BOS
        assertTrue("应包含 EOS", tokenIds.contains(49407L))
    }

    @Test
    fun `中文女人无对应 token 时仍会生成 BOS_EOS 序列`() {
        val tokenizerJson = buildMinimalTokenizerJson(
            vocab = mapOf(
                "<|startoftext|>" to 49406L,
                "<|endoftext|>" to 49407L
            ),
            merges = emptyList()
        )
        every { assets.open("tokenizer.json") } returns ByteArrayInputStream(tokenizerJson.toByteArray())

        val tokenizer = MobileClipTokenizer(context)
        assertTrue(tokenizer.load())

        val tokenIds = tokenizer.encode("女人")
        assertNotNull(tokenIds)
        assertEquals(77, tokenIds!!.size)
        assertEquals(49406L, tokenIds[0]) // BOS
        assertEquals(49407L, tokenIds[1]) // EOS（无内容 token）
    }

    private fun buildMinimalTokenizerJson(
        vocab: Map<String, Long>,
        merges: List<String>
    ): String {
        val vocabJson = vocab.entries.joinToString(",\n            ") { "\"${it.key}\": ${it.value}" }
        val mergesJson = merges.joinToString(",\n            ") { "\"$it\"" }
        return """
        {
          "model": {
            "vocab": {
              $vocabJson
            },
            "merges": [
              $mergesJson
            ]
          }
        }
        """.trimIndent()
    }
}
