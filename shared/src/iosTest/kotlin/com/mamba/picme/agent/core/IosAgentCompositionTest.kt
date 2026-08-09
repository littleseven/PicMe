package com.mamba.picme.agent.core

import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.ChatAgentBridge
import com.mamba.picme.agent.core.inference.remote.ChatUiActionDto
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.data.IosMediaItem
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.domain.repository.AccessState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeBridge : IosMediaRepositoryBridge {
    override fun currentAccessState(): AccessState = AccessState.Full
    override fun fetchAllMedia(): List<IosMediaItem> = listOf(
        IosMediaItem("C-1", "PHOTO", 1000L, fileName = "cat.jpg")
    )
    override fun requestReadWriteAuthorization() = Unit
    override fun addChangeListener(listener: () -> Unit) = Unit
    override fun removeChangeListener() = Unit
    override fun deleteMedia(localIdentifiers: List<String>): Boolean = true
    override fun setFavorite(localIdentifier: String, favorite: Boolean): Boolean = true
}

class IosAgentCompositionTest {

    @Test
    fun initializeWiresOrchestratorAndRegistersChatGalleryCapability() {
        val orchestrator = IosAgentComposition.initialize(FakeBridge())
        assertNotNull(orchestrator.remoteChatEngine, "orchestrator 应可达且 remoteChatEngine 已装配")

        val capability = CapabilityRegistry.getInstance().get("chat_gallery")
        assertNotNull(capability, "chat_gallery capability 应已注册")
        assertTrue(
            capability.supportedCommands().containsAll(
                listOf("get_gallery_summary", "search_media", "refine_media_search", "delete_media")
            ),
            "应覆盖 chat 相册命令"
        )
        assertTrue(capability.supportsCommand(AgentCommand.GetGallerySummary()))
    }

    @Test
    fun initializeIsIdempotent() {
        val first = IosAgentComposition.initialize(FakeBridge())
        val second = IosAgentComposition.initialize(FakeBridge())
        assertTrue(first === second, "重复 initialize 应返回同一 orchestrator 单例")
    }

    @Test
    fun clearChatMemoryWorksWithNoopCleaner() = runTest {
        val orchestrator = IosAgentComposition.initialize(FakeBridge())
        orchestrator.clearChatMemory(ChatAgentBridge.SESSION_ID) // 不抛即过
    }

    @Test
    fun bridgeIsNotRunningWhenIdle() {
        val bridge = ChatAgentBridge(AgentOrchestrator.getInstance())
        assertFalse(bridge.isRunning())
    }

    @Test
    fun watchUiActionsMapsTextReplyAndMediaResults() = runBlocking {
        val bridge = ChatAgentBridge(AgentOrchestrator.getInstance())
        val received = mutableListOf<ChatUiActionDto>()
        val watcher = bridge.watchUiActions { received += it }
        try {
            // MutableSharedFlow replay=0：订阅者未就绪前的 emit 会丢，先等订阅建立再发
            val uiActions = ChatToolService.getInstance().uiActions
            withTimeout(3000) { while (uiActions.subscriptionCount.value == 0) delay(50) }
            uiActions.emit(AgentAction.TextReply(1, "你好"))
            uiActions.emit(
                AgentAction.MediaResults(2, query = "cat", mediaIds = listOf(11L, 22L), totalCount = 2, isRefinement = false)
            )
            withTimeout(3000) { while (received.size < 2) delay(50) }

            val textReply = received[0]
            assertEquals("text_reply", textReply.kind)
            assertEquals("你好", textReply.message)

            val mediaResults = received[1]
            assertEquals("media_results", mediaResults.kind)
            assertEquals("cat", mediaResults.query)
            assertEquals(2L, mediaResults.totalCount)
            assertEquals(listOf(11L, 22L), mediaResults.mediaIds)
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun watchUiActionsMapsSuccessWithCommandAndMediaIds() = runBlocking {
        val bridge = ChatAgentBridge(AgentOrchestrator.getInstance())
        val received = mutableListOf<ChatUiActionDto>()
        val watcher = bridge.watchUiActions { received += it }
        try {
            // MutableSharedFlow replay=0：订阅者未就绪前的 emit 会丢，先等订阅建立再发
            val uiActions = ChatToolService.getInstance().uiActions
            withTimeout(3000) { while (uiActions.subscriptionCount.value == 0) delay(50) }
            uiActions.emit(
                AgentAction.Success(3, AgentCommand.ViewMedia(commandId = 3, mediaId = "42"))
            )
            withTimeout(3000) { while (received.isEmpty()) delay(50) }

            val success = received[0]
            assertEquals("success", success.kind)
            assertEquals("view_media", success.command)
            assertEquals(listOf(42L), success.mediaIds)
        } finally {
            watcher.cancel()
        }
    }
}
