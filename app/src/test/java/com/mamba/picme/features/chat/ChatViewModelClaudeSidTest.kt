package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] claudeSid 持久化接线的单测（Task 8 + 审查修复）。
 *
 * 覆盖：
 * - 恢复场景：fake store 预置 sid，sendClaudeMessage 时 load 生效（chat 请求带旧 sid → --resume）
 * - Session 事件格式判断：12 位 hex 网关 sid 采纳并 save（含轮换覆盖旧 sid 自愈）；
 *   带连字符的 claude init session_id 被忽略
 * - enterClaudeMode 不再 clear 持久化 sid（进程重建恢复链路的回归保护）
 *
 * 基建对齐 [ChatViewModelAppToolTest]（mockkStatic Log + mockkObject Orchestrator）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelClaudeSidTest {

    /** 内存 fake：同 [ClaudeSidStoreTest]，验证 ViewModel 与 store 的交互。 */
    private class InMemoryClaudeSidStore : ClaudeSidStore {
        private val data = mutableMapOf<String, String>()

        override fun load(sessionId: String): String? = data[sessionId]

        override fun save(sessionId: String, sid: String) {
            data[sessionId] = sid
        }

        override fun clear(sessionId: String) {
            data.remove(sessionId)
        }
    }

    private val context: Context = mockk(relaxed = true)
    private val chatMessageDao: ChatMessageDao = mockk(relaxed = true)
    private val chatSessionDao: ChatSessionDao = mockk(relaxed = true)
    private val userSettingsRepository: UserSettingsRepository = mockk(relaxed = true)
    private val claudeChatClient: ClaudeChatClient = mockk()
    private val orchestrator: AgentOrchestrator = mockk(relaxed = true)
    private val sidStore = InMemoryClaudeSidStore()

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
        ).also { it.claudeSidStore = sidStore }
    )

    /** fake chat：先发 [events]，再成功返回。 */
    private fun stubChat(vararg events: ClaudeEvent) {
        coEvery { claudeChatClient.chat(any(), any(), any(), any()) } coAnswers {
            val onEvent = arg<(ClaudeEvent) -> Unit>(3)
            events.forEach(onEvent)
            Result.success("ok")
        }
    }

    @Test
    fun `persisted sid is restored and sent as resume sid`() = runTest {
        sidStore.save("default", "aaaa1111bbbb")
        stubChat()

        val vm = newViewModel()
        vm.sendClaudeMessage("hello")

        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat("pl-test-token", "hello", "aaaa1111bbbb", any())
        }
    }

    @Test
    fun `gateway sid session event is adopted and persisted, overriding rotated old sid`() = runTest {
        // 预置旧 sid（模拟进程重建恢复后，网关 workdir 已清 → 轮换签发新 sid）
        sidStore.save("default", "aaaa1111bbbb")
        stubChat(ClaudeEvent.Session("deadbeef0012"))

        val vm = newViewModel()
        vm.sendClaudeMessage("first")

        // 新网关 sid 覆盖旧 sid 并持久化（自愈）
        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat(any(), "first", "aaaa1111bbbb", any())
        }
        assertEquals("deadbeef0012", sidStore.load("default"))

        // 下一轮带新 sid
        vm.sendClaudeMessage("second")
        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat(any(), "second", "deadbeef0012", any())
        }
    }

    @Test
    fun `claude init session id with hyphens is ignored`() = runTest {
        stubChat(ClaudeEvent.Session("550e8400-e29b-41d4-a716-446655440000"))

        val vm = newViewModel()
        vm.sendClaudeMessage("hello")

        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            claudeChatClient.chat(any(), "hello", isNull(), any())
        }
        assertNull(sidStore.load("default"))
    }

    @Test
    fun `enterClaudeMode does not clear persisted sid`() = runTest {
        sidStore.save("default", "aaaa1111bbbb")

        val vm = newViewModel()
        vm.enterClaudeMode()

        // 持久化 sid 必须保留：进程重建 → 恢复旧会话 → 重开工程师模式 → sendClaudeMessage 靠它 --resume
        assertEquals("aaaa1111bbbb", sidStore.load("default"))
    }

    private companion object {
        const val VERIFY_TIMEOUT_MS = 3_000L
    }
}
