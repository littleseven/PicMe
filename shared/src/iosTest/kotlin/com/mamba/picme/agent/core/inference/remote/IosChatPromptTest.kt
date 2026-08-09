package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.inference.remote.tool.ChatToolManifest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS chat prompt 的诚实性守卫（Phase 6.2 T3）：prompt 不得引用 iOS 未注册的能力，
 * 防止 LLM 幻觉调用不存在的工具。
 */
class IosChatPromptTest {

    private val prompt = IosChatPrompt.build(ChatToolManifest.buildDescriptors())

    @Test
    fun `prompt contains tool inventory for the 8 registered tools`() {
        assertTrue(prompt.contains("可用工具（8）"), "应含 8 工具清单头，实际 prompt 头部：${prompt.take(200)}")
        assertTrue(prompt.contains("search_media"))
        assertTrue(prompt.contains("refine_media_search"))
        assertTrue(prompt.contains("delete_media"))
    }

    @Test
    fun `prompt does not reference unregistered capabilities`() {
        val banned = listOf(
            "run_gallery_script", "draw_chart", "capability.dispatch",
            "ai_optimize", "adjust_image", "edit_image",
            "remember_fact", "recall_memory", "remember_person_relation",
            "start_tag_scan", "bridge.callAsync",
        )
        for (token in banned) {
            assertFalse(prompt.contains(token), "iOS prompt 不应引用未注册能力：$token")
        }
    }

    @Test
    fun `prompt keeps multi turn refine discipline`() {
        assertTrue(prompt.contains("多轮窄化规则"))
        assertTrue(prompt.contains("fromMs"))
        assertTrue(prompt.contains("不要调用 finish"))
    }
}
