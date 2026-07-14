package com.mamba.picme.features.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.local.llm.LlmGenerationMetrics
import com.mamba.picme.agent.core.inference.local.llm.LlmModelNotFoundException
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.features.chat.capability.ChatSearchCapability
import com.mamba.picme.features.chat.capability.SearchOutcome
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ChatViewModel"
private const val MAX_MESSAGES = 500
private const val MAX_PREVIEW_LENGTH = 60
private const val MAX_CARDS = 20

/**
 * Chat 首页 ViewModel — 管理聊天状态与数据流
 *
 * 职责：
 * - 维护消息列表（从 Room 加载）
 * - 处理用户发送消息，通过 LLM 推理获取真实回复
 * - 管理模型切换状态（本地/远程）
 * - 提供处理中状态（isProcessing）
 * - 管理会话列表和当前会话切换
 */
@Suppress("TooManyFunctions") // UI 状态协调器，函数数量由会话管理辅助方法驱动
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    dependencies: ChatViewModelDependencies
) : ViewModel(), ChatSearchCapability.Delegate {

    private val context = dependencies.context.applicationContext
    private val chatMessageDao = dependencies.chatMessageDao
    private val chatSessionDao = dependencies.chatSessionDao
    private val userSettingsRepository = dependencies.userSettingsRepository
    private val mediaSearchEngine = dependencies.mediaSearchEngine

    /** session -> 上一轮搜索全量命中（供 in-set 细化）。 */
    private val lastResultAssets = mutableMapOf<String, List<MediaAsset>>()

    private val orchestrator = AgentOrchestrator.getInstance(context)

    private val _currentSessionId = MutableStateFlow("default")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    /**
     * 当前正在流式生成的 AI 消息（未落库），用于实时展示 token。
     */
    private val _streamingMessage = MutableStateFlow<ChatMessageUi?>(null)
    val streamingMessage: StateFlow<ChatMessageUi?> = _streamingMessage.asStateFlow()

    /**
     * UI 实际展示的消息列表：已持久化消息 + 流式临时消息。
     */
    val displayMessages: StateFlow<List<ChatMessageUi>> = combine(_messages, _streamingMessage) { messages, streaming ->
        if (streaming != null) messages + streaming else messages
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentModel = MutableStateFlow<ChatModelOption>(ChatModelOption.Local)
    val currentModel: StateFlow<ChatModelOption> = _currentModel.asStateFlow()

    private val _threads = MutableStateFlow<List<ChatThreadUi>>(emptyList())
    val threads: StateFlow<List<ChatThreadUi>> = _threads.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * 过滤后的线程列表
     */
    val filteredThreads: StateFlow<List<ChatThreadUi>> = combine(
        _threads,
        _searchQuery
    ) { threads, query ->
        if (query.isBlank()) threads
        else threads.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.lastMessagePreview.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 从设置中心同步推理偏好到 UI 的 ModelSelector
        viewModelScope.launch {
            try {
                userSettingsRepository.aiAgentInferencePreferenceFlow.collect { preference ->
                    _currentModel.value = when (preference) {
                        AiAgentInferencePreference.FORCE_LOCAL -> ChatModelOption.Local
                        AiAgentInferencePreference.FORCE_REMOTE,
                        AiAgentInferencePreference.AUTO -> ChatModelOption.Remote
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to sync inference preference from settings", e)
            }
        }
        loadMessages()
        loadThreads()
    }

    private fun loadMessages() {
        viewModelScope.launch {
            try {
                _currentSessionId
                    .flatMapLatest { sessionId ->
                        chatMessageDao.getMessagesBySession(sessionId)
                    }
                    .collect { entities ->
                        _messages.value = entities.map { it.toUiModel() }
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load messages", e)
            }
        }
    }

    private fun loadThreads() {
        viewModelScope.launch {
            try {
                chatSessionDao.getAllSessions()
                    .collect { sessions ->
                        val threads = sessions.map { session ->
                            val lastMessage = chatMessageDao.getLastMessageForSession(session.sessionId)
                            ChatThreadUi(
                                sessionId = session.sessionId,
                                title = resolveThreadTitle(session),
                                lastMessagePreview = lastMessage?.content?.take(MAX_PREVIEW_LENGTH) ?: "",
                                updatedAt = session.updatedAt,
                                isSelected = session.sessionId == _currentSessionId.value
                            )
                        }
                        _threads.value = threads
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load threads", e)
            }
        }
    }

    private fun resolveThreadTitle(session: ChatSessionEntity): String {
        return when {
            session.sessionId == "default" && session.title == "default" -> "New Chat"
            session.sessionId == "feishu" -> "飞书远程控制"
            session.title.isBlank() -> "Chat"
            else -> session.title
        }
    }

    /**
     * 切换当前会话
     */
    fun switchSession(sessionId: String) {
        _currentSessionId.value = sessionId
        Logger.i(TAG, "Switched to session: $sessionId")
    }

    /**
     * 创建新会话并切换过去
     */
    fun newSession() {
        val sessionId = UUID.randomUUID().toString()
        viewModelScope.launch {
            try {
                chatSessionDao.insertSession(
                    ChatSessionEntity(
                        sessionId = sessionId,
                        title = "New Chat"
                    )
                )
                _currentSessionId.value = sessionId
                Logger.i(TAG, "Created new session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to create session", e)
            }
        }
    }

    /**
     * 重命名会话
     */
    fun renameSession(sessionId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            try {
                val trimmed = newTitle.trim()
                chatSessionDao.updateTitle(sessionId, trimmed)
                Logger.i(TAG, "Renamed session $sessionId to: $trimmed")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to rename session", e)
            }
        }
    }

    /**
     * 删除会话及其消息；如果删除的是当前会话，切回 default
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                chatMessageDao.deleteAllMessagesBySession(sessionId)
                chatSessionDao.deleteSession(sessionId)
                if (_currentSessionId.value == sessionId) {
                    _currentSessionId.value = "default"
                }
                Logger.i(TAG, "Deleted session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete session", e)
            }
        }
    }

    /**
     * 更新搜索关键字（在内存中过滤线程列表）
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * 发送用户消息，通过 Agent 编排器执行命令或返回闲聊回复
     *
     * 流程：
     * 1. 保存用户消息到 Room
     * 2. 构建 Agent 上下文
     * 3. 创建流式占位消息，实时展示 token
     * 4. 调用 [AgentOrchestrator.streamChat] 流式推理
     * 5. 推理完成后保存完整结果到 Room
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                // 0. 确保会话元数据存在
                ensureSessionExists(sessionId)

                // 1. 保存用户消息
                val userMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = "user_text",
                    content = text,
                    modelUsed = null
                )
                chatMessageDao.insertMessage(userMessage)
                chatSessionDao.touchSession(sessionId)

                // 2. 触发处理状态
                _isProcessing.value = true

                // 3. 创建流式占位消息
                val streamingId = "streaming_${System.currentTimeMillis()}"
                _streamingMessage.value = ChatMessageUi(
                    id = streamingId,
                    type = ChatMessageType.AGENT_TEXT,
                    content = "",
                    modelUsed = currentModelLabel()
                )

                // 4. 构建 Agent 上下文
                val agentContext = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId
                )

                // 5. 调用流式推理
                val accumulatedContent = StringBuilder()
                val isLocalInference = orchestrator.getInferencePreference() == AiAgentInferencePreference.FORCE_LOCAL
                val result = orchestrator.streamChat(
                    input = text,
                    agentContext = agentContext,
                    onToken = { token ->
                        accumulatedContent.append(token)
                        val current = _streamingMessage.value
                        if (current != null && current.id == streamingId) {
                            // LOCAL 模式输出 JSON 命令，流式期间展示友好提示而非原始 JSON
                            val displayText = if (isLocalInference) {
                                "正在处理请求..."
                            } else {
                                accumulatedContent.toString()
                            }
                            _streamingMessage.value = current.copy(content = displayText)
                        }
                    }
                )

                // 6. 处理结果
                result.fold(
                    onSuccess = { streamResult ->
                        // 清除流式占位
                        _streamingMessage.value = null

                        if (streamResult.commands.isNotEmpty()) {
                            // 有命令需要执行：通过 CapabilityRegistry 分发
                            Logger.i(TAG, "Executing ${streamResult.commands.size} commands from streaming response")
                            val commands = streamResult.commands
                            val finalCommand = if (commands.size > 1) {
                                AgentCommand.BatchExecute(commands = commands)
                            } else {
                                commands.first()
                            }
                            val action = orchestrator.getCapabilityRegistry()
                                .dispatch(finalCommand, agentContext)
                            handleAgentAction(action.getOrNull(), sessionId, currentModelLabel(), null)
                        } else {
                            // 纯文本回复：保存到 Room（REMOTE 场景或 LOCAL 的 text_reply）
                            val performance = streamResult.metrics?.let { metrics ->
                                LlmPerformance(
                                    promptLen = metrics.promptTokens ?: 0,
                                    decodeLen = metrics.completionTokens ?: 0,
                                    prefillTimeMs = 0,
                                    decodeTimeMs = metrics.latencyMs,
                                    prefillSpeed = 0f,
                                    decodeSpeed = if (metrics.latencyMs > 0 && (metrics.completionTokens ?: 0) > 0)
                                        (metrics.completionTokens!!.toFloat() / metrics.latencyMs * 1000) else 0f
                                )
                            }
                            insertAgentMessage(
                                sessionId = sessionId,
                                content = streamResult.fullResponse,
                                modelUsed = currentModelLabel(),
                                performance = performance
                            )
                        }
                    },
                    onFailure = { error ->
                        // 清除流式占位
                        _streamingMessage.value = null
                        // 保存错误消息
                        insertAgentMessage(
                            sessionId = sessionId,
                            content = "推理出错：${error.message ?: "未知错误"}",
                            modelUsed = "error"
                        )
                    }
                )

                // 7. 清理超限消息
                cleanupIfNeeded(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send message", e)
                _streamingMessage.value = null
                // 保存错误提示
                val errorMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = "agent_text",
                    content = "推理出错：${e.message ?: "未知错误"}",
                    modelUsed = "error"
                )
                chatMessageDao.insertMessage(errorMessage)
                chatSessionDao.touchSession(sessionId)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun currentModelLabel(): String {
        return when (_currentModel.value) {
            is ChatModelOption.Local -> "local_qwen3.5_2b"
            is ChatModelOption.Remote -> "remote_deepseek"
        }
    }

    /**
     * 统一处理用户输入：本地/远程模型都走 processInputWithRouter。
     *
     * 本地 Qwen3-2B 已做过 OpenAI tool_calls 训练，因此 chat 页面统一通过 Tool Calling
     * 路径输出 OpenAI 格式指令；远程模型同样走此路径。
     */
    private suspend fun processAgentInput(text: String, sessionId: String) {
        val agentContext = AgentContext(
            scene = AgentScene.CHAT,
            memorySessionId = sessionId
        )
        val modelLabel = when (_currentModel.value) {
            is ChatModelOption.Local -> "local_qwen3.5_2b"
            is ChatModelOption.Remote -> "remote_deepseek"
        }

        val inferenceResult = orchestrator.processInputWithRouter(text, agentContext)
        val performance = if (_currentModel.value is ChatModelOption.Local) {
            orchestrator.getLastLocalGenerationMetrics()?.toLlmPerformance()
        } else {
            null
        }

        when (inferenceResult) {
            is InferenceResult.Chat -> {
                insertAgentMessage(sessionId, inferenceResult.message, modelLabel, performance)
            }
            is InferenceResult.Local -> {
                val action = orchestrator.getCapabilityRegistry()
                    .dispatch(inferenceResult.command, agentContext)
                handleAgentAction(action.getOrNull(), sessionId, modelLabel, performance)
            }
            is InferenceResult.Batch -> {
                val commands = inferenceResult.commands
                val finalCommand = if (commands.size > 1) {
                    AgentCommand.BatchExecute(commands = commands)
                } else {
                    commands.firstOrNull() ?: AgentCommand.TextReply(message = "没有可执行的命令")
                }
                val action = orchestrator.getCapabilityRegistry()
                    .dispatch(finalCommand, agentContext)
                handleAgentAction(action.getOrNull(), sessionId, modelLabel, performance)
            }
            is InferenceResult.Plan -> {
                val action = orchestrator.getCapabilityRegistry()
                    .dispatch(AgentCommand.ExecutePlan(plan = inferenceResult.plan), agentContext)
                handleAgentAction(action.getOrNull(), sessionId, modelLabel, performance)
            }
        }
    }

    /**
     * 将 AgentAction 渲染为聊天消息
     */
    private suspend fun handleAgentAction(
        action: AgentAction?,
        sessionId: String,
        modelLabel: String,
        performance: LlmPerformance? = null
    ) {
        when (action) {
            is AgentAction.TextReply -> {
                insertAgentMessage(sessionId, action.message, modelLabel, performance)
            }
            is AgentAction.MediaResults -> {
                val assets = lastResultAssets[sessionId].orEmpty()
                    .filter { it.id in action.mediaIds }
                    .take(MAX_CARDS)
                insertMediaResultsMessage(
                    sessionId,
                    MediaResultsUi(
                        query = action.query,
                        assets = assets,
                        totalCount = action.totalCount,
                        isRefinement = action.isRefinement
                    )
                )
            }
            is AgentAction.Success -> {
                insertAgentMessage(sessionId, describeCommandResult(action.command), "command", performance)
            }
            is AgentAction.Error -> {
                insertAgentMessage(sessionId, "❌ ${action.message}", "error", performance)
            }
            is AgentAction.BatchResult -> {
                val summary = action.results.joinToString("\n") { subAction ->
                    when (subAction) {
                        is AgentAction.Success -> describeCommandResult(subAction.command)
                        is AgentAction.Error -> "❌ ${subAction.message}"
                        is AgentAction.TextReply -> subAction.message
                        else -> ""
                    }
                }
                insertAgentMessage(sessionId, summary.ifBlank { "批量操作已完成" }, "command", performance)
            }
            null -> {
                insertAgentMessage(sessionId, "未获取到执行结果", "error", performance)
            }
        }
    }

    /**
     * 把命令执行结果转成用户友好的自然语言
     */
    private fun describeCommandResult(command: AgentCommand): String {
        return when (command) {
            is AgentCommand.NavigateTo -> "✅ 已切换到 ${command.destination}"
            is AgentCommand.GoBack -> "✅ 已返回上一页"
            is AgentCommand.LaunchApp -> {
                val target = command.appName ?: command.packageName ?: "应用"
                "✅ 已打开 $target"
            }
            is AgentCommand.OpenSystemSettings -> "✅ 已打开 ${command.setting} 设置"
            is AgentCommand.AiOptimize -> command.explanation?.let { "✅ $it" }
                ?: "✅ 已执行 AI 一键优化"
            is AgentCommand.BatchExecute -> "✅ 已执行批量操作"
            else -> "✅ 已执行 ${AgentCommand.getMethodName(command)}"
        }
    }

    // ── ChatSearchCapability.Delegate：相册搜索执行 ────────────────

    override suspend fun onSearchMedia(query: String): SearchOutcome {
        val sessionId = _currentSessionId.value
        val result = runCatching { mediaSearchEngine.search(query) }.getOrElse {
            return SearchOutcome(query, emptyList(), 0, isRefinement = false)
        }
        val photos = result.media.filter { it.type == MediaType.PHOTO }
        lastResultAssets[sessionId] = photos
        return SearchOutcome(query, photos.map { it.id }, photos.size, isRefinement = false)
    }

    override suspend fun onRefineMediaSearch(constraint: String): SearchOutcome {
        val sessionId = _currentSessionId.value
        val prior = lastResultAssets[sessionId].orEmpty()
        // 无上一轮 → 当 fresh 全局搜
        if (prior.isEmpty()) return onSearchMedia(constraint)
        val refined = ChatGallerySearch.filterInSet(prior, constraint)
        // in-set 空 → 回退全局重搜 constraint
        if (refined.isEmpty()) {
            val global = runCatching { mediaSearchEngine.search(constraint) }.getOrNull()
            val photos = global?.media?.filter { it.type == MediaType.PHOTO }.orEmpty()
            lastResultAssets[sessionId] = photos
            return SearchOutcome(constraint, photos.map { it.id }, photos.size, isRefinement = true)
        }
        lastResultAssets[sessionId] = refined
        return SearchOutcome(constraint, refined.map { it.id }, refined.size, isRefinement = true)
    }

    /**
     * 回退直连：不经 Agent，直接把文本喂 MediaSearchEngine（LLM 不可用时可用）。单轮。
     */
    fun searchGalleryDirectly(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                ensureSessionExists(sessionId)
                _isProcessing.value = true
                chatMessageDao.insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        type = "user_text",
                        content = text,
                        modelUsed = null
                    )
                )
                chatSessionDao.touchSession(sessionId)
                val outcome = onSearchMedia(text)
                val assets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
                insertMediaResultsMessage(
                    sessionId,
                    MediaResultsUi(outcome.query, assets, outcome.totalCount, isRefinement = false)
                )
                cleanupIfNeeded(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "Direct gallery search failed", e)
                insertAgentMessage(sessionId, "搜索失败：${e.message ?: "未知错误"}", "error")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun insertMediaResultsMessage(sessionId: String, ui: MediaResultsUi) {
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = "media_results",
                content = ChatGallerySearch.serializeContent(ui.assets),
                modelUsed = "gallery_search",
                metadata = ChatGallerySearch.serializeMetadata(ui.query, ui.totalCount, ui.isRefinement)
            )
        )
        chatSessionDao.touchSession(sessionId)
    }

    private suspend fun ensureSessionExists(sessionId: String) {
        val existing = chatSessionDao.getSession(sessionId)
        if (existing == null) {
            chatSessionDao.insertSession(
                ChatSessionEntity(
                    sessionId = sessionId,
                    title = if (sessionId == "default") "New Chat" else "Chat"
                )
            )
        }
    }

    /**
     * 插入 AI 回复/命令结果到 Room
     */
    private suspend fun insertAgentMessage(
        sessionId: String,
        content: String,
        modelUsed: String,
        performance: LlmPerformance? = null
    ) {
        val metadata = performance?.let {
            JSONObject().apply {
                put("prompt_len", it.promptLen)
                put("decode_len", it.decodeLen)
                put("prefill_time_ms", it.prefillTimeMs)
                put("decode_time_ms", it.decodeTimeMs)
                put("prefill_speed", it.prefillSpeed.toDouble())
                put("decode_speed", it.decodeSpeed.toDouble())
            }.toString()
        }
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = "agent_text",
                content = content,
                modelUsed = modelUsed,
                metadata = metadata
            )
        )
        chatSessionDao.touchSession(sessionId)
    }

    /**
     * 发送图片消息，通过本地 LLM 视觉模型进行图像理解
     */
    fun sendImageMessage(imageUri: Uri) {
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                ensureSessionExists(sessionId)
                _isProcessing.value = true

                // 0. 将图片复制到内部存储（content:// URI 权限在进程重启后失效）
                val persistedUri = persistImage(imageUri)
                if (persistedUri == null) {
                    insertAgentMessage(sessionId, "无法保存图片", "error")
                    return@launch
                }

                // 1. 保存用户图片消息到 Room（使用内部存储路径）
                val userMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = "user_image",
                    content = persistedUri,
                    modelUsed = null
                )
                chatMessageDao.insertMessage(userMessage)
                chatSessionDao.touchSession(sessionId)

                // 2. 创建流式占位
                val streamingId = "streaming_${System.currentTimeMillis()}"
                _streamingMessage.value = ChatMessageUi(
                    id = streamingId,
                    type = ChatMessageType.AGENT_TEXT,
                    content = "正在分析图片...",
                    modelUsed = currentModelLabel()
                )

                // 3. 加载 Bitmap
                val bitmap = context.contentResolver.openInputStream(imageUri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                if (bitmap == null) {
                    _streamingMessage.value = null
                    insertAgentMessage(sessionId, "无法加载图片", "error")
                    return@launch
                }

                // 4. 确保模型已加载并执行图像推理
                if (!orchestrator.isModelLoaded) {
                    _streamingMessage.value = ChatMessageUi(
                        id = streamingId,
                        type = ChatMessageType.AGENT_TEXT,
                        content = "正在加载模型...",
                        modelUsed = currentModelLabel()
                    )
                }
                val inferenceResult = orchestrator.withModelLoaded(
                    caller = "ChatViewModel:imageInference"
                ) { engine ->
                    engine.imageInference(
                        systemPrompt = "你是一个图像理解助手。请用简洁的中文描述这张图片的内容，包括主要对象、场景、颜色和氛围。",
                        userPrompt = "请描述这张图片",
                        bitmap = bitmap,
                        maxTokens = 256
                    )
                }

                if (inferenceResult.isFailure) {
                    _streamingMessage.value = null
                    val error = inferenceResult.exceptionOrNull()
                    val message = if (error is LlmModelNotFoundException || error?.message?.contains("模型") == true) {
                        "模型未加载：${error.message ?: "未知错误"}"
                    } else {
                        "图像处理出错：${error?.message ?: "未知错误"}"
                    }
                    insertAgentMessage(sessionId, message, "error")
                    return@launch
                }
                val response = inferenceResult.getOrThrow()

                // 清除流式占位
                _streamingMessage.value = null

                if (response.isBlank()) {
                    insertAgentMessage(sessionId, "(模型未返回结果)", "error")
                } else {
                    insertAgentMessage(
                        sessionId = sessionId,
                        content = response,
                        modelUsed = currentModelLabel(),
                        performance = orchestrator.getLastLocalGenerationMetrics()?.toLlmPerformance()
                    )
                    // 将图片分析结果保存到 MemoryManager，使后续文本消息能引用图片上下文
                    orchestrator.appendImageChatToMemory(
                        sessionId = sessionId,
                        userPrompt = "请描述这张图片",
                        imageAnalysis = response
                    )
                }

                cleanupIfNeeded(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to send image message", e)
                _streamingMessage.value = null
                insertAgentMessage(sessionId, "图像处理出错：${e.message ?: "未知错误"}", "error")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * 切换当前模型
     *
     * 将 UI 的 Local/Remote 选择映射到 [AiAgentInferencePreference]，
     * 同步到 AgentOrchestrator（控制实际推理路由）和 DataStore（设置中心同步更新）。
     */
    fun switchModel(model: ChatModelOption) {
        _currentModel.value = model
        viewModelScope.launch {
            try {
                val preference = when (model) {
                    is ChatModelOption.Local -> AiAgentInferencePreference.FORCE_LOCAL
                    is ChatModelOption.Remote -> AiAgentInferencePreference.FORCE_REMOTE
                }
                // 同步到 AgentOrchestrator（复用已有的远程配置）
                val existingRemoteConfig = orchestrator.getUserRemoteConfig()
                orchestrator.configure(
                    mode = orchestrator.getAgentMode(),
                    modelId = orchestrator.getCurrentModelId(),
                    privacyLevel = AiAgentPrivacyLevel.STRICT,
                    remoteConfig = existingRemoteConfig,
                    inferencePreference = preference
                )
                // 同步到 DataStore（设置中心会感知变化）
                userSettingsRepository.updateAiAgentInferencePreference(preference)
                Logger.i(TAG, "Model switched to: ${model.label}, inferencePreference=$preference")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to sync inference preference switch", e)
            }
        }
    }

    /**
     * 清空当前会话
     */
    fun clearChat() {
        viewModelScope.launch {
            try {
                val sessionId = _currentSessionId.value
                chatMessageDao.deleteAllMessagesBySession(sessionId)
                chatSessionDao.updateTitle(sessionId, "New Chat")
                _messages.value = emptyList()
                Logger.i(TAG, "Chat cleared for session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to clear chat", e)
            }
        }
    }

    /**
     * 如果消息数超过上限，删除最早的消息
     */
    private suspend fun cleanupIfNeeded(sessionId: String) {
        try {
            val count = chatMessageDao.getMessageCount(sessionId)
            if (count > MAX_MESSAGES) {
                val excess = count - MAX_MESSAGES
                chatMessageDao.deleteOldestMessages(sessionId, excess)
                Logger.i(TAG, "Cleaned up $excess old messages for session $sessionId")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to cleanup messages", e)
        }
    }

    private fun ChatMessageEntity.toUiModel(): ChatMessageUi {
        val isMediaResults = type == "media_results"
        val performance = if (isMediaResults) null else metadata?.let { parsePerformanceMetadata(it) }
        val mediaResults = if (isMediaResults) ChatGallerySearch.deserialize(content, metadata) else null
        return ChatMessageUi(
            id = id,
            type = when (type) {
                "user_text" -> ChatMessageType.USER_TEXT
                "agent_text" -> ChatMessageType.AGENT_TEXT
                "user_image" -> ChatMessageType.USER_IMAGE
                "agent_image" -> ChatMessageType.AGENT_IMAGE
                "command" -> ChatMessageType.COMMAND
                "plan_preview" -> ChatMessageType.PLAN_PREVIEW
                "media_results" -> ChatMessageType.MEDIA_RESULTS
                else -> ChatMessageType.AGENT_TEXT
            },
            content = content,
            modelUsed = modelUsed,
            timestamp = timestamp,
            performance = performance,
            mediaResults = mediaResults
        )
    }

    /**
     * 从 metadata JSON 解析本地 LLM 性能指标
     */
    private fun parsePerformanceMetadata(metadata: String): LlmPerformance? {
        return try {
            val json = JSONObject(metadata)
            LlmPerformance(
                promptLen = json.optLong("prompt_len", 0),
                decodeLen = json.optLong("decode_len", 0),
                prefillTimeMs = json.optLong("prefill_time_ms", 0),
                decodeTimeMs = json.optLong("decode_time_ms", 0),
                prefillSpeed = json.optDouble("prefill_speed", 0.0).toFloat(),
                decodeSpeed = json.optDouble("decode_speed", 0.0).toFloat()
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse performance metadata", e)
            null
        }
    }

    private fun LlmGenerationMetrics.toLlmPerformance(): LlmPerformance {
        return LlmPerformance(
            promptLen = promptLen,
            decodeLen = decodeLen,
            prefillTimeMs = prefillTime / 1000,
            decodeTimeMs = decodeTime / 1000,
            prefillSpeed = prefillSpeed,
            decodeSpeed = decodeSpeed
        )
    }

    /**
     * 将 content:// URI 图片复制到内部存储，返回持久化路径
     * 解决 content picker 临时权限在进程重启后失效导致图片不显示的问题
     */
    private fun persistImage(sourceUri: Uri): String? {
        return try {
            val imagesDir = java.io.File(context.filesDir, "picme_images")
            if (!imagesDir.exists()) imagesDir.mkdirs()

            val destFile = java.io.File(imagesDir, "img_${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to persist image", e)
            null
        }
    }
}
