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
    fun `default persona suffix is date line plus reply language rule`() {
        val suffix = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertEquals(
            "\n\n当前日期：2026-08-22。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。" +
                "\n\nApp 界面语言为简体中文。请始终用简体中文回复用户，无论本提示词、工具描述或工具返回内容使用何种语言。工具返回内容可能是英文或其他语言——请用简体中文总结转述。",
            suffix
        )
    }

    @Test
    fun `persona segment appended after date line`() {
        val suffix = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.WARM, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertEquals(
            "\n\n当前日期：2026-08-22。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。" +
                "\n\n你的语气温暖贴心：先回应用户的情绪，共情之后再给出回答或建议；多使用肯定与鼓励的措辞，让用户感到被理解和支持。" +
                "\n\nApp 界面语言为简体中文。请始终用简体中文回复用户，无论本提示词、工具描述或工具返回内容使用何种语言。工具返回内容可能是英文或其他语言——请用简体中文总结转述。",
            suffix
        )
    }

    @Test
    fun `persona segment follows reply language`() {
        val en = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.CONCISE, ReplyLanguage.ENGLISH, today
        )
        assertTrue(en.contains("crisp and efficient"))
        assertFalse(en.contains("简洁干练"))
    }

    @Test
    fun `reply language rule segment follows reply language`() {
        val en = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.ENGLISH, today
        )
        assertTrue(en.contains("Always reply to the user in English"))

        val simplified = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertTrue(simplified.contains("始终用简体中文回复"))

        val traditional = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.TRADITIONAL_CHINESE, today
        )
        assertTrue(traditional.contains("始終用繁體中文回覆"))
    }
}
