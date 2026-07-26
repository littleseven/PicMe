package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelEditResultTest {

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
            mediaRepository = mockk(relaxed = true),
            picMeAuthClient = authClient,
            getGallerySummaryUseCase = mockk(relaxed = true),
            queryGalleryMediaUseCase = mockk(relaxed = true),
            startTagScanUseCase = StartTagScanUseCase(context),
            personDao = mockk(relaxed = true),
            controlledVocab = ControlledVocab(),
            chatEditStateHolder = ChatEditStateHolder(),
            chatEditProcessor = mockk(relaxed = true)
        )
    )

    @Test
    fun `insertEditResultMessage persists agent_edit_result with metadata`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        val sessionId = "session-edit"
        val imageUri = "content://media/external/images/media/42"
        val explanation = "✅ 已调亮并加滤镜"

        vm.insertEditResultMessage(sessionId, imageUri, explanation, "remote_deepseek")
        advanceUntilIdle()

        val slot = slot<com.mamba.picme.data.local.ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }

        val entity = slot.captured
        assertEquals("agent_edit_result", entity.type)
        assertEquals(explanation, entity.content)
        assertEquals(sessionId, entity.sessionId)
        assertEquals("remote_deepseek", entity.modelUsed)

        val metadata = entity.metadata
        assertNotNull(metadata)
        val json = JSONObject(metadata!!)
        assertEquals(imageUri, json.getString("imageUri"))
        assertEquals(explanation, json.getString("explanation"))
        assertNotNull(json.getJSONArray("suggestions"))

        coVerify { chatSessionDao.touchSession(eq(sessionId), any()) }
    }
}
