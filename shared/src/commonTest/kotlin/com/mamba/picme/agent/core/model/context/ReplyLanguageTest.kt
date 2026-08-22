package com.mamba.picme.agent.core.model.context

import com.mamba.picme.domain.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyLanguageTest {

    @Test
    fun `explicit app languages map directly`() {
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.ENGLISH.toReplyLanguage("zh-CN"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.CHINESE.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.TRADITIONAL_CHINESE.toReplyLanguage("en-US"))
    }

    @Test
    fun `system resolves simplified chinese locales`() {
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-CN"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hans"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-SG"))
    }

    @Test
    fun `system resolves traditional chinese locales`() {
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-TW"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hant-HK"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-MO"))
    }

    @Test
    fun `system falls back to english for non chinese locales`() {
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("ja-JP"))
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("fr-FR"))
    }

    @Test
    fun `system locale tag edge cases`() {
        // 裸 "zh"（无子标签）→ 简体
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh"))
        // 显式 Hans 脚本 + 地区
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hans-CN"))
        // 大写 tag 经 lowercase 归一化
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("ZH-HANT-TW"))
        // 空串 → 英文兜底
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage(""))
        // "zh*" 但非 "zh-" 的畸形 tag → 英文（锁定收紧后的子标签边界）
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("zhcn"))
        // 裸脚本 tag（iOS 真实形态）→ 繁体
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hant"))
    }
}
