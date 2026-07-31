package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.core.diag.DiagJobStatus
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.DiagClient
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import com.mamba.picme.domain.tag.ControlledVocab
import io.mockk.coEvery
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 远程诊断加固单测：
 * - A1：confirmDiagnosis 作用于按钮所在气泡的 jobId（多次诊断不串）
 * - A2：诊断/修复轮询 30 分钟总超时 → 写气泡并退出
 * - S1 配套：轮询到 TIMED_OUT 提示用户重试
 *
 * 测试手法同 [ChatViewModelGuestModeTest]：mockkStatic(Log) + mockkObject(AgentOrchestrator.Companion)。
 * pollDiagnose/pollFix 为 internal（@VisibleForTesting）可直接调用；diagPollTimeoutMs=0 立即超时。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelDiagTest {

    private val context: Context = mockk(relaxed = true)
    private val chatMessageDao: ChatMessageDao = mockk(relaxed = true)
    private val chatSessionDao: ChatSessionDao = mockk(relaxed = true)
    private val userSettingsRepository: UserSettingsRepository = mockk(relaxed = true)
    private val diagClient: DiagClient = mockk()
    private val orchestrator: AgentOrchestrator = mockk(relaxed = true)

    private val tokenFlow = MutableStateFlow("pl-test-token")

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
        every { context.getString(com.mamba.picme.R.string.diag_poll_timeout) } returns "POLL_TIMEOUT"
        every { context.getString(com.mamba.picme.R.string.diag_job_timed_out) } returns "JOB_TIMED_OUT"
        every { userSettingsRepository.serverAuthTokenFlow } returns tokenFlow
        every { userSettingsRepository.aiAgentInferencePreferenceFlow } returns
            MutableStateFlow(AiAgentInferencePreference.FORCE_REMOTE)

        every { chatMessageDao.getMessagesBySession(any()) } returns flowOf(emptyList())
        coEvery { chatMessageDao.getLastMessageForSession(any()) } returns null
        coEvery { chatMessageDao.getMessageCount(any()) } returns 0
        every { chatSessionDao.getAllSessions() } returns flowOf(emptyList())
        coEvery { chatSessionDao.getSession(any()) } returns null

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
            mediaSearchEngine = mockk(relaxed = true),
            mediaFeedbackRepository = mockk(relaxed = true),
            mediaRepository = mockk(relaxed = true),
            picMeAuthClient = mockk(relaxed = true),
            diagClient = diagClient,
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

    private fun status(s: String, id: Int = 1) = DiagJobStatus(
        jobId = id, status = s, rootCause = "rc", fixBranch = "diag-fix/$id",
        compareUrl = null, tested = true, error = null, updatedAt = 1L,
    )

    // ── A1：确认绑定按钮所在气泡的 jobId ──────────────────────────

    @Test
    fun `confirmDiagnosis acts on the job bound to the clicked bubble`() = runTest {
        val confirmed = mutableListOf<Int>()
        coEvery { diagClient.confirmFix(any(), any(), any()) } answers {
            confirmed += secondArg<Int>()
            Result.success(Unit)
        }
        coEvery { diagClient.fetchDiagStatus(any(), any()) } answers {
            val id = secondArg<Int>()
            Result.success(status(if (id in confirmed) "FIXED" else "DIAGNOSED", id))
        }
        val vm = newViewModel()
        advanceUntilIdle()
        // 两次进行中的诊断：job 7（旧气泡）与 job 8（新气泡）
        vm.trackDiagForTesting("t", 7, "msg7")
        vm.trackDiagForTesting("t", 8, "msg8")

        vm.confirmDiagnosis(7, "push") // 点旧气泡按钮
        advanceUntilIdle()

        assertEquals("确认的是旧气泡的 job 7，而不是最新的 job 8", listOf(7), confirmed)
    }

    // ── A2：轮询 30 分钟总超时 ──────────────────────────────────

    @Test
    fun `pollDiagnose exits with timeout bubble when total timeout elapses`() = runTest {
        coEvery { diagClient.fetchDiagStatus(any(), any()) } returns Result.success(status("QUEUED"))
        val vm = newViewModel()
        vm.diagPollTimeoutMs = 0 // 立即超时：不进入轮询循环
        vm.trackDiagForTesting("t", 1, "msg1")

        vm.pollDiagnose("t", 1, "msg1")

        assertTrue(vm.messages.value.any { it.id == "msg1" && it.content == "POLL_TIMEOUT" })
    }

    @Test
    fun `pollFix exits with timeout bubble when total timeout elapses`() = runTest {
        coEvery { diagClient.fetchDiagStatus(any(), any()) } returns Result.success(status("FIX_REQUESTED"))
        val vm = newViewModel()
        vm.diagPollTimeoutMs = 0
        vm.trackDiagForTesting("t", 1, "msg1")

        vm.pollFix("t", 1, "msg1")

        assertTrue(vm.messages.value.any { it.id == "msg1" && it.content == "POLL_TIMEOUT" })
    }

    // ── TIMED_OUT 透出（S1 配套）─────────────────────────────────

    @Test
    fun `pollDiagnose shows retry hint on TIMED_OUT`() = runTest {
        coEvery { diagClient.fetchDiagStatus(any(), any()) } returns Result.success(status("TIMED_OUT"))
        val vm = newViewModel()
        vm.trackDiagForTesting("t", 1, "msg1")

        vm.pollDiagnose("t", 1, "msg1")

        assertTrue(vm.messages.value.any { it.id == "msg1" && it.content == "JOB_TIMED_OUT" })
    }

    @Test
    fun `pollFix shows retry hint on TIMED_OUT`() = runTest {
        coEvery { diagClient.fetchDiagStatus(any(), any()) } returns Result.success(status("TIMED_OUT"))
        val vm = newViewModel()
        vm.trackDiagForTesting("t", 1, "msg1")

        vm.pollFix("t", 1, "msg1")

        assertTrue(vm.messages.value.any { it.id == "msg1" && it.content == "JOB_TIMED_OUT" })
    }
}
