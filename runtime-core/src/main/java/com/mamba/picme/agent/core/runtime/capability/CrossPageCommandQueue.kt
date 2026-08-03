package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 跨页面命令队列
 *
 * 管理跨页面命令的排队、执行和生命周期：
 * - 上限检查（默认 50）
 * - 去重（相同 commandId 替换）
 * - TTL 过期清理（默认 5 分钟）
 * - 重试机制（默认 3 次）
 * - 场景匹配后自动执行
 */
class CrossPageCommandQueue(
    private val sceneManager: SceneManager,
    private val commandExecutor: CommandExecutor,
    private val findCapability: (AgentCommand) -> Capability?,
    private val externalScope: CoroutineScope? = null,
    private val logger: Logger? = null
) {

    companion object {
        const val MAX_QUEUE_SIZE = 50
        const val QUEUE_TTL_MS = 300_000L
        const val MAX_RETRY_COUNT = 3
        const val QUEUE_POLL_INTERVAL_MS = 500L
    }

    private val tag = "CrossPageCommandQueue"

    data class QueuedCommand(
        val command: AgentCommand,
        val context: AgentContext,
        val pageContext: PageContext?,
        val targetScene: String,
        val enqueueTime: Long = System.currentTimeMillis(),
        val retryCount: Int = 0
    )

    private val commandQueue = mutableListOf<QueuedCommand>()
    private val queueLock = Object()

    private val _queueEvents = MutableSharedFlow<QueueEvent>(extraBufferCapacity = 64)
    val queueEvents: SharedFlow<QueueEvent> = _queueEvents.asSharedFlow()

    @Volatile
    private var isProcessorRunning = false

    sealed class QueueEvent {
        data class Enqueued(val commandType: String, val queueSize: Int) : QueueEvent()
        data class Executed(val commandType: String, val success: Boolean) : QueueEvent()
        data class Expired(val commandType: String, val ageMs: Long) : QueueEvent()
        data class Dropped(val commandType: String, val reason: String) : QueueEvent()
        data class Retry(val commandType: String, val retryCount: Int) : QueueEvent()
        data class QueueCleared(val previousSize: Int) : QueueEvent()
    }

    /**
     * [externalScope] 未注入时复用的内部 scope：懒创建并缓存，
     * 避免 getter 每次新建 [SupervisorJob] 启动处理协程后旧 Job 泄漏。
     */
    private val fallbackScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    private val queueScope: CoroutineScope
        get() = externalScope ?: fallbackScope

    fun enqueue(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        capability: Capability
    ) {
        val targetScene = capability.activeScenes().firstOrNull()?.name ?: SceneManager.Scene.UNKNOWN.name
        val commandType = command::class.simpleName ?: "Unknown"

        synchronized(queueLock) {
            if (commandQueue.size >= MAX_QUEUE_SIZE) {
                logger?.w(tag, "Queue full ($MAX_QUEUE_SIZE), dropping oldest command")
                val dropped = commandQueue.removeAt(0)
                _queueEvents.tryEmit(
                    QueueEvent.Dropped(
                        commandType = dropped.command::class.simpleName ?: "Unknown",
                        reason = "Queue exceeded max size $MAX_QUEUE_SIZE"
                    )
                )
            }

            val existingIndex = commandQueue.indexOfFirst { it.command.commandId == command.commandId }
            if (existingIndex >= 0) {
                logger?.d(tag, "Duplicate command ${command.commandId} detected, replacing old entry")
                commandQueue.removeAt(existingIndex)
            }

            val queuedCommand = QueuedCommand(command, context, pageContext, targetScene)
            commandQueue.add(queuedCommand)
            val currentSize = commandQueue.size

            logger?.i(tag, "Command queued for scene $targetScene, queue size: $currentSize")
            _queueEvents.tryEmit(QueueEvent.Enqueued(commandType = commandType, queueSize = currentSize))
        }

        startQueueProcessor()
    }

    fun clear() {
        val previousSize: Int
        synchronized(queueLock) {
            previousSize = commandQueue.size
            commandQueue.clear()
        }
        logger?.i(tag, "Command queue cleared (was $previousSize)")
        _queueEvents.tryEmit(QueueEvent.QueueCleared(previousSize = previousSize))
    }

    fun size(): Int = synchronized(queueLock) { commandQueue.size }

    private fun startQueueProcessor() {
        if (isProcessorRunning) return
        isProcessorRunning = true

        queueScope.launch {
            logger?.i(tag, "Queue processor started")
            while (true) {
                val currentScene = sceneManager.currentScene.value
                val now = System.currentTimeMillis()
                var executedCount = 0
                var expiredCount = 0

                synchronized(queueLock) {
                    val iterator = commandQueue.iterator()

                    while (iterator.hasNext()) {
                        val queued = iterator.next()
                        val capability = findCapability(queued.command)
                        val sceneMatch = capability?.let { cap ->
                            cap.activeScenes().any { it.name == currentScene.name }
                        } ?: false
                        val available = capability?.isAvailable() ?: false
                        val ageMs = now - queued.enqueueTime

                        if (ageMs > QUEUE_TTL_MS) {
                            iterator.remove()
                            expiredCount++
                            val cmdType = queued.command::class.simpleName ?: "Unknown"
                            logger?.w(tag, "Command expired after ${ageMs}ms: $cmdType")
                            _queueEvents.tryEmit(
                                QueueEvent.Expired(commandType = cmdType, ageMs = ageMs)
                            )
                            continue
                        }

                        logger?.d(tag, "Checking queued command: ${queued.command::class.simpleName}, " +
                            "capability=${capability?.name}, sceneMatch=$sceneMatch, available=$available, age=${ageMs}ms")

                        if (capability != null && sceneMatch && available) {
                            iterator.remove()
                            executedCount++
                            val cmdType = queued.command::class.simpleName ?: "Unknown"
                            logger?.i(tag, "Executing queued command for scene $currentScene")

                            launch {
                                val result = commandExecutor.execute(
                                    queued.command,
                                    queued.context,
                                    queued.pageContext,
                                    capability
                                )
                                val success = result.isSuccess
                                _queueEvents.tryEmit(QueueEvent.Executed(commandType = cmdType, success = success))

                                if (!success && queued.retryCount < MAX_RETRY_COUNT) {
                                    logger?.w(tag, "Command failed, retrying (${queued.retryCount + 1}/$MAX_RETRY_COUNT)")
                                    val retryCommand = queued.copy(retryCount = queued.retryCount + 1)
                                    synchronized(queueLock) {
                                        commandQueue.add(retryCommand)
                                    }
                                    _queueEvents.tryEmit(
                                        QueueEvent.Retry(commandType = cmdType, retryCount = queued.retryCount + 1)
                                    )
                                }
                            }
                        }
                    }
                }

                if (executedCount > 0) {
                    logger?.i(tag, "Queue processor executed $executedCount commands, remaining: ${size()}")
                }
                if (expiredCount > 0) {
                    logger?.w(tag, "Queue processor expired $expiredCount commands")
                }

                val isEmpty = synchronized(queueLock) { commandQueue.isEmpty() }
                if (isEmpty) {
                    logger?.i(tag, "Queue processor stopped, queue empty")
                    isProcessorRunning = false
                    break
                }

                delay(QUEUE_POLL_INTERVAL_MS)
            }
        }
    }
}
