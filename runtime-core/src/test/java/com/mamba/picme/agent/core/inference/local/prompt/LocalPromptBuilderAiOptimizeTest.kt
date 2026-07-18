package com.mamba.picme.agent.core.inference.local.prompt

import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.runtime.state.SceneManager
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptBuilderAiOptimizeTest {

    private val sceneManager = SceneManager.getInstance().apply {
        transitionTo(SceneManager.Scene.CHAT)
    }
    private val builder = LocalPromptBuilder(sceneManager)

    @Test
    fun `CHAT L2 prompt includes ai_optimize capability`() {
        val prompt = builder.buildL2SystemPrompt(emptyList(), AgentContext(scene = AgentScene.CHAT))

        assertTrue("should mention ai_optimize", prompt.contains("ai_optimize"))
        assertTrue("should mention fast mode", prompt.contains("fast"))
        assertTrue("should mention smart mode", prompt.contains("smart"))
    }

    @Test
    fun `CHAT L2 prompt includes last user image URI when provided`() {
        val context = AgentContext(
            scene = AgentScene.CHAT,
            lastUserImageUri = "/data/data/com.mamba.picme/files/picme_images/img_abc.jpg"
        )
        val prompt = builder.buildL2SystemPrompt(emptyList(), context)

        assertTrue("should surface last_user_image_uri", prompt.contains("last_user_image_uri=/data/data/com.mamba.picme/files/picme_images/img_abc.jpg"))
    }

    @Test
    fun `CHAT L2 prompt includes ai_optimize examples`() {
        val prompt = builder.buildL2SystemPrompt(emptyList(), AgentContext(scene = AgentScene.CHAT))

        assertTrue("should include optimize example", prompt.contains("帮我优化这张照片"))
        assertTrue("should include image_uri in example", prompt.contains("\"image_uri\""))
    }
}
