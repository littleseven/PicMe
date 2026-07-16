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

    @Test
    fun `CHAT scene examples exclude camera capture commands`() {
        val section = builder.buildL2CapabilitiesSection(SceneManager.Scene.CHAT)
        assertTrue("CHAT 示例应包含 text_reply，实际:\n$section", section.contains("text_reply"))
        assertTrue("CHAT 示例应包含 search_media，实际:\n$section", section.contains("search_media"))
        assertTrue(
            "CHAT 示例不应包含 capture（相机命令不应在聊天页示例中出现），实际:\n$section",
            !section.contains("\"method\":\"capture\"")
        )
    }

    @Test
    fun `CAMERA scene examples still include capture commands`() {
        val section = builder.buildL2CapabilitiesSection(SceneManager.Scene.CAMERA)
        assertTrue(
            "CAMERA 示例应保留 capture 命令，实际:\n$section",
            section.contains("\"method\":\"capture\"")
        )
    }

    @Test
    fun `CHAT scene system prompt forbids camera commands`() {
        val sceneManager = SceneManager.getInstance()
        sceneManager.transitionTo(SceneManager.Scene.CHAT, saveToHistory = false)
        try {
            val prompt = builder.buildL2SystemPrompt(
                emptyList(),
                com.mamba.picme.agent.core.model.context.AgentContext(
                    scene = com.mamba.picme.agent.core.model.context.AgentScene.CHAT
                )
            )
            assertTrue(
                "CHAT 场景 system prompt 应禁止输出相机命令，实际:\n$prompt",
                prompt.contains("禁止在聊天页输出 capture") || prompt.contains("当前页面没有相机能力")
            )
        } finally {
            sceneManager.leaveScene(SceneManager.Scene.CHAT)
        }
    }

    @Test
    fun `CHAT scene advertises feedback more and exclude commands`() {
        val section = builder.buildL2CapabilitiesSection(SceneManager.Scene.CHAT)
        assertTrue("CHAT 应广告 feedback 命令，实际:\n$section", section.contains("feedback"))
        assertTrue("CHAT 应广告 more 命令，实际:\n$section", section.contains("more"))
        assertTrue("CHAT 应广告 exclude 命令，实际:\n$section", section.contains("exclude"))
    }

    @Test
    fun `buildStateSection includes recentSearchResults when present`() {
        val snapshot = com.mamba.picme.agent.core.model.context.SearchResultSnapshot(
            query = "海边日落",
            results = listOf(com.mamba.picme.agent.core.model.context.ResultItem("img_001", listOf("海", "日落", "沙滩"))),
            totalCount = 8,
            isRefinement = false,
            timestamp = 0L
        )
        val ctx = com.mamba.picme.agent.core.model.context.AgentContext(
            scene = com.mamba.picme.agent.core.model.context.AgentScene.CHAT,
            recentSearchResults = listOf(snapshot)
        )
        val state = builder.buildStateSection(ctx, SceneManager.Scene.CHAT)
        assertTrue("状态片段应包含 query，实际:\n$state", state.contains("海边日落"))
        assertTrue("状态片段应包含 mediaId，实际:\n$state", state.contains("img_001"))
        assertTrue("状态片段应包含 tags，实际:\n$state", state.contains("日落"))
    }
}
