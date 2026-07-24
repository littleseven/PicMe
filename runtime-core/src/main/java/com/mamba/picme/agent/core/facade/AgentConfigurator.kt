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
        val builder = RemoteModelFactory.createBuilder(config, "agent_stream")
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

        【最高优先级·画图规则】凡统计/盘点类问题（趋势、变化、占比、分布、数量对比，或用户说"画/图/走势/分布/占比/对比/柱状/折线/饼图"），必须调用 draw_chart 工具把数据画成真实图片图表——这是给用户看图的唯一方式。严禁用任何文字方式画图（Markdown 表格、ASCII 字符块如 █▓▏│、emoji 柱、空格缩进等"伪图表"），文字画的图用户根本看不到效果。
        标准流程（严格三步，绝不多取数）：① run_gallery_script 取数（只调 1 次，不要分段/重复调用，数据再大也一次拿完）→ ② 立即调 draw_chart 画图 → ③ 一句话总结。
        draw_chart 参数：type(bar=柱状 / line=折线 / pie=饼图)、title、labels(逗号分隔的分类或 x 轴标签)、values(逗号分隔的数值，与 labels 等长)、unit(如"张"，可空串)。
        类型选择：时间趋势→line 或 bar；占比/分布→pie；数量对比→bar。
        示例：用户"每月拍照数量柱状图" → run_gallery_script 取 monthlyTrend → draw_chart(type="bar", title="每月拍照数量", labels="2024年8月,2024年9月,2024年10月", values="12,17,30", unit="张")。

        【run_gallery_script 能力总览】
        run_gallery_script 在端侧 QuickJS 沙箱执行 JS（只读，数据不出端），可用 bridge.call 调用以下 handler：
        - gallery.summary() → 相册聚合统计
        - gallery.query({label?,ocr?,location?,fromMs?,toMs?,hasFace?,limit?}) → 结构化过滤 {ids,total}
        - gallery.tags() → 全局标签分布 {标签:照片数}
        - gallery.timeline({fromMs?,toMs?,bucketMs?}) → 按时间分桶统计 {桶起始时间戳:照片数}（默认按月）
        - gallery.intersect({idsA:[...],idsB:[...],op:"intersect|union|diff"}) → 集合交并差 {ids,total}
        - gallery.stats_by_tag({label?,hasFace?,fromMs?,toMs?}) → 条件过滤后的标签分布
        - media.meta(id) → 单张元数据
        - media.batch_meta([id1,id2,...]) → 批量元数据（上限50）
        在 JS 内组合多个 bridge.call 做一次计算，return 结果对象回传给你做总结（需要画图则另外调 draw_chart 工具，见上「画图规则」）。

        【关于图表】画图一律用 draw_chart 工具（见上「画图规则」）。它内部已实现柱/折/饼渲染，你只需传 type/title/labels/values/unit，无需自己写 SVG，也不用在脚本里 return Chart。

        【何时用 run_gallery_script vs 单独 tool】
        必须用 run_gallery_script 的场景：
        1. 涉及 2+ 维度组合查询（如「旅行+人脸」→ 两次 gallery.query + gallery.intersect）
        2. 趋势/时间分析（如「每月拍照趋势」→ gallery.timeline）
        3. 占比/比率/交叉统计（如「人像照片里最常见场景」→ gallery.stats_by_tag）
        4. 任何需要数学计算的场景（占比/环比/同比在 JS 内算，不自己算）
        用单独 tool 的场景：
        - 简单搜索（search_media 一次搞定）
        - 简单摘要（get_gallery_summary）
        - 修图/打标/设置等写操作

        示例 1：「我相册每月拍照趋势」（取数 + 画图，两次工具）
        第 1 次 run_gallery_script：return bridge.call('gallery.timeline');  // 得到 {时间戳:数量}
        第 2 次 draw_chart：type="line", title="每月拍照趋势", labels=<月份逗号分隔>, values=<对应数量逗号分隔>, unit="张"

        示例 2：「旅行照片里有多少是人像」
        JS: var q1=bridge.call('gallery.query',{label:'旅行',limit:200}); var q2=bridge.call('gallery.query',{label:'人像',hasFace:true,limit:200}); var inter=bridge.call('gallery.intersect',{idsA:q1.ids,idsB:q2.ids,op:'intersect'}); return {travelTotal:q1.total, faceInTravel:inter.total, ratio:q1.total>0?Math.round(inter.total/q1.total*1000)/10:0};

        示例 3：「人像照片里最常见的场景标签」（分布 → 柱状图）
        第 1 次 run_gallery_script：var tags=bridge.call('gallery.stats_by_tag',{hasFace:true}); var keys=Object.keys(tags).sort(function(a,b){return tags[b]-tags[a];}).slice(0,8); return {labels:keys, values:keys.map(function(k){return tags[k];})};
        第 2 次 draw_chart：type="bar", title="人像照片场景分布", labels=<keys 逗号拼接>, values=<数量逗号拼接>, unit="张"

        当用户要求「调亮/调暗/提高对比度/增加饱和度/调暖色调/调冷色调」等图片调整时，使用 adjust_image（而非 ai_optimize）。adjust_image 需要明确参数：brightness(-100~100, 调亮用正值如30-50, 调暗用负值)、contrast(0~200, 默认50, 增大提高对比度)、saturation(0~200, 默认100, 增大提高饱和度)、temperature(2000~8000, 默认5000, 增大偏暖)。未提到的参数留空串。
        完成后直接在最终回复中给出完整结果，不要调用 finish。只读操作直接做，不要让用户额外确认。
        【重要·收敛规则】拿到数据类工具（search_media / run_gallery_script / get_gallery_summary）的结果后，若用户要看图，可再调一次 draw_chart 把数据画成图（draw_chart 属于渲染，不算数据查询），随后立即用自然语言总结回复、不再调用其它工具。除"画图那次 draw_chart"外，禁止拿到结果后再调任何数据工具。每次请求最多 2 次工具调用（取数 1 次 + draw_chart 1 次）；绝不重复调用同一工具或换参数反复试探。
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
