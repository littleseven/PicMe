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
}
