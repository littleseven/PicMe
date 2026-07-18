package com.mamba.picme.domain.search

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSynonymsTest {

    @Test
    fun `expand gender term includes single-char label`() {
        assertTrue(SearchSynonyms.expand("女性").contains("女"))
        assertTrue(SearchSynonyms.expand("男人").contains("男"))
    }

    @Test
    fun `expand animal term includes common pet labels`() {
        val expanded = SearchSynonyms.expand("动物")
        assertTrue(
            "动物应扩展到常见宠物标签，实际: $expanded",
            expanded.contains("猫") || expanded.contains("狗") || expanded.contains("宠物")
        )
    }

    @Test
    fun `expand unknown term returns itself`() {
        val expanded = SearchSynonyms.expand("某随机词")
        assertTrue(expanded.contains("某随机词"))
    }

    @Test
    fun `expand always includes original query`() {
        assertTrue(SearchSynonyms.expand("女性").contains("女性"))
    }
}
