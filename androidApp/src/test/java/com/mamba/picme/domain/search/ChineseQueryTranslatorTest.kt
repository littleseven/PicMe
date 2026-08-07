package com.mamba.picme.domain.search

import android.content.Context
import android.util.Log
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.ChineseQueryTranslator
import com.mamba.picme.domain.tag.i18n.OpusMtTranslator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 验证中文查询翻译器对人物类查询的处理。
 *
 * 这些测试用于定位“美女/女人”检索不到图片的根因：
 * 1. 词表覆盖不足导致部分常见中文词 fallback 到原中文，CLIP tokenizer 无法正确编码。
 * 2. 人物类查询未被识别为人物搜索，无法触发 hasFace 回退。
 */
class ChineseQueryTranslatorTest {

    private val context: Context = mockk(relaxed = true)

    @Before
    fun setup() {
        // 屏蔽 Android Log，避免 JVM 测试崩溃
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @Test
    fun `美女命中词表并扩展为多个英文候选`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("美女" to "beautiful woman"),
            enToZh = emptyMap(),
            enSynonyms = emptyMap()
        )
        val translator = ChineseQueryTranslator(context, vocab, translator = null)

        val candidates = translator.expandForClip("美女")

        // 词表翻译 + CLIP 扩展共同作用，返回多个英文候选以提升召回
        assertEquals(
            listOf("beautiful woman", "woman", "female", "portrait of a woman"),
            candidates
        )
    }

    @Test
    fun `女人未命中词表但可通过 CLIP 扩展获得英文候选`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("美女" to "beautiful woman"),
            enToZh = emptyMap(),
            enSynonyms = emptyMap()
        )
        // 模拟 OPUS-MT 翻译失败（模型未下载/加载失败）
        val failingTranslator = mockk<OpusMtTranslator> {
            every { translate("女人") } throws IllegalStateException("OPUS-MT model not available")
        }
        val translator = ChineseQueryTranslator(context, vocab, translator = failingTranslator)

        val candidates = translator.expandForClip("女人")

        // 修复：即使词表未命中且 OPUS-MT 不可用，CLIP_QUERY_EXPANSIONS 也能提供英文候选，
        // 避免中文字符直接进入 CLIP tokenizer 导致 embedding 质量差。
        // 当 fallback 为原中文时会被视为无效翻译，仅返回英文扩展候选。
        assertEquals(
            listOf("woman", "female", "adult woman"),
            candidates
        )
        assertFalse(translator.containsChinese(candidates.first()))
    }

    @Test
    fun `女人命中词表时扩展为对应英文及同义候选`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("女人" to "woman"),
            enToZh = emptyMap(),
            enSynonyms = emptyMap()
        )
        val translator = ChineseQueryTranslator(context, vocab, translator = null)

        // 词表翻译 + CLIP 查询扩展共同作用，返回多个英文候选以提升召回
        assertEquals(
            listOf("woman", "female", "adult woman"),
            translator.expandForClip("女人")
        )
    }

    @Test
    fun `美女命中词表时扩展为多个英文候选`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("美女" to "beautiful woman"),
            enToZh = emptyMap(),
            enSynonyms = emptyMap()
        )
        val translator = ChineseQueryTranslator(context, vocab, translator = null)

        assertEquals(
            listOf("beautiful woman", "woman", "female", "portrait of a woman"),
            translator.expandForClip("美女")
        )
    }

    @Test
    fun `纯英文人物查询直接返回原词`() {
        val translator = ChineseQueryTranslator(context, BilingualVocab.empty(), translator = null)

        assertEquals(listOf("woman"), translator.expandForClip("woman"))
        assertEquals(listOf("beautiful woman"), translator.expandForClip("beautiful woman"))
    }

    @Test
    fun `包含中文的查询被识别为中文查询`() {
        val translator = ChineseQueryTranslator(context, BilingualVocab.empty(), translator = null)

        assertTrue(translator.containsChinese("美女"))
        assertTrue(translator.containsChinese("女人"))
        assertFalse(translator.containsChinese("woman"))
    }

    @Test
    fun `大美女模型翻译异常时只返回扩展英文候选`() {
        val vocab = BilingualVocab.empty()
        // 模拟 OPUS-MT 输出异常翻译 "Oh, oh, my God."
        val badTranslator = mockk<OpusMtTranslator> {
            every { translate("大美女") } returns "Oh, oh, my God."
        }
        val translator = ChineseQueryTranslator(context, vocab, translator = badTranslator)

        val candidates = translator.expandForClip("大美女")

        // 异常翻译应被过滤，仅返回 CLIP_QUERY_EXPANSIONS 中的英文候选
        assertEquals(
            listOf("beautiful woman", "woman", "female", "portrait of a woman"),
            candidates
        )
        assertFalse(candidates.any { it.contains("God", ignoreCase = true) })
    }
}
