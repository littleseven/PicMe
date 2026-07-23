package com.mamba.picme.agent.core.facade

import android.content.Context
import android.view.WindowManager
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.inference.local.pipeline.LocalInferencePipeline
import com.mamba.picme.agent.core.inference.local.prompt.LocalPromptBuilder
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentCallback
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgent
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.MemoryManager
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.cache.IntentCache
import com.mamba.picme.agent.core.runtime.policy.PrivacyGuard
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.model.chat.ChatModel

/**
 * Agent 配置器
 *
 * 负责初始化和配置 Agent 运行时所需的所有核心组件。
 * 作为 [AgentOrchestrator] 的依赖工厂，集中管理组件生命周期。
 */
class AgentConfigurator(private val context: Context) {

    private val tag = "AgentConfigurator"

    /**
     * 获取 Application Context
     */
    fun getContext(): Context = context

    // 核心组件（延迟初始化）
    val localLlmEngine = LocalLlmEngine(context)
    val memoryManager = MemoryManager(context)
    val privacyGuard = PrivacyGuard()
    val sceneManager = SceneManager.getInstance()
    val localPromptBuilder = LocalPromptBuilder(sceneManager)
    val capabilityRegistry = CapabilityRegistry.getInstance()
    val intentCache = IntentCache()

    /**
     * 模式临时覆盖栈（用于飞书远程控制等场景强制使用特定推理模式）。
     *
     * - [pushModeOverride] 压入覆盖模式
     * - [popModeOverride] 弹出恢复
     * - [getAgentMode] 优先返回栈顶覆盖模式，栈空时返回持久化模式
     *
     * 使用场景：RemoteCommandDispatcher 在处理飞书消息时压入 REMOTE，
     * 处理完成后弹出，不影响用户设置的持久化模式。
     */
    private val modeOverrideStack = ArrayDeque<AiAgentMode>()

    // 配置状态
    private var agentMode: AiAgentMode = AiAgentMode.REMOTE
    private var currentModelId: String = "qwen3_5_2b"
    private var userRemoteConfig: RemoteModelConfig? = null

    /**
     * 设备级标识（访客试用额度 X-Device-Id）。独立于 [userRemoteConfig] 持有，
     * 避免被多次 configure 覆盖丢失（例如 AiAgentUseCase init 用 fallback 重配 remoteConfig 时，
     * 带 deviceId 的 config 被裸 PICME_SERVER_DEFAULT 覆盖，导致 guest 请求无 X-Device-Id → 401）。
     */
    private var deviceId: String = ""

    fun setDeviceId(id: String) {
        if (id.isNotBlank()) deviceId = id
    }
    private var localInferencePipeline: LocalInferencePipeline? = null
    private var localUseOpencl: Boolean = false
    private var inferencePreference: AiAgentInferencePreference = AiAgentInferencePreference.FORCE_REMOTE

    /**
     * 获取或创建本地推理管道
     */
    fun getLocalPipeline(): LocalInferencePipeline {
        val existing = localInferencePipeline
        if (existing != null) return existing
        val pipeline = LocalInferencePipeline(
            localEngine = localLlmEngine,
            sceneManager = sceneManager,
            capabilityRegistry = capabilityRegistry,
            intentCache = intentCache,
            privacyGuard = privacyGuard,
            memoryManager = memoryManager
        )
        localInferencePipeline = pipeline
        return pipeline
    }

    /**
     * 配置 Agent 运行参数
     */
    fun configure(
        mode: AiAgentMode,
        modelId: String,
        privacyLevel: AiAgentPrivacyLevel,
        remoteConfig: RemoteModelConfig? = null,
        localUseOpencl: Boolean = false,
        inferencePreference: AiAgentInferencePreference? = null
    ) {
        this.agentMode = mode
        this.currentModelId = modelId
        this.localUseOpencl = localUseOpencl
        if (inferencePreference != null) {
            this.inferencePreference = inferencePreference
        }
        if (remoteConfig != null && remoteConfig.baseUrl.isNotBlank() && remoteConfig.modelId.isNotBlank()) {
            this.userRemoteConfig = remoteConfig
            localInferencePipeline = null
        }
        privacyGuard.updateConfig(privacyLevel, mode)
        Logger.i(tag, "Configured: mode=$mode, model=$modelId, privacy=$privacyLevel, " +
            "localUseOpencl=$localUseOpencl, " +
            "inferencePreference=${this.inferencePreference}, " +
            "remoteModel=${remoteConfig?.modelId ?: "default"}, " +
            "effectiveRemoteModel=${userRemoteConfig?.modelId ?: "fallback"}")
    }

    /**
     * 当前 Agent 运行模式
     *
     * 优先返回临时覆盖模式（[modeOverrideStack] 栈顶），
     * 栈空时返回持久化模式（[agentMode]）。
     */
    fun getAgentMode(): AiAgentMode = modeOverrideStack.lastOrNull() ?: agentMode

    /**
     * 压入模式临时覆盖。
     * 此后 [getAgentMode] 将返回 [mode]，直到 [popModeOverride] 被调用。
     *
     * 支持嵌套：多次压入需要对应次数弹出。
     */
    fun pushModeOverride(mode: AiAgentMode) {
        modeOverrideStack.addLast(mode)
        Logger.d(tag, "Mode override pushed: $mode (stack size=${modeOverrideStack.size})")
    }

    /**
     * 弹出模式临时覆盖。
     * 恢复栈为空时返回持久化模式。
     *
     * @throws NoSuchElementException 栈已空时调用
     */
    fun popModeOverride() {
        val popped = modeOverrideStack.removeLastOrNull()
        if (popped != null) {
            Logger.d(tag, "Mode override popped: $popped (stack size=${modeOverrideStack.size})")
        } else {
            Logger.w(tag, "popModeOverride called on empty stack")
        }
    }

    /**
     * 当前模型 ID
     */
    fun getCurrentModelId(): String = currentModelId

    /**
     * 当前本地 LLM 后端是否使用 OpenCL
     */
    fun getLocalUseOpencl(): Boolean = localUseOpencl

    /**
     * 当前推理偏好（FORCE_LOCAL / FORCE_REMOTE / AUTO）
     */
    fun getInferencePreference(): AiAgentInferencePreference = inferencePreference

    /**
     * 用户远程配置
     */
    fun getUserRemoteConfig(): RemoteModelConfig? = userRemoteConfig

    /**
     * 模型是否已加载
     */
    val isModelLoaded: Boolean
        get() = localLlmEngine.isLoaded

    /**
     * 创建远程聊天模型（同步，兼容不支持 SSE 的网关）
     *
     * 使用同步 [com.mamba.model.chat.ChatModel] 而非流式模型。
     * SCF AI Gateway 等代理网关通常不支持 SSE 流式传输，
     * 发送 stream=true 会导致连接被关闭。同步调用已验证可靠（与飞书 RemoteReActAgent 一致）。
     *
     * @param config 远程模型配置（baseUrl / apiKey / modelId / gatewayToken）
     * @return 同步聊天模型实例
     */
    fun createRemoteChatModel(config: RemoteModelConfig): ChatModel {
        val builder = RemoteModelFactory.createBuilder(config)
            .logRequests(true)
            .logResponses(true)
        if (config.gatewayToken.isNotBlank()) {
            builder.customHeader("X-App-Token", config.gatewayToken)
        } else {
            // 未注册访客：无账号 token 时改用设备级试用额度（X-Device-Id）。
            // 优先用 config.deviceId；若被 fallback 覆盖为空，回退到独立持有的 [deviceId]。
            val effectiveDeviceId = config.deviceId.ifBlank { deviceId }
            if (effectiveDeviceId.isNotBlank()) {
                builder.customHeader("X-Device-Id", effectiveDeviceId)
            }
        }
        Logger.i(tag, "RemoteChatModel created: model=${config.modelId}, baseUrl=${config.baseUrl.take(40)}")
        return builder.build()
    }

    // ── 飞书 ReAct Agent（懒创建）────────────────────────────────────

    private var cachedFeishuAgent: RemoteReActAgent? = null

    /** 缓存的 Feishu Agent 对应的配置，用于检测配置变更 */
    private var cachedFeishuAgentConfig: RemoteModelConfig? = null

    // ── chat ReAct Agent（懒创建）────────────────────────────────────

    private var cachedChatAgent: RemoteReActAgent? = null
    private var cachedChatAgentConfig: RemoteModelConfig? = null

    /** chat ReAct 专属 system prompt：强调用工具调度相册能力，含 run_gallery_script 用法 */
    private val chatSystemPrompt = """
        你是 PoLang 相册 AI 助手，通过调用工具帮助用户管理、搜索、分析本地相册。
        可用工具：search_media（搜索）、refine_media_search（细化）、get_gallery_summary（摘要）、
        start_tag_scan（打标）、ai_optimize（修图）、record_feedback/more_like_this/exclude_constraint（反馈）、
        run_gallery_script（执行 JS 做组合计算/盘点）、view_media/delete_media/share_media/favorite_media、
        change_theme/change_language/toggle_setting 等设置、navigate_to/go_back。
        对于"盘点/统计/分析相册"类请求，优先用 run_gallery_script：生成一段 JS，调用 bridge.call('gallery.summary') 取数据、在 JS 内计算（比率/占比/分布）、return 一个结果对象，该对象会回传给你做自然语言总结。
        完成后直接在最终回复中给出完整结果（如 Markdown 格式的盘点报表），不要调用 finish。只读操作直接做，不要让用户额外确认。
    """.trimIndent()

    /**
     * 获取或创建飞书 ReAct Agent。
     * 优先使用用户配置的远程模型，未配置时使用 PoLang Server 默认兜底。
     *
     * 当用户配置发生变更时（cachedFeishuAgentConfig != userRemoteConfig），
     * 自动重建 Agent 以确保使用最新的 API Key / baseUrl / model。
     */
    fun getFeishuAgent(windowManager: WindowManager, callback: RemoteReActAgentCallback): RemoteReActAgent? {
        val existing = cachedFeishuAgent
        val currentConfig = userRemoteConfig ?: RemoteModelConfig.PICME_SERVER_DEFAULT

        // 配置变更检测：如果用户修改了远程模型配置，重建 Agent
        if (existing != null && cachedFeishuAgentConfig != null) {
            val configChanged = cachedFeishuAgentConfig?.modelId != currentConfig.modelId
                || cachedFeishuAgentConfig?.baseUrl != currentConfig.baseUrl
                || cachedFeishuAgentConfig?.apiKey != currentConfig.apiKey
                || cachedFeishuAgentConfig?.gatewayToken != currentConfig.gatewayToken
            if (configChanged) {
                Logger.i("AgentConfigurator", "Remote config changed (model=${currentConfig.modelId}), rebuilding Feishu Agent")
                existing.shutdown()
                cachedFeishuAgent = null
                cachedFeishuAgentConfig = null
            } else {
                return existing
            }
        } else if (existing != null) {
            return existing
        }

        val cfg = try {
            RemoteReActAgentConfig.Builder()
                .apiKey(currentConfig.apiKey)
                .baseUrl(currentConfig.baseUrl)
                .modelName(currentConfig.modelId)
                .gatewayToken(currentConfig.gatewayToken)
                .build()
        } catch (e: Exception) {
            Logger.w("AgentConfigurator", "Failed to build FeishuAgent config", e)
            return null
        }

        val agent = RemoteReActAgent(cfg, windowManager, callback, context)
        agent.initialize()
        cachedFeishuAgent = agent
        cachedFeishuAgentConfig = currentConfig
        Logger.i("AgentConfigurator", "Feishu ReAct Agent created: model=${cfg.modelName}, baseUrl=${currentConfig.baseUrl.take(40)}")
        return agent
    }

    /**
     * 获取或创建 chat ReAct Agent（用 ChatToolService，chat 场域能力工具，不含 UI/相机）。
     * 配置变更时重建（同 getFeishuAgent）。
     */
    fun getChatAgent(callback: RemoteReActAgentCallback): RemoteReActAgent? {
        val existing = cachedChatAgent
        val currentConfig = userRemoteConfig ?: RemoteModelConfig.PICME_SERVER_DEFAULT
        if (existing != null && cachedChatAgentConfig != null) {
            val configChanged = cachedChatAgentConfig?.modelId != currentConfig.modelId
                || cachedChatAgentConfig?.baseUrl != currentConfig.baseUrl
                || cachedChatAgentConfig?.apiKey != currentConfig.apiKey
                || cachedChatAgentConfig?.gatewayToken != currentConfig.gatewayToken
            if (configChanged) {
                Logger.i(tag, "Remote config changed (model=${currentConfig.modelId}), rebuilding Chat Agent")
                existing.shutdown()
                cachedChatAgent = null
                cachedChatAgentConfig = null
            } else {
                return existing
            }
        } else if (existing != null) {
            return existing
        }
        val cfg = try {
            RemoteReActAgentConfig.Builder()
                .apiKey(currentConfig.apiKey)
                .baseUrl(currentConfig.baseUrl)
                .modelName(currentConfig.modelId)
                .gatewayToken(currentConfig.gatewayToken)
                .systemPrompt(chatSystemPrompt + "\n\n当前日期：${java.time.LocalDate.now()}。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。")
                .build()
        } catch (e: Exception) {
            Logger.w(tag, "Failed to build ChatAgent config", e)
            return null
        }
        val agent = RemoteReActAgent(
            config = cfg,
            windowManager = null,
            callback = callback,
            appContext = context,
            toolService = ChatToolService.getInstance()
        )
        agent.initialize()
        cachedChatAgent = agent
        cachedChatAgentConfig = currentConfig
        Logger.i(tag, "Chat ReAct Agent created: model=${cfg.modelName}, baseUrl=${currentConfig.baseUrl.take(40)}")
        return agent
    }

    /**
     * 清除飞书 ReAct Agent 缓存（用于配置变更后重建）
     */
    fun clearFeishuAgent() {
        cachedFeishuAgent?.shutdown()
        cachedFeishuAgent = null
        cachedFeishuAgentConfig = null
        Logger.i("AgentConfigurator", "Feishu ReAct Agent cleared")
    }
}
