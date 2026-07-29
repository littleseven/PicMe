package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CapabilityRegistry] 分发前置路径（查找失败 / 入队）的观测埋点单测。
 *
 * 回归背景（2026-07-29「盘点相册返回暂不支持」）：METHOD_NOT_FOUND 与命令入队
 * 不经过 [CommandExecutor.execute]，此前 tool_call_log 零记录，故障只能靠 LLM
 * 请求体反推。现这两条路径必须经 [CommandExecutor.recordDispatchEvent] 上报。
 */
class CapabilityRegistryDispatchObservabilityTest {

    private data class Recorded(
        val capability: String,
        val commandType: String,
        val success: Boolean,
        val errorCode: Int?,
        val errorMessage: String?,
        val traceId: String?
    )

    private val recorded = mutableListOf<Recorded>()
    private val sceneManager = SceneManager.getInstance()

    @After
    fun tearDown() {
        CommandExecutor.recorder = null
    }

    private fun installRecorder() {
        CommandExecutor.recorder = CommandExecutionRecorder { capability, commandType, _, success, errorCode, errorMessage, traceId ->
            recorded += Recorded(capability, commandType, success, errorCode, errorMessage, traceId)
        }
    }

    private fun fakeCapability(
        name: String,
        scenes: List<SceneManager.Scene>,
        available: Boolean = true
    ) = object : BaseCapability() {
        override val name = name
        override val description = "fake capability"
        override fun activeScenes(): List<SceneManager.Scene> = scenes
        override fun isAvailable(): Boolean = available
        override fun supportedCommands(): List<String> = listOf("flip_camera")
        override suspend fun execute(
            command: AgentCommand,
            context: AgentContext,
            pageContext: PageContext?
        ): Result<AgentAction> = Result.success(AgentAction.Success(command.commandId, command))
    }

    @Test
    fun `capability not found records METHOD_NOT_FOUND with unresolved placeholder`() = runTest {
        installRecorder()
        sceneManager.transitionTo(SceneManager.Scene.CHAT)
        val registry = CapabilityRegistry.create(sceneManager)

        val result = registry.dispatch(
            AgentCommand.FlipCamera(),
            AgentContext(scene = AgentScene.CHAT, traceId = "trace-nf"),
            null
        )

        // 行为不变：仍返回「暂不支持此操作」
        val action = (result.getOrNull() as? AgentAction.Error)
        assertNotNull(action)
        assertEquals(AgentErrorCode.METHOD_NOT_FOUND, action!!.errorCode)
        assertEquals("暂不支持此操作", action.message)

        val r = recorded.single()
        assertEquals("(unresolved)", r.capability)
        assertEquals("flip_camera", r.commandType)
        assertFalse(r.success)
        assertEquals(AgentErrorCode.METHOD_NOT_FOUND, r.errorCode)
        assertNotNull(r.errorMessage)
        assertTrue(r.errorMessage!!.contains("flip_camera"))
        assertEquals("trace-nf", r.traceId)
    }

    @Test
    fun `scene mismatch queues command and records COMMAND_QUEUED`() = runTest {
        installRecorder()
        sceneManager.transitionTo(SceneManager.Scene.CHAT)
        val registry = CapabilityRegistry.create(sceneManager)
        registry.register(fakeCapability("fake_gallery", scenes = listOf(SceneManager.Scene.GALLERY)))

        val result = registry.dispatch(
            AgentCommand.FlipCamera(),
            AgentContext(scene = AgentScene.CHAT, traceId = "trace-q"),
            null
        )

        // 行为不变：仍返回跨页排队提示
        val action = result.getOrNull()
        assertTrue(action is AgentAction.TextReply)

        val r = recorded.single()
        assertEquals("fake_gallery", r.capability)
        assertEquals("flip_camera", r.commandType)
        assertFalse(r.success)
        assertEquals(AgentErrorCode.COMMAND_QUEUED, r.errorCode)
        assertTrue(r.errorMessage!!.contains("scene mismatch"))
        assertEquals("trace-q", r.traceId)
    }

    @Test
    fun `delegate not bound queues command and records COMMAND_QUEUED`() = runTest {
        installRecorder()
        sceneManager.transitionTo(SceneManager.Scene.CHAT)
        val registry = CapabilityRegistry.create(sceneManager)
        registry.register(
            fakeCapability("fake_chat", scenes = listOf(SceneManager.Scene.CHAT), available = false)
        )

        registry.dispatch(AgentCommand.FlipCamera(), AgentContext(scene = AgentScene.CHAT), null)

        val r = recorded.single()
        assertEquals("fake_chat", r.capability)
        assertFalse(r.success)
        assertEquals(AgentErrorCode.COMMAND_QUEUED, r.errorCode)
        assertEquals("delegate not bound", r.errorMessage)
    }

    @Test
    fun `successful dispatch is recorded by CommandExecutor not by dispatch event`() = runTest {
        installRecorder()
        sceneManager.transitionTo(SceneManager.Scene.CHAT)
        val registry = CapabilityRegistry.create(sceneManager)
        registry.register(fakeCapability("fake_chat", scenes = listOf(SceneManager.Scene.CHAT)))

        registry.dispatch(AgentCommand.FlipCamera(), AgentContext(scene = AgentScene.CHAT), null)

        // 正常执行仍只有 CommandExecutor 的一条成功记录，无新增的 dispatch event 重复记录
        val r = recorded.single()
        assertTrue(r.success)
        assertEquals("fake_chat", r.capability)
    }

    @Test
    fun `unregistered page capability falls back to METHOD_NOT_FOUND`() = runTest {
        installRecorder()
        sceneManager.transitionTo(SceneManager.Scene.CHAT)
        val registry = CapabilityRegistry.create(sceneManager)
        val capability = fakeCapability("fake_page", scenes = listOf(SceneManager.Scene.CHAT))
        registry.register(capability)

        // 页面退出 → 注销 → 命令不可达（2026-07-29 单轨收敛：CameraCapability 生命周期）
        registry.unregister(capability)
        registry.dispatch(AgentCommand.FlipCamera(), AgentContext(scene = AgentScene.CHAT), null)

        val r = recorded.single()
        assertFalse(r.success)
        assertEquals(AgentErrorCode.METHOD_NOT_FOUND, r.errorCode)
        assertEquals("(unresolved)", r.capability)
    }
}
