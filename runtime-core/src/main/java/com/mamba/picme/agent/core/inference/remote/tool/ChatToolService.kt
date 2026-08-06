package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.EditParams
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.agent.core.inference.remote.log.TraceIdHolder
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.capability.CommandExecutor
import com.mamba.tool.P
import com.mamba.tool.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.future.future
import java.util.concurrent.TimeUnit

/**
 * chat 场景专用 ToolService（远程 ReAct）。
 *
 * 与 [RemoteControlToolService]（飞书远程控制 RPA：UI 操作 + 相机）区分：本类只暴露 **chat 场景可用**
 * 的能力命令（相册搜索/摘要、打标、AI 修图、反馈、设置、导航、JS 脚本），不含 UI 自动化与
 * 相机控制（相机 Capability 属 CAMERA 场景，chat 场景下 dispatch 不可用）。
 *
 * 每个 @Tool 是 [dispatchCommand] 的薄封装：命令统一进 [CapabilityRegistry]（scene=CHAT），
 * 复用既有 chat Capability（ChatSearchCapability/ChatGallerySummaryCapability/ChatRunScriptCapability 等）。
 *
 * 路由定位见 `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` §2.4（chat ReAct 入口）。与
 * [RemoteControlToolService] 同名但描述不同的工具（如 `run_gallery_script`）是按 agent 故意差异化，
 * 非漂移；逐字节相同的描述（如 `draw_chart`）抽到 [GalleryToolDocs] 共享。
 *
 * **隐私不变式（决策1 / ADR-008）**：本 ToolService 运行在远程 ReAct 链路上，但其 @Tool 执行的
 * 媒体处理（`ai_optimize`/`edit_image`/`adjust_image`/打标/人脸）均在**端侧** renderer/本地模型完成，
 * **图片/视频字节绝不作为多模态输入上传给远程 LLM**；返回给模型的是纯文本 observation
 * （如「图片已优化，结果已展示在聊天中」）。受 `RemoteInferenceNoMediaUploadGuardTest` 守卫保护。
 *
 * **重要**：@Tool 参数**不能用 Kotlin 默认值**——langchain4j 用 Java 反射调用，Kotlin 默认参数会
 * 编译出 DefaultConstructorMarker 合成方法，导致反射"Wrong number of arguments"。所有参数必填，
 * 可选语义用空串/默认值由调用方传入（@P 描述说明）。
 */
class ChatToolService private constructor() {

    companion object {
        @Volatile
        private var instance: ChatToolService? = null
        fun getInstance(): ChatToolService =
            instance ?: synchronized(this) {
                instance ?: ChatToolService().also { instance = it }
            }
    }

    private val tag = "ChatToolService"

    /**
     * dispatch 常驻内部 scope（SupervisorJob 隔离单命令失败）。替代 GlobalScope：
     * 等待超时后通过 deferred.cancel() 级联取消底层 dispatch 协程，避免协程裸跑。
     */
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** UI 事件流：dispatchCommand 执行后的原始 AgentAction 发到此 flow，ChatViewModel collect 渲染卡片/跳转。 */
    val uiActions = MutableSharedFlow<AgentAction>(extraBufferCapacity = 16)

    /**
     * 当轮 traceId 持有器：由 [com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgent]
     * 每轮任务开始时（与 LLM listener 共用同一 holder）写入，dispatchCommand 读取后注入 AgentContext，
     * 使 chat 远程 ReAct 路径下的 tool（含 JS 脚本）执行也带 traceId，与 LLM 调用关联。
     */
    @Volatile
    var traceIdHolder: TraceIdHolder? = null

    /**
     * 指令驱动图片调整 handler（由 ChatViewModel 注入）。
     *
     * 参数：imageUri, brightness(-100~100), contrast(0~200, 默认50), saturation(0~200, 默认100), temperature(2000~8000, 默认5000)
     * 返回：结果描述（成功时含 file:// URI；失败时含错误信息）
     */
    var adjustImageHandler: (suspend (String, Float?, Float?, Float?, Float?) -> String)? = null

    // ── 相册 ──────────────────────────────────────────────────────

    @Tool(name = "get_gallery_summary", value = ["获取本地相册摘要：照片/视频/媒体总数、含人脸数、人物聚类数、已/未打标数、语义向量数、扫描建议。"])
    fun getGallerySummary(): String =
        dispatchCommand(AgentCommand.GetGallerySummary(includeDetails = false))

    @Tool(name = "search_media", value = ["搜索本地相册。query 为自然语言搜索词，如'去年夏天海边的小孩'。返回匹配照片。"])
    fun searchMedia(
        @P(name = "query", value = "自然语言搜索词") query: String
    ): String = dispatchCommand(AgentCommand.SearchMedia(query = query))

    @Tool(name = "refine_media_search", value = ["在上一轮搜索结果内细化过滤，如'只要夜景''找找4月的'。constraint 为细化条件；时间窄化务必传 fromMs/toMs（毫秒，据当前日期算）做精确交集，留空串=不限。"])
    fun refineMediaSearch(
        @P(name = "constraint", value = "细化条件") constraint: String,
        @P(name = "fromMs", value = "时间起点（毫秒），如某月起始；空串=不限") fromMs: String,
        @P(name = "toMs", value = "时间终点（毫秒），如某月末；空串=不限") toMs: String
    ): String {
        val intent = refineTimeIntent(fromMs, toMs)
        return dispatchCommand(AgentCommand.RefineMediaSearch(constraint = constraint, intent = intent))
    }

    @Tool(name = "view_media", value = ["查看指定媒体。media_id 为媒体 URI 或 id，无则留空串。"])
    fun viewMedia(
        @P(name = "media_id", value = "媒体 id/URI，无则空串") mediaId: String
    ): String = dispatchCommand(AgentCommand.ViewMedia(mediaId = mediaId.ifBlank { null }))

    // 写操作确认两层策略·Tier B：顶层 @Tool 直调写操作不经应用内确认（区别于 JS
    // capability.dispatch 的 Tier A——后者经 CapabilityDispatchHandler + WriteConfirmationController
    // 带预览确认）。删除由系统 MediaStore 授权框兜底、ReAct 循环对用户透明。分级 SSOT 见 CommandRisk。
    @Tool(name = "delete_media", value = ["删除媒体。media_ids 为 id 列表逗号分隔，无则空串。"])
    fun deleteMedia(
        @P(name = "media_ids", value = "媒体 id 列表逗号分隔，无则空串") mediaIds: String
    ): String {
        val ids = mediaIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return dispatchCommand(AgentCommand.DeleteMedia(mediaIds = ids))
    }

    @Tool(name = "share_media", value = ["分享媒体。media_ids 为 id 列表逗号分隔，无则空串。"])
    fun shareMedia(
        @P(name = "media_ids", value = "媒体 id 列表逗号分隔，无则空串") mediaIds: String
    ): String {
        val ids = mediaIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return dispatchCommand(AgentCommand.ShareMedia(mediaIds = ids))
    }

    @Tool(name = "select_media", value = ["选择/取消选择媒体。selected 为 true 选中 / false 取消。"])
    fun selectMedia(
        @P(name = "media_id", value = "媒体 id") mediaId: String,
        @P(name = "selected", value = "true 选中 / false 取消") selected: Boolean
    ): String = dispatchCommand(AgentCommand.SelectMedia(mediaId = mediaId, selected = selected))

    @Tool(name = "favorite_media", value = ["收藏/取消收藏媒体。favorite 为 true 收藏 / false 取消。"])
    fun favoriteMedia(
        @P(name = "media_id", value = "媒体 id") mediaId: String,
        @P(name = "favorite", value = "true 收藏 / false 取消") favorite: Boolean
    ): String = dispatchCommand(AgentCommand.FavoriteMedia(mediaId = mediaId, favorite = favorite))

    @Tool(name = "switch_view_mode", value = ["切换相册视图。mode: grid(网格)/list(列表)。"])
    fun switchViewMode(
        @P(name = "mode", value = "视图模式：grid/list") mode: String
    ): String = dispatchCommand(AgentCommand.SwitchViewMode(mode = mode))

    // ── 反馈 ──────────────────────────────────────────────────────

    @Tool(name = "record_feedback", value = ["记录用户对搜索结果的反馈。action: like/dislike。target: last(上次结果)/ordinal:N(第N张)/desc:描述/mediaId:id。"])
    fun recordFeedback(
        @P(name = "target", value = "反馈目标：last / ordinal:N / desc:文本 / mediaId:id") target: String,
        @P(name = "action", value = "like 或 dislike") action: String
    ): String = dispatchCommand(
        AgentCommand.RecordMediaFeedback(
            target = parseFeedbackTarget(target),
            action = parseFeedbackAction(action)
        )
    )

    @Tool(name = "more_like_this", value = ["基于指定图片推荐更多相似照片。target 同 record_feedback。"])
    fun moreLikeThis(
        @P(name = "target", value = "目标：last / ordinal:N / desc:文本 / mediaId:id") target: String
    ): String = dispatchCommand(AgentCommand.MoreLikeThis(target = parseFeedbackTarget(target)))

    @Tool(name = "exclude_constraint", value = ["在后续搜索中排除某类约束，如'不要夜景'。constraint 为排除条件。"])
    fun excludeConstraint(
        @P(name = "constraint", value = "排除条件") constraint: String
    ): String = dispatchCommand(AgentCommand.ExcludeConstraint(constraint = constraint))

    // ── 打标 / 修图 ───────────────────────────────────────────────

    @Tool(name = "start_tag_scan", value = ["查询 TAG 扫描状态（人脸/标签/语义索引进度）。无需参数。"])
    fun startTagScan(): String =
        dispatchCommand(AgentCommand.StartTagScan(action = "query", taskType = null, mode = null))

    @Tool(name = "ai_optimize", value = ["AI 一键优化图片。image_uri 为图片 URI。"])
    fun aiOptimize(
        @P(name = "image_uri", value = "图片 URI") imageUri: String
    ): String = dispatchCommand(AgentCommand.AiOptimize(imageUri = imageUri))

    @Tool(
        name = "adjust_image",
        value = ["按显式参数调整图片亮度/对比度/饱和度/色温，返回调整后的图片。用户说「调亮」「增加对比度」「提高饱和度」等指令时使用。brightness: -100(暗)~100(亮)，0=不变。contrast: 0~200，50=默认。saturation: 0~200，100=默认。temperature: 2000(冷蓝)~8000(暖黄)，5000=默认。未指定的参数留空串表示不调整。"]
    )
    fun adjustImage(
        @P(name = "image_uri", value = "图片 URI") imageUri: String,
        @P(name = "brightness", value = "亮度 -100~100，0=不变，留空=不调") brightness: String,
        @P(name = "contrast", value = "对比度 0~200，50=默认，留空=不调") contrast: String,
        @P(name = "saturation", value = "饱和度 0~200，100=默认，留空=不调") saturation: String,
        @P(name = "temperature", value = "色温 2000(冷)~8000(暖)，5000=默认，留空=不调") temperature: String
    ): String {
        val handler = adjustImageHandler ?: return "Error: 图片调整暂不可用"
        val b = brightness.toFloatOrNull()
        val c = contrast.toFloatOrNull()
        val s = saturation.toFloatOrNull()
        val t = temperature.toFloatOrNull()
        return kotlinx.coroutines.runBlocking {
            handler.invoke(imageUri, b, c, s, t)
        }
    }

    @Tool(
        name = "edit_image",
        value = ["对话式图片编辑（美颜/滤镜/调色），后台完成并把结果图发到聊天中，**绝不跳转到编辑页**。用户说「磨皮 30」「瘦脸」「美白一点」「换胶片风」「再亮一点」等编辑意图时使用（含 adjust_image 覆盖不了的场景）。edits 为 JSON 字符串，字段（均可选，只传要改的）：smoothing/whitening/big_eyes/lip_color/blush/eyebrow（美颜 0~100）、slim_face（-50~50）、brightness/exposure（-100~100）、contrast/saturation（0~200，100 为原图）、temperature（色温开尔文 2000~8000，5000 为原图，越大越暖）、tint（-100~100）、filter_name（滤镜枚举，如 FILM_GOLD/COOL）、filter_intensity（0~100）、style_name（风格枚举）。**模糊/相对调整（「美白一点」「再亮一点」）必须用 *_delta 字段且幅度要小**：美颜 ±10 以内、亮度/曝光 ±15 以内、对比度/饱和度 ±15 以内、色温 ±500 以内、tint ±15 以内、slim_face ±5 以内（超出会被截断）；如 {\"brightness_delta\":10} 表示再亮一点。只有用户明确给出数值（「磨皮 50」）才用绝对值字段。不支持的编辑（消除物体/局部美颜）不要编造参数，在 explanation 返回 [unsupported:erase] 或 [unsupported:local_beauty]。"]
    )
    fun editImage(
        @P(name = "image_uri", value = "目标图片 URI，留空串表示用最近发送的图片") imageUri: String,
        @P(name = "edits", value = "编辑参数 JSON 字符串，如 {\"smoothing\":30,\"filter_name\":\"FILM_GOLD\",\"filter_intensity\":70}") edits: String,
        @P(name = "explanation", value = "给用户的一句话说明；未支持请求填 [unsupported:erase] 或 [unsupported:local_beauty]，无则留空串") explanation: String
    ): String {
        val params = try {
            EditParams.fromJson(org.json.JSONObject(edits.ifBlank { "{}" }))
        } catch (e: Exception) {
            return "Error: edits JSON 解析失败: ${e.message}"
        }
        return dispatchCommand(
            AgentCommand.EditImage(
                params = params,
                imageUri = imageUri,
                explanation = explanation.ifBlank { null }
            )
        )
    }

    @Tool(
        name = "run_gallery_script",
        value = ["在端侧沙箱执行 JavaScript 做相册盘点/统计分析（取数类 handler 只读、数据不出端；删除/收藏等写操作走 capability.dispatch，会弹窗经用户确认）。所有 handler 均为异步，**必须用 await bridge.callAsync(name, args) 调用**（bridge.call 已禁用，调用会报错）。可用 handler： gallery.summary → 相册聚合统计（totalPhotos/totalVideos/totalMedia/hasFaceCount/personClusterCount/namedPersonCount/labeledCount/unlabeledCount/semanticEncodedCount/remainingPass1/remainingPass3/isScanning/currentPass/recommendation）； gallery.query({label?,ocr?,location?,fromMs?,toMs?,hasFace?,person?,limit?}) → 结构化过滤命中，返回 {ids:[...], total:N}（多维 AND，全可选；ids 已截断到 limit，total 为未截断真实数）； gallery.tags → 实际打标标签分布 {标签:照片数}（按计数降序 top 50）； gallery.timeline({fromMs?,toMs?,bucketMs?}) → 按时间分桶统计 {\"桶起始时间戳\":照片数}（默认按月，bucketMs=2592000000=月/31536000000=年）； gallery.intersect({idsA:[...],idsB:[...],op:\"intersect|union|diff\"}) → 集合交并差，返回 {ids:[...],total:N}（用于多次 query 结果交叉，如旅行+人脸）； media.meta(id) → 单张元数据 {id,type,captureMs,fileName,labels:[...],locationName,city,hasFace,faceId,aestheticScore,faceQualityScore}（不含路径/GPS/OCR/向量）； media.batch_meta([id1,id2,...]) → 批量元数据 [{...},...]（上限 50，避免循环调 media.meta）； gallery.stats_by_tag({label?,hasFace?,fromMs?,toMs?}) → 条件过滤后的标签分布（如人像照片内的场景标签）； face.cluster({topN?}) → 人脸聚类盘点 {clusterCount,namedCount,totalEmbeddings,unassignedEmbeddings,topPersons:[{personId,name,faceCount,coverMediaId}]}（topN 默认 10 上限 50，不含 embedding 原始数据）； tag.audit({topN?}) → 打标覆盖审计 {totalMedia,unlabeledCount,neverScannedCount,lastScanAt,outOfVocabTags:{标签:照片数}}（词表外标签 topN 默认 10 上限 50）； gallery.stats_by_city({topN?}) → 按城市分组的媒体计数分布 {城市:照片数}（topN 默认 10 上限 50）； tag.scan_status({}) → TAG 扫描会话状态快照 {active,state,sessionId,currentPass,processed,total,pending,failed,estimatedRemainingMs}（只读查询，绝不触发扫描；无会话时仅回 {active:false,state:null}）。 可并发取数：var r=await Promise.all([bridge.callAsync('gallery.summary',{}),bridge.callAsync('gallery.tags',{})]); var s=r[0],t=r[1]; 在 JS 内组合计算（如某标签占比 = query.total / summary.totalMedia；环比 = 本月/上月-1），return 结果对象回传给你做总结。 示例：var s=await bridge.callAsync('gallery.summary',{}); var t=await bridge.callAsync('gallery.tags',{}); return {total:s.totalMedia, topTags:t}; 写操作（删除/收藏/选中）：用 await bridge.callAsync('capability.dispatch',{method,params})，写操作会在端侧弹窗等用户确认（拒绝或超时 Promise 会 reject，必须 try/catch）。支持的 method： delete_media {ids:[数字id,...]}（删除，不可恢复）、favorite_media {id:数字id, favorite:true/false}、select_media {id:数字id, selected:true/false}、remember_fact {content:文本, category?:文本}、forget_fact {fact_id?:数字id, query?:文本}、remember_person_relation {name:人物名, relation:关系称谓}、forget_person_relation {name:人物名}、get_gallery_summary {}、recall_memory {query:文本}、query_person_relation {name?:人物名}（后三者只读直通）；其余 method 会报错。 完整示例（找出截图标签照片并批量删除）：var q=await bridge.callAsync('gallery.query',{label:'截图',limit:200}); if(q.ids.length===0){return {deleted:0};} try{var r=await bridge.callAsync('capability.dispatch',{method:'delete_media',params:{ids:q.ids}}); return {deleted:q.total, result:r};}catch(e){return {deleted:0, cancelled:true, reason:String(e)};}"]
    )
    fun runGalleryScript(
        @P(name = "code", value = "JS 源码；用 await bridge.callAsync 取数据（gallery.summary/tags/timeline/query/stats_by_tag/stats_by_city/intersect, media.meta/batch_meta, face.cluster, tag.audit, tag.scan_status）；写操作（删除/收藏/选中/记忆）用 await bridge.callAsync('capability.dispatch',{method,params})（会弹窗等用户确认，需 try/catch 处理拒绝），return 结果对象") code: String
    ): String = dispatchCommand(AgentCommand.ExecuteScript(code = code))

    @Tool(name = "draw_chart", value = [GalleryToolDocs.DRAW_CHART])
    fun drawChart(
        @P(name = "type", value = "图表类型：bar(柱状)/line(折线)/pie(饼图)") type: String,
        @P(name = "title", value = "图表标题") title: String,
        @P(name = "labels", value = "分类/x 轴标签，英文逗号分隔，如 '1月,2月,3月' 或 '人像,风景,美食'") labels: String,
        @P(name = "values", value = "每个标签对应的数值，英文逗号分隔，与 labels 等长，如 '12,8,21'") values: String,
        @P(name = "unit", value = "数值单位，如 '张'；无则空串") unit: String
    ): String {
        val labelList = labels.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val valueList = values.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        return dispatchCommand(
            AgentCommand.DrawChart(
                type = type,
                title = title,
                labels = labelList,
                values = valueList,
                unit = unit.ifBlank { null }
            )
        )
    }

    // ── 记忆（人物关系 + 事实） ─────────────────────────────────────

    @Tool(
        name = "remember_person_relation",
        value = ["记住人物与「我」的关系，如用户说「小宝是我女儿」「大宝是我发小」。name 为已命名人物名（须已在相册人物分组命名，未命名会返回引导提示），relation 为关系谓词（spouse/partner/son/daughter/child/father/mother/parent/elder_brother/elder_sister/younger_brother/younger_sister/sibling/grandfather/grandmother/grandparent/other_family/friend/classmate/colleague/other）、中文称谓（女儿/老公/对象等）或任意自定义称呼原话（发小/闺蜜等，按原话记住）。重复声明自动覆盖旧关系。"]
    )
    fun rememberPersonRelation(
        @P(name = "name", value = "已命名人物名") name: String,
        @P(name = "relation", value = "关系谓词或中文称谓") relation: String
    ): String = dispatchCommand(AgentCommand.RememberPersonRelation(name = name, relation = relation))

    @Tool(
        name = "forget_person_relation",
        value = ["忘记与某人物的全部关系，如用户说「忘掉小宝的关系」。name 为人物名。"]
    )
    fun forgetPersonRelation(
        @P(name = "name", value = "人物名") name: String
    ): String = dispatchCommand(AgentCommand.ForgetPersonRelation(name = name))

    @Tool(
        name = "list_person_relations",
        value = ["查询已记住的人物关系。用户问「看一下我的人物关系」「我女儿是谁」「小宝和我什么关系」「我记住了哪些关系」「谁是我的家人」时调用本工具，不要凭印象回答。name 留空返回全部指向「我」的关系；指定人物名只查该人物。返回关系列表（含自定义称呼）。"]
    )
    fun listPersonRelations(
        @P(name = "name", value = "人物名，指定则只查该人物与「我」的关系；留空查全部") name: String
    ): String = dispatchCommand(AgentCommand.QueryPersonRelation(name = name.ifBlank { null }))

    @Tool(
        name = "remember_fact",
        value = ["记住一条事实，如用户说「帮我记住小宝对花粉过敏」「记住我喜欢低饱和度滤镜」。content 为原子化事实内容（一条一个事实），category 为可选分类（如 健康/偏好），无则空串。"]
    )
    fun rememberFact(
        @P(name = "content", value = "事实内容") content: String,
        @P(name = "category", value = "可选分类，无则空串") category: String
    ): String = dispatchCommand(
        AgentCommand.RememberFact(
            content = content,
            category = category.ifBlank { null },
            source = "CHAT_TOOL"
        )
    )

    @Tool(
        name = "forget_fact",
        value = ["忘记一条事实，如用户说「忘掉花粉过敏那条」。fact_id 优先（先用 recall_memory 拿到），无则空串；query 为内容模糊匹配（恰好一条才删，多条返回候选）。"]
    )
    fun forgetFact(
        @P(name = "fact_id", value = "事实 id，无则空串") factId: String,
        @P(name = "query", value = "内容模糊匹配，无则空串") query: String
    ): String = dispatchCommand(
        AgentCommand.ForgetFact(
            factId = factId.toLongOrNull(),
            query = query.ifBlank { null }
        )
    )

    @Tool(
        name = "recall_memory",
        value = ["system prompt【关于用户】段已含常见记忆，优先直接引用；本工具仅在该段未列全（被截断）或需拿 factId 删除时调用。query 为模糊匹配关键词，空串返回全部。返回列表含 factId，供 forget_fact 精确删除。"]
    )
    fun recallMemory(
        @P(name = "query", value = "模糊匹配关键词，空串返回全部") query: String
    ): String = dispatchCommand(AgentCommand.RecallMemory(query = query))

    // ── 设置 ──────────────────────────────────────────────────────

    @Tool(name = "change_theme", value = ["切换主题。theme: system/light/dark。"])
    fun changeTheme(
        @P(name = "theme", value = "system/light/dark") theme: String
    ): String = dispatchCommand(AgentCommand.ChangeTheme(theme = theme))

    @Tool(name = "change_language", value = ["切换语言。language: zh/en。"])
    fun changeLanguage(
        @P(name = "language", value = "zh 或 en") language: String
    ): String = dispatchCommand(AgentCommand.ChangeLanguage(language = language))

    @Tool(name = "toggle_setting", value = ["切换开关型设置。key 为设置键，enabled 为开/关。"])
    fun toggleSetting(
        @P(name = "key", value = "设置键") key: String,
        @P(name = "enabled", value = "true/false") enabled: Boolean
    ): String = dispatchCommand(AgentCommand.ToggleSetting(settingKey = key, enabled = enabled))

    @Tool(name = "download_model", value = ["下载模型。model_id 为模型标识。"])
    fun downloadModel(
        @P(name = "model_id", value = "模型 id") modelId: String
    ): String = dispatchCommand(AgentCommand.DownloadModel(modelId = modelId))

    @Tool(name = "switch_face_engine", value = ["切换人脸检测引擎。engine: mediapipe/mnn/mlkit。"])
    fun switchFaceEngine(
        @P(name = "engine", value = "引擎名") engine: String
    ): String = dispatchCommand(AgentCommand.SwitchFaceEngine(engine = engine))

    // ── 导航 / 系统 ───────────────────────────────────────────────

    @Tool(name = "navigate_to", value = ["导航到页面。destination: camera/gallery/settings/debug。"])
    fun navigateTo(
        @P(name = "destination", value = "camera/gallery/settings/debug") destination: String
    ): String = dispatchCommand(AgentCommand.NavigateTo(destination = destination))

    @Tool(name = "go_back", value = ["返回上一页。"])
    fun goBack(): String = dispatchCommand(AgentCommand.GoBack())

    @Tool(name = "launch_app", value = ["打开外部应用。package_name 或 app_name 至少给一个，另一个空串。"])
    fun launchApp(
        @P(name = "package_name", value = "包名，无则空串") packageName: String,
        @P(name = "app_name", value = "应用名，无则空串") appName: String
    ): String = dispatchCommand(
        AgentCommand.LaunchApp(
            packageName = packageName.ifBlank { null },
            appName = appName.ifBlank { null },
            activityClass = null
        )
    )

    @Tool(name = "open_system_settings", value = ["打开系统设置页。setting: wifi/bluetooth/location 等。"])
    fun openSystemSettings(
        @P(name = "setting", value = "设置项") setting: String
    ): String = dispatchCommand(AgentCommand.OpenSystemSettings(setting = setting))

    // ── 通用 ──────────────────────────────────────────────────────

    @Tool(name = "delay", value = ["等待指定毫秒。delay_ms 1~300000。"])
    fun delay(
        @P(name = "delay_ms", value = "延迟毫秒") delayMs: Long
    ): String = dispatchCommand(
        AgentCommand.Delay(delayMs = delayMs.coerceIn(1, 300000))
    )

    @Tool(name = "finish", value = ["任务完成时调用，提供完成摘要给用户。"])
    fun finish(
        @P(name = "summary", value = "给用户的完成摘要") summary: String
    ): String = summary

    /**
     * langchain4j AiServices 约定的统一工具入口（解析 argsJson + 分发到 @Tool 方法）。
     * AiServices.tryInvokeTool 优先调 callTool(toolName, argsJson)；无此方法才 fallback
     * 到 tryInvokeByMethodName（不支持带参 → got 0）。故必须实现 callTool。
     */
    fun callTool(toolName: String, argsJson: String): String {
        val args = try {
            org.json.JSONObject(argsJson)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        return when (toolName) {
            "get_gallery_summary" -> getGallerySummary()
            "search_media" -> searchMedia(args.optString("query", ""))
            "refine_media_search" -> refineMediaSearch(
                args.optString("constraint", ""),
                args.optString("fromMs", ""),
                args.optString("toMs", ""),
            )
            "view_media" -> viewMedia(args.optString("media_id", ""))
            "delete_media" -> deleteMedia(args.optString("media_ids", ""))
            "share_media" -> shareMedia(args.optString("media_ids", ""))
            "select_media" -> selectMedia(args.optString("media_id", ""), args.optBoolean("selected", true))
            "favorite_media" -> favoriteMedia(args.optString("media_id", ""), args.optBoolean("favorite", true))
            "switch_view_mode" -> switchViewMode(args.optString("mode", "grid"))
            "record_feedback" -> recordFeedback(args.optString("target", "last"), args.optString("action", "like"))
            "more_like_this" -> moreLikeThis(args.optString("target", "last"))
            "exclude_constraint" -> excludeConstraint(args.optString("constraint", ""))
            "start_tag_scan" -> startTagScan()
            "remember_person_relation" -> rememberPersonRelation(
                args.optString("name", ""),
                args.optString("relation", "")
            )
            "forget_person_relation" -> forgetPersonRelation(args.optString("name", ""))
            "list_person_relations" -> listPersonRelations(args.optString("name", ""))
            "remember_fact" -> rememberFact(
                args.optString("content", ""),
                args.optString("category", "")
            )
            "forget_fact" -> forgetFact(
                args.optString("fact_id", ""),
                args.optString("query", "")
            )
            "recall_memory" -> recallMemory(args.optString("query", ""))
            "ai_optimize" -> aiOptimize(args.optString("image_uri", ""))
            "adjust_image" -> adjustImage(
                args.optString("image_uri", ""),
                args.optString("brightness", ""),
                args.optString("contrast", ""),
                args.optString("saturation", ""),
                args.optString("temperature", "")
            )
            "run_gallery_script" -> runGalleryScript(args.optString("code", ""))
            "edit_image" -> editImage(
                args.optString("image_uri", ""),
                args.optString("edits", ""),
                args.optString("explanation", "")
            )
            "draw_chart" -> drawChart(
                type = args.optString("type", "bar"),
                title = args.optString("title", ""),
                labels = args.optString("labels", ""),
                values = args.optString("values", ""),
                unit = args.optString("unit", "")
            )
            "change_theme" -> changeTheme(args.optString("theme", "system"))
            "change_language" -> changeLanguage(args.optString("language", "zh"))
            "toggle_setting" -> toggleSetting(args.optString("key", ""), args.optBoolean("enabled", true))
            "download_model" -> downloadModel(args.optString("model_id", ""))
            "switch_face_engine" -> switchFaceEngine(args.optString("engine", "mlkit"))
            "navigate_to" -> navigateTo(args.optString("destination", ""))
            "go_back" -> goBack()
            "launch_app" -> launchApp(args.optString("package_name", ""), args.optString("app_name", ""))
            "open_system_settings" -> openSystemSettings(args.optString("setting", ""))
            "delay" -> delay(args.optLong("delay_ms", 1000))
            "finish" -> finish(args.optString("summary", "任务完成"))
            else -> "Error: Unknown tool: $toolName"
        }
    }

    // ── 内部：命令分发（复用 RemoteControlToolService.dispatchCommand 范式，scene=CHAT）────

    private fun dispatchCommand(command: AgentCommand): String {
        val deferred = dispatchScope.future {
            CapabilityRegistry.getInstance()
                .dispatch(command, AgentContext(scene = AgentScene.CHAT, traceId = traceIdHolder?.value), null)
        }
        return try {
            val result = deferred.get(5, TimeUnit.SECONDS)
            result.fold(
                onSuccess = { action ->
                    // UI 通道：把原始 AgentAction 发给 ChatViewModel 渲染（卡片/跳转等）
                    uiActions.tryEmit(action)
                    // LLM observation：基于真实执行结果生成（而非 "OK"）
                    when (action) {
                        is AgentAction.MediaResults ->
                            "找到 ${action.totalCount} 张「${action.query}」的照片，已展示在卡片中"
                        is AgentAction.TextReply -> action.message
                        is AgentAction.Success -> when (action.command) {
                            is AgentCommand.AiOptimize -> "图片已优化，结果已展示在聊天中"
                            is AgentCommand.EditImage -> "图片已编辑完成，结果图已发到聊天中"
                            else -> "OK"
                        }
                        is AgentAction.Error -> "Error: ${action.message}"
                        else -> "OK: ${action::class.simpleName}"
                    }
                },
                onFailure = { "Error: ${it.message}" },
            )
        } catch (e: java.util.concurrent.TimeoutException) {
            // 等待 dispatch 5s 超时：取消底层 dispatch 协程，避免超时后协程裸跑
            // （CompletableFuture.cancel 会级联取消 future 协程）。
            deferred.cancel(true)
            // 记调用方视角的等待超时，二者可经 traceId 关联。
            Logger.w(tag, "dispatchCommand wait timed out: ${command::class.simpleName}")
            CommandExecutor.recordDispatchEvent(
                capability = "(chat_tool)",
                commandType = AgentCommand.getMethodName(command),
                success = false,
                errorCode = CommandExecutor.ERROR_CODE_TIMEOUT,
                errorMessage = "dispatch wait timed out after 5s",
                traceId = traceIdHolder?.value
            )
            "Error: ${e.message}"
        } catch (e: Exception) {
            Logger.w(tag, "dispatchCommand failed: ${command::class.simpleName}: ${e.message}")
            "Error: ${e.message}"
        }
    }

    private fun parseFeedbackTarget(target: String): FeedbackTarget = when {
        target == "last" -> FeedbackTarget.LastShown
        target.startsWith("ordinal:") ->
            runCatching { FeedbackTarget.Ordinal(target.removePrefix("ordinal:").toInt()) }
                .getOrDefault(FeedbackTarget.LastShown)
        target.startsWith("desc:") -> FeedbackTarget.Description(target.removePrefix("desc:"))
        target.startsWith("mediaId:") -> FeedbackTarget.MediaId(target.removePrefix("mediaId:"))
        else -> FeedbackTarget.Description(target)
    }

    private fun parseFeedbackAction(action: String): FeedbackAction =
        runCatching { FeedbackAction.valueOf(action.trim().uppercase()) }
            .getOrDefault(FeedbackAction.LIKE)

    /**
     * 把 refine_media_search 的 fromMs/toMs 解析成 [SearchIntent]（timeRange）。
     * 二者都空 → null（走 onRefineMediaSearch 的字符串路径）；任一非空 → 结构化时间，走精确交集。
     */
    private fun refineTimeIntent(fromMs: String, toMs: String): SearchIntent? {
        val start = fromMs.trim().toLongOrNull()
        val end = toMs.trim().toLongOrNull()
        if (start == null && end == null) return null
        return SearchIntent(
            query = "",
            timeRange = TimeRange(startMs = start ?: 0L, endMs = end ?: Long.MAX_VALUE),
        )
    }
}
