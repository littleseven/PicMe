package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CrossPageCommandQueue 队列机制测试（Phase 4 Task 15 覆盖盲区补齐）。
 *
 * 覆盖 KMP 化后（synchronized → 协程 Mutex）的核心契约：上限丢最旧、同 commandId 去重替换、
 * clear 清空并上报事件。`findCapability` 恒 null ——命令永不匹配场景、永不执行，
 * 队列纯积压，隔离测试入队/清理机制本身（场景匹配执行路径由 CapabilityRegistry 系列测试覆盖）。
 */
class CrossPageCommandQueueTest {

    private val context = AgentContext(scene = AgentScene.CAMERA)

    /** 测试桩 Capability：只用于 enqueue 的 targetScene 推导，execute 不会被触达。 */
    private val stubCapability = object : Capability {
        override val name: String = "stub"
        override val description: String = "stub"
        override fun supportedCommands(): List<String> = emptyList()
        override fun getCommandDescription(command: String): String = ""
        override fun isAvailable(): Boolean = true
        override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CAMERA)
        override suspend fun execute(
            command: AgentCommand,
            context: AgentContext,
            pageContext: PageContext?
        ): Result<AgentAction> = error("stub capability must never execute")
    }

    private fun newQueue(scope: kotlinx.coroutines.CoroutineScope) = CrossPageCommandQueue(
        sceneManager = SceneManager.getInstance(),
        commandExecutor = CommandExecutor(),
        findCapability = { null },
        externalScope = scope
    )

    @Test
    fun enqueueDeduplicatesByCommandId() = runTest {
        val queue = newQueue(backgroundScope)
        val command = AgentCommand.AdjustZoom(commandId = 42, zoomRatio = 1f)

        queue.enqueue(command, context, null, stubCapability)
        queue.enqueue(command.copy(zoomRatio = 2f), context, null, stubCapability)

        assertEquals(1, queue.size())
    }

    @Test
    fun clearEmptiesQueueAndEmitsEvent() = runTest {
        val queue = newQueue(backgroundScope)
        val events = mutableListOf<CrossPageCommandQueue.QueueEvent>()
        backgroundScope.launch { queue.queueEvents.collect { event -> events.add(event) } }
        // SharedFlow replay=0：必须先让收集器完成订阅，否则 tryEmit 时无订阅者直接丢事件
        testScheduler.runCurrent()

        queue.enqueue(AgentCommand.FlipCamera(), context, null, stubCapability)
        queue.enqueue(AgentCommand.CapturePhoto(), context, null, stubCapability)
        assertEquals(2, queue.size())

        queue.clear()

        assertEquals(0, queue.size())
        testScheduler.runCurrent()
        val cleared = events.filterIsInstance<CrossPageCommandQueue.QueueEvent.QueueCleared>()
        assertEquals(1, cleared.size)
        assertEquals(2, cleared.single().previousSize)
    }

    @Test
    fun enqueueDropsOldestWhenFull() = runTest {
        val queue = newQueue(backgroundScope)
        val events = mutableListOf<CrossPageCommandQueue.QueueEvent>()
        backgroundScope.launch { queue.queueEvents.collect { event -> events.add(event) } }
        // SharedFlow replay=0：必须先让收集器完成订阅，否则 tryEmit 时无订阅者直接丢事件
        testScheduler.runCurrent()

        repeat(CrossPageCommandQueue.MAX_QUEUE_SIZE + 1) { index ->
            queue.enqueue(AgentCommand.FlipCamera(commandId = index + 1), context, null, stubCapability)
        }

        assertEquals(CrossPageCommandQueue.MAX_QUEUE_SIZE, queue.size())
        testScheduler.runCurrent()
        val dropped = events.filterIsInstance<CrossPageCommandQueue.QueueEvent.Dropped>()
        assertEquals(1, dropped.size)
        assertTrue(dropped.single().reason.contains("${CrossPageCommandQueue.MAX_QUEUE_SIZE}"))
    }

    @Test
    fun enqueuedEventCarriesCurrentSize() = runTest {
        val queue = newQueue(backgroundScope)
        val events = mutableListOf<CrossPageCommandQueue.QueueEvent>()
        backgroundScope.launch { queue.queueEvents.collect { event -> events.add(event) } }
        // SharedFlow replay=0：必须先让收集器完成订阅，否则 tryEmit 时无订阅者直接丢事件
        testScheduler.runCurrent()

        queue.enqueue(AgentCommand.FlipCamera(commandId = 1), context, null, stubCapability)
        queue.enqueue(AgentCommand.FlipCamera(commandId = 2), context, null, stubCapability)

        testScheduler.runCurrent()
        val enqueued = events.filterIsInstance<CrossPageCommandQueue.QueueEvent.Enqueued>()
        assertEquals(2, enqueued.size)
        assertEquals(listOf(1, 2), enqueued.map { it.queueSize })
        assertIs<CrossPageCommandQueue.QueueEvent.Enqueued>(enqueued.first())
    }
}
