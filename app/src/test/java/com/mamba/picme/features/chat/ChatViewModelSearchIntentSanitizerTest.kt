package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.search.SearchResult
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] SearchIntent 时间词清洗回归测试。
 *
 * 覆盖：当 LLM 已把“去年夏天”等时间词转成 [SearchIntent.timeRange] 后，
 * 仍误把“夏天”等时间词塞进 keywords 时，VM 层应在转成 [StructuredFilter] 前将其剔除，
 * 避免 MediaSearchEngine 把时间候选集与空标签候选集取交集后返回 0 张。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelSearchIntentSanitizerTest {

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
        coEvery { chatSessionDao.getSession(any()) } returns null

        mockkObject(AgentOrchestrator.Companion)
        every { AgentOrchestrator.getInstance(any()) } returns orchestrator
        every { orchestrator.getInferencePreference() } returns AiAgentInferencePreference.FORCE_LOCAL

        coEvery { mediaSearchEngine.search(filter = any(), limitToIds = any(), enableSemanticSearch = any()) } returns
            SearchResult(emptyList(), "")
        coEvery { mediaSearchEngine.search(query = any(), llmSearch = any(), enableSemanticSearch = any(), limitToIds = any()) } returns
            SearchResult(emptyList(), "")
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
    fun `onSearchMedia strips time-only keyword when timeRange present`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "去年夏天的照片",
            timeRange = TimeRange(startMs = 1_718_198_400_000, endMs = 1_725_145_599_999),
            keywords = listOf("夏天")
        )

        viewModel.onSearchMedia("去年夏天的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertTrue("时间词 '夏天' 应从 keywords 中剔除", filterSlot.captured.keywords.isEmpty())
        assertEquals("time_range 应保留", intent.timeRange?.startMs, filterSlot.captured.timeRange?.startMs)
    }

    @Test
    fun `onSearchMedia keeps non-time keywords and strips time-only ones`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "去年夏天小孩的照片",
            timeRange = TimeRange(startMs = 1_718_198_400_000, endMs = 1_725_145_599_999),
            keywords = listOf("夏天", "小孩")
        )

        viewModel.onSearchMedia("去年夏天小孩的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertEquals(listOf("小孩"), filterSlot.captured.keywords)
    }

    @Test
    fun `onSearchMedia strips Chinese and Arabic month keywords when timeRange present`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "去年3月的照片",
            timeRange = TimeRange(startMs = 1_709_251_200_000, endMs = 1_717_106_399_999),
            keywords = listOf("3月", "三月", "照片")
        )

        viewModel.onSearchMedia("去年3月的照片", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertEquals(listOf("照片"), filterSlot.captured.keywords)
    }

    @Test
    fun `onSearchMedia leaves keywords unchanged when no timeRange`() = runTest {
        val viewModel = newViewModel()
        val intent = SearchIntent(
            query = "夏天的回忆",
            timeRange = null,
            keywords = listOf("夏天")
        )

        viewModel.onSearchMedia("夏天的回忆", intent)
        advanceUntilIdle()

        val filterSlot = slot<StructuredFilter>()
        coVerify { mediaSearchEngine.search(filter = capture(filterSlot), limitToIds = any(), enableSemanticSearch = any()) }
        assertEquals(listOf("夏天"), filterSlot.captured.keywords)
    }

    @Test
    fun `onRefineMediaSearch also sanitizes time-only keywords`() = runTest {
        val viewModel = newViewModel()
        // 先给当前 session 注入一轮结果，使 refine 走 in-set 分支
        viewModel.onSearchMedia(
            "去年的照片",
            SearchIntent(
                query = "去年的照片",
                timeRange = TimeRange(startMs = 1_704_067_200_000, endMs = 1_735_603_199_999),
                keywords = emptyList()
            )
        )
        advanceUntilIdle()

        val refineIntent = SearchIntent(
            query = "只要夏天的",
            timeRange = TimeRange(startMs = 1_718_198_400_000, endMs = 1_725_145_599_999),
            keywords = listOf("夏天")
        )
        viewModel.onRefineMediaSearch("只要夏天的", refineIntent)
        advanceUntilIdle()

        val filters = mutableListOf<StructuredFilter>()
        coVerify(atLeast = 1) { mediaSearchEngine.search(filter = capture(filters), limitToIds = any(), enableSemanticSearch = any()) }
        val refineFilter = filters.last()
        assertTrue("细化时也应剔除时间词 '夏天'", refineFilter.keywords.isEmpty())
    }
}
