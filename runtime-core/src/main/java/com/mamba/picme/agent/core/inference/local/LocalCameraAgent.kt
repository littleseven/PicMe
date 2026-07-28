package com.mamba.picme.agent.core.inference.local

import com.mamba.picme.agent.core.facade.AgentConfigurator
import com.mamba.picme.agent.core.inference.local.llm.LlmModelNotFoundException
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.inference.local.parser.LocalCommandParser
import com.mamba.picme.agent.core.local.llm.LlmChatRequest
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentIdGenerator
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.thread.ThreadPoolManager
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地相机 Agent（决策3 / ADR-010 step5b）。
 *
 * 从 [com.mamba.picme.agent.core.facade.AgentOrchestrator] 抽出的**本地推理路径**：
 * - [processInputWithRouter]：相机入口，走 [com.mamba.picme.agent.core.inference.local.pipeline.LocalInferencePipeline]
 *   （L1/L2/L3 本地快速通道，自定义 JSON 数组协议）。
 * - [processUserInput]：原始入口（GlobalAgentPanel 相机面板 / 飞书 RemoteCommandDispatcher 复用），
 *   直接调 [LocalLlmEngine].chat + [LocalCommandParser]。
 * - 记忆回写（fire-and-forget）、`<think>` 过滤、命令解析等辅助方法。
 *
 * 共享组件经 [AgentConfigurator] 只读访问（engine/pipeline/memory/intentCache/capabilityRegistry/sceneManager），
 * 模型加载经 [LocalModelService]；与远程链路（RemoteChatEngine）严格隔离。
 */
class LocalCameraAgent internal constructor(
    private val configurator: AgentConfigurator,
    private val localModelService: LocalModelService
) {

    private val tag = "LocalCameraAgent"
    private val orchestratorDispatcher = ThreadPoolManager.getInstance().orchestratorDispatcher

    /** 后台作用域：对话历史 fire-and-forget 持久化。 */
    private val backgroundScope = CoroutineScope(SupervisorJob())

    private val localLlmEngine: LocalLlmEngine get() = configurator.localLlmEngine
    private val memoryManager get() = configurator.memoryManager
    private val sceneManager get() = configurator.sceneManager
    private val promptBuilder get() = configurator.localPromptBuilder
    private val intentCache get() = configurator.intentCache
    private val capabilityRegistry get() = configurator.capabilityRegistry

    /**
     * 使用 LocalPipeline 处理输入（支持 L2 本地快速通道）。
     *
     * 统一走 LocalPipeline 路由，LOCAL 模式下优先尝试 L2 本地快速通道。
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
                val loadResult = localModelService.ensureModelLoaded(caller = "processInputWithRouter")
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

    /** 将 InferenceResult 保存到 MemoryManager */
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
     * 处理用户输入（原始入口，保留兼容）。
     *
     * LOCAL/REMOTE/FEISHU 统一走本地推理；OFF 直接返回。
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
            return@withContext capabilityRegistry.dispatch(cachedCommand, agentContext, pageContext)
        }

        // 1. 获取当前场景的 Capability 列表
        val capabilities = capabilityRegistry.getCapabilitiesForCurrentScene()

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
            ?: promptBuilder.buildSystemPrompt(capabilities, agentContext)

        // 3. 根据模式选择推理引擎（LOCAL/REMOTE/FEISHU 统一走本地推理；OFF 直接返回）
        when (val mode = configurator.getAgentMode()) {
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
                // LOCAL/REMOTE/FEISHU 均使用本地 LLM（MNN-LLM）
                if (!localLlmEngine.isLoaded) {
                    val loadResult = localModelService.ensureModelLoaded(caller = "processUserInput:$mode")
                    if (loadResult.isFailure) {
                        return@withContext handleModelLoadError(loadResult)
                    }
                }
                Logger.d(tag, "Using local LLM (MNN-LLM) for $mode mode")
                val localMessages = memoryManager.buildContextMessages(
                    agentContext.memorySessionId, systemPrompt, input
                )
                val responseResult = try {
                    Result.success(
                        localLlmEngine.chat(
                            LlmChatRequest(messages = localMessages)
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
                        Logger.e(tag, "LLM inference failed (mode=$mode)", error)
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
    }

    /** 处理模型加载错误 */
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

    /** 处理 LLM 响应 */
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
        return capabilityRegistry.dispatch(command, agentContext, pageContext)
    }

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

    /** 过滤 Qwen3 模型的 `<think>` 标签 */
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

    /** 清空当前场景的对话历史 */
    suspend fun clearMemory(sessionId: String) {
        memoryManager.clearHistory(sessionId)
    }

    /**
     * 将图片对话保存到 MemoryManager，使后续文本消息可以引用图片分析结果。
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

    /** 解析 LLM 响应（暴露给测试使用） */
    fun parseLlmResponse(response: String, context: AgentContext): AgentCommand {
        return LocalCommandParser.parseLlmResponse(response, context)
    }

    /** 根据 method 字段解析为具体命令（暴露给测试使用） */
    fun parseCommandByMethod(
        method: String,
        json: String,
        context: AgentContext,
        fallbackText: String
    ): AgentCommand {
        return LocalCommandParser.parseCommandByMethod(method, json, context, fallbackText)
    }
}
