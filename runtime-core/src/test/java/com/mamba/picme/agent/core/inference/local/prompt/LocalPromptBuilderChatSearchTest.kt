package com.mamba.picme.agent.core.inference.local.prompt

import com.mamba.picme.agent.core.runtime.state.SceneManager
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回归：chat 页搜相册必须在 CHAT 场景把 search_media / refine_media_search 暴露给 LLM，
 * 否则 LLM 只能用 navigate_to(gallery)（"已切换到gallery" bug）。
 */
class LocalPromptBuilderChatSearchTest {

    private lateinit var builder: LocalPromptBuilder

    @Before
    fun setup() {
        builder = LocalPromptBuilder(SceneManager.getInstance())
    }

    @Test
    fun `CHAT scene advertises search_media and refine_media_search`() {
        val section = builder.buildL2CapabilitiesSection(SceneManager.Scene.CHAT)
        assertTrue("CHAT 应广告 search_media，实际:\n$section", section.contains("search_media"))
        assertTrue("CHAT 应广告 refine_media_search，实际:\n$section", section.contains("refine_media_search"))
    }

    @Test
    fun `CHAT scene tells LLM results show in-chat without navigating`() {
        val section = builder.buildL2CapabilitiesSection(SceneManager.Scene.CHAT)
        assertTrue(
            "CHAT 应提示搜索结果直接在对话中展示、不要导航，实际:\n$section",
            section.contains("直接显示") || section.contains("不要") || section.contains("无需")
        )
    }

    @Test
    fun `GALLERY scene still advertises search_media`() {
        val section = builder.buildL2CapabilitiesSection(SceneManager.Scene.GALLERY)
        assertTrue(section.contains("search_media"))
    }
}
