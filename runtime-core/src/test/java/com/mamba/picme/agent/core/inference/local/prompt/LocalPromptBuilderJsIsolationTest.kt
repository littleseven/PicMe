package com.mamba.picme.agent.core.inference.local.prompt

import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.runtime.state.SceneManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回归：本地小模型（Qwen3.5-2B）的 system prompt 绝不能暴露 JS 沙箱 / 图表能力。
 *
 * `run_gallery_script` / `draw_chart` / `capability.dispatch` / `bridge.callAsync` 仅由远程 chat
 * ReAct agent 使用（ChatToolService 的 @Tool + AgentConfigurator.chatSystemPrompt）——端侧 2B
 * 模型无法可靠生成 JS，把这些入口塞进本地 prompt 会诱导它输出 `bridge.callAsync(...)` 之类的
 * 不可解析噪声。本地 L2 prompt 按场景硬编码（[LocalPromptBuilder.buildChatL2StaticPrompt] /
 * [LocalPromptBuilder.buildL2CapabilitiesSection]），本测试锁死该硬编码不得引入 JS/图表命令。
 *
 * 关联路由策略（AGENT_ARCHITECTURE.md）：tool_call 为主链，JS 是 `run_gallery_script` 的内部
 * 实现细节，对本地小模型不可见。
 */
class LocalPromptBuilderJsIsolationTest {

    private lateinit var builder: LocalPromptBuilder

    /** JS / 图表能力的不变式禁词：任一出现在本地 prompt 即视为回归。 */
    private val jsSurfaceTokens = listOf(
        "run_gallery_script",
        "draw_chart",
        "bridge.callAsync",
        "capability.dispatch",
    )

    @Before
    fun setup() {
        builder = LocalPromptBuilder(SceneManager.getInstance())
    }

    @Test
    fun `CHAT L2 prompt does not expose JS or chart capabilities`() {
        val sceneManager = SceneManager.getInstance()
        sceneManager.transitionTo(SceneManager.Scene.CHAT, saveToHistory = false)
        try {
            val prompt = builder.buildL2SystemPrompt(
                emptyList(),
                AgentContext(scene = AgentScene.CHAT)
            )
            // 健全性：确认拿到的是 chat prompt（含 search_media），而非空串
            assertTrue("CHAT prompt 应广告 search_media，实际:\n$prompt", prompt.contains("search_media"))
            jsSurfaceTokens.forEach { token ->
                assertFalse(
                    "CHAT 本地 prompt 不得暴露 JS/图表能力『$token』（仅远程 chat agent 可用），实际:\n$prompt",
                    prompt.contains(token)
                )
            }
        } finally {
            sceneManager.leaveScene(SceneManager.Scene.CHAT)
        }
    }

    @Test
    fun `CHAT capabilities section does not list JS or chart commands`() {
        val section = builder.buildL2CapabilitiesSection(SceneManager.Scene.CHAT)
        jsSurfaceTokens.forEach { token ->
            assertFalse(
                "CHAT 能力清单不得列出『$token』，实际:\n$section",
                section.contains(token)
            )
        }
    }

    @Test
    fun `non-CHAT scenes also do not leak JS or chart capabilities`() {
        listOf(
            SceneManager.Scene.CAMERA,
            SceneManager.Scene.GALLERY,
            SceneManager.Scene.SETTINGS,
            SceneManager.Scene.DEBUG,
        ).forEach { scene ->
            val section = builder.buildL2CapabilitiesSection(scene)
            jsSurfaceTokens.forEach { token ->
                assertFalse(
                    "$scene 能力清单不得列出『$token』，实际:\n$section",
                    section.contains(token)
                )
            }
        }
    }
}
