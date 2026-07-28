package com.mamba.picme.agent.core.facade

import android.content.Context
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentIdGenerator
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import com.mamba.picme.agent.core.local.llm.LlmChatRequest
import com.mamba.picme.agent.core.inference.local.llm.LlmGenerationMetrics
import com.mamba.picme.agent.core.inference.local.llm.LlmModelNotFoundException
import com.mamba.picme.agent.core.inference.local.parser.LocalCommandParser
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentCallback
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgent
import com.mamba.picme.agent.core.inference.remote.react.AgentExecutionMetrics
import com.mamba.picme.agent.core.inference.remote.RemoteChatEngine
import com.mamba.picme.agent.core.inference.local.LocalModelService
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.thread.ThreadPoolManager
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Agent 编排器（统一入口）
 *
 * **线程模型**：
 * 所有专有线程池由 [ThreadPoolManager] 集中管理，四线程池完全隔离：
 * - **编排线程**（PoLang-Orchestrator-Thread）：双线程，处理用户输入的整个生命周期
 * - **LLM 推理线程**（PoLang-LLM-Model-Thread）：单线程，模型加载和推理
 * - **DataStore 线程**（PoLang-DataStore-Thread）：单线程，对话历史持久化
 * - **网络线程**（PoLang-Network-Thread）：单线程，远程 HTTP API 调用
 *
 * 各线程池完全隔离，无直接依赖关系。数据持久化为 fire-and-forget 异步操作，
 * 不阻塞推理与编排流程。
 */
class AgentOrchestrator private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: AgentOrchestrator? = null

        fun getInstance(context: Context): AgentOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: AgentOrchestrator(context.applicationContext).also { instance = it }
            }
        }
    }

    private val tag = "AgentOrchestrator"
    private val configurator = AgentConfigurator(context)

    /** 远程 chat 推理引擎（决策3 / ADR-010）：chat 远程 ReAct 链路隔离出口。 */
    val remoteChatEngine = RemoteChatEngine(configurator)

    /** 本地模型加载服务（决策3 / ADR-010 step3）：相机 Agent + 后台打标 Worker 共用。 */
    val localModelService = LocalModelService(configurator)

    private val orchestratorDispatcher = ThreadPoolManager.getInstance().orchestratorDispatcher

    /**
     * 后台作用域：用于 fire-and-forget 异步操作（如对话历史保存）。
     * SupervisorJob 确保单个后台任务失败不影响其他任务。
     */
    private val backgroundScope = CoroutineScope(SupervisorJob())

    /** 模型加载状态（委托 [localModelService]；场景驱动卸载已移入 LocalModelService）。 */
    val isModelLoading: StateFlow<Boolean>
        get() = localModelService.isModelLoading

    // 便捷访问器
    private val localLlmEngine get() = configurator.localLlmEngine
    private val memoryManager get() = configurator.memoryManager
    private val sceneManager get() = configurator.sceneManager
    private val promptBuilder get() = configurator.localPromptBuilder
    private val _capabilityRegistry get() = configurator.capabilityRegistry
    private val intentCache get() = configurator.intentCache
    private val privacyGuard get() = configurator.privacyGuard

    /**
     * 当前活跃场景（可观察）
     */
    val currentScene = sceneManager.currentScene

    /**
     * 注册 Capability（应用级，通常由 PoLangApplication 调用）
     */
    fun registerCapability(capability: Capability) {
        _capabilityRegistry.register(capability)
    }

    /**
     * 注入记忆快照供给者（转发给内部 [AgentConfigurator]）。须在 chat/飞书 agent 首次构建前
     * 调用——app 在 PoLangApplication.onCreate 注入，早于 agent 懒构建。
     */
    fun setMemoryContextProvider(provider: MemoryContextProvider) {
        configurator.setMemoryContextProvider(provider)
    }

    /**
     * 获取 CapabilityRegistry
     */
    fun getCapabilityRegistry(): CapabilityRegistry {
        return _capabilityRegistry
    }

    /**
     * 获取本地 LLM 推理引擎（委托 [localModelService]）。
     *
     * 供非 Agent 消费者（如后台标签索引 Worker）直接使用模型进行推理。
     * **注意**：调用方应确保模型已加载后再使用。
     */
    fun getLlmEngine(): LocalLlmEngine = localModelService.getLlmEngine()

    /**
     * 获取最近一次本地 LLM 生成的性能指标（委托 [localModelService]）。
     */
    fun getLastLocalGenerationMetrics(): com.mamba.picme.agent.core.inference.local.llm.LlmGenerationMetrics? =
        localModelService.getLastLocalGenerationMetrics()

    /**
     * 场景切换
     */
    fun transitionToScene(scene: SceneManager.Scene, saveToHistory: Boolean = true) {
        sceneManager.transitionTo(scene, saveToHistory)
        Logger.i(tag, "Transitioned to scene: $scene")
    }

    /**
     * 返回上一场景
     */
    fun navigateBack(): Boolean {
        return sceneManager.navigateBack()
    }

    /**
     * 初始化配置
     */
    fun configure(
        mode: AiAgentMode,
        modelId: String,
        privacyLevel: AiAgentPrivacyLevel,
        remoteConfig: RemoteModelConfig? = null,
        localUseOpencl: Boolean = false,
        inferencePreference: AiAgentInferencePreference? = null
    ) {
        configurator.configure(mode, modelId, privacyLevel, remoteConfig, localUseOpencl, inferencePreference)
    }

    /**
     * 仅更新远程运行时配置，**不触碰持久 mode/modelId**（P0-3 配置污染止血，ADR-010 step 1）。
     * 详见 [AgentConfigurator.updateRemoteRuntimeConfig]。chat 发消息 / remoteConfig 同步等
     * 只想换远程配置的场景应调本方法，勿用 [configure] 回写 [getAgentMode]。
     */
    fun updateRemoteRuntimeConfig(
        remoteConfig: RemoteModelConfig?,
        privacyLevel: AiAgentPrivacyLevel? = null,
        inferencePreference: AiAgentInferencePreference? = null
    ) {
        configurator.updateRemoteRuntimeConfig(remoteConfig, privacyLevel, inferencePreference)
    }

    /**
     * 压入模式临时覆盖。
     * 此后所有推理路由将强制使用 [mode]，直到 [popModeOverride] 被调用。
     *
     * 典型场景：飞书远程控制强制使用 REMOTE 模式，无论用户本地设置如何。
     * 支持嵌套：多次压入需要对应次数弹出。
     */
    fun pushModeOverride(mode: AiAgentMode) {
        configurator.pushModeOverride(mode)
    }

    /**
     * 弹出模式临时覆盖。
     * 恢复栈为空时返回持久化模式。
     */
    fun popModeOverride() {
        configurator.popModeOverride()
    }

    /**
     * 获取当前用户远程模型配置（用于模式同步时保留 gatewayToken 等认证信息）
     */
    fun getUserRemoteConfig(): RemoteModelConfig? = configurator.getUserRemoteConfig()

    /** 设置设备级标识（访客试用额度 X-Device-Id），独立于 remoteConfig 持有，不被后续 configure 覆盖。 */
    fun setDeviceId(id: String) = configurator.setDeviceId(id)

    /**
     * 获取当前 Agent 运行模式（含临时覆盖）
     */
    fun getAgentMode(): AiAgentMode = configurator.getAgentMode()

    /**
     * 获取当前推理偏好（FORCE_LOCAL / FORCE_REMOTE / AUTO）
     */
    fun getInferencePreference(): AiAgentInferencePreference = configurator.getInferencePreference()

    /**
     * 获取当前模型 ID
     */
    fun getCurrentModelId(): String = configurator.getCurrentModelId()

    /**
     * 清除飞书 ReAct Agent 缓存（配置变更后强制重建）
     */
    fun clearFeishuAgent() {
        configurator.clearFeishuAgent()
    }

    /**
     * 加载本地模型（委托 [localModelService]）
     *
     * @param modelId 模型 ID，为空时使用当前配置模型
     */
    suspend fun loadModel(modelId: String? = null): Result<Unit> =
        localModelService.loadModel(modelId)

    /**
     * 卸载模型（委托 [localModelService]）
     */
    fun unloadModel() {
        localModelService.unloadModel()
    }

    /**
     * 确保本地模型已加载。
     *
     * 所有本地 LLM 推理入口应统一通过此方法（或 [withModelLoaded]）加载模型，
     * 避免调用方遗漏加载检查导致空结果或崩溃。
     *
     * @param modelId 模型 ID，为空时使用当前配置模型
     * @param useOpencl 是否使用 OpenCL，为 null 时使用当前配置
     * @param caller 调用方标识，用于加载审计日志
     * @return 加载结果
     */
    suspend fun ensureModelLoaded(
        modelId: String? = null,
        useOpencl: Boolean? = null,
        caller: String = "unknown"
    ): Result<Unit> = localModelService.ensureModelLoaded(modelId, useOpencl, caller)

    /**
     * 在模型已加载的前提下执行推理块。
     *
     * 如果模型未加载，会先尝试加载，成功后再执行 [inferenceBlock]。
     * 所有 imageInference / generate / chat 等本地 LLM 调用应统一走此入口。
     *
     * @param modelId 模型 ID，为空时使用当前配置模型
     * @param useOpencl 是否使用 OpenCL，为 null 时使用当前配置
     * @param caller 调用方标识，用于加载审计日志
     * @param inferenceBlock 推理逻辑块，接收 [LocalLlmEngine]
     * @return 推理结果；加载失败时返回 failure
     */
    suspend fun <T> withModelLoaded(
        modelId: String? = null,
        useOpencl: Boolean? = null,
        caller: String = "unknown",
        inferenceBlock: suspend (LocalLlmEngine) -> T
    ): Result<T> = localModelService.withModelLoaded(modelId, useOpencl, caller, inferenceBlock)

    /**
     * 模型是否已加载（委托 [localModelService]）
     */
    val isModelLoaded: Boolean
        get() = localModelService.isModelLoaded

    /**
     * 使用 LocalPipeline 处理输入（支持 L2 本地快速通道）
     *
     * 统一走 LocalPipeline 路由，LOCAL 模式下优先尝试 L2 本地快速通道。
     *
     * @param input 用户自然语言输入
     * @param agentContext 当前 Agent 上下文
     * @param pageContext 页面特定上下文（可选）
     * @return 推理结果
     */
    suspend fun processInputWithRouter(
        input: String,
        agentContext: AgentContext,
        pageContext: PageContext? = null
    ): InferenceResult = withContext(orchestratorDispatcher) {
        Logger.d(tag, "Processing input via LocalPipeline: '$input'")

        // 场景驱动的模型管理
        localModelService.applySceneDrivenModelPolicy()

        Logger.i(tag, "[RouterEntry] mode=${configurator.getAgentMode()}, input='$input', modelLoaded=${localLlmEngine.isLoaded}")

        // 确保本地模型已加载（所有非 OFF 模式）
        if (configurator.getAgentMode() != AiAgentMode.OFF) {
            if (!localLlmEngine.isLoaded) {
                Logger.i(tag, "[RouterEntry] Local model not loaded, attempting load")
                val loadResult = ensureModelLoaded(caller = "processInputWithRouter")
                if (loadResult.isFailure) {
                    Logger.e(tag, "[RouterEntry] Local model load failed")
                } else {
                    Logger.i(tag, "[RouterEntry] Local model loaded successfully")
                }
            } else {
                Logger.i(tag, "[RouterEntry] Local model already loaded")
            }
        } else {
            Logger.i(tag, "[RouterEntry] Mode is ${configurator.getAgentMode()}, skip local model load check")
        }

        // 通过推理管道路由
        Logger.i(tag, "[RouterEntry] Calling pipeline processInput")
        val inferenceResult = try {
            when (configurator.getAgentMode()) {
                AiAgentMode.OFF -> InferenceResult.Chat(message = "AI Agent 已关闭")
                else -> configurator.getLocalPipeline().processInput(input, agentContext)
            }
        } catch (exception: Exception) {
            Logger.e(tag, "Pipeline routing failed", exception)
            InferenceResult.Local(
                command = AgentCommand.Error(reason = "推理路由失败：${exception.message ?: "未知错误"}")
            )
        }

        Logger.i(tag, "[RouterEntry] Pipeline result: ${inferenceResult::class.simpleName}")

        // 学习：解析成功且非错误命令时写入 L1 缓存
        if (inferenceResult is InferenceResult.Local &&
            inferenceResult.command !is AgentCommand.Error &&
            inferenceResult.command !is AgentCommand.TextReply
        ) {
            intentCache.put(input, inferenceResult.command)
            Logger.d(tag, "L1 cache learned: '$input' -> ${inferenceResult.command::class.simpleName}")
        }

        // 保存对话到 MemoryManager（供后续历史上下文使用）
        saveInferenceResultToMemory(input, inferenceResult, agentContext.memorySessionId)

        inferenceResult
    }

    /**
     * 将 InferenceResult 保存到 MemoryManager
     */
    private suspend fun saveInferenceResultToMemory(
        userInput: String,
        result: InferenceResult,
        sessionId: String
    ) {
        when (result) {
            is InferenceResult.Local -> {
                val responseText = when (val cmd = result.command) {
                    is AgentCommand.TextReply -> cmd.message
                    else -> result.responseText.ifBlank { AgentCommand.getMethodName(cmd) }
                }
                saveConversation(sessionId, userInput, result.command, responseText)
            }
            is InferenceResult.Batch -> {
                val firstCommand = result.commands.firstOrNull()
                if (firstCommand != null) {
                    val finalCommand = if (result.commands.size > 1) {
                        AgentCommand.BatchExecute(commands = result.commands)
                    } else {
                        firstCommand
                    }
                    saveConversation(sessionId, userInput, finalCommand, "")
                }
            }
            is InferenceResult.Plan -> {
                val planCommand = AgentCommand.ExecutePlan(plan = result.plan)
                saveConversation(sessionId, userInput, planCommand, result.plan.description)
            }
            is InferenceResult.Chat -> {
                val textCommand = AgentCommand.TextReply(message = result.message)
                saveConversation(sessionId, userInput, textCommand, result.message)
            }
        }
    }

    /**
     * 处理用户输入（原始入口，保留兼容）
     *
     * @param input 用户自然语言输入
     * @param agentContext 当前 Agent 上下文
     * @param pageContext 页面特定上下文（可选）
     * @param customSystemPrompt 自定义 system prompt（可选）
     * @return Agent 执行结果
     */
    suspend fun processUserInput(
        input: String,
        agentContext: AgentContext,
        pageContext: PageContext? = null,
        customSystemPrompt: String? = null
    ): Result<AgentAction> = withContext(orchestratorDispatcher) {
        Logger.d(tag, "Processing input: '$input', scene=${sceneManager.currentScene.value}, mode=${configurator.getAgentMode()}")

        // 场景驱动的模型管理
        localModelService.applySceneDrivenModelPolicy()

        // 0. L1 缓存查询
        val cachedCommand = intentCache.match(input)
        if (cachedCommand != null) {
            Logger.i(tag, "L1 cache hit for input='$input' -> ${cachedCommand::class.simpleName}")
            saveConversation(agentContext.memorySessionId, input, cachedCommand, "")
            return@withContext _capabilityRegistry.dispatch(cachedCommand, agentContext, pageContext)
        }

        // 1. 获取当前场景的 Capability 列表
        val capabilities = _capabilityRegistry.getCapabilitiesForCurrentScene()

        // 仅 LOCAL 模式需要 Capability 列表；REMOTE/FEISHU 也使用本地推理
        if (configurator.getAgentMode() == AiAgentMode.LOCAL && capabilities.isEmpty()) {
            Logger.w(tag, "No capabilities available for current scene in LOCAL mode")
            return@withContext Result.success(
                AgentAction.Error(
                    commandId = AgentIdGenerator.nextId(),
                    errorCode = AgentErrorCode.SCENE_MISMATCH,
                    message = "当前页面暂不支持 AI 控制"
                )
            )
        }

        // 2. 构建 system prompt（仅 LOCAL 模式使用）
        val systemPrompt = customSystemPrompt
            ?: configurator.localPromptBuilder.buildSystemPrompt(capabilities, agentContext)

        // 3. 根据模式选择推理引擎（所有模式统一走本地推理）
        val inferenceResult = when (configurator.getAgentMode()) {
            AiAgentMode.LOCAL -> {
                // 本地模式：使用 MNN-LLM
                if (!localLlmEngine.isLoaded) {
                    val loadResult = ensureModelLoaded(caller = "processUserInput:LOCAL")
                    if (loadResult.isFailure) {
                        return@withContext handleModelLoadError(loadResult)
                    }
                }
                Logger.d(tag, "Using local LLM (MNN-LLM)")
                // 构建带历史上下文的 messages
                val localMessages = memoryManager.buildContextMessages(
                    agentContext.memorySessionId, systemPrompt, input
                )
                val responseResult = try {
                    Result.success(
                        localLlmEngine.chat(
                            LlmChatRequest(
                                messages = localMessages
                            )
                        ).aiMessage.text()
                    )
                } catch (e: Exception) {
                    Result.failure(e)
                }
                return@withContext responseResult.fold(
                    onSuccess = { rawResponse ->
                        handleLlmResponse(rawResponse, input, agentContext, pageContext, agentContext.memorySessionId)
                    },
                    onFailure = { error ->
                        Logger.e(tag, "LLM inference failed (mode=${configurator.getAgentMode()})", error)
                        Result.success(
                            AgentAction.Error(
                                commandId = AgentIdGenerator.nextId(),
                                errorCode = AgentErrorCode.INTERNAL_ERROR,
                                message = "推理失败：${error.message ?: "未知错误"}"
                            )
                        )
                    }
                )
            }
            AiAgentMode.OFF -> {
                Logger.w(tag, "Agent is OFF")
                return@withContext Result.success(
                    AgentAction.Error(
                        commandId = AgentIdGenerator.nextId(),
                        errorCode = AgentErrorCode.INVALID_REQUEST,
                        message = "AI Agent 已关闭"
                    )
                )
            }
            else -> {
                // REMOTE/FEISHU 模式统一使用本地推理
                Logger.d(tag, "Using local LLM for ${configurator.getAgentMode()} mode")
                if (!localLlmEngine.isLoaded) {
                    val loadResult = ensureModelLoaded(caller = "processUserInput:${configurator.getAgentMode()}")
                    if (loadResult.isFailure) {
                        return@withContext handleModelLoadError(loadResult)
                    }
                }
                val localMessages = memoryManager.buildContextMessages(
                    agentContext.memorySessionId, systemPrompt, input
                )
                val responseResult = try {
                    Result.success(
                        localLlmEngine.chat(
                            LlmChatRequest(
                                messages = localMessages
                            )
                        ).aiMessage.text()
                    )
                } catch (e: Exception) {
                    Result.failure(e)
                }
                return@withContext responseResult.fold(
                    onSuccess = { rawResponse ->
                        handleLlmResponse(rawResponse, input, agentContext, pageContext, agentContext.memorySessionId)
                    },
                    onFailure = { error ->
                        Logger.e(tag, "LLM inference failed (mode=${configurator.getAgentMode()})", error)
                        Result.success(
                            AgentAction.Error(
                                commandId = AgentIdGenerator.nextId(),
                                errorCode = AgentErrorCode.INTERNAL_ERROR,
                                message = "推理失败：${error.message ?: "未知错误"}"
                            )
                        )
                    }
                )
            }
        }

        // 4. 处理 InferenceResult
        return@withContext handleInferenceResult(inferenceResult, input, agentContext, pageContext)
    }



    /**
     * 处理模型加载错误
     */
    private fun handleModelLoadError(loadResult: Result<Unit>): Result<AgentAction> {
        val error = loadResult.exceptionOrNull()
        val message = if (error is LlmModelNotFoundException) {
            error.message ?: "模型未下载"
        } else {
            "模型加载失败：${error?.message ?: "未知错误"}"
        }
        return Result.success(
            AgentAction.Error(
                commandId = AgentIdGenerator.nextId(),
                errorCode = AgentErrorCode.INTERNAL_ERROR,
                message = message
            )
        )
    }

    /**
     * 处理 LLM 响应
     */
    private suspend fun handleLlmResponse(
        rawResponse: String,
        userInput: String,
        agentContext: AgentContext,
        pageContext: PageContext?,
        memorySessionId: String
    ): Result<AgentAction> {
        val responseForHistory = filterThinkTags(rawResponse)
        Logger.i(tag, "LLM raw response: ${rawResponse.replace("\n", "\\n")}")

        // 解析命令（使用 LocalCommandParser）
        val command = LocalCommandParser.parseLlmResponse(rawResponse, agentContext)
        Logger.i(tag, "Parsed command: ${command::class.simpleName}")

        // L1 缓存学习
        if (command !is AgentCommand.Error && command !is AgentCommand.TextReply) {
            intentCache.put(userInput, command)
            Logger.d(tag, "L1 cache learned: '$userInput' -> ${command::class.simpleName}")
        }

        saveConversation(memorySessionId, userInput, command, responseForHistory)
        return _capabilityRegistry.dispatch(command, agentContext, pageContext)
    }

    /**
     * 保存对话
     */
    /**
     * 异步保存对话历史（fire-and-forget）。
     *
     * 不阻塞调用方，对话历史在后台 DataStore 线程上异步持久化。
     * 即使保存失败也不影响当前推理响应。
     */
    private fun saveConversation(
        sessionId: String,
        userInput: String,
        command: AgentCommand,
        rawResponse: String
    ) {
        val assistantResponse = if (command is AgentCommand.TextReply) {
            command.message
        } else {
            rawResponse
        }
        backgroundScope.launch {
            memoryManager.appendConversation(sessionId, userInput, assistantResponse)
        }
    }

    /**
     * 过滤 Qwen3 模型的 <think> 标签
     */
    private fun filterThinkTags(response: String): String {
        val thinkStart = response.indexOf("<think>")
        if (thinkStart == -1) return response.trim()

        val thinkEnd = response.indexOf("</think>", thinkStart)
        return if (thinkEnd != -1) {
            (response.substring(0, thinkStart) + response.substring(thinkEnd + 8)).trim()
        } else {
            val afterTag = response.substring(thinkStart + 7).trim()
            val beforeTag = response.substring(0, thinkStart).trim()
            if (afterTag.contains("{")) afterTag else beforeTag
        }
    }

    /**
     * 清空当前场景的对话历史
     */
    suspend fun clearMemory(sessionId: String) {
        memoryManager.clearHistory(sessionId)
    }

    /**
     * 将图片对话保存到 MemoryManager，使后续文本消息可以引用图片分析结果。
     *
     * 场景：用户在 chat 页面发送一张图片后，后续几轮文本对话都与该图片相关。
     * 此方法将图片分析的用户提示词和 AI 分析结果存入对话记忆，
     * 使后续 [buildContextMessages] 能够包含图片上下文。
     *
     * @param sessionId    会话 ID
     * @param userPrompt   用户对图片的提示词（如 "请描述这张图片"）
     * @param imageAnalysis AI 对图片的分析文本
     */
    fun appendImageChatToMemory(
        sessionId: String,
        userPrompt: String,
        imageAnalysis: String
    ) {
        backgroundScope.launch {
            memoryManager.appendConversation(sessionId, userPrompt, imageAnalysis)
            Logger.d(tag, "Image chat saved to memory: session=$sessionId, analysisLen=${imageAnalysis.length}")
        }
    }

    // ── 飞书 ReAct 入口 ─────────────────────────────────────────────

    /**
     * 处理飞书远程控制输入（ReAct 循环）。
     *
     * 使用 [RemoteReActAgent] 执行多轮 Observe→Think→Act→Verify 循环，
     * 通过应用内 UI 自动化工具完成用户请求。
     *
     * @param input 用户自然语言输入
     * @param windowManager 用于获取屏幕信息的 WindowManager
     * @param timeoutMs 超时时间（毫秒），默认 120 秒
     * @return 任务完成摘要或错误信息
     */
    suspend fun processRemoteImInput(
        input: String,
        windowManager: android.view.WindowManager,
        timeoutMs: Long = 120_000L
    ): Result<String> = withContext(Dispatchers.IO) {
        Logger.d(tag, "processRemoteImInput: input='$input', timeout=${timeoutMs}ms")

        val agent = configurator.getFeishuAgent(windowManager, object : RemoteReActAgentCallback {
            override fun onLoopStart(iteration: Int) {}
            override fun onContent(iteration: Int, content: String) {}
            override fun onToolCall(iteration: Int, toolName: String, args: String) {}
            override fun onToolResult(iteration: Int, toolName: String, result: String) {}
            override fun onComplete(iteration: Int, summary: String, totalTokens: Int, metrics: AgentExecutionMetrics?) {}
            override fun onError(iteration: Int, error: Throwable, totalTokens: Int, metrics: AgentExecutionMetrics?) {}
        }) ?: return@withContext Result.failure(
            IllegalStateException("Feishu ReAct Agent 初始化失败")
        )

        if (agent.isRunning()) {
            return@withContext Result.failure(
                IllegalStateException("Agent 正在执行其他任务")
            )
        }

        return@withContext try {
            val job = coroutineContext[kotlinx.coroutines.Job]

            val result = withTimeout(timeoutMs) {
                suspendCoroutine<String> { continuation ->
                    var executionMetrics: AgentExecutionMetrics? = null
                    val callback = object : RemoteReActAgentCallback {
                        override fun onLoopStart(iteration: Int) {
                            Logger.d(tag, "Feishu ReAct iteration #$iteration")
                        }
                        override fun onContent(iteration: Int, content: String) {
                            Logger.d(tag, "Feishu ReAct content: ${content.take(200)}")
                        }
                        override fun onToolCall(iteration: Int, toolName: String, args: String) {
                            Logger.d(tag, "Feishu ReAct toolCall: $toolName(${args.take(100)})")
                        }
                        override fun onToolResult(iteration: Int, toolName: String, result: String) {
                            Logger.d(tag, "Feishu ReAct toolResult: $toolName → ${result.take(80)}")
                        }
                        override fun onComplete(iteration: Int, summary: String, totalTokens: Int, metrics: AgentExecutionMetrics?) {
                            Logger.i(tag, "Feishu ReAct complete: $iteration rounds, $totalTokens tokens")
                            executionMetrics = metrics
                            continuation.resume("✅ $summary")
                        }
                        override fun onError(iteration: Int, error: Throwable, totalTokens: Int, metrics: AgentExecutionMetrics?) {
                            Logger.e(tag, "Feishu ReAct error: ${error.message}")
                            executionMetrics = metrics
                            continuation.resume("❌ ${error.message ?: "未知错误"}")
                        }
                    }

                    // 协程取消时自动取消 Agent
                    job?.invokeOnCompletion { cause ->
                        if (cause != null) {
                            Logger.d(tag, "Feishu ReAct coroutine cancelled: ${cause.message}")
                            agent.cancel()
                        }
                    }

                    agent.executeTask(input, callback)
                    Logger.d(tag, "executeTask submitted, waiting for callback...")
                }
            }
            // 将性能指标附加到返回结果
            val metrics = agent.getLastExecutionMetrics()
            val finalResult = if (metrics != null) {
                val perfInfo = buildString {
                    append("\n\n---\n")
                    val model = metrics.modelName ?: "未知"
                    val latency = "${metrics.latencyMs}ms"
                    val tokens = if (metrics.promptTokens != null && metrics.completionTokens != null) {
                        "${metrics.promptTokens + metrics.completionTokens} tokens (${metrics.promptTokens} in / ${metrics.completionTokens} out)"
                    } else {
                        ""
                    }
                    append("$model | $latency | $tokens")
                }
                result + perfInfo
            } else {
                result
            }
            Logger.d(tag, "processRemoteImInput got result: ${finalResult.take(100)}")
            Result.success(finalResult)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Logger.e(tag, "processRemoteImInput timeout after ${timeoutMs}ms")
            agent.cancel()
            Result.failure(RuntimeException("⏰ 处理超时（${timeoutMs / 1000}秒），请稍后重试"))
        } catch (e: Exception) {
            Logger.e(tag, "processRemoteImInput error", e)
            Result.failure(e)
        }
    }

    /**
     * 解析 LLM 响应（暴露给测试使用）
     */
    fun parseLlmResponse(response: String, context: AgentContext): AgentCommand {
        return LocalCommandParser.parseLlmResponse(response, context)
    }

    /**
     * 根据 method 字段解析为具体命令
     */
    fun parseCommandByMethod(
        method: String,
        json: String,
        context: AgentContext,
        fallbackText: String
    ): AgentCommand {
        return LocalCommandParser.parseCommandByMethod(method, json, context, fallbackText)
    }

    /**
     * 处理 InferenceResult 并转换为 AgentAction
     */
    private suspend fun handleInferenceResult(
        inferenceResult: InferenceResult,
        userInput: String,
        agentContext: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val memorySessionId = agentContext.memorySessionId

        return when (inferenceResult) {
            is InferenceResult.Local -> {
                Logger.d(tag, "Handling Local result: ${inferenceResult.command::class.simpleName}")
                saveConversation(memorySessionId, userInput, inferenceResult.command, "")
                _capabilityRegistry.dispatch(inferenceResult.command, agentContext, pageContext)
            }
            is InferenceResult.Batch -> {
                Logger.d(tag, "Handling Batch result: ${inferenceResult.commands.size} commands")
                if (inferenceResult.commands.isEmpty()) {
                    Result.success(
                        AgentAction.Error(
                            commandId = AgentIdGenerator.nextId(),
                            errorCode = AgentErrorCode.INVALID_REQUEST,
                            message = "未解析到任何命令"
                        )
                    )
                } else {
                    val firstCommand = inferenceResult.commands.first()
                    val remainingCommands = inferenceResult.commands.drop(1)
                    val finalCommand = if (remainingCommands.isNotEmpty()) {
                        AgentCommand.BatchExecute(
                            commands = listOf(firstCommand) + remainingCommands
                        )
                    } else {
                        firstCommand
                    }
                    saveConversation(memorySessionId, userInput, finalCommand, "")
                    _capabilityRegistry.dispatch(finalCommand, agentContext, pageContext)
                }
            }
            is InferenceResult.Plan -> {
                Logger.d(tag, "Handling Plan result: ${inferenceResult.plan.steps.size} steps")
                val planCommand = AgentCommand.ExecutePlan(plan = inferenceResult.plan)
                saveConversation(memorySessionId, userInput, planCommand, inferenceResult.plan.description)
                _capabilityRegistry.dispatch(planCommand, agentContext, pageContext)
            }
            is InferenceResult.Chat -> {
                Logger.d(tag, "Handling Chat result: ${inferenceResult.message}")
                val textCommand = AgentCommand.TextReply(message = inferenceResult.message)
                saveConversation(memorySessionId, userInput, textCommand, inferenceResult.message)
                Result.success(
                    AgentAction.TextReply(
                        commandId = AgentIdGenerator.nextId(),
                        message = inferenceResult.message
                    )
                )
            }
        }
    }
}
