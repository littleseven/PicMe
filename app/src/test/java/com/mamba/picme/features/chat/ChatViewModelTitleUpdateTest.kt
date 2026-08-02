package com.mamba.picme.features.chat

import com.mamba.picme.R
import com.mamba.picme.agent.core.local.llm.StreamChatResult
import com.mamba.picme.data.local.ChatSessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 会话标题自动更新行为测试。
 *
 * 覆盖：首条消息触发自动命名、用户已自定义标题时不覆盖、非首条消息不覆盖。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelTitleUpdateTest : ChatViewModelTestBase() {

    @Before
    override fun setUp() {
        super.setUp()
        every { context.getString(R.string.new_chat) } returns "New Chat"
        every { context.getString(R.string.chat_title_image_first) } returns "Image Chat"
    }

    @Test
    fun `first text message updates default title`() = runTest {
        coEvery { chatSessionDao.getSession("default") } returns ChatSessionEntity(
            sessionId = "default",
            title = "New Chat"
        )
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), any()) } returns Result.success(
            StreamChatResult(fullResponse = "好的")
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("帮我找去年冬天的照片")
        advanceUntilIdle()

        coVerify { chatSessionDao.updateTitle("default", "帮我找去年冬天的照片", any()) }
    }

    @Test
    fun `first text message does not overwrite custom title`() = runTest {
        coEvery { chatSessionDao.getSession("default") } returns ChatSessionEntity(
            sessionId = "default",
            title = "我的自定义标题"
        )
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), any()) } returns Result.success(
            StreamChatResult(fullResponse = "好的")
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("帮我找去年冬天的照片")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatSessionDao.updateTitle(any(), any()) }
    }

    @Test
    fun `second message does not update title`() = runTest {
        coEvery { chatSessionDao.getSession("default") } returns ChatSessionEntity(
            sessionId = "default",
            title = "New Chat"
        )
        coEvery { chatMessageDao.getMessageCount("default") } returns 2
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), any()) } returns Result.success(
            StreamChatResult(fullResponse = "好的")
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("再帮我找一张")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatSessionDao.updateTitle(any(), any()) }
    }
}
