package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptSuffixTest {

    private val today = "2026-08-22"

    @Test
    fun `default persona suffix is date line only`() {
        val suffix = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertEquals(
            "\n\n当前日期：2026-08-22。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。",
            suffix
        )
    }

    @Test
    fun `persona segment appended after date line`() {
        val suffix = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.WARM, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertTrue(suffix.startsWith("\n\n当前日期：2026-08-22"))
        assertTrue(suffix.contains("温暖贴心"))
    }

    @Test
    fun `persona segment follows reply language`() {
        val en = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.CONCISE, ReplyLanguage.ENGLISH, today
        )
        assertTrue(en.contains("crisp and efficient"))
        assertFalse(en.contains("简洁干练"))
    }
}
