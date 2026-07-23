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
 */
class ChatToolService {

    private val tag = "ChatToolService"

    // ── 相册 ──────────────────────────────────────────────────────

    @Tool(name = "get_gallery_summary", value = ["获取本地相册摘要：照片/视频/媒体总数、含人脸数、人物聚类数、已/未打标数、语义向量数、扫描建议。include_details=true 时附剩余 Pass1/Pass3 任务数。"])
    fun getGallerySummary(
        @P(name = "include_details", value = "是否返回剩余任务数，默认 false") includeDetails: Boolean = false
    ): String = dispatchCommand(AgentCommand.GetGallerySummary(includeDetails = includeDetails))

    @Tool(name = "search_media", value = ["搜索本地相册。query 为自然语言搜索词，如'去年夏天海边的小孩'。返回匹配照片。"])
    fun searchMedia(
        @P(name = "query", value = "自然语言搜索词") query: String
    ): String = dispatchCommand(AgentCommand.SearchMedia(query = query))

    @Tool(name = "refine_media_search", value = ["在上一轮搜索结果内细化过滤，如'只要夜景'。constraint 为细化条件。"])
    fun refineMediaSearch(
        @P(name = "constraint", value = "细化条件") constraint: String
    ): String = dispatchCommand(AgentCommand.RefineMediaSearch(constraint = constraint))

    @Tool(name = "view_media", value = ["查看指定媒体。media_id 为媒体 URI 或 id。"])
    fun viewMedia(
        @P(name = "media_id", value = "媒体 id/URI") mediaId: String = ""
    ): String = dispatchCommand(AgentCommand.ViewMedia(mediaId = mediaId.ifBlank { null }))

    @Tool(name = "delete_media", value = ["删除媒体。media_ids 为 id 列表（逗号分隔或数组）。"])
    fun deleteMedia(
        @P(name = "media_ids", value = "媒体 id 列表，逗号分隔") mediaIds: String = ""
    ): String {
        val ids = mediaIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return dispatchCommand(AgentCommand.DeleteMedia(mediaIds = ids))
    }

    @Tool(name = "share_media", value = ["分享媒体。media_ids 为 id 列表（逗号分隔）。"])
    fun shareMedia(
        @P(name = "media_ids", value = "媒体 id 列表，逗号分隔") mediaIds: String = ""
    ): String {
        val ids = mediaIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return dispatchCommand(AgentCommand.ShareMedia(mediaIds = ids))
    }

    @Tool(name = "select_media", value = ["选择/取消选择媒体。selected 默认 true。"])
    fun selectMedia(
        @P(name = "media_id", value = "媒体 id") mediaId: String,
        @P(name = "selected", value = "true 选中 / false 取消") selected: Boolean = true
    ): String = dispatchCommand(AgentCommand.SelectMedia(mediaId = mediaId, selected = selected))

    @Tool(name = "favorite_media", value = ["收藏/取消收藏媒体。favorite 默认 true。"])
    fun favoriteMedia(
        @P(name = "media_id", value = "媒体 id") mediaId: String,
        @P(name = "favorite", value = "true 收藏 / false 取消") favorite: Boolean = true
    ): String = dispatchCommand(AgentCommand.FavoriteMedia(mediaId = mediaId, favorite = favorite))

    @Tool(name = "switch_view_mode", value = ["切换相册视图。mode: grid(网格)/list(列表) 等。"])
    fun switchViewMode(
        @P(name = "mode", value = "视图模式") mode: String = "grid"
    ): String = dispatchCommand(AgentCommand.SwitchViewMode(mode = mode))

    // ── 反馈 ──────────────────────────────────────────────────────

    @Tool(name = "record_feedback", value = ["记录用户对搜索结果的反馈。action: like/dislike。target: last(上次结果)/ordinal:N(第N张)/desc:描述/mediaId:id。"])
    fun recordFeedback(
        @P(name = "target", value = "反馈目标：last / ordinal:N / desc:文本 / mediaId:id") target: String = "last",
        @P(name = "action", value = "like 或 dislike") action: String = "like"
    ): String = dispatchCommand(
        AgentCommand.RecordMediaFeedback(
            target = parseFeedbackTarget(target),
            action = parseFeedbackAction(action)
        )
    )

    @Tool(name = "more_like_this", value = ["基于指定图片推荐更多相似照片。target 同 record_feedback。"])
    fun moreLikeThis(
        @P(name = "target", value = "目标：last / ordinal:N / desc:文本 / mediaId:id") target: String = "last"
    ): String = dispatchCommand(AgentCommand.MoreLikeThis(target = parseFeedbackTarget(target)))

    @Tool(name = "exclude_constraint", value = ["在后续搜索中排除某类约束，如'不要夜景'。constraint 为排除条件。"])
    fun excludeConstraint(
        @P(name = "constraint", value = "排除条件") constraint: String
    ): String = dispatchCommand(AgentCommand.ExcludeConstraint(constraint = constraint))

    // ── 打标 / 修图 ───────────────────────────────────────────────

    @Tool(name = "start_tag_scan", value = ["启动/查询 TAG 扫描（为人脸/标签/语义建立索引）。action: query(查询状态)/start(启动)。task_type/mode 可选。"])
    fun startTagScan(
        @P(name = "action", value = "query 或 start，默认 query") action: String = "query",
        @P(name = "task_type", value = "可选任务类型") taskType: String = "",
        @P(name = "mode", value = "可选模式") mode: String = ""
    ): String = dispatchCommand(
        AgentCommand.StartTagScan(
            action = action,
            taskType = taskType.ifBlank { null },
            mode = mode.ifBlank { null }
        )
    )

    @Tool(name = "ai_optimize", value = ["AI 一键优化图片。image_uri 为图片 URI。mode: fast(本地快速,默认)/smart(智能)。"])
    fun aiOptimize(
        @P(name = "image_uri", value = "图片 URI") imageUri: String,
        @P(name = "mode", value = "fast 或 smart，默认 fast") mode: String = "fast"
    ): String = dispatchCommand(AgentCommand.AiOptimize(imageUri = imageUri, mode = mode))

    @Tool(
        name = "run_gallery_script",
        value = ["在端侧沙箱执行一段 JavaScript，用于相册盘点/统计分析等需要组合计算的场景（只读，数据不出端）。脚本通过 bridge.call('gallery.summary') 取相册聚合统计（返回对象含 totalPhotos/totalVideos/totalMedia/hasFaceCount/personClusterCount/namedPersonCount/labeledCount/unlabeledCount/semanticEncodedCount/remainingPass1/remainingPass3/isScanning/currentPass/recommendation）。在 JS 内做计算（如打标率=labeledCount/totalMedia、未打标占比），最后 return 一个结果对象——该对象会回传给你做自然语言总结。示例：var s=bridge.call('gallery.summary'); return {total:s.totalMedia, labeledRatio: s.totalMedia>0 ? s.labeledCount/s.totalMedia : 0};"]
    )
    fun runGalleryScript(
        @P(name = "code", value = "JavaScript 源码；用 bridge.call('gallery.summary') 取数据，return 结果对象") code: String
    ): String = dispatchCommand(AgentCommand.ExecuteScript(code = code))

    // ── 设置 ──────────────────────────────────────────────────────

    @Tool(name = "change_theme", value = ["切换主题。theme: system/light/dark。"])
    fun changeTheme(
        @P(name = "theme", value = "system/light/dark") theme: String = "system"
    ): String = dispatchCommand(AgentCommand.ChangeTheme(theme = theme))

    @Tool(name = "change_language", value = ["切换语言。language: zh/en。"])
    fun changeLanguage(
        @P(name = "language", value = "zh 或 en") language: String = "zh"
    ): String = dispatchCommand(AgentCommand.ChangeLanguage(language = language))

    @Tool(name = "toggle_setting", value = ["切换开关型设置。key 为设置键，enabled 为开/关。"])
    fun toggleSetting(
        @P(name = "key", value = "设置键") key: String,
        @P(name = "enabled", value = "true/false") enabled: Boolean = true
    ): String = dispatchCommand(AgentCommand.ToggleSetting(settingKey = key, enabled = enabled))

    @Tool(name = "download_model", value = ["下载模型。model_id 为模型标识。"])
    fun downloadModel(
        @P(name = "model_id", value = "模型 id") modelId: String
    ): String = dispatchCommand(AgentCommand.DownloadModel(modelId = modelId))

    @Tool(name = "switch_face_engine", value = ["切换人脸检测引擎。engine: mediapipe/mnn/ncnn/mlkit。"])
    fun switchFaceEngine(
        @P(name = "engine", value = "引擎名") engine: String = "mlkit"
    ): String = dispatchCommand(AgentCommand.SwitchFaceEngine(engine = engine))

    // ── 导航 / 系统 ───────────────────────────────────────────────

    @Tool(name = "navigate_to", value = ["导航到页面。destination: camera/gallery/settings/debug。"])
    fun navigateTo(
        @P(name = "destination", value = "camera/gallery/settings/debug") destination: String
    ): String = dispatchCommand(AgentCommand.NavigateTo(destination = destination))

    @Tool(name = "go_back", value = ["返回上一页。"])
    fun goBack(): String = dispatchCommand(AgentCommand.GoBack())

    @Tool(name = "launch_app", value = ["打开外部应用。package_name 或 app_name 至少给一个。"])
    fun launchApp(
        @P(name = "package_name", value = "包名，可选") packageName: String = "",
        @P(name = "app_name", value = "应用名，可选") appName: String = ""
    ): String = dispatchCommand(
        AgentCommand.LaunchApp(
            packageName = packageName.ifBlank { null },
            appName = appName.ifBlank { null },
            activityClass = null
        )
    )

    @Tool(name = "open_system_settings", value = ["打开系统设置页。setting: wifi/bluetooth/location 等。"])
    fun openSystemSettings(
        @P(name = "setting", value = "设置项") setting: String = ""
    ): String = dispatchCommand(AgentCommand.OpenSystemSettings(setting = setting))

    // ── 通用 ──────────────────────────────────────────────────────

    @Tool(name = "delay", value = ["等待指定毫秒。delay_ms 1~300000。"])
    fun delay(
        @P(name = "delay_ms", value = "延迟毫秒") delayMs: Long = 1000L
    ): String = dispatchCommand(
        AgentCommand.Delay(delayMs = delayMs.coerceIn(1, 300000))
    )

    @Tool(name = "finish", value = ["任务完成时调用，提供完成摘要给用户。"])
    fun finish(
        @P(name = "summary", value = "给用户的完成摘要") summary: String
    ): String = summary

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
                    when (action) {
                        is AgentAction.TextReply -> action.message
                        is AgentAction.Success -> "OK"
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
