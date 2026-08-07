package com.mamba.picme.domain.search

import com.mamba.picme.domain.model.AppLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证人物类查询的人物搜索识别行为。
 *
 * 策略：
 * 1. 主观/口语化人物词（如“美女”“女人”）不触发 hasFace 回退，完全依赖 MobileCLIP 语义召回。
 * 2. MediaSearchEngine 中已彻底移除 SQL 标签未命中时的 hasFace 回退逻辑。
 * 3. QueryParser 仍保留传统人物词识别，供其他可能的上层调用使用。
 */
class PeopleSearchRecognitionTest {

    @Test
    fun `QueryParser 仍识别传统人物词`() {
        assertTrue("'人' 应被识别为人物搜索", QueryParser.isPeopleSearch("人"))
        assertTrue("'小孩' 应被识别为人物搜索", QueryParser.isPeopleSearch("小孩"))
        assertTrue("'合影' 应被识别为人物搜索", QueryParser.isPeopleSearch("合影"))
    }

    @Test
    fun `QueryParser 仍能解析人物词为结构化过滤`() {
        val filter = QueryParser.parse("小孩", AppLanguage.CHINESE)
        assertFalse("人物词解析后不应需要 LLM", filter?.needsLlm ?: true)
    }
}
