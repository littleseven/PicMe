package com.mamba.picme.domain.tag

import com.mamba.picme.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 [ImageDescriptionStrategyResolver]：Qwen3-VL 按 UI 语言直出提示词；
 * Florence-2 不用提示词（走 caption），中文 UI 需 en→zh 翻译。
 */
class ImageDescriptionStrategyTest {

    @Test
    fun qwen_chinese_outputs_chinese_prompts_and_no_translate() {
        val s = ImageDescriptionStrategyResolver.resolve("qwen3_vl_2b", AppLanguage.CHINESE)
        assertTrue(s.systemPrompt.contains("图像理解助手"))
        assertEquals("请描述这张图片", s.userPrompt)
        assertFalse(s.needsZhTranslate)
    }

    @Test
    fun qwen_traditional_chinese_also_uses_chinese_prompts() {
        val s = ImageDescriptionStrategyResolver.resolve("qwen3_vl_2b", AppLanguage.TRADITIONAL_CHINESE)
        assertTrue(s.systemPrompt.contains("图像理解助手"))
        assertFalse(s.needsZhTranslate)
    }

    @Test
    fun qwen_english_outputs_english_prompts_and_no_translate() {
        val s = ImageDescriptionStrategyResolver.resolve("qwen3_vl_2b", AppLanguage.ENGLISH)
        assertTrue(s.systemPrompt.contains("image understanding assistant"))
        assertEquals("Describe this image", s.userPrompt)
        assertFalse(s.needsZhTranslate)
    }

    @Test
    fun florence2_chinese_needs_translate_and_ignores_prompts() {
        val s = ImageDescriptionStrategyResolver.resolve("florence2_base", AppLanguage.CHINESE)
        assertEquals("", s.systemPrompt)
        assertEquals("", s.userPrompt)
        assertTrue(s.needsZhTranslate)
    }

    @Test
    fun florence2_english_no_translate() {
        val s = ImageDescriptionStrategyResolver.resolve("florence2_base", AppLanguage.ENGLISH)
        assertEquals("", s.systemPrompt)
        assertFalse(s.needsZhTranslate)
    }
}
