package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.R
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.ChatStreamEvent
import com.mamba.picme.agent.core.local.llm.StreamChatResult
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ChatViewModel 流式接线回归：TextSnapshot 经节奏器、streamChat 返回后 finish，
 * 最终 streamingMessage 被清除、不崩。节奏细节由 StreamingPacingControllerTest 覆盖。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelStreamingWiringTest {

    private val context: Context = mockk(relaxed = true)
    private val chatMessageDao: ChatMessageDao = mockk(relaxed = true)
    private val chatSessionDao: ChatSessionDao = mockk(relaxed = true)
    private val mediaSearchEngine: MediaSearchEngine = mockk(relaxed = true)
    private val mediaFeedbackRepository: MediaFeedbackRepository = mockk(relaxed = true)
    private val authClient: PoLangAuthClient = mockk(relaxed = true)
    private val userSettingsRepository: UserSettingsRepository = mockk(relaxed = true)
    private val orchestrator: AgentOrchestrator = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        every { context.applicationContext } returns context
        every { context.getString(R.string.new_chat) } returns "New Chat"
        every { context.getString(R.string.chat_title_image_first) } returns "Image Chat"
        every { context.getString(R.string.chat_calling_tool) } returns "正在调用工具"

        every { userSettingsRepository.serverAuthTokenFlow } returns MutableStateFlow("")
        every { userSettingsRepository.aiAgentInferencePreferenceFlow } returns
            MutableStateFlow(AiAgentInferencePreference.FORCE_REMOTE)
        every { chatMessageDao.getMessagesBySession(any()) } returns flowOf(emptyList())
        coEvery { chatMessageDao.getLastMessageForSession(any()) } returns null
        every { chatSessionDao.getAllSessions() } returns flowOf(emptyList())

        mockkObject(AgentOrchestrator.Companion)
        every { AgentOrchestrator.getInstance(any()) } returns orchestrator
        every { orchestrator.getInferencePreference() } returns AiAgentInferencePreference.FORCE_REMOTE
    }

    @After
    fun tearDown() {
        unmockkObject(AgentOrchestrator.Companion)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun newViewModel() = ChatViewModel(
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = chatMessageDao,
            chatSessionDao = chatSessionDao,
            userSettingsRepository = userSettingsRepository,
            mediaSearchEngine = mediaSearchEngine,
            mediaFeedbackRepository = mediaFeedbackRepository,
            mediaRepository = mockk(relaxed = true),
            picMeAuthClient = authClient,
            getGallerySummaryUseCase = mockk(relaxed = true),
            queryGalleryMediaUseCase = mockk(relaxed = true),
            startTagScanUseCase = StartTagScanUseCase(context),
            personDao = mockk(relaxed = true),
            controlledVocab = ControlledVocab(),
            chatEditStateHolder = ChatEditStateHolder(),
            chatEditProcessor = mockk(relaxed = true),
            chatImageStore = mockk(relaxed = true),
            saveChatEditResultUseCase = mockk(relaxed = true)
        )
    )

    @Test
    fun `text snapshot is paced and streaming message clears on finish`() = runTest {
        coEvery { chatSessionDao.getSession("default") } returns
            ChatSessionEntity(sessionId = "default", title = "New Chat")
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        val onEventSlot = slot<(ChatStreamEvent) -> Unit>()
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), capture(onEventSlot)) } answers {
            onEventSlot.captured(ChatStreamEvent.TextSnapshot("你好世界"))
            Result.success(StreamChatResult(fullResponse = "你好世界"))
        }

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("测试")
        advanceUntilIdle()

        assertNull(vm.streamingMessage.value)
    }
}
