package com.mamba.picme.agent.core.inference.remote.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.tool.ToolExecutionRequest
import org.json.JSONObject

/**
 * Tool Calls 命令解析器（远程推理专用）
 *
 * 将标准 OpenAI tool_calls 格式的 [ToolExecutionRequest] 直接解析为 [AgentCommand]。
 * 不经过 method/params 中间格式，与本地 LLM 的 [LocalCommandParser] 完全隔离。
 *
 * 解析逻辑：
 * - tool_calls[i].function.name → 命令类型映射
 * - tool_calls[i].function.arguments → 命令参数（标准 JSON 对象）
 *
 * 注意：tool_calls 是 assistant message 的独立字段，与 content 互斥。
 * 当存在 tool_calls 时，content 必须为 null。
 *
 * 例如：
 * ```
 * {"name":"switch_filter","arguments":"{\"filter\":\"WARM\"}"}
 * → AgentCommand.SwitchFilter(filterType = FilterType.WARM)
 * ```
 */
object ToolCallCommandParser {

    private const val TAG = "ToolCallCommandParser"

    /**
     * 将单个 ToolExecutionRequest 解析为 AgentCommand
     *
     * @param request 工具执行请求（来自 OpenAI tool_calls）
     * @param context 当前 Agent 上下文（用于默认值）
     * @return 解析后的 AgentCommand
     */
    fun parse(request: ToolExecutionRequest, context: AgentContext): AgentCommand {
        Logger.d(TAG, "Parsing tool call: name=${request.name()}, arguments=${request.arguments()}")

        val args = try {
            JSONObject(request.arguments())
        } catch (e: Exception) {
            Logger.w(TAG, "Invalid arguments JSON for ${request.name()}: ${request.arguments()}")
            JSONObject()
        }

        return when (request.name()) {
            "adjust_beauty" -> parseAdjustBeauty(args, context)
            "switch_filter" -> parseSwitchFilter(args)
            "switch_style" -> parseSwitchStyle(args)
            "switch_scene" -> parseSwitchScene(args)
            "switch_ratio" -> parseSwitchRatio(args)
            "adjust_exposure" -> parseAdjustExposure(args)
            "adjust_zoom" -> parseAdjustZoom(args)
            "flip_camera" -> AgentCommand.FlipCamera()
            "capture" -> AgentCommand.CapturePhoto()
            "toggle_recording" -> AgentCommand.ToggleRecording()
            "delay" -> parseDelay(args)
            "switch_mode" -> parseSwitchMode(args)
            "navigate_to" -> parseNavigateTo(args)
            "go_back" -> AgentCommand.GoBack()
            "ai_optimize" -> parseAiOptimize(args)
            "get_gallery_summary" -> parseGetGallerySummary(args)
            "text_reply" -> parseTextReply(args)
            "launch_app" -> parseLaunchApp(args)
            "open_system_settings" -> parseOpenSystemSettings(args)
            // Gallery 命令
            "view_media" -> parseViewMedia(args)
            "delete_media" -> parseDeleteMedia(args)
            "share_media" -> parseShareMedia(args)
            "select_media" -> parseSelectMedia(args)
            "search_media" -> parseSearchMedia(args)
            "refine_media_search" -> parseRefineMediaSearch(args)
            "feedback" -> parseRecordMediaFeedback(args)
            "more" -> parseMoreLikeThis(args)
            "exclude" -> parseExcludeConstraint(args)
            "switch_view_mode" -> parseSwitchViewMode(args)
            "favorite_media" -> parseFavoriteMedia(args)
            // 设置命令
            "change_theme" -> parseChangeTheme(args)
            "change_language" -> parseChangeLanguage(args)
            "download_model" -> parseDownloadModel(args)
            "switch_face_engine" -> parseSwitchFaceEngine(args)
            "toggle_setting" -> parseToggleSetting(args)
            else -> {
                Logger.w(TAG, "Unknown tool name: ${request.name()}, treating as text reply")
                AgentCommand.TextReply(message = "未知命令: ${request.name()}")
            }
        }
    }

    /**
     * 批量解析 ToolExecutionRequest 列表
     */
    fun parseAll(requests: List<ToolExecutionRequest>, context: AgentContext): List<AgentCommand> {
        return requests.mapNotNull { req ->
            try {
                parse(req, context)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to parse tool call: ${req.name()}", e)
                null
            }
        }
    }

    // ==================== 相机命令解析 ====================

    private fun parseAdjustBeauty(args: JSONObject, context: AgentContext): AgentCommand.AdjustBeauty {
        val settings = context.beautySettings.copy(
            enabled = true,
            smoothing = args.optDouble("smoothing", context.beautySettings.smoothing.toDouble()).toFloat(),
            whitening = args.optDouble("whitening", context.beautySettings.whitening.toDouble()).toFloat(),
            slimFace = args.optDouble("slim_face", context.beautySettings.slimFace.toDouble()).toFloat(),
            bigEyes = args.optDouble("big_eyes", context.beautySettings.bigEyes.toDouble()).toFloat(),
            lipColor = args.optDouble("lip_color", context.beautySettings.lipColor.toDouble()).toFloat(),
            blush = args.optDouble("blush", context.beautySettings.blush.toDouble()).toFloat(),
            eyebrow = args.optDouble("eyebrow", context.beautySettings.eyebrow.toDouble()).toFloat()
        )
        return AgentCommand.AdjustBeauty(settings = settings)
    }

    private fun parseSwitchFilter(args: JSONObject): AgentCommand.SwitchFilter {
        val filterName = args.optString("filter", "NONE")
        return AgentCommand.SwitchFilter(filterType = resolveFilterType(filterName))
    }

    private fun parseSwitchStyle(args: JSONObject): AgentCommand.SwitchStyle {
        val styleName = args.optString("style", "NONE")
        return AgentCommand.SwitchStyle(styleFilter = resolveStyleFilter(styleName))
    }

    private fun parseSwitchScene(args: JSONObject): AgentCommand.SwitchScene {
        val scene = args.optString("scene", "none")
        return AgentCommand.SwitchScene(sceneName = scene)
    }

    private fun parseSwitchRatio(args: JSONObject): AgentCommand.SwitchRatio {
        val ratio = args.optString("ratio", "full")
        return AgentCommand.SwitchRatio(ratio = ratio)
    }

    private fun parseAdjustExposure(args: JSONObject): AgentCommand.AdjustExposure {
        val exposure = args.optInt("exposure", 0)
        return AgentCommand.AdjustExposure(exposure = exposure.coerceIn(-2, 2))
    }

    private fun parseAdjustZoom(args: JSONObject): AgentCommand.AdjustZoom {
        val zoom = args.optDouble("zoom", 1.0).toFloat()
        return AgentCommand.AdjustZoom(zoomRatio = zoom.coerceAtLeast(0.5f))
    }

    private fun parseDelay(args: JSONObject): AgentCommand.Delay {
        val delayMs = args.optLong("delay_ms", 3000)
        return AgentCommand.Delay(delayMs = delayMs.coerceIn(1, 300000))
    }

    private fun parseSwitchMode(args: JSONObject): AgentCommand.SwitchMode {
        val modeName = args.optString("mode", "PHOTO")
        val mode = runCatching { MediaType.valueOf(modeName) }.getOrDefault(MediaType.PHOTO)
        return AgentCommand.SwitchMode(mode = mode)
    }

    // ==================== 导航命令解析 ====================

    private fun parseNavigateTo(args: JSONObject): AgentCommand.NavigateTo {
        val destination = args.optString("destination", "")
        return AgentCommand.NavigateTo(destination = destination)
    }

    private fun parseAiOptimize(args: JSONObject): AgentCommand.AiOptimize {
        val imageUri = args.optString("image_uri", "")
        val mode = args.optString("mode", "fast")
        return AgentCommand.AiOptimize(imageUri = imageUri, mode = mode)
    }

    private fun parseGetGallerySummary(args: JSONObject): AgentCommand.GetGallerySummary {
        return AgentCommand.GetGallerySummary(
            includeDetails = args.optBoolean("include_details", false)
        )
    }

    private fun parseTextReply(args: JSONObject): AgentCommand.TextReply {
        val message = args.optString("message", "收到")
        return AgentCommand.TextReply(message = message)
    }

    // ==================== 系统/外部 App 命令解析 ====================

    private fun parseLaunchApp(args: JSONObject): AgentCommand.LaunchApp {
        val packageName = args.optString("package_name", "").takeIf { it.isNotEmpty() }
        val appName = args.optString("app_name", "").takeIf { it.isNotEmpty() }
        val activityClass = args.optString("activity_class", "").takeIf { it.isNotEmpty() }
        return AgentCommand.LaunchApp(
            packageName = packageName,
            appName = appName,
            activityClass = activityClass
        )
    }

    private fun parseOpenSystemSettings(args: JSONObject): AgentCommand.OpenSystemSettings {
        val setting = args.optString("setting", "")
        return AgentCommand.OpenSystemSettings(setting = setting)
    }

    // ==================== Gallery 命令解析 ====================

    private fun parseViewMedia(args: JSONObject): AgentCommand.ViewMedia {
        val mediaId = args.optString("media_id", "").takeIf { it.isNotEmpty() }
        return AgentCommand.ViewMedia(mediaId = mediaId)
    }

    private fun parseDeleteMedia(args: JSONObject): AgentCommand.DeleteMedia {
        val mediaIds = args.optJSONArray("media_ids")?.let { arr ->
            List(arr.length()) { arr.optString(it, "") }.filter { it.isNotEmpty() }
        } ?: emptyList()
        return AgentCommand.DeleteMedia(mediaIds = mediaIds)
    }

    private fun parseShareMedia(args: JSONObject): AgentCommand.ShareMedia {
        val mediaIds = args.optJSONArray("media_ids")?.let { arr ->
            List(arr.length()) { arr.optString(it, "") }.filter { it.isNotEmpty() }
        } ?: emptyList()
        return AgentCommand.ShareMedia(mediaIds = mediaIds)
    }

    private fun parseSelectMedia(args: JSONObject): AgentCommand.SelectMedia {
        return AgentCommand.SelectMedia(
            mediaId = args.optString("media_id", ""),
            selected = args.optBoolean("selected", true)
        )
    }

    private fun parseSearchMedia(args: JSONObject): AgentCommand.SearchMedia {
        return AgentCommand.SearchMedia(query = args.optString("query", ""))
    }

    private fun parseRefineMediaSearch(args: JSONObject): AgentCommand.RefineMediaSearch {
        return AgentCommand.RefineMediaSearch(
            constraint = args.optString("constraint", args.optString("query", ""))
        )
    }

    private fun parseRecordMediaFeedback(args: JSONObject): AgentCommand.RecordMediaFeedback {
        val targetStr = args.optString("target", "last")
        val actionStr = args.optString("action", "like")
        val action = runCatching {
            FeedbackAction.valueOf(actionStr.uppercase())
        }.getOrDefault(FeedbackAction.LIKE)
        return AgentCommand.RecordMediaFeedback(
            target = parseFeedbackTarget(targetStr),
            action = action,
            queryHint = args.optString("query_hint", "").takeIf { it.isNotEmpty() }
        )
    }

    private fun parseMoreLikeThis(args: JSONObject): AgentCommand.MoreLikeThis {
        val targetStr = args.optString("target", "last")
        return AgentCommand.MoreLikeThis(
            target = parseFeedbackTarget(targetStr),
            queryHint = args.optString("query_hint", "").takeIf { it.isNotEmpty() }
        )
    }

    private fun parseExcludeConstraint(args: JSONObject): AgentCommand.ExcludeConstraint {
        return AgentCommand.ExcludeConstraint(constraint = args.optString("constraint", ""))
    }

    /**
     * 解析反馈目标字符串为 FeedbackTarget。
     *
     * 支持格式：ordinal:3, desc:海边, last, mediaId:img_001
     */
    private fun parseFeedbackTarget(target: String): FeedbackTarget {
        return when {
            target == "last" -> FeedbackTarget.LastShown
            target.startsWith("ordinal:") ->
                FeedbackTarget.Ordinal(target.removePrefix("ordinal:").toInt())
            target.startsWith("desc:") ->
                FeedbackTarget.Description(target.removePrefix("desc:"))
            target.startsWith("mediaId:") ->
                FeedbackTarget.MediaId(target.removePrefix("mediaId:"))
            else -> FeedbackTarget.Description(target)
        }
    }

    private fun parseSwitchViewMode(args: JSONObject): AgentCommand.SwitchViewMode {
        return AgentCommand.SwitchViewMode(mode = args.optString("mode", "grid"))
    }

    private fun parseFavoriteMedia(args: JSONObject): AgentCommand.FavoriteMedia {
        return AgentCommand.FavoriteMedia(
            mediaId = args.optString("media_id", ""),
            favorite = args.optBoolean("favorite", true)
        )
    }

    // ==================== 设置命令解析 ====================

    private fun parseChangeTheme(args: JSONObject): AgentCommand.ChangeTheme {
        return AgentCommand.ChangeTheme(theme = args.optString("theme", "system"))
    }

    private fun parseChangeLanguage(args: JSONObject): AgentCommand.ChangeLanguage {
        return AgentCommand.ChangeLanguage(language = args.optString("language", "zh"))
    }

    private fun parseDownloadModel(args: JSONObject): AgentCommand.DownloadModel {
        return AgentCommand.DownloadModel(modelId = args.optString("model_id", ""))
    }

    private fun parseSwitchFaceEngine(args: JSONObject): AgentCommand.SwitchFaceEngine {
        return AgentCommand.SwitchFaceEngine(engine = args.optString("engine", "mlkit"))
    }

    private fun parseToggleSetting(args: JSONObject): AgentCommand.ToggleSetting {
        return AgentCommand.ToggleSetting(
            settingKey = args.optString("key", ""),
            enabled = args.optBoolean("enabled", true)
        )
    }

    // ==================== 辅助方法 ====================

    private fun resolveFilterType(name: String): FilterType {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> FilterType.NONE
            "LEICA_CLASSIC", "徕卡经典", "徕卡经典滤镜" -> FilterType.LEICA_CLASSIC
            "LEICA_VIBRANT", "VIBRANT", "LEICA_VIVID", "VIVID", "徕卡鲜艳", "徕卡鲜艳滤镜" -> FilterType.LEICA_VIBRANT
            "LEICA_BW", "BW", "BLACK_WHITE", "LEICA_MONOCHROME", "MONOCHROME", "徕卡黑白", "徕卡黑白滤镜" -> FilterType.LEICA_BW
            "FILM_GOLD", "胶片金", "胶片金滤镜" -> FilterType.FILM_GOLD
            "FILM_FUJI", "胶片富士", "富士", "胶片富士滤镜" -> FilterType.FILM_FUJI
            "VINTAGE", "RETRO", "OLD", "复古", "怀旧" -> FilterType.VINTAGE
            "COOL", "COLD", "冷色", "冷色调", "冷色滤镜", "冷调", "冷调滤镜", "冷滤镜" -> FilterType.COOL
            "WARM", "暖色", "暖色调", "暖色滤镜", "暖调", "暖调滤镜", "暖滤镜" -> FilterType.WARM
            else -> runCatching { FilterType.valueOf(normalized) }.getOrDefault(FilterType.NONE)
        }
    }

    private fun resolveStyleFilter(name: String): StyleFilter {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> StyleFilter.NONE
            "TOON", "CARTOON", "COMIC" -> StyleFilter.TOON
            "SKETCH" -> StyleFilter.SKETCH
            "POSTERIZE", "POSTER" -> StyleFilter.POSTERIZE
            "EMBOSS" -> StyleFilter.EMBOSS
            "CROSSHATCH", "CROSS_HATCH" -> StyleFilter.CROSSHATCH
            else -> runCatching { StyleFilter.valueOf(normalized) }.getOrDefault(StyleFilter.NONE)
        }
    }
}
