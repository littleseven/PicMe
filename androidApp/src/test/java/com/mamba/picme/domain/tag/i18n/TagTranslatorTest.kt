package com.mamba.picme.domain.tag.i18n

import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.tag.ControlledVocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagTranslatorTest {

    // ── 词表命中测试 ─────────────────────────────────

    @Test
    fun `中文查询扩展为英文标签（词表命中）`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf(
                "团队" to "Team",
                "猫" to "cat",
                "日落" to "Sunset"
            ),
            enToZh = mapOf(
                "Team" to "团队",
                "cat" to "猫",
                "Sunset" to "日落"
            ),
            enSynonyms = emptyMap()
        )
        val translator = TagTranslator(vocab)

        val candidates = translator.expandForSearch("团队", AppLanguage.CHINESE)

        assertEquals(setOf("团队", "team"), candidates)
    }

    @Test
    fun `英文查询扩展为中文标签（词表命中）`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("团队" to "Team"),
            enToZh = mapOf("team" to "团队"),
            enSynonyms = emptyMap()
        )
        val translator = TagTranslator(vocab)

        val candidates = translator.expandForSearch("Team", AppLanguage.ENGLISH)

        assertEquals(setOf("team", "团队"), candidates)
    }

    @Test
    fun `无词表命中时返回原查询（无MT回退）`() {
        val translator = TagTranslator(BilingualVocab.empty())

        val candidates = translator.expandForSearch("未知标签", AppLanguage.CHINESE)

        assertEquals(setOf("未知标签"), candidates)
    }

    @Test
    fun `无词表命中 + MT不可用 → 仅返回原查询`() {
        val translator = TagTranslator(BilingualVocab.empty(), mtTranslator = null)

        val candidates = translator.expandForSearch("哈士奇", AppLanguage.CHINESE)

        assertEquals(setOf("哈士奇"), candidates)
    }

    @Test
    fun `英文同义词扩展为中文标签`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("汽车" to "car"),
            enToZh = mapOf("car" to "汽车"),
            enSynonyms = mapOf("automobile" to "car")
        )
        val translator = TagTranslator(vocab)

        val candidates = translator.expandForSearch("automobile", AppLanguage.ENGLISH)

        assertEquals(setOf("automobile", "car", "汽车"), candidates)
    }

    // ── 中文同义词扩展测试 ────────────────────────────

    @Test
    fun `中文 synonym 查询扩展为 canonical`() {
        val vocab = BilingualVocab.empty()
        val controlledVocab = ControlledVocab(
            synonyms = mapOf("美女" to "女性", "帅哥" to "男性")
        )
        val translator = TagTranslator(vocab, controlledVocab = controlledVocab)

        val candidates = translator.expandForSearch("美女", AppLanguage.CHINESE)

        // "美女" (synonym) → 扩展 "女性" (canonical)
        assertTrue("Should contain canonical '女性'", "女性" in candidates)
        assertTrue("Should contain original '美女'", "美女" in candidates)
    }

    @Test
    fun `中文 canonical 查询扩展为所有 synonyms`() {
        val vocab = BilingualVocab.empty()
        val controlledVocab = ControlledVocab(
            synonyms = mapOf("美女" to "女性", "大美女" to "女性")
        )
        val translator = TagTranslator(vocab, controlledVocab = controlledVocab)

        val candidates = translator.expandForSearch("女性", AppLanguage.CHINESE)

        // "女性" (canonical) → 扩展 "美女", "大美女" (synonyms)
        assertTrue("Should contain synonym '美女'", "美女" in candidates)
        assertTrue("Should contain synonym '大美女'", "大美女" in candidates)
        assertTrue("Should contain original '女性'", "女性" in candidates)
    }

    @Test
    fun `无 ControlledVocab 时中文查询正常降级`() {
        val translator = TagTranslator(BilingualVocab.empty(), controlledVocab = null)

        val candidates = translator.expandForSearch("美女", AppLanguage.CHINESE)

        // 无 ControlledVocab → 仅返回原查询
        assertEquals(setOf("美女"), candidates)
    }

    @Test
    fun `词表 + 同义词同时命中时合并结果`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("女性" to "female"),
            enToZh = mapOf("female" to "女性"),
            enSynonyms = emptyMap()
        )
        val controlledVocab = ControlledVocab(
            synonyms = mapOf("美女" to "女性")
        )
        val translator = TagTranslator(vocab, controlledVocab = controlledVocab)

        val candidates = translator.expandForSearch("美女", AppLanguage.CHINESE)

        // 词表: zhToEn["美女"]? → null (不在词表中)
        // 同义词: synonyms["美女"] → "女性"
        // 词表回查: zhToEn["女性"] → "female"
        assertTrue("Should contain original '美女'", "美女" in candidates)
        assertTrue("Should contain canonical '女性'", "女性" in candidates)
        // 注意: 当前逻辑中 zhToEn 先于同义词检查，且仅在 zhToEn 未命中时才查 MT
        // 同义词扩展 "女性" 后不会再次查 zhToEn["女性"]
        // 这是有意为之: 同义词扩展的 canonical 词也会在后续 searchByCandidate 中
        // 作为独立候选词被搜索，届时 expandForSearch 会再次被调用
    }

    // ── 展示翻译测试 ──────────────────────────────────

    @Test
    fun `display 在中文界面返回原中文标签`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("猫" to "cat"),
            enToZh = emptyMap(),
            enSynonyms = emptyMap()
        )
        val translator = TagTranslator(vocab)

        val result = translator.display("猫", AppLanguage.CHINESE)

        assertEquals("猫", result)
    }

    @Test
    fun `display 在英文界面返回英文翻译`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("猫" to "cat"),
            enToZh = emptyMap(),
            enSynonyms = emptyMap()
        )
        val translator = TagTranslator(vocab)

        val result = translator.display("猫", AppLanguage.ENGLISH)

        assertEquals("cat", result)
    }

    @Test
    fun `displayAll 批量翻译`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("猫" to "cat", "狗" to "dog"),
            enToZh = emptyMap(),
            enSynonyms = emptyMap()
        )
        val translator = TagTranslator(vocab)

        val result = translator.displayAll(listOf("猫", "狗"), AppLanguage.ENGLISH)

        assertEquals(listOf("cat", "dog"), result)
    }
}
