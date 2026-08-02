package com.mamba.picme.agent.core.inference.local.prompt

import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.runtime.state.SceneManager
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ai_optimize 能力的行为契约：CHAT 场景 L2 prompt 必须广告该命令并透传用户最近图片 URI，
 * 否则用户发图后本地小模型无法触发修图（静默退化，运行时不报错）。
 * 断言只锁定契约锚点（命令名 / 参数名 / 数据透传），不耦合 prompt 具体措辞。
 */
class LocalPromptBuilderAiOptimizeTest {

    private val sceneManager = SceneManager.getInstance().apply {
        transitionTo(SceneManager.Scene.CHAT)
    }
    private val builder = LocalPromptBuilder(sceneManager)

    @Test
    fun `CHAT L2 prompt advertises ai_optimize command with image_uri param`() {
        val prompt = builder.buildL2SystemPrompt(emptyList(), AgentContext(scene = AgentScene.CHAT))

        assertTrue("CHAT prompt 应广告 ai_optimize 命令", prompt.contains("ai_optimize"))
        assertTrue("ai_optimize 应声明 image_uri 参数", prompt.contains("image_uri"))
    }

    @Test
    fun `CHAT L2 prompt surfaces last user image URI when provided`() {
        val uri = "/data/data/com.mamba.picme/files/picme_images/img_abc.jpg"
        val context = AgentContext(scene = AgentScene.CHAT, lastUserImageUri = uri)
        val prompt = builder.buildL2SystemPrompt(emptyList(), context)

        assertTrue("应透传用户最近图片 URI", prompt.contains(uri))
    }
}
