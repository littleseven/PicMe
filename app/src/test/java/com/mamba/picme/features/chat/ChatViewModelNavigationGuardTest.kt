package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.local.llm.StreamChatResult
import com.mamba.picme.agent.core.local.llm.StreamMetrics
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 聊天页导航守卫回归测试。
 *
 * 覆盖：当 LLM 对模糊表述（如"我想看看相册"）误输出 navigate_to 时，
 * VM 层应将其拦截并替换为 text_reply，避免页面自动跳转。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelNavigationGuardTest : ChatViewModelTestBase() {

    private val registry: CapabilityRegistry = mockk(relaxed = true)

    @Before
    override fun setUp() {
        super.setUp()

        coEvery { chatMessageDao.getMessageCount(any()) } returns 1
        coEvery { getGallerySummaryUseCase(any<Boolean>()) } returns emptyGallerySummary()

        every { orchestrator.getCapabilityRegistry() } returns registry
        every { orchestrator.getAgentMode() } returns AiAgentMode.REMOTE
        every { orchestrator.getCurrentModelId() } returns "remote_deepseek"
        coEvery { orchestrator.configure(any(), any(), any(), any(), any()) } returns Unit
        coEvery { registry.dispatch(any(), any()) } returns Result.success(
            AgentAction.Success(commandId = 0, command = AgentCommand.TextReply(message = ""))
        )
    }

    private fun emptyGallerySummary(): GallerySummary = GallerySummary(
        totalPhotos = 0,
        totalVideos = 0,
        totalMedia = 0,
        hasFaceCount = 0,
        personClusterCount = 0,
        namedPersonCount = 0,
        labeledCount = 0,
        unlabeledCount = 0,
        semanticEncodedCount = 0,
        remainingPass1 = 0,
        remainingPass3 = 0,
        isScanning = false,
        recommendation = GallerySummary.ScanRecommendation.NONE
    )

    private fun mockStreamCommands(commands: List<AgentCommand>) {
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), any()) } returns Result.success(
            StreamChatResult(
                fullResponse = "",
                metrics = StreamMetrics(latencyMs = 100, promptTokens = 10, completionTokens = 5),
                commands = commands
            )
        )
    }

    @Test
    fun `模糊跳转表述被拦截为 text_reply`() = runTest {
        val viewModel = newViewModel()
        mockStreamCommands(listOf(AgentCommand.NavigateTo(destination = "gallery")))

        viewModel.sendMessage("我想看看相册")
        advanceUntilIdle()

        val commandSlot = slot<AgentCommand>()
        coVerify(atLeast = 1) { registry.dispatch(capture(commandSlot), any()) }
        val dispatched = commandSlot.captured
        assertTrue("模糊表述应被拦截为 text_reply，实际：$dispatched", dispatched is AgentCommand.TextReply)
    }

    @Test
    fun `明确跳转口令放行 navigate_to`() = runTest {
        val viewModel = newViewModel()
        mockStreamCommands(listOf(AgentCommand.NavigateTo(destination = "camera")))

        viewModel.sendMessage("去相机")
        advanceUntilIdle()

        val commandSlot = slot<AgentCommand>()
        coVerify(atLeast = 1) { registry.dispatch(capture(commandSlot), any()) }
        val dispatched = commandSlot.captured
        assertTrue("明确口令应放行 navigate_to，实际：$dispatched", dispatched is AgentCommand.NavigateTo)
    }

    @Test
    fun `返回口令放行 go_back`() = runTest {
        val viewModel = newViewModel()
        mockStreamCommands(listOf(AgentCommand.GoBack()))

        viewModel.sendMessage("返回")
        advanceUntilIdle()

        val commandSlot = slot<AgentCommand>()
        coVerify(atLeast = 1) { registry.dispatch(capture(commandSlot), any()) }
        val dispatched = commandSlot.captured
        assertTrue("\"返回\"应放行 go_back，实际：$dispatched", dispatched is AgentCommand.GoBack)
    }

    @Test
    fun `批量命令中只替换导航命令`() = runTest {
        val viewModel = newViewModel()
        mockStreamCommands(
            listOf(
                AgentCommand.NavigateTo(destination = "gallery"),
                AgentCommand.SearchMedia(query = "海边的照片")
            )
        )

        viewModel.sendMessage("我想看看海边的照片")
        advanceUntilIdle()

        val commandSlot = slot<AgentCommand>()
        coVerify(atLeast = 1) { registry.dispatch(capture(commandSlot), any()) }
        val batch = commandSlot.captured
        assertTrue("应打包为 BatchExecute，实际：$batch", batch is AgentCommand.BatchExecute)
        val inner = (batch as AgentCommand.BatchExecute).commands
        assertTrue("导航命令应被替换为 text_reply", inner[0] is AgentCommand.TextReply)
        assertTrue("搜索命令应保留", inner[1] is AgentCommand.SearchMedia)
    }
}
