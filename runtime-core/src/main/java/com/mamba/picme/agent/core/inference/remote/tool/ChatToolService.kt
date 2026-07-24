package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.tool.P
import com.mamba.tool.Tool
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.future.future
import java.util.concurrent.TimeUnit

/**
 * chat 场景专用 ToolService（远程 ReAct）。
 *
 * 与 [PoLangToolService]（飞书远程控制 RPA：UI 操作 + 相机）区分：本类只暴露 **chat 场景可用**
 * 的能力命令（相册搜索/摘要、打标、AI 修图、反馈、设置、导航、JS 脚本），不含 UI 自动化与
 * 相机控制（相机 Capability 属 CAMERA 场景，chat 场景下 dispatch 不可用）。
 *
 * 每个 @Tool 是 [dispatchCommand] 的薄封装：命令统一进 [CapabilityRegistry]（scene=CHAT），
 * 复用既有 chat Capability（ChatSearchCapability/ChatGallerySummaryCapability/ChatRunScriptCapability 等）。
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

    /** UI 事件流：dispatchCommand 执行后的原始 AgentAction 发到此 flow，ChatViewModel collect 渲染卡片/跳转。 */
    val uiActions = MutableSharedFlow<AgentAction>(extraBufferCapacity = 16)

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

    @Tool(name = "refine_media_search", value = ["在上一轮搜索结果内细化过滤，如'只要夜景'。constraint 为细化条件。"])
    fun refineMediaSearch(
        @P(name = "constraint", value = "细化条件") constraint: String
    ): String = dispatchCommand(AgentCommand.RefineMediaSearch(constraint = constraint))

    @Tool(name = "view_media", value = ["查看指定媒体。media_id 为媒体 URI 或 id，无则留空串。"])
    fun viewMedia(
        @P(name = "media_id", value = "媒体 id/URI，无则空串") mediaId: String
    ): String = dispatchCommand(AgentCommand.ViewMedia(mediaId = mediaId.ifBlank { null }))

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

    @Tool(name = "ai_optimize", value = ["AI 一键优化图片。image_uri 为图片 URI。mode: fast(本地快速)/smart(智能)。"])
    fun aiOptimize(
        @P(name = "image_uri", value = "图片 URI") imageUri: String,
        @P(name = "mode", value = "fast 或 smart") mode: String
    ): String = dispatchCommand(AgentCommand.AiOptimize(imageUri = imageUri, mode = mode))

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
        name = "run_gallery_script",
        value = ["在端侧沙箱执行 JavaScript 做相册盘点/统计分析（只读，数据不出端）。可用 bridge.call： gallery.summary() → 相册聚合统计（totalPhotos/totalVideos/totalMedia/hasFaceCount/personClusterCount/namedPersonCount/labeledCount/unlabeledCount/semanticEncodedCount/remainingPass1/remainingPass3/isScanning/currentPass/recommendation）； gallery.query({label?,ocr?,location?,fromMs?,toMs?,hasFace?,limit?}) → 结构化过滤命中，返回 {ids:[...], total:N}（多维 AND，全可选；ids 已截断到 limit，total 为未截断真实数）； gallery.tags() → 实际打标标签分布 {标签:照片数}（按计数降序 top 50）； gallery.timeline({fromMs?,toMs?,bucketMs?}) → 按时间分桶统计 {\"桶起始时间戳\":照片数}（默认按月，bucketMs=2592000000=月/31536000000=年）； gallery.intersect({idsA:[...],idsB:[...],op:\"intersect|union|diff\"}) → 集合交并差，返回 {ids:[...],total:N}（用于多次 query 结果交叉，如旅行+人脸）； media.meta(id) → 单张元数据 {id,type,captureMs,fileName,labels:[...],locationName,hasFace,faceId}（不含路径/GPS/OCR/向量）； media.batch_meta([id1,id2,...]) → 批量元数据 [{...},...]（上限 50，避免循环调 media.meta）； gallery.stats_by_tag({label?,hasFace?,fromMs?,toMs?}) → 条件过滤后的标签分布（如人像照片内的场景标签）。 在 JS 内组合计算（如某标签占比 = query.total / summary.totalMedia；环比 = 本月/上月-1），return 结果对象回传给你做总结。 示例：var s=bridge.call('gallery.summary'); var t=bridge.call('gallery.tags'); return {total:s.totalMedia, topTags:t};"]
    )
    fun runGalleryScript(
        @P(name = "code", value = "JS 源码；用 bridge.call 取数据（gallery.summary/tags/timeline/query/stats_by_tag, gallery.intersect, media.meta/batch_meta），return 结果对象") code: String
    ): String = dispatchCommand(AgentCommand.ExecuteScript(code = code))

    @Tool(
        name = "draw_chart",
        value = ["画出图表并渲染成真实图片展示给用户——这是展示图表的唯一方式，严禁用文字、Markdown 表格、ASCII/emoji 画图（文字画的图用户看不到效果）。先用 run_gallery_script 拿到数据，再把数据传给本工具画图。"]
    )
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

    @Tool(name = "switch_face_engine", value = ["切换人脸检测引擎。engine: mediapipe/mnn/ncnn/mlkit。"])
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
            "refine_media_search" -> refineMediaSearch(args.optString("constraint", ""))
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
            "ai_optimize" -> aiOptimize(args.optString("image_uri", ""), args.optString("mode", "fast"))
            "adjust_image" -> adjustImage(
                args.optString("image_uri", ""),
                args.optString("brightness", ""),
                args.optString("contrast", ""),
                args.optString("saturation", ""),
                args.optString("temperature", "")
            )
            "run_gallery_script" -> runGalleryScript(args.optString("code", ""))
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

    // ── 内部：命令分发（复用 PoLangToolService.dispatchCommand 范式，scene=CHAT）────

    @OptIn(DelicateCoroutinesApi::class)
    private fun dispatchCommand(command: AgentCommand): String {
        return try {
            val deferred = GlobalScope.future {
                CapabilityRegistry.getInstance()
                    .dispatch(command, AgentContext(scene = AgentScene.CHAT), null)
            }
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
                            else -> "OK"
                        }
                        is AgentAction.Error -> "Error: ${action.message}"
                        else -> "OK: ${action::class.simpleName}"
                    }
                },
                onFailure = { "Error: ${it.message}" },
            )
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
}
