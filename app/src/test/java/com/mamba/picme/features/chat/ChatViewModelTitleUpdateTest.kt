package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.R
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.local.llm.StreamChatResult
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
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
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 会话标题自动更新行为测试。
 *
 * 覆盖：首条消息触发自动命名、用户已自定义标题时不覆盖、非首条消息不覆盖。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelTitleUpdateTest {

    private val context: Context = mockk(relaxed = true)
    private val chatMessageDao: ChatMessageDao = mockk(relaxed = true)
    private val chatSessionDao: ChatSessionDao = mockk(relaxed = true)
    private val mediaSearchEngine: MediaSearchEngine = mockk(relaxed = true)
    private val mediaFeedbackRepository: MediaFeedbackRepository = mockk(relaxed = true)
    private val authClient: PoLangAuthClient = mockk(relaxed = true)
    private val userSettingsRepository: UserSettingsRepository = mockk(relaxed = true)

    private val tokenFlow = MutableStateFlow("")
    private val preferenceFlow = MutableStateFlow(AiAgentInferencePreference.FORCE_LOCAL)
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

        every { userSettingsRepository.serverAuthTokenFlow } returns tokenFlow
        every { userSettingsRepository.aiAgentInferencePreferenceFlow } returns preferenceFlow

        every { chatMessageDao.getMessagesBySession(any()) } returns flowOf(emptyList())
        coEvery { chatMessageDao.getLastMessageForSession(any()) } returns null
        every { chatSessionDao.getAllSessions() } returns flowOf(emptyList())

        mockkObject(AgentOrchestrator.Companion)
        every { AgentOrchestrator.getInstance(any()) } returns orchestrator
        every { orchestrator.getInferencePreference() } returns AiAgentInferencePreference.FORCE_LOCAL
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
            picMeAuthClient = authClient,
            getGallerySummaryUseCase = mockk(relaxed = true),
            queryGalleryMediaUseCase = mockk(relaxed = true),
            startTagScanUseCase = StartTagScanUseCase(context)
        )
    )

    @Test
    fun `first text message updates default title`() = runTest {
        coEvery { chatSessionDao.getSession("default") } returns ChatSessionEntity(
            sessionId = "default",
            title = "New Chat"
        )
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        coEvery { orchestrator.streamChat(any(), any(), any()) } returns Result.success(
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
        coEvery { orchestrator.streamChat(any(), any(), any()) } returns Result.success(
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
        coEvery { orchestrator.streamChat(any(), any(), any()) } returns Result.success(
            StreamChatResult(fullResponse = "好的")
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("再帮我找一张")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatSessionDao.updateTitle(any(), any()) }
    }
}
