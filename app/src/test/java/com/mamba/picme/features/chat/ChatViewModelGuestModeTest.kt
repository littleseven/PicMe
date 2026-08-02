package com.mamba.picme.features.chat

import com.mamba.picme.R
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 访客模式 / 注册引导 单测（design §4.3 / client plan §5）。
 *
 * 覆盖：
 * - isGuestMode 在 (推理偏好 × token) 组合下的派生（参数化合并）
 * - guest 配额耗尽 403 → 弹注册 sheet + 插入「试用额度已用完」气泡（软引导）
 * - 注册用户额度耗尽 403 → 不触发 guest sheet（走通用错误气泡）
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelGuestModeTest : ChatViewModelTestBase() {

    @Before
    override fun setUp() {
        super.setUp()
        every { context.getString(R.string.chat_guest_quota_used_up) } returns "QUOTA_USED_UP"
    }

    // ── isGuestMode 派生（3 组合并） ──────────────────────────────

    @Test
    fun `isGuestMode derived from preference and token combination`() = runTest {
        val cases = listOf(
            Triple(AiAgentInferencePreference.FORCE_REMOTE, "", true),
            Triple(AiAgentInferencePreference.FORCE_REMOTE, "pl-abc123token", false),
            Triple(AiAgentInferencePreference.FORCE_LOCAL, "", true),
        )
        cases.forEach { (preference, token, expectedGuest) ->
            preferenceFlow.value = preference
            tokenFlow.value = token
            val vm = newViewModel()
            advanceUntilIdle()
            assertEquals("preference=$preference, token='$token'", expectedGuest, vm.isGuestMode.value)
        }
    }

    // ── 403 配额耗尽分流 ──────────────────────────────────────────

    @Test
    fun `guest 403 opens registration sheet and inserts exhaustion bubble`() = runTest {
        preferenceFlow.value = AiAgentInferencePreference.FORCE_REMOTE
        tokenFlow.value = ""
        // 模拟 server 真实 403 body（LlmRoute：guest 配额耗尽）—— 异常 message 即此 body
        coEvery {
            orchestrator.remoteChatEngine.streamChat(any(), any(), any())
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
            orchestrator.remoteChatEngine.streamChat(any(), any(), any())
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
