package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.core.agenttools.AppTool
import com.mamba.picme.core.agenttools.AppToolExecutor
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.ClaudeChatClient
import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.tag.ControlledVocab
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 接线 app_tool_request 的单测（spec §3.1/§3.3，Task 7）。
 *
 * 覆盖 [ChatViewModel.handleAppToolRequest]：
 * - 正常路径：executor 被调用 → payload 经 [ClaudeChatClient.postToolResult] 回传 →
 *   renderer 合成 ToolResult 步骤（过程气泡 SUCCESS）
 * - executor 抛异常 / 未知工具：回传 `{error}` payload（不让 Claude 挂起等结果）
 *
 * 基建对齐 [ChatViewModelGuestModeTest]（mockkStatic Log + mockkObject Orchestrator）；
 * handleAppToolRequest 内部走 Dispatchers.IO（真实线程池），断言用 mockk timeout verify + 轮询。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelAppToolTest {

    private val context: Context = mockk(relaxed = true)
    private val chatMessageDao: ChatMessageDao = mockk(relaxed = true)
    private val chatSessionDao: ChatSessionDao = mockk(relaxed = true)
    private val userSettingsRepository: UserSettingsRepository = mockk(relaxed = true)
    private val claudeChatClient: ClaudeChatClient = mockk()
    private val appToolExecutor: AppToolExecutor = mockk()
    private val orchestrator: AgentOrchestrator = mockk(relaxed = true)

    private val tokenFlow = MutableStateFlow("pl-test-token")
    private val preferenceFlow = MutableStateFlow(AiAgentInferencePreference.FORCE_REMOTE)

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
            claudeChatClient = claudeChatClient,
            getGallerySummaryUseCase = mockk(relaxed = true),
            queryGalleryMediaUseCase = mockk(relaxed = true),
            startTagScanUseCase = StartTagScanUseCase(context),
            personDao = mockk(relaxed = true),
            controlledVocab = ControlledVocab(),
            chatEditStateHolder = ChatEditStateHolder(),
            chatEditProcessor = mockk(relaxed = true),
            chatImageStore = mockk(relaxed = true),
            saveChatEditResultUseCase = mockk(relaxed = true),
        ).also { it.appToolExecutor = appToolExecutor }
    )

    @Test
    fun `app tool request executes and posts payload back with success step`() = runTest {
        val payload = JSONObject().put("empty", false).put("logs", "hello")
        coEvery { appToolExecutor.execute(AppTool.GET_LOGS, any()) } returns payload
        coEvery { claudeChatClient.postToolResult(any(), any(), any()) } returns Result.success(Unit)

        val vm = newViewModel()
        val renderer = ClaudeAgentRenderer()
        // ViewModel 收到 AppToolRequest 时先合成 ToolUse 步骤（过程气泡 RUNNING）
        renderer.apply(ClaudeEvent.ToolUse("app_get_logs", JSONObject()))
        vm.handleAppToolRequest("req-1", "app_get_logs", JSONObject().put("lines", 50), renderer)

        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            appToolExecutor.execute(AppTool.GET_LOGS, match { it.optInt("lines") == 50 })
        }
        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.postToolResult(
                "pl-test-token",
                "req-1",
                match { it.optString("logs") == "hello" },
            )
        }
        awaitTrue("过程气泡应收尾为 SUCCESS") {
            renderer.state.steps.lastOrNull()?.status == ClaudeStepStatus.SUCCESS
        }
    }

    @Test
    fun `executor failure posts error payload so claude does not hang`() = runTest {
        coEvery { appToolExecutor.execute(any(), any()) } throws RuntimeException("boom")
        coEvery { claudeChatClient.postToolResult(any(), any(), any()) } returns Result.success(Unit)

        val vm = newViewModel()
        vm.handleAppToolRequest("req-2", "app_get_logs", JSONObject(), ClaudeAgentRenderer())

        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.postToolResult(
                "pl-test-token",
                "req-2",
                match { it.optString("error").contains("boom") },
            )
        }
    }

    @Test
    fun `unknown tool posts error payload without calling executor`() = runTest {
        coEvery { claudeChatClient.postToolResult(any(), any(), any()) } returns Result.success(Unit)

        val vm = newViewModel()
        vm.handleAppToolRequest("req-3", "app_unknown_tool", JSONObject(), ClaudeAgentRenderer())

        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.postToolResult(
                "pl-test-token",
                "req-3",
                match { it.optString("error").isNotBlank() },
            )
        }
        // postToolResult 发生在 execute 之后，到这里 execute 若被调早已发生
        coVerify(exactly = 0) { appToolExecutor.execute(any(), any()) }
    }

    /** 轮询等待异步（Dispatchers.IO 真实线程）收尾，避免 sleep 拍脑袋时长。 */
    private fun awaitTrue(message: String, timeoutMs: Long = VERIFY_TIMEOUT_MS, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(message, cond())
    }

    private companion object {
        const val VERIFY_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 20L
    }
}
