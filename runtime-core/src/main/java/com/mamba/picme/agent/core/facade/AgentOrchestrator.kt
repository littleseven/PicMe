package com.mamba.picme.agent.core.facade

import android.content.Context
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentCallback
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgent
import com.mamba.picme.agent.core.inference.remote.react.AgentExecutionMetrics
import com.mamba.picme.agent.core.inference.remote.RemoteChatEngine
import com.mamba.picme.agent.core.inference.local.LocalModelService
import com.mamba.picme.agent.core.inference.local.LocalCameraAgent
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.thread.ThreadPoolManager
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** 本地相机 Agent（决策3 / ADR-010 step5b）：本地推理路径隔离出口。 */
    val localCameraAgent = LocalCameraAgent(configurator, localModelService)

    private val orchestratorDispatcher = ThreadPoolManager.getInstance().orchestratorDispatcher

    /**
     * 后台作用域：用于 fire-and-forget 异步操作（如对话历史保存）。
     * SupervisorJob 确保单个后台任务失败不影响其他任务。
     */
    private val backgroundScope = CoroutineScope(SupervisorJob())

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

    /** 使用 LocalPipeline 处理输入（委托 [localCameraAgent]） */
    suspend fun processInputWithRouter(
        input: String,
        agentContext: AgentContext,
        pageContext: PageContext? = null
    ): InferenceResult = localCameraAgent.processInputWithRouter(input, agentContext, pageContext)

    /** 处理用户输入（委托 [localCameraAgent]） */
    suspend fun processUserInput(
        input: String,
        agentContext: AgentContext,
        pageContext: PageContext? = null,
        customSystemPrompt: String? = null
    ): Result<AgentAction> = localCameraAgent.processUserInput(input, agentContext, pageContext, customSystemPrompt)

    /** 清空当前场景的对话历史（委托 [localCameraAgent]） */
    suspend fun clearMemory(sessionId: String) {
        localCameraAgent.clearMemory(sessionId)
    }

    /** 将图片对话保存到 MemoryManager（委托 [localCameraAgent]） */
    fun appendImageChatToMemory(
        sessionId: String,
        userPrompt: String,
        imageAnalysis: String
    ) {
        localCameraAgent.appendImageChatToMemory(sessionId, userPrompt, imageAnalysis)
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

    /** 解析 LLM 响应（委托 [localCameraAgent]，暴露给测试使用） */
    fun parseLlmResponse(response: String, context: AgentContext): AgentCommand =
        localCameraAgent.parseLlmResponse(response, context)

    /** 根据 method 字段解析为具体命令（委托 [localCameraAgent]） */
    fun parseCommandByMethod(
        method: String,
        json: String,
        context: AgentContext,
        fallbackText: String
    ): AgentCommand = localCameraAgent.parseCommandByMethod(method, json, context, fallbackText)

}

