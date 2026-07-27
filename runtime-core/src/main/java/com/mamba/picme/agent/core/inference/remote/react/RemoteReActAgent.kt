package com.mamba.picme.agent.core.inference.remote.react

import android.view.WindowManager
import com.mamba.memory.ChatMemory
import com.mamba.model.chat.request.ToolChoice
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.DataStoreChatMemoryStore
import com.mamba.picme.agent.core.inference.remote.tool.PoLangToolService
import com.mamba.service.AiServices
import com.mamba.data.message.SystemMessage
import com.mamba.model.chat.listener.ChatModelListener
import com.mamba.model.chat.listener.ChatModelResponseContext
import com.mamba.model.output.TokenUsage
import com.mamba.picme.agent.core.inference.remote.log.TraceIdHolder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程 ReAct Agent（AiServices 版本）。
 *
 * <p>使用 AiServices 模式替代手动 ReAct loop：
 * <ul>
 *   <li>通过 {@link AiServices.Builder} 显式注入所有依赖</li>
 *   <li>工具调用由 AiServices 代理自动处理</li>
 *   <li>ChatMemory 自动维护对话历史</li>
 *   <li>无 SPI/ServiceLoader 依赖</li>
 * </ul>
 *
 * @see AiServices
 */
class RemoteReActAgent(
    private val config: RemoteReActAgentConfig,
    private val windowManager: WindowManager? = null,
    private val callback: RemoteReActAgentCallback,
    private val appContext: android.content.Context? = null,
    private val toolService: Any? = null
) {
    companion object {
        private const val TAG = "RemoteReActAgent"
    }

    // 飞书（远程控制 RPA）默认用 PoLangToolService(windowManager)；chat 注入 ChatToolService（不需 windowManager）。
    // 当 toolService=null 时要求 windowManager 非 null（飞书路径）。
    private val effectiveToolService: Any =
        toolService ?: PoLangToolService(windowManager!!)

    private val chatModel by lazy {
        val remoteModelConfig = RemoteModelConfig(
            modelId = config.modelName,
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            gatewayToken = config.gatewayToken ?: ""
        )

        val builder = RemoteModelFactory.createBuilder(remoteModelConfig, "react", traceIdHolder)
            .logRequests(true)
            .logResponses(true)

        config.gatewayToken?.let {
            builder.customHeader("X-App-Token", it)
        }
        // 注册与访客均带 X-Device-Id：注册用户用于后台 device 维度展示,访客用于设备级试用额度。
        if (config.deviceId.isNotBlank()) {
            builder.customHeader("X-Device-Id", config.deviceId)
        }

        builder.listeners(object : ChatModelListener {
            override fun onResponse(responseContext: ChatModelResponseContext) {
                val usage = responseContext.chatResponse().metadata()?.tokenUsage()
                if (usage != null) {
                    val current = accumulatedTokenUsage
                    accumulatedTokenUsage = if (current == null) {
                        usage
                    } else {
                        current.add(usage)
                    }
                    Logger.d(TAG, "Token usage accumulated: input=${usage.inputTokenCount()}, output=${usage.outputTokenCount()}, total=${usage.totalTokenCount()}")
                }
            }
        })

        builder.build()
    }

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 当轮 traceId 持有器：[executeTask] 开始时写入，[chatModel] 的 CapturingChatModelListener
     * 读取后落入 LlmCallRecord。单线程 executor 串行执行 → 无竞态。
     */
    private val traceIdHolder = TraceIdHolder()

    /** 记录每次执行的性能指标 */
    private var lastExecutionMetrics: AgentExecutionMetrics? = null

    /** 累计 Token 使用量（多轮工具调用时累加） */
    private var accumulatedTokenUsage: TokenUsage? = null

    /** 每个 session 最多保留最近 10 轮对话（5 个 user+assistant 对） */
    private val maxMemoryMessages = 10

    /**
     * 当前会话的 memory ID。飞书默认 "feishu_p2p"；chat 经 [setSessionId] 按会话切换，
     * 使每段聊天对话拥有独立 ChatMemory，避免历史互相污染（旧文字图 / 大数据不会带进新会话）。
     */
    private var sessionId: String = "feishu_p2p"

    /** 切换会话：换 memory ID 并废弃缓存的 assistant，下次重建时绑定新 memory。 */
    fun setSessionId(id: String) {
        if (id.isNotBlank() && id != sessionId) {
            sessionId = id
            assistant = null
        }
    }

    /** DataStore 持久化存储 */
    private val chatMemoryStore by lazy {
        val ctx = appContext
            ?: throw IllegalStateException("No context available for DataStoreChatMemoryStore")
        DataStoreChatMemoryStore(ctx)
    }

    /** sessionId → ChatMemory 缓存 */
    private val sessionMemories = mutableMapOf<String, ChatMemory>()

    /** AiServices 代理缓存 */
    private var assistant: PoLangAssistant? = null

    /**
     * PoLang AI 助手接口契约（内联，避免单独文件）。
     */
    private interface PoLangAssistant {
        fun chat(message: String): String
    }

    /**
     * 获取或创建指定 session 的 ChatMemory
     */
    private fun getOrCreateMemory(sessionId: String): ChatMemory {
        return sessionMemories.getOrPut(sessionId) {
            DataStoreChatMemory(
                memoryId = sessionId,
                store = chatMemoryStore,
                maxMessages = maxMemoryMessages
            )
        }
    }

    /**
     * 获取或创建 PoLangAssistant（AiServices 代理）。
     */
    private fun getOrCreateAssistant(): PoLangAssistant {
        return assistant ?: run {
            val memory = getOrCreateMemory(sessionId)
            val newAssistant = AiServices.builder(PoLangAssistant::class.java)
                .builder()
                .chatModel(chatModel)
                .chatMemory(memory)
                .tools(effectiveToolService)
                .systemMessageProvider { SystemMessage.from(config.systemPrompt) }
                .toolChoice(ToolChoice.AUTO)
                .maxIterations(config.maxIterations)
                .build()
            assistant = newAssistant
            newAssistant
        }
    }

    fun initialize() {
        Logger.i(TAG, "Remote ReAct Agent initialized: model=${config.modelName}")
    }

    /**
     * 获取最近一次执行的性能指标
     */
    fun getLastExecutionMetrics(): AgentExecutionMetrics? = lastExecutionMetrics

    fun executeTask(userPrompt: String, taskCallback: RemoteReActAgentCallback? = null, traceId: String? = null) {
        if (running.get()) {
            (taskCallback ?: callback).onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        accumulatedTokenUsage = null

        executor.submit {
            try {
                runAgentWithAiServices(userPrompt, taskCallback, traceId)
            } catch (e: Exception) {
                Logger.e(TAG, "Agent execution error", e)
                (taskCallback ?: callback).onError(0, e, 0)
            } finally {
                running.set(false)
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    fun isRunning(): Boolean = running.get()

    // ==================== AiServices 代理调用（替代手动 ReAct loop）====================

    private fun runAgentWithAiServices(userPrompt: String, taskCallback: RemoteReActAgentCallback? = null, traceId: String? = null) {
        val cb = taskCallback ?: callback

        Logger.d(TAG, "runAgentWithAiServices start: userPrompt='$userPrompt'")
        cb.onLoopStart(1)

        val startTime = System.currentTimeMillis()
        traceIdHolder.value = traceId
        try {
            // 获取 AiServices 代理（自动处理工具调用循环）
            val assistant = getOrCreateAssistant()

            // 调用 chat 方法，AiServices 内部自动处理：
            // 1. 添加 UserMessage 到 ChatMemory
            // 2. 调用 LLM 传入 toolSpecifications
            // 3. 如果返回 tool calls，自动执行工具并构建 ToolExecutionResultMessage
            // 4. 继续循环直到没有 tool calls 或达到 maxIterations
            //
            // 注意：这里的重试由底层 ChatModel（OpenAiChatModel 等）在 HTTP 层完成；
            // 我们不在 assistant.chat 级别做顶层重试，因为一旦工具已经被执行，重新添加
            // UserMessage 会破坏 "assistant tool_calls → tool messages → assistant" 的合法序列。
            val result = assistant.chat(userPrompt)

            val latencyMs = System.currentTimeMillis() - startTime
            val totalTokens = accumulatedTokenUsage
            val metrics = AgentExecutionMetrics(
                latencyMs = latencyMs,
                promptTokens = totalTokens?.inputTokenCount(),
                completionTokens = totalTokens?.outputTokenCount(),
                modelName = config.modelName
            )
            lastExecutionMetrics = metrics

            Logger.i(TAG, "Task complete: result='$result', latency=${latencyMs}ms, tokens=$totalTokens")
            cb.onComplete(1, result, totalTokens?.totalTokenCount() ?: 0, metrics)

        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            val metrics = AgentExecutionMetrics(
                latencyMs = latencyMs,
                promptTokens = accumulatedTokenUsage?.inputTokenCount(),
                completionTokens = accumulatedTokenUsage?.outputTokenCount(),
                modelName = config.modelName
            )
            lastExecutionMetrics = metrics

            if (cancelled.get()) {
                Logger.d(TAG, "Task cancelled")
                cb.onComplete(0, "Task cancelled", accumulatedTokenUsage?.totalTokenCount() ?: 0, metrics)
            } else {
                Logger.e(TAG, "Agent execution failed", e)
                val friendlyError = RuntimeException(buildFriendlyErrorMessage(e), e)
                cb.onError(0, friendlyError, accumulatedTokenUsage?.totalTokenCount() ?: 0, metrics)
            }
        }

        traceIdHolder.value = null
        Logger.d(TAG, "runAgentWithAiServices end")
    }

    /**
     * 把底层异常转换为飞书用户友好的错误描述。
     */
    private fun buildFriendlyErrorMessage(original: Throwable): String {
        val causeChain = generateSequence<Throwable>(original) { it.cause }
        val hasUpstream = causeChain.any { it.message?.contains("upstream_error", ignoreCase = true) == true }
        val hasToolSequence = original.message?.contains("tool_calls", ignoreCase = true) == true

        return when {
            hasUpstream -> {
                "远程模型服务暂时不可用（upstream error），请稍后重试，或到设置切换其他模型供应商。"
            }
            hasToolSequence -> {
                "对话历史中的工具调用消息顺序异常，已自动重置会话，请重新发送指令。"
            }
            else -> {
                "远程模型调用失败：${original.message ?: "未知错误"}"
            }
        }
    }

    /**
     * 重置当前会话（清除 ChatMemory 和 Assistant）。
     * 用于开始新的对话或重置状态。
     */
    fun resetSession() {
        assistant = null
        sessionMemories[sessionId]?.clear()
        Logger.d(TAG, "Session reset")
    }
}

/**
 * 基于 DataStore 的 ChatMemory 实现
 *
 * 实现 [com.mamba.memory.ChatMemory] 接口，
 * 使用 [DataStoreChatMemoryStore] 作为后端持久化器。
 * 支持最大消息数限制（滑动窗口）。
 *
 * @property memoryId 会话 ID
 * @property store DataStore 持久化器
 * @property maxMessages 最大消息数（超出时丢弃最早的消息）
 */
private class DataStoreChatMemory(
    private val memoryId: String,
    private val store: DataStoreChatMemoryStore,
    private val maxMessages: Int = 10
) : ChatMemory {

    override fun id(): Any = memoryId

    // 内存缓存：messages() 直接返回，避免每次 add 都 read DataStore 导致写/读时序丢失 tool 历史
    private val cache: MutableList<com.mamba.data.message.ChatMessage> =
        store.getMessages(memoryId).toMutableList()

    override fun messages(): MutableList<com.mamba.data.message.ChatMessage> = cache

    override fun add(message: com.mamba.data.message.ChatMessage) {
        // 内存缓存更新（DataStore 仅持久化，不再每次 read，避免写/读时序丢失 tool 历史）
        if (message is com.mamba.data.message.SystemMessage) {
            cache.removeAll { it is com.mamba.data.message.SystemMessage }
            cache.add(0, message)
        } else {
            cache.add(message)
        }

        trimToMaxMessages(cache)
        store.updateMessages(memoryId, cache)
    }

    /**
     * 将消息列表截断到 [maxMessages]，同时保证 OpenAI tool_calls 序列合法：
     * 每个包含 tool_calls 的 assistant 消息及其后续所有 tool result 消息必须成块保留/删除，
     * 不能只保留一半，否则会出现 "insufficient tool messages following tool_calls message" 错误。
     */
    private fun trimToMaxMessages(messages: MutableList<com.mamba.data.message.ChatMessage>) {
        if (messages.size <= maxMessages) return

        val systemMsg = messages.filterIsInstance<com.mamba.data.message.SystemMessage>().firstOrNull()
        val nonSystem = messages.filter { it !is com.mamba.data.message.SystemMessage }

        // 把非系统消息划分成 "块"：
        // - 普通消息自己一块
        // - assistant(tool_calls) + 紧随其后的所有 tool result 消息为一块
        val blocks = mutableListOf<MutableList<com.mamba.data.message.ChatMessage>>()
        var i = 0
        while (i < nonSystem.size) {
            val msg = nonSystem[i]
            if (msg is com.mamba.data.message.AiMessage && msg.hasToolExecutionRequests()) {
                val block = mutableListOf<com.mamba.data.message.ChatMessage>(msg)
                i++
                while (i < nonSystem.size && nonSystem[i] is com.mamba.data.message.ToolExecutionResultMessage) {
                    block.add(nonSystem[i])
                    i++
                }
                blocks.add(block)
            } else {
                blocks.add(mutableListOf(msg))
                i++
            }
        }

        // 从最新的一块往回取，确保 tool-call 块不被拆散
        val systemSize = if (systemMsg != null) 1 else 0
        val available = maxMessages - systemSize
        val keptBlocks = mutableListOf<MutableList<com.mamba.data.message.ChatMessage>>()
        var keptCount = 0
        for (block in blocks.asReversed()) {
            if (block.size > available) {
                // 单块就超出预算，丢弃整块，避免破坏 tool_calls 配对
                continue
            }
            if (keptCount + block.size <= available) {
                keptBlocks.add(0, block)
                keptCount += block.size
            } else {
                break
            }
        }

        messages.clear()
        systemMsg?.let { messages.add(it) }
        keptBlocks.forEach { messages.addAll(it) }
    }

    override fun clear() {
        store.deleteMessages(memoryId)
    }

    /**
     * 清空并重新设置消息列表（用于过滤历史消息）
     */
    fun clearAndSet(messages: MutableList<com.mamba.data.message.ChatMessage>) {
        store.updateMessages(memoryId, messages)
    }
}
