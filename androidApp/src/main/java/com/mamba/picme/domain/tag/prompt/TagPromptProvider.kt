package com.mamba.picme.domain.tag.prompt

import com.mamba.picme.domain.model.AppLanguage

/**
 * TAG 生成 Prompt 提供者
 *
 * 根据当前用户语言返回不同的 system/user prompt，使：
 * - 中文用户获得中文标签
 * - 英文用户获得英文标签
 *
 * 实现类必须保证输出格式一致（JSON），仅语言不同。
 */
interface TagPromptProvider {
    fun systemPrompt(lang: AppLanguage): String
    fun userPrompt(lang: AppLanguage, faceCount: Int, isGroupPhoto: Boolean): String

    /** 精简版：只要求 Qwen 输出 activity 和 summary */
    fun systemPromptForActivityAndSummary(lang: AppLanguage): String
    fun userPromptForActivityAndSummary(lang: AppLanguage, faceCount: Int, isGroupPhoto: Boolean): String
}
