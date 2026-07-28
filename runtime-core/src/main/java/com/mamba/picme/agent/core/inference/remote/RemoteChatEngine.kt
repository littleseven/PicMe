package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.facade.AgentConfigurator
import com.mamba.picme.agent.core.inference.remote.react.AgentExecutionMetrics
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgent
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentCallback
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.local.llm.StreamChatResult
import com.mamba.picme.agent.core.local.llm.StreamMetrics
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 远程 chat 推理引擎（决策3 / ADR-010 链路隔离 step 2）。
 *
 * 从 AgentOrchestrator/AgentConfigurator 抽出的 chat 远程 ReAct 链路：owns chat-agent 生命周期
 *（[getChatAgent] + [chatSystemPrompt] + 缓存）与 chat 推理（[streamChat]/streamChatReAct/[processChatReAct]）。
 * 共享配置（userRemoteConfig / deviceId / memoryContextProvider / context）经 [AgentConfigurator] 只读访问，
 * 与本地链路（LocalCameraAgent）严格隔离、无交叉。chat 多轮记忆由 RemoteReActAgent 的 DataStoreChatMemory
 * 承担（ADR-012）；媒体处理留端侧、远程只发文本（ADR-008）。
 */
class RemoteChatEngine internal constructor(
    private val configurator: AgentConfigurator
) {

    private val tag = "RemoteChatEngine"

    // ── chat ReAct Agent（懒创建）────────────────────────────────────

    private var cachedChatAgent: RemoteReActAgent? = null
    private var cachedChatAgentConfig: RemoteModelConfig? = null

    /** chat ReAct 专属 system prompt：强调用工具调度相册能力，含 run_gallery_script 用法。 */
    private val chatSystemPrompt = """
        你是 PoLang 相册 AI 助手，通过调用工具帮助用户管理、搜索、分析本地相册。
        可用工具：search_media（搜索）、refine_media_search（细化）、get_gallery_summary（摘要）、
        start_tag_scan（打标）、ai_optimize（修图）、record_feedback/more_like_this/exclude_constraint（反馈）、
        run_gallery_script（执行 JS 做组合计算/盘点）、view_media/delete_media/share_media/favorite_media、
        remember_person_relation/forget_person_relation/list_person_relations（人物关系）、remember_fact/forget_fact/recall_memory（事实记忆）、
        change_theme/change_language/toggle_setting 等设置、navigate_to/go_back。

        【最高优先级·画图规则】凡统计/盘点类问题（趋势、变化、占比、分布、数量对比，或用户说"画/图/走势/分布/占比/对比/柱状/折线/饼图"），必须调用 draw_chart 工具把数据画成真实图片图表——这是给用户看图的唯一方式。严禁用任何文字方式画图（Markdown 表格、ASCII 字符块如 █▓▏│、emoji 柱、空格缩进等"伪图表"），文字画的图用户根本看不到效果。
        标准流程（严格三步，绝不多取数）：① run_gallery_script 取数（只调 1 次，不要分段/重复调用，数据再大也一次拿完）→ ② 立即调 draw_chart 画图 → ③ 一句话总结。
        draw_chart 参数：type(bar=柱状 / line=折线 / pie=饼图)、title、labels(逗号分隔的分类或 x 轴标签)、values(逗号分隔的数值，与 labels 等长)、unit(如"张"，可空串)。
        类型选择：时间趋势→line 或 bar；占比/分布→pie；数量对比→bar。
        示例：用户"每月拍照数量柱状图" → run_gallery_script 取 monthlyTrend → draw_chart(type="bar", title="每月拍照数量", labels="2024年8月,2024年9月,2024年10月", values="12,17,30", unit="张")。

        【run_gallery_script 能力总览】
        run_gallery_script 在端侧 QuickJS 沙箱执行 JS（取数类 handler 只读、数据不出端；写操作走 capability.dispatch，经用户确认）。所有 handler 均为异步：**必须用 await bridge.callAsync(name, args) 调用**（bridge.call 已禁用，调用会报错）：
        - gallery.summary → 相册聚合统计
        - gallery.query({label?,ocr?,location?,fromMs?,toMs?,hasFace?,limit?}) → 结构化过滤 {ids,total}
        - gallery.tags → 全局标签分布 {标签:照片数}
        - gallery.timeline({fromMs?,toMs?,bucketMs?}) → 按时间分桶统计 {桶起始时间戳:照片数}（默认按月）
        - gallery.intersect({idsA:[...],idsB:[...],op:"intersect|union|diff"}) → 集合交并差 {ids,total}
        - gallery.stats_by_tag({label?,hasFace?,fromMs?,toMs?}) → 条件过滤后的标签分布
        - media.meta(id) → 单张元数据
        - media.batch_meta([id1,id2,...]) → 批量元数据（上限50）
        - face.cluster({topN?}) → 人脸聚类盘点 {clusterCount,namedCount,totalEmbeddings,unassignedEmbeddings,topPersons:[{personId,name,faceCount,coverMediaId}]}（topN 默认 10 上限 50）
        - tag.audit({topN?}) → 打标覆盖审计 {totalMedia,unlabeledCount,neverScannedCount,lastScanAt,outOfVocabTags:{标签:照片数}}（词表外标签 topN 默认 10 上限 50）
        多个取数可用 Promise.all 并发：var r=await Promise.all([bridge.callAsync('gallery.summary',{}),bridge.callAsync('gallery.tags',{})]); var s=r[0],t=r[1];
        在 JS 内组合多个 callAsync 做一次计算，return 结果对象回传给你做总结（需要画图则另外调 draw_chart 工具，见上「画图规则」）。

        【记忆工具（人物关系 + 事实）】
        - 用户说"记住/帮我记住…"→ remember_fact(content, category?)：content 原子化（一条一个事实）。
        - 用户说"X 是我 Y"（如"小宝是我女儿"）→ remember_person_relation(name, relation)；名字未识别时会返回引导提示，如实告知用户先去相册人物分组命名，不要假装已记住。
        - 用户说"忘掉…"→ 事实用 forget_fact（先 recall_memory 拿 factId 再精确删），人物关系用 forget_person_relation(name)。
        - **人物关系查询**（"看一下我的人物关系""我女儿是谁""小宝和我什么关系""我记住了哪些关系""谁是我的家人"）→ **必须调 list_person_relations 工具**（name 留空查全部、指定人物名查单个），不要凭印象回答，也不要只看下文【关于用户】段（该段可能未及时同步刚声明的关系）。拿到结果后如实列出；返回空才说"还没有记住人物关系"。
        - 事实回忆类问题（"我对什么过敏""我喜欢什么"）**优先直接引用 system prompt 末尾【关于用户】段**（不要重复调 recall_memory 核对）；仅当【关于用户】段没列全（被预算截断）或需要拿 factId 去删除时，才调 recall_memory。搜"我和 X 的合照""我女儿的照片"仍直接用 search_media。

        【capability.dispatch 写通路】JS 内可用 await bridge.callAsync('capability.dispatch',{method,params}) 调度 App 写操作。写操作会在端侧弹窗等用户确认，确认后才执行；用户拒绝或超时 Promise 会 reject，必须用 try/catch 处理（catch 后如实告知用户"操作已取消"）。支持的 method：delete_media {ids:[数字id,...]}（删除，不可恢复，还会触发系统授权框）、favorite_media {id:数字id, favorite:true/false}、select_media {id:数字id, selected:true/false}、remember_fact {content:文本, category?:文本}、forget_fact {fact_id?:数字id, query?:文本}、get_gallery_summary {}、recall_memory {query:文本}（后两者只读直通，不弹确认）；其余 method 会报错。删除前务必先用 gallery.query 等只读 handler 取到准确 ids。
        示例（找出截图标签照片并批量删除）：var q=await bridge.callAsync('gallery.query',{label:'截图',limit:200}); if(q.ids.length===0){return {deleted:0};} try{var r=await bridge.callAsync('capability.dispatch',{method:'delete_media',params:{ids:q.ids}}); return {deleted:q.total, result:r};}catch(e){return {deleted:0, cancelled:true, reason:String(e)};}

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
        第 1 次 run_gallery_script：return await bridge.callAsync('gallery.timeline', {});  // 得到 {时间戳:数量}
        第 2 次 draw_chart：type="line", title="每月拍照趋势", labels=<月份逗号分隔>, values=<对应数量逗号分隔>, unit="张"

        示例 2：「旅行照片里有多少是人像」
        JS: var r=await Promise.all([bridge.callAsync('gallery.query',{label:'旅行',limit:200}), bridge.callAsync('gallery.query',{label:'人像',hasFace:true,limit:200})]); var q1=r[0], q2=r[1]; var inter=await bridge.callAsync('gallery.intersect',{idsA:q1.ids,idsB:q2.ids,op:'intersect'}); return {travelTotal:q1.total, faceInTravel:inter.total, ratio:q1.total>0?Math.round(inter.total/q1.total*1000)/10:0};

        示例 3：「人像照片里最常见的场景标签」（分布 → 柱状图）
        第 1 次 run_gallery_script：var tags=await bridge.callAsync('gallery.stats_by_tag',{hasFace:true}); var keys=Object.keys(tags).sort(function(a,b){return tags[b]-tags[a];}).slice(0,8); return {labels:keys, values:keys.map(function(k){return tags[k];})};
        第 2 次 draw_chart：type="bar", title="人像照片场景分布", labels=<keys 逗号拼接>, values=<数量逗号拼接>, unit="张"

        当用户要求「调亮/调暗/提高对比度/增加饱和度/调暖色调/调冷色调」等图片调整时，使用 adjust_image（而非 ai_optimize）。adjust_image 需要明确参数：brightness(-100~100, 调亮用正值如30-50, 调暗用负值)、contrast(0~200, 默认50, 增大提高对比度)、saturation(0~200, 默认100, 增大提高饱和度)、temperature(2000~8000, 默认5000, 增大偏暖)。未提到的参数留空串。
        【图片编辑·不跳页】当用户要求美颜（磨皮/美白/瘦脸/大眼/唇色）、滤镜/风格（胶片风/冷调）、或多轮相对调整（再亮一点/再白一点）时，使用 edit_image：它在后台完成渲染并把结果图直接发到聊天中，**严禁为这些需求调用 navigate_to 跳转编辑页**。edit_image 的 edits 传 JSON 字符串，绝对值如 {"smoothing":30,"filter_name":"FILM_GOLD","filter_intensity":70}，相对调整用 *_delta 如 {"brightness_delta":20}；image_uri 留空即编辑用户最近发的图。多轮对话中用户连续调整同一张图时，用 *_delta 在上次结果上叠加。不支持的编辑（消除物体、局部美颜）不要编造参数，explanation 填 [unsupported:erase] 或 [unsupported:local_beauty]。
        完成后直接在最终回复中给出完整结果，不要调用 finish。只读操作直接做，不要让用户额外确认。
        【重要·收敛规则】拿到数据类工具（search_media / run_gallery_script / get_gallery_summary）的结果后，若用户要看图，可再调一次 draw_chart 把数据画成图（draw_chart 属于渲染，不算数据查询），随后立即用自然语言总结回复、不再调用其它工具。除"画图那次 draw_chart"外，禁止拿到结果后再调任何数据工具。每次请求最多 2 次工具调用（取数 1 次 + draw_chart 1 次）；绝不重复调用同一工具或换参数反复试探。
    """.trimIndent()

    /**
     * 流式自由聊天（chat 远程 ReAct）。占位"正在思考…"期间无增量 token——远程为同步一次性返回
     * （onToken 仅回调一次完整 summary）；多轮记忆由 DataStoreChatMemory 承担。
     */
    suspend fun streamChat(
        input: String,
        agentContext: AgentContext,
        onToken: (String) -> Unit
    ): Result<StreamChatResult> {
        val preference = configurator.getInferencePreference()
        Logger.d(tag, "streamChat: preference=$preference, input='$input'")
        // chat 页统一走远程 ReAct（tool_calls），无论 preference（ADR-005 协议分离）。
        Logger.i(tag, "streamChat routing to Chat ReAct (preference=$preference)")
        return streamChatReAct(input, agentContext, onToken)
    }

    /** chat 远程 ReAct：调 [processChatReAct] 拿 summary，包成 TextReply 命令回 chat。 */
    private suspend fun streamChatReAct(
        input: String,
        agentContext: AgentContext,
        onToken: (String) -> Unit
    ): Result<StreamChatResult> {
        val startTime = System.currentTimeMillis()
        return try {
            processChatReAct(input, agentContext.memorySessionId, traceId = agentContext.traceId).fold(
                onSuccess = { summary ->
                    onToken(summary)
                    val latencyMs = System.currentTimeMillis() - startTime
                    val commands = if (summary.isNotBlank()) {
                        listOf(AgentCommand.TextReply(message = summary))
                    } else {
                        emptyList()
                    }
                    val base = StreamChatResult(
                        fullResponse = summary,
                        metrics = StreamMetrics(latencyMs = latencyMs, promptTokens = null, completionTokens = null)
                    )
                    Result.success(base.copy(commands = commands))
                },
                onFailure = { Result.failure(it) },
            )
        } catch (e: Exception) {
            Logger.e(tag, "streamChatReAct error", e)
            Result.failure(e)
        }
    }

    /**
     * chat 远程推理（ReAct tool_calls 循环）。用 [getChatAgent]（ChatToolService，chat 场域能力工具）
     * 执行多轮 tool 调用，完成后返回自然语言 summary。
     */
    internal suspend fun processChatReAct(
        input: String,
        sessionId: String,
        timeoutMs: Long = 120_000L,
        traceId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        Logger.d(tag, "processChatReAct: input='$input', sessionId='$sessionId', timeout=${timeoutMs}ms")

        val agent = getChatAgent(object : RemoteReActAgentCallback {
            override fun onLoopStart(iteration: Int) {}
            override fun onContent(iteration: Int, content: String) {}
            override fun onToolCall(iteration: Int, toolName: String, args: String) {}
            override fun onToolResult(iteration: Int, toolName: String, result: String) {}
            override fun onComplete(iteration: Int, summary: String, totalTokens: Int, metrics: AgentExecutionMetrics?) {}
            override fun onError(iteration: Int, error: Throwable, totalTokens: Int, metrics: AgentExecutionMetrics?) {}
        }) ?: return@withContext Result.failure(
            IllegalStateException("Chat ReAct Agent 初始化失败")
        )

        if (agent.isRunning()) {
            return@withContext Result.failure(IllegalStateException("Agent 正在执行其他任务"))
        }

        agent.setSessionId(sessionId)

        return@withContext try {
            val job = coroutineContext[kotlinx.coroutines.Job]
            val summary = withTimeout(timeoutMs) {
                suspendCoroutine<String> { continuation ->
                    val callback = object : RemoteReActAgentCallback {
                        override fun onLoopStart(iteration: Int) {
                            Logger.d(tag, "Chat ReAct iteration #$iteration")
                        }
                        override fun onContent(iteration: Int, content: String) {
                            Logger.d(tag, "Chat ReAct content: ${content.take(200)}")
                        }
                        override fun onToolCall(iteration: Int, toolName: String, args: String) {
                            Logger.d(tag, "Chat ReAct toolCall: $toolName(${args.take(100)})")
                        }
                        override fun onToolResult(iteration: Int, toolName: String, result: String) {
                            Logger.d(tag, "Chat ReAct toolResult: $toolName → ${result.take(80)}")
                        }
                        override fun onComplete(iteration: Int, summary: String, totalTokens: Int, metrics: AgentExecutionMetrics?) {
                            Logger.i(tag, "Chat ReAct complete: $iteration rounds, $totalTokens tokens")
                            continuation.resume(summary)
                        }
                        override fun onError(iteration: Int, error: Throwable, totalTokens: Int, metrics: AgentExecutionMetrics?) {
                            Logger.e(tag, "Chat ReAct error: ${error.message}")
                            continuation.resume("出错了：${error.message ?: "未知错误"}")
                        }
                    }
                    job?.invokeOnCompletion { cause ->
                        if (cause != null) {
                            Logger.d(tag, "Chat ReAct coroutine cancelled: ${cause.message}")
                            agent.cancel()
                        }
                    }
                    agent.executeTask(input, callback, traceId)
                    Logger.d(tag, "Chat ReAct executeTask submitted, waiting for callback...")
                }
            }
            Result.success(summary)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Logger.e(tag, "processChatReAct timeout after ${timeoutMs}ms")
            agent.cancel()
            Result.failure(RuntimeException("处理超时（${timeoutMs / 1000}秒），请稍后重试"))
        } catch (e: Exception) {
            Logger.e(tag, "processChatReAct error", e)
            Result.failure(e)
        }
    }

    /**
     * 获取或创建 chat ReAct Agent（ChatToolService，chat 场域能力工具，不含 UI/相机）。
     * 配置变更时自动重建。共享配置经 [configurator] 只读访问。
     */
    private fun getChatAgent(callback: RemoteReActAgentCallback): RemoteReActAgent? {
        val existing = cachedChatAgent
        val currentConfig = configurator.getUserRemoteConfig() ?: RemoteModelConfig.PICME_SERVER_DEFAULT
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
        val memProvider = configurator.getMemoryContextProvider()
        val cfg = try {
            RemoteReActAgentConfig.Builder()
                .apiKey(currentConfig.apiKey)
                .baseUrl(currentConfig.baseUrl)
                .modelName(currentConfig.modelId)
                .gatewayToken(currentConfig.gatewayToken)
                .deviceId(configurator.getDeviceId())
                .systemPrompt(chatSystemPrompt + "\n\n当前日期：${java.time.LocalDate.now()}。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。")
                .apply { if (memProvider != null) memoryContextProvider(memProvider) }
                .build()
        } catch (e: Exception) {
            Logger.w(tag, "Failed to build ChatAgent config", e)
            return null
        }
        val agent = RemoteReActAgent(
            config = cfg,
            windowManager = null,
            callback = callback,
            appContext = configurator.getContext(),
            toolService = ChatToolService.getInstance()
        )
        agent.initialize()
        cachedChatAgent = agent
        cachedChatAgentConfig = currentConfig
        Logger.i(tag, "Chat ReAct Agent created: model=${cfg.modelName}, baseUrl=${currentConfig.baseUrl.take(40)}")
        return agent
    }
}
