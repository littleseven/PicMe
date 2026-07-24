package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.R
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 访客模式 / 注册引导 单测（design §4.3 / client plan §5）。
 *
 * 覆盖：
 * - isGuestMode 在 (推理偏好 × token) 组合下的派生
 * - guest 配额耗尽 403 → 弹注册 sheet + 插入「试用额度已用完」气泡（软引导）
 * - 注册用户额度耗尽 403 → 不触发 guest sheet（走通用错误气泡）
 *
 * 注意：ChatViewModel 构造体内直接 [AgentOrchestrator.getInstance] 静态单例（非注入），
 * 这里用 mockkStatic 兜住；Logger 内部走 android.util.Log（JVM stub），一并屏蔽。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelGuestModeTest {

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

        // Logger 内部走 android.util.Log，JVM 单测下是 stub → 屏蔽
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        every { context.applicationContext } returns context
        every { context.getString(R.string.chat_guest_quota_used_up) } returns "QUOTA_USED_UP"

        every { userSettingsRepository.serverAuthTokenFlow } returns tokenFlow
        every { userSettingsRepository.aiAgentInferencePreferenceFlow } returns preferenceFlow

        // Room DAO：空消息 / 空会话，避免 init 的 loadMessages/loadThreads 干扰
        every { chatMessageDao.getMessagesBySession(any()) } returns flowOf(emptyList())
        coEvery { chatMessageDao.getLastMessageForSession(any()) } returns null
        coEvery { chatMessageDao.getMessageCount(any()) } returns 0
        every { chatSessionDao.getAllSessions() } returns flowOf(emptyList())
        coEvery { chatSessionDao.getSession(any()) } returns null

        // 绕过 AgentOrchestrator companion 单例（getInstance 非 @JvmStatic，mockkStatic 拦不住，用 mockkObject）
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

    // ── isGuestMode 派生 ───────────────────────────────────────────

    @Test
    fun `isGuestMode true when remote and no server token`() = runTest {
        preferenceFlow.value = AiAgentInferencePreference.FORCE_REMOTE
        tokenFlow.value = ""
        val vm = newViewModel()
        advanceUntilIdle()
        assertTrue("远程 + 无 token 应为访客模式", vm.isGuestMode.value)
    }

    @Test
    fun `isGuestMode false when registered token present`() = runTest {
        preferenceFlow.value = AiAgentInferencePreference.FORCE_REMOTE
        tokenFlow.value = "pl-abc123token"
        val vm = newViewModel()
        advanceUntilIdle()
        assertFalse("远程但有 token 不是访客", vm.isGuestMode.value)
    }

    @Test
    fun `chat ignores FORCE_LOCAL and stays remote - guest when no token`() = runTest {
        // chat 页已移除本地 LLM：即使全局偏好 FORCE_LOCAL，chat 仍用远程模型（访客由 PICME_SERVER_DEFAULT 兜底）
        preferenceFlow.value = AiAgentInferencePreference.FORCE_LOCAL
        tokenFlow.value = ""
        val vm = newViewModel()
        advanceUntilIdle()
        assertTrue("chat 忽略 FORCE_LOCAL 仍远程，无 token 为访客", vm.isGuestMode.value)
    }

    // ── 403 配额耗尽分流 ──────────────────────────────────────────

    @Test
    fun `guest 403 opens registration sheet and inserts exhaustion bubble`() = runTest {
        preferenceFlow.value = AiAgentInferencePreference.FORCE_REMOTE
        tokenFlow.value = ""
        // 模拟 server 真实 403 body（LlmRoute：guest 配额耗尽）—— 异常 message 即此 body
        coEvery {
            orchestrator.streamChat(any(), any(), any())
        } returns Result.failure(
            RuntimeException("""{"error":"quota_exceeded","tier":"guest","message":"guest quota used up"}""")
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("帮我找张照片")
        advanceUntilIdle()

        assertTrue("访客配额耗尽应弹注册 sheet", vm.showRegistrationSheet.value)
        coVerify {
            chatMessageDao.insertMessage(match { entity -> entity.content == "QUOTA_USED_UP" })
        }
    }

    @Test
    fun `registered user 403 does not open guest sheet`() = runTest {
        preferenceFlow.value = AiAgentInferencePreference.FORCE_REMOTE
        tokenFlow.value = "pl-abc123token"
        // account 配额耗尽：body 同样含 quota_exceeded，但 isGuestMode=false → 不应弹 guest sheet
        coEvery {
            orchestrator.streamChat(any(), any(), any())
        } returns Result.failure(
            RuntimeException("""{"error":"quota_exceeded","tier":"account","message":"free quota used up"}""")
        )

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("帮我找张照片")
        advanceUntilIdle()

        assertFalse("注册用户额度耗尽不应触发 guest sheet", vm.showRegistrationSheet.value)
    }

    @Test
    fun `blank message is a no-op`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("   ")
        advanceUntilIdle()

        assertFalse(vm.isProcessing.value)
        coVerify(exactly = 0) { chatMessageDao.insertMessage(any()) }
    }
}
