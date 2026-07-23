package com.mamba.picme.features.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.SearchResultSnapshot
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.inference.local.llm.LlmGenerationMetrics
import com.mamba.picme.agent.core.inference.local.llm.LlmModelNotFoundException
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.model.ProviderConfigs
import com.mamba.picme.domain.search.MediaFeedbackUseCase
import com.mamba.picme.domain.usecase.StartTagScanResult
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import android.util.Log
import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.agent.core.js.syncHandler
import com.mamba.picme.agent.core.js.toJsValue
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.features.chat.capability.ChatGallerySummaryCapability
import com.mamba.picme.features.chat.capability.ChatRunScriptCapability
import com.mamba.picme.features.chat.capability.ChatSearchCapability
import com.mamba.picme.features.chat.capability.ChatStartTagScanCapability
import com.mamba.picme.features.chat.capability.SearchOutcome
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "ChatViewModel"
private const val MAX_MESSAGES = 500
private const val MAX_PREVIEW_LENGTH = 60
private const val MAX_CARDS = 20

/** Chat 选图后的用户意图。EDIT 在 UI 层直接跳 PhotoEditor，不会进入 VM 的 sendImageWithIntent。 */
enum class ImageIntent { UNDERSTAND, FIND_SIMILAR, EDIT }

/**
 * chat 远程模型来源（chat 已移除本地 LLM、仅远程；用户配了自配 Key 时可「默认服务器/自配 Key」切换）。
 */
enum class RemoteModelSource(val label: String) {
    DEFAULT("官方LLM"),
    USER_KEY("自配 Key")
}

/**
 * 流式生成期间的占位文案。
 *
 * L2 协议下本地/远程输出恒为 JSON 指令（如 search_media / text_reply），不可直接展示原始 token；
 * 且远程推理为同步一次性返回（onToken 只回调一次），流式期间无可增量展示的文本。
 * 因此生成阶段统一展示该友好提示，待解析完成后再替换为最终文本/卡片消息。
 */
private const val STREAMING_THINKING_HINT = "正在思考..."

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
) : ViewModel(),
    ChatSearchCapability.Delegate,
    ChatGallerySummaryCapability.Delegate,
    ChatRunScriptCapability.Delegate,
    ChatStartTagScanCapability.Delegate {

    private val context = dependencies.context.applicationContext
    private val chatMessageDao = dependencies.chatMessageDao
    private val chatSessionDao = dependencies.chatSessionDao
    private val userSettingsRepository = dependencies.userSettingsRepository
    private val mediaSearchEngine = dependencies.mediaSearchEngine
    private val mediaFeedbackRepository = dependencies.mediaFeedbackRepository
    private val getGallerySummaryUseCase = dependencies.getGallerySummaryUseCase
    private val startTagScanUseCase = dependencies.startTagScanUseCase
    private val chatImageRenderer = dependencies.chatImageRenderer

    private val mediaFeedbackUseCase = MediaFeedbackUseCase(mediaFeedbackRepository)
    private val authClient = dependencies.picMeAuthClient

    /** session -> 上一轮搜索全量命中（供 in-set 细化）。 */
    private val lastResultAssets = mutableMapOf<String, List<MediaAsset>>()

    /** session -> 最近搜索快照（多轮对话指代用）。 */
    private val sessionSearchSnapshots = mutableMapOf<String, MutableList<SearchResultSnapshot>>()

    /** session -> 当前生效的排除约束（内存实现，跟随当前搜索结果）。 */
    private val sessionExcludes = mutableMapOf<String, MutableSet<String>>()

    /** 防止用户快速重复点击同一反馈按钮。 */
    private val pendingFeedbackActions = mutableSetOf<String>()

    /** 当前会话最近一条用户图片消息的持久化 URI，供 ai_optimize 指代「这张照片】。 */
    private val _lastUserImageUri = MutableStateFlow<String?>(null)

    /**
     * 时间专属词集合。当 [SearchIntent.timeRange] 已经表达了时间范围时，
     * 这些词不应再作为内容关键词去匹配标签/OCR/文件名，否则会导致时间候选集与空标签候选集交集为空。
     */
    private val timeOnlyKeywords = setOf(
        "去年", "今年", "明年", "前年", "后年",
        "春天", "夏天", "秋天", "冬天", "春季", "夏季", "秋季", "冬季",
        "上半年", "下半年", "近半年", "最近半年", "半年",
        "近一年", "最近一年", "一年", "近几年", "最近几年",
        "最近", "近三个月", "近3个月",
        "今天", "昨天", "前天", "明天", "后天",
        "上周", "本周", "下周",
        "上星期", "这星期", "下星期", "上个星期", "这个星期", "下个星期",
        "上个月", "这个月", "下个月", "上月", "今月", "下月"
    )

    /** 匹配“3月”“12月”“五月”等月份表达。 */
    private val monthKeywordRegex = Regex("""^(\d{1,2}月|[一二三四五六七八九十]{1,3}月)$""")

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
     * AI 优化命令触发后需要导航到编辑器的目标 URI。
     */
    private val _pendingAiOptimizeNavigation = MutableStateFlow<String?>(null)
    val pendingAiOptimizeNavigation: StateFlow<String?> = _pendingAiOptimizeNavigation.asStateFlow()

    fun consumeAiOptimizeNavigation() {
        _pendingAiOptimizeNavigation.value = null
    }

    /**
     * UI 实际展示的消息列表：已持久化消息 + 流式临时消息。
     */
    val displayMessages: StateFlow<List<ChatMessageUi>> = combine(_messages, _streamingMessage) { messages, streaming ->
        if (streaming != null) messages + streaming else messages
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentModel = MutableStateFlow<ChatModelOption>(ChatModelOption.Remote)
    val currentModel: StateFlow<ChatModelOption> = _currentModel.asStateFlow()

    /** chat 可选远程模型项（官方 / 用户自配）。 */
    data class ChatRemoteModel(val id: String, val displayName: String, val remoteConfig: RemoteModelConfig)

    private val officialModel = ChatRemoteModel("official", "官方LLM", RemoteModelConfig.PICME_SERVER_DEFAULT)

    /** 可选模型列表：官方 + 用户自配（已配置 apiKey 的）。 */
    private val _availableModels = MutableStateFlow<List<ChatRemoteModel>>(listOf(officialModel))
    val availableModels: StateFlow<List<ChatRemoteModel>> = _availableModels.asStateFlow()

    /** 当前选中模型 id（默认官方）。 */
    private val _selectedModelId = MutableStateFlow(officialModel.id)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    /** 当前选中模型。 */
    val selectedModel: ChatRemoteModel
        get() = _availableModels.value.find { it.id == _selectedModelId.value } ?: officialModel

    /**
     * 官方模型注入账户 token（gatewayToken → X-App-Token，走 PoLang Server 账户额度）；
     * 用户自配模型用其 apiKey 直连，无需注入。
     */
    private fun effectiveRemoteConfig(model: ChatRemoteModel): RemoteModelConfig =
        if (model.id == officialModel.id) {
            model.remoteConfig.copy(gatewayToken = _serverAuthToken.value)
        } else {
            model.remoteConfig
        }

    /** 用户是否配了自配 Key（决定是否显示模型切换胶囊）。从设置中心 flow 实时更新。 */
    private val _hasUserKey = MutableStateFlow(false)
    val hasUserKey: StateFlow<Boolean> = _hasUserKey.asStateFlow()

    // ── 访客模式与注册引导 ──────────────────────────────────
    private val _serverAuthToken = MutableStateFlow("")

    init {
        viewModelScope.launch {
            userSettingsRepository.serverAuthTokenFlow.collect { token ->
                _serverAuthToken.value = token
            }
        }
    }

    /** 远程模式且未注册（无 server token）→ 访客试用，由服务端设备级额度放行。 */
    val isGuestMode: StateFlow<Boolean> = combine(_currentModel, _serverAuthToken) { model, token ->
        model is ChatModelOption.Remote && token.isBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _showRegistrationSheet = MutableStateFlow(false)
    val showRegistrationSheet: StateFlow<Boolean> = _showRegistrationSheet.asStateFlow()

    fun openRegistrationSheet() {
        _showRegistrationSheet.value = true
    }

    fun dismissRegistrationSheet() {
        _showRegistrationSheet.value = false
    }

    fun sendVerificationCode(email: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { authClient.sendVerificationCode(email).also(onResult) }
    }

    fun verifyCode(email: String, code: String, onResult: (Result<*>) -> Unit) {
        viewModelScope.launch {
            val result = authClient.verifyCode(email, code)
            result.onSuccess { auth ->
                userSettingsRepository.updateServerAuth(auth.token, email)
                _showRegistrationSheet.value = false
            }
            onResult(result)
        }
    }

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
                    // chat 页仅远程：无论全局偏好如何（含 FORCE_LOCAL），chat 都用远程模型
                    _currentModel.value = ChatModelOption.Remote
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to sync inference preference from settings", e)
            }
        }
        // 实时监听用户自配 Key：决定是否显示「默认服务器/自配 Key」切换（配 key 后即时刷新）
        viewModelScope.launch {
            try {
                userSettingsRepository.aiAgentRemoteModelConfigsFlow.collect { json ->
                    val userConfigs = RemoteModelConfigs.fromJson(json).configs.filter { cfg -> cfg.isConfigured }
                    val userModels = userConfigs.map { cfg -> ChatRemoteModel(cfg.uniqueKey, cfg.modelId, cfg) }
                    _availableModels.value = listOf(officialModel) + userModels
                    _hasUserKey.value = userModels.isNotEmpty()
                    Logger.i(
                        TAG,
                        "availableModels: official + ${userModels.size} user = ${userModels.map { it.displayName }}"
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to observe remote model configs", e)
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
     * 2. 触发处理状态
     * 3. 创建流式占位消息，实时展示 token
     * 4. 获取相册摘要
     * 5. 构建 Agent 上下文
     * 6. 调用 [AgentOrchestrator.streamChat] 流式推理
     * 7. 推理完成后保存完整结果到 Room
     */
    fun sendMessage(text: String, imageUri: String? = null) {
        if (text.isBlank()) return

        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                // 0. 确保会话元数据存在
                ensureSessionExists(sessionId)

                // 携带图片时，把图片 uri 作为上下文并写入 metadata，供 UI 图文混排展示
                if (imageUri != null) {
                    _lastUserImageUri.value = imageUri
                }

                // 1. 保存用户消息
                val userMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = if (imageUri != null) "user_image_text" else "user_text",
                    content = text,
                    modelUsed = null,
                    metadata = imageUri?.let { """{"imageUri":"$it"}""" }
                )
                chatMessageDao.insertMessage(userMessage)
                chatSessionDao.touchSession(sessionId)

                // 1.5 自动命名：根据用户的第一条消息生成会话标题
                val messageCount = chatMessageDao.getMessageCount(sessionId)
                if (messageCount == 1) {
                    updateSessionTitleIfDefault(sessionId, generateAutoTitle(userMessage))
                }

                // 2. 触发处理状态
                _isProcessing.value = true

                // 3. 创建流式占位消息（立即展示「思考中」提示，避免空气泡）
                val streamingId = "streaming_${System.currentTimeMillis()}"
                _streamingMessage.value = ChatMessageUi(
                    id = streamingId,
                    type = ChatMessageType.AGENT_TEXT,
                    content = STREAMING_THINKING_HINT,
                    modelUsed = currentModelLabel()
                )

                // 3.5 获取相册摘要并注入上下文
                val gallerySummary = getGallerySummaryUseCase(includeDetails = false)

                // 4. 构建 Agent 上下文
                val agentContext = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId,
                    recentSearchResults = sessionSearchSnapshots[sessionId].orEmpty(),
                    lastUserImageUri = _lastUserImageUri.value,
                    gallerySummary = gallerySummary
                )

                // 5. 调用流式推理
                //
                // 流式期间占位文案保持「正在思考...」不变：
                // - 修复「先闪现 JSON 指令再出卡片」：本地/远程 L2 输出恒为 JSON 指令
                //   （如 search_media / text_reply），不能把原始 token 直接展示到气泡。
                // - 修复「空气泡过段时间才有内容」：占位一开始即为非空提示；远程推理为
                //   同步一次性返回（onToken 仅回调一次），本来就没有可增量展示的文本。
                // chat 推理前同步配置 remoteConfig：确保用当前 _remoteSource 对应的远程源，
                // 避免其他场景（AiAgentUseCase/PoLangApplication）注入的 userRemoteConfig 残留导致走错服务器。
                orchestrator.configure(
                    mode = orchestrator.getAgentMode(),
                    modelId = orchestrator.getCurrentModelId(),
                    privacyLevel = AiAgentPrivacyLevel.STRICT,
                    remoteConfig = effectiveRemoteConfig(selectedModel),
                    inferencePreference = AiAgentInferencePreference.FORCE_REMOTE
                )
                Logger.i(
                    TAG,
                    "chat inference: model=${selectedModel.displayName}, baseUrl=${selectedModel.remoteConfig.baseUrl}"
                )
                val result = orchestrator.streamChat(
                    input = text,
                    agentContext = agentContext,
                    // 占位文案已在创建时设好并保持不变，故逐 token 无需更新气泡。
                    onToken = { _ -> }
                )

                // 6. 处理结果
                result.fold(
                    onSuccess = { streamResult ->
                        // 清除流式占位
                        _streamingMessage.value = null

                        // 性能数据统一在此计算并透传给所有回复路径（文本/命令），
                        // 让 remote(DeepSeek) 响应气泡也展示 prompt/decode tokens、延迟、速度。
                        // 此前仅纯文本路径填 performance，命令路径（remote ReAct 常走）传 null。
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

                        // 检测 LLM 安全对齐误触发：用户想搜相册但 LLM 拒绝了
                        val replyText = (streamResult.commands.firstOrNull() as? AgentCommand.TextReply)?.message
                            ?: streamResult.fullResponse
                        if (isRefusedSearchRequest(text, replyText)) {
                            Logger.w(TAG, "LLM refused search request, falling back to direct gallery search")
                            val outcome = onSearchMedia(text)
                            val assets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
                            if (assets.isNotEmpty()) {
                                insertMediaResultsMessage(
                                    sessionId,
                                    MediaResultsUi(outcome.query, assets, outcome.totalCount, isRefinement = false)
                                )
                            } else {
                                insertAgentMessage(sessionId, "没有找到相关照片", currentModelLabel())
                            }
                        } else if (streamResult.commands.isNotEmpty()) {
                            // 有命令需要执行：通过 CapabilityRegistry 分发
                            Logger.i(TAG, "Executing ${streamResult.commands.size} commands from streaming response")
                            // 聊天页拦截模糊跳转：只有明确说"去相机/去相册/去设置/返回"等口令时才放行
                            val commands = sanitizeNavigationCommands(streamResult.commands, text)
                            val finalCommand = if (commands.size > 1) {
                                AgentCommand.BatchExecute(commands = commands)
                            } else {
                                commands.first()
                            }
                            val action = orchestrator.getCapabilityRegistry()
                                .dispatch(finalCommand, agentContext)
                            val actionValue = action.getOrNull()
                            if (actionValue is AgentAction.Error) {
                                // 聊天页命令分发失败时，优先展示模型原始回复，避免把"暂不支持此操作"抛给用户
                                Logger.w(TAG, "Capability dispatch failed in chat, falling back to full response. error=${actionValue.message}, detail=${actionValue.detail}")
                                insertAgentMessage(sessionId, streamResult.fullResponse.ifBlank { actionValue.message }, currentModelLabel(), performance)
                            } else {
                                handleAgentAction(actionValue, sessionId, currentModelLabel(), performance)
                            }
                        } else {
                            // 纯文本回复：保存到 Room（REMOTE 场景或 LOCAL 的 text_reply）
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
                        // langchain4j 异常 message = HTTP 响应体（OkHttpClient→HttpException→AuthenticationException 全程透传，状态码不进 message）。
                        // guest 配额耗尽时 server 返回 403 body={"error":"quota_exceeded",...}（见 LlmRoute），据此识别。
                        val errorBody = error.message.orEmpty()
                        val isGuestQuota = isGuestMode.value &&
                            errorBody.contains("quota_exceeded", ignoreCase = true)
                        if (isGuestQuota) {
                            // 访客试用额度用完 → 友好提示 + 打开注册引导（软引导，非硬阻断）
                            insertAgentMessage(
                                sessionId = sessionId,
                                content = context.getString(R.string.chat_guest_quota_used_up),
                                modelUsed = currentModelLabel(),
                            )
                            _showRegistrationSheet.value = true
                        } else {
                            insertAgentMessage(
                                sessionId = sessionId,
                                content = context.getString(
                                    R.string.chat_inference_error,
                                    error.message ?: "unknown",
                                ),
                                modelUsed = "error",
                            )
                        }
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

    /**
     * 检测 LLM 是否拒绝了用户的相册搜索意图（安全对齐误触发）。
     *
     * 当用户输入包含搜索关键词（照片/图片/搜/找…）且 LLM 仅返回 TextReply
     * 且回复包含拒绝关键词（不能/无法/抱歉…搜索/推荐/内容）时，
     * 判定为安全对齐误触发，应回退到直接搜索本地相册。
     */
    private fun isRefusedSearchRequest(userInput: String, replyText: String): Boolean {
        val searchKeywords = listOf("照片", "图片", "照", "搜", "找")
        val refusalKeywords = listOf("不能", "无法", "抱歉", "不合适", "不当", "拒绝")
        val refusalTargets = listOf("搜索", "推荐", "此类", "内容", "提供")

        val hasSearchIntent = searchKeywords.any { keyword -> userInput.contains(keyword) }
        val hasRefusal = refusalKeywords.any { keyword -> replyText.contains(keyword) } &&
            refusalTargets.any { keyword -> replyText.contains(keyword) }

        return hasSearchIntent && hasRefusal
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
                val safeCommands = sanitizeNavigationCommands(listOf(inferenceResult.command), text)
                val action = orchestrator.getCapabilityRegistry()
                    .dispatch(safeCommands.first(), agentContext)
                handleAgentAction(action.getOrNull(), sessionId, modelLabel, performance)
            }
            is InferenceResult.Batch -> {
                val safeCommands = sanitizeNavigationCommands(inferenceResult.commands, text)
                val finalCommand = if (safeCommands.size > 1) {
                    AgentCommand.BatchExecute(commands = safeCommands)
                } else {
                    safeCommands.firstOrNull() ?: AgentCommand.TextReply(message = "没有可执行的命令")
                }
                val action = orchestrator.getCapabilityRegistry()
                    .dispatch(finalCommand, agentContext)
                handleAgentAction(action.getOrNull(), sessionId, modelLabel, performance)
            }
            is InferenceResult.Plan -> {
                val sanitizedPlan = inferenceResult.plan.copy(
                    steps = inferenceResult.plan.steps.map { step ->
                        val safe = sanitizeNavigationCommands(listOf(step.action), text)
                        step.copy(action = safe.firstOrNull() ?: step.action)
                    }
                )
                val action = orchestrator.getCapabilityRegistry()
                    .dispatch(AgentCommand.ExecutePlan(plan = sanitizedPlan), agentContext)
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
                when (val cmd = action.command) {
                    is AgentCommand.AiOptimize -> {
                        val targetUri = cmd.imageUri.takeIf { it.isNotBlank() }
                            ?: _lastUserImageUri.value
                        if (targetUri.isNullOrBlank()) {
                            insertAgentMessage(sessionId, "请先发送一张图片，再说“帮我优化这张照片”", currentModelLabel(), performance)
                        } else {
                            // chat 内执行优化渲染，结果直接作为图片消息返回（不再跳转编辑器）
                            val renderer = chatImageRenderer
                            if (renderer == null) {
                                insertAgentMessage(sessionId, "⚠️ 图像优化暂不可用", currentModelLabel(), performance)
                            } else {
                                val outcome = renderer.aiOptimize(targetUri)
                                if (outcome.imageUri != null) {
                                    insertAgentImageMessage(
                                        sessionId = sessionId,
                                        imageUri = outcome.imageUri,
                                        content = cmd.explanation ?: outcome.explanation,
                                        modelUsed = currentModelLabel(),
                                        performance = performance
                                    )
                                } else {
                                    insertAgentMessage(sessionId, outcome.explanation, currentModelLabel(), performance)
                                }
                            }
                        }
                    }
                    else -> {
                        insertAgentMessage(sessionId, describeCommandResult(cmd), "command", performance)
                    }
                }
            }
            is AgentAction.Error -> {
                val message = if (action.message == "feedback_resolve_failure") {
                    context.getString(R.string.feedback_resolve_failure)
                } else {
                    action.message
                }
                insertAgentMessage(sessionId, "❌ $message", "error", performance)
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
            is AgentCommand.StartTagScan -> "✅ 已执行 TAG 扫描控制"
            is AgentCommand.BatchExecute -> "✅ 已执行批量操作"
            is AgentCommand.RecordMediaFeedback -> when (command.action) {
                FeedbackAction.LIKE -> "✅ ${context.getString(R.string.feedback_confirmed_like)}"
                FeedbackAction.DISLIKE -> "✅ ${context.getString(R.string.feedback_confirmed_dislike)}"
                else -> "✅ 已记录反馈"
            }
            is AgentCommand.ExcludeConstraint -> "✅ ${context.getString(R.string.feedback_excluded, command.constraint)}"
            else -> "✅ 已执行 ${AgentCommand.getMethodName(command)}"
        }
    }

    /**
     * 用户点击搜索结果卡片上的反馈按钮。
     */
    fun onMediaFeedback(mediaId: String, query: String, action: FeedbackAction) {
        val key = "$mediaId-$query-${action.name}"
        if (pendingFeedbackActions.contains(key)) return
        pendingFeedbackActions.add(key)

        viewModelScope.launch {
            try {
                when (action) {
                    FeedbackAction.LIKE, FeedbackAction.DISLIKE -> {
                        mediaFeedbackUseCase.record(
                            mediaId = mediaId,
                            queryText = query,
                            sessionId = _currentSessionId.value,
                            action = action
                        )
                        updateCurrentResultsFeedback(mediaId, action, query)
                    }
                    FeedbackAction.MORE_LIKE_THIS -> {
                        triggerMoreLikeThis(mediaId, query)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to record media feedback", e)
            } finally {
                pendingFeedbackActions.remove(key)
            }
        }
    }

    private suspend fun updateCurrentResultsFeedback(mediaId: String, action: FeedbackAction, query: String) {
        val currentMessages = _messages.value
        val updatedMessages = currentMessages.map { message ->
            val mr = message.mediaResults
            if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null && mr.query == query) {
                val updatedState = mr.feedbackState.toMutableMap().apply {
                    when (action) {
                        FeedbackAction.LIKE -> put(mediaId, FeedbackAction.LIKE)
                        FeedbackAction.DISLIKE -> put(mediaId, FeedbackAction.DISLIKE)
                        else -> { /* no-op */ }
                    }
                }
                val reorderedAssets = reorderAssetsByFeedback(mr.assets, updatedState, query)
                message.copy(
                    mediaResults = mr.copy(
                        assets = reorderedAssets,
                        feedbackState = updatedState
                    )
                )
            } else {
                message
            }
        }
        _messages.value = updatedMessages
    }

    private suspend fun reorderAssetsByFeedback(
        assets: List<MediaAsset>,
        feedbackState: Map<String, FeedbackAction>,
        query: String
    ): List<MediaAsset> {
        val scores = mediaFeedbackUseCase.getScoresForQuery(query)
        return assets.sortedByDescending { asset ->
            val score = scores[asset.id.toString()]
            val delta = mediaFeedbackUseCase.calculateScoreDelta(score)
            val baseIndex = assets.indexOf(asset)
            val baseScore = (assets.size - baseIndex).toFloat()
            baseScore + delta * 100f
        }
    }

    private suspend fun triggerMoreLikeThis(mediaId: String, query: String) {
        val sessionId = _currentSessionId.value
        val asset = lastResultAssets[sessionId]?.find { it.id.toString() == mediaId }
            ?: return
        val tags = asset.labels?.let { parseLabels(it) }?.take(3) ?: emptyList()
        val constraint = if (tags.isNotEmpty()) {
            "和这张照片类似的：${tags.joinToString("、")}"
        } else {
            "更多类似这张照片的"
        }
        val outcome = onRefineMediaSearch(constraint)
        if (outcome.mediaIds.isNotEmpty()) {
            val refinedAssets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
            insertMediaResultsMessage(
                sessionId,
                MediaResultsUi(
                    query = constraint,
                    assets = refinedAssets,
                    totalCount = outcome.totalCount,
                    isRefinement = true
                )
            )
        } else {
            insertAgentMessage(
                sessionId,
                context.getString(R.string.feedback_no_more_results),
                "gallery_search"
            )
        }
    }

    private fun parseLabels(labelsJson: String): List<String> {
        return try {
            val json = org.json.JSONObject(labelsJson)
            val tags = json.optJSONArray("tags")
            (0 until (tags?.length() ?: 0)).map { tags!!.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @VisibleForTesting
    internal fun resolveTarget(target: FeedbackTarget, sessionId: String? = null): MediaAsset? {
        val sid = sessionId ?: _currentSessionId.value
        val assets = lastResultAssets[sid].orEmpty()
        if (assets.isEmpty()) return null
        return when (target) {
            is FeedbackTarget.LastShown -> assets.firstOrNull()
            is FeedbackTarget.Ordinal -> assets.getOrNull((target.index - 1).coerceAtLeast(0))
            is FeedbackTarget.MediaId -> assets.find { it.id.toString() == target.id }
            is FeedbackTarget.Description -> assets.find { matchesTags(it, target.text) }
        }
    }

    private fun matchesTags(asset: MediaAsset, description: String): Boolean {
        val labels = asset.labels?.let { parseLabels(it) } ?: emptyList()
        val terms = description.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return false
        return terms.any { term ->
            labels.any { label -> label.contains(term, ignoreCase = true) } ||
                asset.fileName.contains(term, ignoreCase = true)
        }
    }

    // ── ChatSearchCapability.Delegate：相册搜索执行 ────────────────

    override suspend fun onSearchMedia(query: String, intent: SearchIntent?): SearchOutcome {
        val sessionId = _currentSessionId.value
        val start = System.currentTimeMillis()
        val result = runCatching {
            if (intent != null) {
                val filter = searchIntentToStructuredFilter(intent)
                mediaSearchEngine.search(filter = filter)
            } else {
                mediaSearchEngine.search(query)
            }
        }.getOrElse {
            Logger.w(TAG, "onSearchMedia failed for '$query'", it)
            return SearchOutcome(query, emptyList(), 0, isRefinement = false)
        }
        Logger.i(TAG, "onSearchMedia query='$query' intent=$intent total=${result.media.size} time=${System.currentTimeMillis() - start}ms")
        val photos = result.media.filter { it.type == MediaType.PHOTO }
        lastResultAssets[sessionId] = photos
        recordSearchSnapshot(sessionId, query, photos.size, isRefinement = false)
        return SearchOutcome(query, photos.map { it.id }, photos.size, isRefinement = false)
    }

    override suspend fun onRefineMediaSearch(constraint: String, intent: SearchIntent?): SearchOutcome {
        val sessionId = _currentSessionId.value
        val prior = lastResultAssets[sessionId].orEmpty()
        // 无上一轮 → 当 fresh 全局搜
        if (prior.isEmpty()) return onSearchMedia(constraint, intent)

        val start = System.currentTimeMillis()
        val priorIds = prior.map { asset -> asset.id }.toSet()
        val result = runCatching {
            if (intent != null) {
                // LLM 已给出标准化意图：直接在 prior 内执行结构化过滤
                val filter = searchIntentToStructuredFilter(intent)
                mediaSearchEngine.search(filter = filter, limitToIds = priorIds).media
            } else {
                // 兜底：字符串解析 + in-set 过滤
                val cleaned = ChatGallerySearch.cleanConstraint(constraint)
                val searchHits = mediaSearchEngine.search(cleaned, limitToIds = priorIds).media
                ChatGallerySearch.resolveRefine(prior, searchHits, cleaned)
            }
        }.getOrElse {
            Logger.w(TAG, "onRefineMediaSearch failed for '$constraint'", it)
            return SearchOutcome(constraint, emptyList(), 0, isRefinement = true)
        }

        val refined = result
        val faceInPrior = prior.count { a -> a.hasFace }
        Logger.i(
            TAG,
            "onRefineMediaSearch prior=${prior.size} hasFaceInPrior=$faceInPrior " +
                "constraint='$constraint' intent=$intent refined=${refined.size} " +
                "time=${System.currentTimeMillis() - start}ms"
        )
        // in-set 空 → 保留上一轮结果集不变，返回空细化结果。不再全局重搜 constraint：
        // 那会用与既有条件无关的新结果覆盖状态，破坏多轮收敛（用户会看到无关照片）。
        if (refined.isEmpty()) {
            return SearchOutcome(constraint, emptyList(), 0, isRefinement = true)
        }
        lastResultAssets[sessionId] = refined
        recordSearchSnapshot(sessionId, constraint, refined.size, isRefinement = true)
        return SearchOutcome(constraint, refined.map { it.id }, refined.size, isRefinement = true)
    }

    /**
     * 将 runtime-core 的 [SearchIntent] 转换为 app 层的 [StructuredFilter]。
     *
     * 转换前先做时间词清洗：只要 [SearchIntent.timeRange] 已给出，就把“夏天”“去年”等
     * 时间专属词从 keywords / ocrKeywords / locationKeywords 中剔除，避免引擎把
     * 时间约束与空内容候选集取交集导致 0 结果。
     */
    private fun searchIntentToStructuredFilter(intent: SearchIntent): StructuredFilter {
        val sanitized = sanitizeTimeKeywords(intent)
        return StructuredFilter(
            timeRange = sanitized.timeRange?.let {
                com.mamba.picme.domain.model.TimeRange(startMs = it.startMs, endMs = it.endMs)
            },
            keywords = sanitized.keywords,
            ocrKeywords = sanitized.ocrKeywords,
            locationKeywords = sanitized.locationKeywords,
            personName = sanitized.personName,
            hasFaces = sanitized.hasFaces,
            needsLlm = false
        )
    }

    /**
     * 当意图中同时存在 [timeRange] 和时间专属词时，剔除这些时间专属词。
     * 这是 Prompt 约束之外的第二层保险，防止小模型/远程模型仍把“夏天”当成内容关键词。
     */
    private fun sanitizeTimeKeywords(intent: SearchIntent): SearchIntent {
        if (intent.timeRange == null) return intent
        fun isTimeOnly(word: String): Boolean = word in timeOnlyKeywords || monthKeywordRegex.matches(word)
        return intent.copy(
            keywords = intent.keywords.filterNot(::isTimeOnly),
            ocrKeywords = intent.ocrKeywords.filterNot(::isTimeOnly),
            locationKeywords = intent.locationKeywords.filterNot(::isTimeOnly)
        )
    }

    private fun recordSearchSnapshot(
        sessionId: String,
        query: String,
        totalCount: Int,
        isRefinement: Boolean
    ) {
        val assets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
        if (assets.isEmpty()) return
        val snapshot = SearchSnapshotBuilder.build(assets, query, totalCount, isRefinement)
        val list = sessionSearchSnapshots.getOrPut(sessionId) { mutableListOf() }
        list.add(snapshot)
        if (list.size > SearchSnapshotBuilder.MAX_ROUNDS) {
            list.removeAt(0)
        }
    }

    override suspend fun onRecordMediaFeedback(target: FeedbackTarget, action: FeedbackAction): Boolean {
        val sessionId = _currentSessionId.value
        val asset = resolveTarget(target, sessionId) ?: return false
        val mediaId = asset.id.toString()
        val query = sessionSearchSnapshots[sessionId]?.lastOrNull()?.query ?: ""
        mediaFeedbackUseCase.record(
            mediaId = mediaId,
            queryText = query,
            sessionId = sessionId,
            action = action
        )
        updateCurrentResultsFeedback(mediaId, action, query)
        return true
    }

    override suspend fun onMoreLikeThis(target: FeedbackTarget): SearchOutcome {
        val sessionId = _currentSessionId.value
        val asset = resolveTarget(target, sessionId)
            ?: return SearchOutcome("", emptyList(), 0, isRefinement = false)
        val tags = asset.labels?.let { parseLabels(it) }?.take(3) ?: emptyList()
        val constraint = if (tags.isNotEmpty()) {
            "和这张照片类似的：${tags.joinToString("、")}"
        } else {
            "更多类似这张照片的"
        }
        return onRefineMediaSearch(constraint)
    }

    override suspend fun onExcludeConstraint(constraint: String): Boolean {
        if (constraint.isBlank()) return false
        val sessionId = _currentSessionId.value
        if (lastResultAssets[sessionId].isNullOrEmpty()) return false
        sessionExcludes.getOrPut(sessionId) { mutableSetOf() }.add(constraint)
        reapplyFiltersToCurrentResults(sessionId)
        mediaFeedbackUseCase.recordExclude(constraint, sessionId)
        return true
    }

    // ── ChatGallerySummaryCapability.Delegate：相册摘要 ─────────────

    override suspend fun onGetGallerySummary(includeDetails: Boolean): GallerySummary? {
        return getGallerySummaryUseCase(includeDetails)
    }

    // ── ChatRunScriptCapability.Delegate：执行 JS 脚本（端侧沙箱）─────────────

    override suspend fun onRunScript(code: String): String {
        return withContext(Dispatchers.Default) {
            JsRuntime(
                scope = viewModelScope,
                evalTimeoutMs = 3_000,
                onLog = { msg -> Log.i("PoLang:Js", msg) }
            ).use { rt ->
                // gallery.summary：同步 handler，runBlocking 读本地相册摘要（~50ms，切 IO 不死锁）
                rt.register(syncHandler("gallery.summary") {
                    runBlocking { getGallerySummaryUseCase(includeDetails = true)?.toJsValue() ?: JsValue.Null }
                })
                rt.eval(code).toJson()
            }
        }
    }

    // ── ChatStartTagScanCapability.Delegate：TAG 扫描控制 ─────────────

    override suspend fun onStartTagScan(
        action: String,
        taskType: String?,
        mode: String?
    ): StartTagScanResult {
        return startTagScanUseCase(action = action, taskType = taskType, mode = mode)
    }

    private fun reapplyFiltersToCurrentResults(sessionId: String) {
        val current = lastResultAssets[sessionId] ?: return
        val excludes = sessionExcludes[sessionId] ?: return
        if (excludes.isEmpty()) return
        val filtered = current.filter { asset ->
            val labels = asset.labels?.let { parseLabels(it) } ?: emptyList()
            val text = (labels + asset.fileName).joinToString(" ")
            excludes.none { constraint -> text.contains(constraint, ignoreCase = true) }
        }
        lastResultAssets[sessionId] = filtered
        recordSearchSnapshot(
            sessionId = sessionId,
            query = sessionSearchSnapshots[sessionId]?.lastOrNull()?.query ?: "",
            totalCount = filtered.size,
            isRefinement = true
        )
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
     * 根据用户的第一条消息自动生成会话标题。
     *
     * - 文本消息：取内容前 [ChatTitleGenerator.MAX_AUTO_TITLE_LENGTH] 个字符，去除首尾标点，合并换行/连续空白。
     * - 图片消息：统一显示为图片对话标题。
     */
    private fun generateAutoTitle(firstUserMessage: ChatMessageEntity): String {
        return ChatTitleGenerator.generateTitle(
            firstUserMessageType = firstUserMessage.type,
            textContent = firstUserMessage.content,
            imageTitle = context.getString(R.string.chat_title_image_first),
            fallbackTitle = context.getString(R.string.new_chat)
        )
    }

    /**
     * 如果当前标题仍是系统默认值，则将其更新为自动生成的标题。
     *
     * 保护用户手动重命名的标题不被覆盖。
     */
    private suspend fun updateSessionTitleIfDefault(
        sessionId: String,
        candidateTitle: String
    ) {
        val session = chatSessionDao.getSession(sessionId) ?: return
        if (!isDefaultTitle(session.title)) return
        chatSessionDao.updateTitle(sessionId, candidateTitle)
        Logger.i(TAG, "Auto-updated session title to: $candidateTitle")
    }

    /**
     * 判断标题是否为系统默认标题。
     */
    private fun isDefaultTitle(title: String): Boolean {
        if (title.isBlank()) return true
        if (title == "New Chat" || title == "Chat") return true
        if (title == context.getString(R.string.new_chat)) return true
        return false
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
     * 插入一条带结果图的 AI 消息（type=agent_image）。用于 chat 内执行图像编辑后直接返回结果。
     */
    private suspend fun insertAgentImageMessage(
        sessionId: String,
        imageUri: String,
        content: String,
        modelUsed: String,
        performance: LlmPerformance? = null
    ) {
        val metadata = JSONObject().apply {
            put("imageUri", imageUri)
            performance?.let { p ->
                put("prompt_len", p.promptLen)
                put("decode_len", p.decodeLen)
                put("prefill_time_ms", p.prefillTimeMs)
                put("decode_time_ms", p.decodeTimeMs)
                put("prefill_speed", p.prefillSpeed.toDouble())
                put("decode_speed", p.decodeSpeed.toDouble())
            }
        }.toString()
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = "agent_image",
                content = content,
                modelUsed = modelUsed,
                metadata = metadata
            )
        )
        chatSessionDao.touchSession(sessionId)
    }

    /**
     * 仅暂存图片：复制到内部存储 + 设 [_lastUserImageUri]，**不**插入消息、**不**触发推理。
     * 返回持久化后的路径字符串；失败返回 null。供 Chat 输入框「缩略图预览」用。
     */
    fun stageImage(uri: Uri): String? {
        val persisted = persistImage(uri) ?: return null
        _lastUserImageUri.value = persisted
        return persisted
    }

    /**
     * 按 [intent] 发送「图 + 意图/文字」。`uri` 为已通过 [stageImage] 持久化的内部存储路径。
     * - 文字非空：作为 Agent 指令，图片经 [_lastUserImageUri] 作为上下文（[sendMessage]）。
     * - [ImageIntent.FIND_SIMILAR]：以图搜图，命中则插 media-results 轮播，否则提示无结果。
     * - [ImageIntent.UNDERSTAND]（默认）：复用 [sendImageMessage] 的图像理解链路。
     * 注意：[ImageIntent.EDIT] 由 UI 直接跳 PhotoEditor，不应进入本方法。
     */
    fun sendImageWithIntent(uri: String, intent: ImageIntent, text: String?) {
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                ensureSessionExists(sessionId)
                when {
                    !text.isNullOrBlank() -> sendMessage(text, uri)
                    intent == ImageIntent.FIND_SIMILAR -> {
                        _isProcessing.value = true
                        val bitmap = runCatching {
                            android.graphics.BitmapFactory.decodeFile(uri)
                        }.getOrNull()
                        val assets = if (bitmap != null) {
                            mediaSearchEngine.searchByImage(bitmap)
                        } else {
                            emptyList()
                        }
                        _isProcessing.value = false
                        if (assets.isNotEmpty()) {
                            insertMediaResultsMessage(
                                sessionId,
                                MediaResultsUi(
                                    query = context.getString(R.string.chat_intent_find_similar),
                                    assets = assets.take(MAX_CARDS),
                                    totalCount = assets.size,
                                    isRefinement = false
                                )
                            )
                        } else {
                            insertAgentMessage(
                                sessionId,
                                context.getString(R.string.gallery_search_no_results),
                                "gallery_search"
                            )
                        }
                    }
                    else -> sendImageMessage(Uri.fromFile(java.io.File(uri)))
                }
                chatSessionDao.touchSession(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "sendImageWithIntent failed", e)
                _isProcessing.value = false
            }
        }
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
                _lastUserImageUri.value = persistedUri
                chatSessionDao.touchSession(sessionId)

                // 1.5 自动命名：根据用户的第一条消息生成会话标题
                val messageCount = chatMessageDao.getMessageCount(sessionId)
                if (messageCount == 1) {
                    updateSessionTitleIfDefault(sessionId, generateAutoTitle(userMessage))
                }

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
        // chat 页仅远程：忽略切换到本地（保留接口兼容，UI 已不暴露本地选项）
        if (model !is ChatModelOption.Remote) {
            Logger.i(TAG, "switchModel ignored non-Remote option (chat is remote-only): $model")
            return
        }
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
     * 切换 chat 远程模型（官方 / 用户自配某项）。chat 仅远程：配置 orchestrator 用对应 RemoteModelConfig。
     */
    fun switchModel(modelId: String) {
        val model = _availableModels.value.find { it.id == modelId } ?: return
        _selectedModelId.value = modelId
        viewModelScope.launch {
            try {
                orchestrator.configure(
                    mode = orchestrator.getAgentMode(),
                    modelId = orchestrator.getCurrentModelId(),
                    privacyLevel = AiAgentPrivacyLevel.STRICT,
                    remoteConfig = effectiveRemoteConfig(model),
                    inferencePreference = AiAgentInferencePreference.FORCE_REMOTE
                )
                Logger.i(TAG, "chat model switched: ${model.displayName} (${model.remoteConfig.baseUrl})")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to switch chat model", e)
            }
        }
    }

    /**
     * 当图片从媒体库中被删除后，同步把对应 media_results 消息里的该图片移除。
     * 如果某条 media_results 消息的所有图片都被删光，则整条消息一起删掉。
     */
    fun removeMediaResultAsset(mediaId: Long) {
        viewModelScope.launch {
            try {
                val currentMessages = _messages.value
                val updatedMessages = currentMessages.mapNotNull { message ->
                    val mr = message.mediaResults
                    if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null &&
                        mr.assets.any { it.id == mediaId }
                    ) {
                        val newAssets = mr.assets.filter { it.id != mediaId }
                        if (newAssets.isEmpty()) {
                            chatMessageDao.getMessageById(message.id)?.let { entity ->
                                chatMessageDao.insertMessage(
                                    entity.copy(
                                        type = "agent_text",
                                        content = "结果中的照片已被删除",
                                        metadata = null
                                    )
                                )
                            }
                            return@mapNotNull message.copy(
                                type = ChatMessageType.AGENT_TEXT,
                                content = "结果中的照片已被删除",
                                mediaResults = null
                            )
                        }
                        val newTotal = (mr.totalCount - 1).coerceAtLeast(newAssets.size)
                        chatMessageDao.getMessageById(message.id)?.let { entity ->
                            chatMessageDao.insertMessage(
                                entity.copy(
                                    content = ChatGallerySearch.serializeContent(newAssets),
                                    metadata = ChatGallerySearch.serializeMetadata(
                                        mr.query,
                                        newTotal,
                                        mr.isRefinement
                                    )
                                )
                            )
                        }
                        message.copy(
                            mediaResults = mr.copy(
                                assets = newAssets,
                                totalCount = newTotal
                            )
                        )
                    } else {
                        message
                    }
                }
                _messages.value = updatedMessages
                Logger.i(TAG, "Removed media result asset $mediaId from chat UI")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to remove media result asset $mediaId", e)
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

    private fun parseImageUri(metadata: String): String? = try {
        org.json.JSONObject(metadata).optString("imageUri").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
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
                "user_image_text" -> ChatMessageType.USER_IMAGE_TEXT
                "agent_image" -> ChatMessageType.AGENT_IMAGE
                "command" -> ChatMessageType.COMMAND
                "plan_preview" -> ChatMessageType.PLAN_PREVIEW
                "media_results" -> ChatMessageType.MEDIA_RESULTS
                else -> ChatMessageType.AGENT_TEXT
            },
            content = content,
            imageUri = if (type == "user_image_text" || type == "agent_image") metadata?.let { m -> parseImageUri(m) } else null,
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

    /**
     * 判断用户输入是否包含明确的页面跳转口令。
     *
     * 仅当匹配以下模式时才允许在 chat 页执行 navigate_to / go_back：
     * - "去/回/打开 + 相机/相册/设置/调试/模型中心"
     * - "返回/后退/上一页"
     *
     * 模糊表述（如"我想看看相册""帮我打开相机""想去拍照"）应被拦截。
     */
    private fun isExplicitNavigationRequest(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        val explicitPatterns = listOf(
            Regex("""(去|回|打开)\s*(相机|相册|设置|调试|模型中心|model_center)"""),
            Regex("""(返回|后退|上一页|回去)""")
        )
        return explicitPatterns.any { it.containsMatchIn(trimmed) }
    }

    /**
     * 在 chat 页拦截模糊跳转命令。
     *
     * 如果命令是 navigate_to / go_back 但用户输入不匹配明确跳转口令，
     * 则将其替换为 text_reply，避免聊天中因 LLM 误判而突然跳转页面。
     */
    private fun sanitizeNavigationCommands(
        commands: List<AgentCommand>,
        userInput: String
    ): List<AgentCommand> {
        if (commands.isEmpty()) return commands
        val hasNavigation = commands.any { it is AgentCommand.NavigateTo || it is AgentCommand.GoBack }
        if (!hasNavigation) return commands
        // 明确跳转口令：放行
        if (isExplicitNavigationRequest(userInput)) return commands
        // 否则把所有导航命令替换为提示文本
        return commands.map { cmd ->
            when (cmd) {
                is AgentCommand.NavigateTo, is AgentCommand.GoBack -> AgentCommand.TextReply(
                    message = "在聊天页我不会自动跳转页面，请直接说\"去相机/去相册/去设置/返回\"，或点击底部 tab 切换。"
                )
                else -> cmd
            }
        }
    }
}