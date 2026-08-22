package com.mamba.picme.agent.core.model.config

import com.mamba.picme.agent.core.model.context.ReplyLanguage
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantPersonaTest {

    @Test
    fun `default persona injects nothing`() {
        ReplyLanguage.entries.forEach { language ->
            assertNull(personaPromptSegment(AssistantPersona.DEFAULT, language))
        }
    }

    @Test
    fun `warm persona has segment per language`() {
        assertTrue(personaPromptSegment(AssistantPersona.WARM, ReplyLanguage.SIMPLIFIED_CHINESE)!!.contains("温暖贴心"))
        assertTrue(personaPromptSegment(AssistantPersona.WARM, ReplyLanguage.TRADITIONAL_CHINESE)!!.contains("溫暖貼心"))
        assertTrue(personaPromptSegment(AssistantPersona.WARM, ReplyLanguage.ENGLISH)!!.contains("warm and caring"))
    }

    @Test
    fun `lively persona has segment per language`() {
        assertTrue(personaPromptSegment(AssistantPersona.LIVELY, ReplyLanguage.SIMPLIFIED_CHINESE)!!.contains("emoji"))
        assertTrue(personaPromptSegment(AssistantPersona.LIVELY, ReplyLanguage.TRADITIONAL_CHINESE)!!.contains("emoji"))
        assertTrue(personaPromptSegment(AssistantPersona.LIVELY, ReplyLanguage.ENGLISH)!!.contains("lively and humorous"))
    }

    @Test
    fun `concise persona has segment per language`() {
        assertTrue(personaPromptSegment(AssistantPersona.CONCISE, ReplyLanguage.SIMPLIFIED_CHINESE)!!.contains("简洁干练"))
        assertTrue(personaPromptSegment(AssistantPersona.CONCISE, ReplyLanguage.TRADITIONAL_CHINESE)!!.contains("簡潔幹練"))
        assertTrue(personaPromptSegment(AssistantPersona.CONCISE, ReplyLanguage.ENGLISH)!!.contains("crisp and efficient"))
    }
}
