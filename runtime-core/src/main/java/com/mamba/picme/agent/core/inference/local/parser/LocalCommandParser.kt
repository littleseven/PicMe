package com.mamba.picme.agent.core.inference.local.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentIdGenerator
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter

/**
 * 本地 LLM 命令解析器
 *
 * 将本地 LLM 输出的 method/params JSON 数组响应解析为结构化 [AgentCommand]。
 * 协议格式：
 * - JSON 数组：[{"method":"capture","params":{}}, ...]
 * - 单个 JSON：{"method":"capture","params":{}}
 *
 * 提取为独立 object 以便在纯 JVM 单元测试中直接调用，
 * 避免实例化 [com.mamba.picme.agent.core.facade.AgentOrchestrator] 时触发 JNI/MNN 加载。
 */
object LocalCommandParser {

    private const val TAG = "LocalCommandParser"

    /**
     * 解析 LLM 响应为 AgentCommand
     *
     * 支持精简 JSON 格式：{"method":"...","params":{...}}。
     * 支持 JSON 数组格式：[{"method":"...","params":{}}, ...]，解析为 BatchExecute。
     *
     * @param response LLM 原始文本输出
     * @param context 当前 Agent 上下文
     * @return 解析后的命令
     */
    fun parseLlmResponse(response: String, context: AgentContext): AgentCommand {
        val cleaned = cleanLlmResponse(response)

        // 优先处理 JSON 数组（L2 本地快速通道输出）
        if (cleaned.startsWith("[")) {
            return parseLlmResponseArray(cleaned, context, response)
        }

        return parseSingleCommand(cleaned, context, response)
    }

    /**
     * 解析 L2 Batch 响应为命令列表（用于 L3 本地快速通道）
     *
     * 与 [parseLlmResponse] 不同，此方法返回命令列表而非 BatchExecute 包装对象，
     * 便于 L3 本地快速通道将命令列表包装为 ExecutionPlan。
     *
     * @param response LLM 原始文本输出
     * @param context 当前 Agent 上下文
     * @return 解析后的命令列表（空列表表示解析失败）
     */
    fun parseL2BatchResponse(response: String, context: AgentContext): List<AgentCommand> {
        val cleaned = cleanLlmResponse(response)

        // 优先处理 JSON 数组
        if (cleaned.startsWith("[")) {
            return parseLlmResponseArrayToList(cleaned, context, response)
        }

        // 单个 JSON 对象
        val command = parseSingleCommand(cleaned, context, response)
        return if (command is AgentCommand.Error) emptyList() else listOf(command)
    }

    /**
     * 解析 JSON 数组为命令列表（内部方法）
     *
     * 扫描过程中跳过字符串字面量，避免 message/参数值中的 { 或 } 干扰大括号匹配。
     */
    private fun parseLlmResponseArrayToList(
        cleaned: String,
        context: AgentContext,
        fallbackText: String
    ): List<AgentCommand> {
        Logger.i(TAG, "Parsing LLM response as array to list")

        val commands = mutableListOf<AgentCommand>()
        var depth = 0
        var objectStart = -1
        var i = 0
        while (i < cleaned.length) {
            when (cleaned[i]) {
                '"' -> i = skipJsonString(cleaned, i)
                '{' -> {
                    if (depth == 0) objectStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        val objectJson = cleaned.substring(objectStart, i + 1)
                        val command = parseSingleCommand(objectJson, context, objectJson)
                        if (command !is AgentCommand.Error) {
                            commands.add(command)
                        }
                        objectStart = -1
                    }
                }
            }
            i++
        }

        Logger.i(TAG, "Parsed ${commands.size} commands from array to list")
        return commands
    }

    /**
     * 解析 JSON 数组为 BatchExecute（内部方法）
     *
     * 扫描过程中跳过字符串字面量，避免 message/参数值中的 { 或 } 干扰大括号匹配。
     */
    private fun parseLlmResponseArray(
        cleaned: String,
        context: AgentContext,
        fallbackText: String
    ): AgentCommand {
        Logger.i(TAG, "Parsing LLM response as array")

        val commands = mutableListOf<AgentCommand>()
        var depth = 0
        var objectStart = -1
        var i = 0
        while (i < cleaned.length) {
            when (cleaned[i]) {
                '"' -> i = skipJsonString(cleaned, i)
                '{' -> {
                    if (depth == 0) objectStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        val objectJson = cleaned.substring(objectStart, i + 1)
                        val command = parseSingleCommand(objectJson, context, objectJson)
                        if (command !is AgentCommand.Error) {
                            commands.add(command)
                        }
                        objectStart = -1
                    }
                }
            }
            i++
        }

        return if (commands.isNotEmpty()) {
            Logger.i(TAG, "Parsed ${commands.size} commands from array")
            AgentCommand.BatchExecute(commandId = AgentIdGenerator.nextId(), commands = commands)
        } else {
            Logger.w(TAG, "Array parsing yielded no valid commands, falling back")
            parseSingleCommand(cleaned, context, fallbackText)
        }
    }

    /**
     * 跳过 JSON 字符串字面量（包含转义字符）。
     *
     * @return 字符串结束引号的索引；若未找到则返回字符串末尾。
     */
    private fun skipJsonString(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2 // 跳过转义字符
                '"' -> return i
                else -> i++
            }
        }
        return text.length - 1
    }

    /**
     * 清理 LLM 原始响应
     */
    private fun cleanLlmResponse(response: String): String {
        Logger.i(TAG, "Raw LLM response before cleaning: '${response.replace("\n", "\\n")}'")
        var cleaned = response.trim()

        // 0. 预处理：修复小模型常见格式错误
        // 0a. 移除 [last] 等模型内部标记
        cleaned = cleaned.replace(Regex("\\[last\\]"), "").trim()
        // 0b. 修复用 [] 包裹 JSON 对象的情况（如 ["method":"..."]）
        // 将 ["method" -> {"method" ， "params"]" -> "params"} 等常见错误模式修复
        if (cleaned.contains("[\"method\"") || cleaned.contains("[\"action\"")) {
            cleaned = cleaned.replace("[\"method\"", "{\"method\"")
            cleaned = cleaned.replace("[\"action\"", "{\"action\"")
            cleaned = cleaned.replace("\"params\"][", "\"params\":{")
            cleaned = cleaned.replace("][]", "}")
            cleaned = cleaned.replace("][", "},{")
        }
        // 0c. 修复缺少外层 {} 的情况（如 "method":"...","params":{...}）
        if (!cleaned.startsWith("{") && !cleaned.startsWith("[") &&
            (cleaned.contains("\"method\"") || cleaned.contains("\"action\""))
        ) {
            cleaned = "{$cleaned}"
        }

        // 1. 移除 <think>...</think> 和 <thinking>...</thinking> 标签（含未闭合情况）
        val thinkTags = listOf("<think>" to "</think>", "<thinking>" to "</thinking>")
        for ((startTag, endTag) in thinkTags) {
            while (true) {
                val thinkStart = cleaned.indexOf(startTag)
                val thinkEnd = cleaned.indexOf(endTag)
                if (thinkStart >= 0 && thinkEnd > thinkStart) {
                    // 先尝试从 think 标签内部提取 JSON（小模型常把 JSON 藏在思考过程里）
                    val thinkContent = cleaned.substring(thinkStart + startTag.length, thinkEnd)
                    val innerJson = tryExtractJsonFromRaw(thinkContent)
                    if (innerJson != null) {
                        cleaned = innerJson
                        break
                    }
                    cleaned = cleaned.removeRange(thinkStart, thinkEnd + endTag.length).trim()
                } else {
                    break
                }
            }
            // 处理未闭合开始标签：尝试从标签后提取 JSON
            val orphanStart = cleaned.indexOf(startTag)
            if (orphanStart >= 0) {
                val afterTag = cleaned.substring(orphanStart + startTag.length).trim()
                val beforeTag = cleaned.substring(0, orphanStart).trim()
                // 优先使用标签后的内容（通常 JSON 在 think 标签后）
                cleaned = if (containsJsonMethodKey(afterTag)) {
                    afterTag
                } else {
                    beforeTag
                }
            }
            // 处理单独的结束标签（如模型只输出了 </think>）
            cleaned = cleaned.replace(endTag, "").trim()
        }

        // 2. 移除代码块标记 (``json ... ``` 或 ``` ... ```)
        cleaned = cleaned.replace(Regex("^```\\w*\\n?"), "").replace(Regex("\\n?```\\s*\$"), "").trim()

        Logger.i(TAG, "Cleaned response: '$cleaned'")

        return cleaned
    }

    /**
     * 解析单个 JSON 对象为 AgentCommand
     */
    private fun parseSingleCommand(
        cleaned: String,
        context: AgentContext,
        fallbackText: String
    ): AgentCommand {
        var localCleaned = cleaned

        // 3. 检查是否包含 JSON method 字段
        val hasJsonMethod = containsJsonMethodKey(localCleaned)
        if (!hasJsonMethod) {
            // 兜底 1：尝试从原始响应中直接提取 JSON（绕过 think 标签截断问题）
            val fallbackJson = tryExtractJsonFromRaw(fallbackText)
            if (fallbackJson != null) {
                Logger.i(TAG, "Fallback JSON extraction succeeded: '$fallbackJson'")
                localCleaned = fallbackJson
            } else {
                // 兜底 2：关键词匹配（小模型不输出 JSON 时的最终防线）
                // 先尝试清理后的内容，再尝试原始响应（think 标签内可能包含关键词）
                val keywordCommand = tryParseByKeywords(localCleaned)
                    ?: tryParseByKeywords(fallbackText)
                if (keywordCommand != null) {
                    Logger.i(TAG, "Keyword fallback matched: ${keywordCommand::class.simpleName}")
                    return keywordCommand
                }
                Logger.d(TAG, "No JSON method found, treating as free chat")
                return AgentCommand.TextReply(message = localCleaned.ifBlank { "你好，我是小浪，有什么可以帮你的吗？" })
            }
        }

        // 4. 提取 JSON 部分
        val jsonStart = localCleaned.indexOf('{')
        val jsonEnd = localCleaned.lastIndexOf('}')
        val json = if (jsonStart >= 0 && jsonEnd > jsonStart) {
            localCleaned.substring(jsonStart, jsonEnd + 1)
        } else {
            localCleaned
        }

        return try {
            val method = extractJsonField(json, "method")
            val action = extractJsonField(json, "action")
            val commandId = extractJsonInt(json, "id") ?: AgentIdGenerator.nextId()

            val effectiveAction = method ?: action ?: "text_reply"

            // 如果有 params 对象，将 params 中的字段合并到 json 中用于解析
            val paramsJson = extractJsonObject(json, "params")
            val mergedJson = if (paramsJson != null) {
                // 将 params 内容扁平化合并到顶层，便于现有解析逻辑处理
                mergeParamsIntoJson(json, paramsJson)
            } else {
                json
            }

            parseCommandByMethod(effectiveAction, mergedJson, context, localCleaned, commandId)
        } catch (exception: Exception) {
            Logger.w(TAG, "Failed to parse LLM response, fallback to text: $json", exception)
            AgentCommand.TextReply(message = localCleaned.ifBlank { "收到你的消息了，但没理解具体意图，请再描述一下~" })
        }
    }

    /**
     * 根据 method 字段解析为具体命令
     *
     * @param method method 字段值
     * @param json 原始 JSON 字符串（已合并 params）
     * @param context 当前 Agent 上下文
     * @param fallbackText 解析失败时的回退文本
     * @param commandId 命令唯一标识（32位自增整型）
     * @return 解析后的命令
     */
    fun parseCommandByMethod(
        method: String,
        json: String,
        context: AgentContext,
        fallbackText: String,
        commandId: Int = AgentIdGenerator.nextId()
    ): AgentCommand {
        return when (method) {
            "adjust_beauty" -> {
                val smoothing = extractJsonFloat(json, "smoothing") ?: context.beautySettings.smoothing
                val whitening = extractJsonFloat(json, "whitening") ?: context.beautySettings.whitening
                val slimFace = extractJsonFloat(json, "slim_face") ?: context.beautySettings.slimFace
                val bigEyes = extractJsonFloat(json, "big_eyes") ?: context.beautySettings.bigEyes
                val lipColor = extractJsonFloat(json, "lip_color") ?: context.beautySettings.lipColor
                val blush = extractJsonFloat(json, "blush") ?: context.beautySettings.blush
                val eyebrow = extractJsonFloat(json, "eyebrow") ?: context.beautySettings.eyebrow
                AgentCommand.AdjustBeauty(
                    commandId = commandId,
                    settings = context.beautySettings.copy(
                        enabled = true,
                        smoothing = smoothing,
                        whitening = whitening,
                        slimFace = slimFace,
                        bigEyes = bigEyes,
                        lipColor = lipColor,
                        blush = blush,
                        eyebrow = eyebrow
                    )
                )
            }
            "switch_filter" -> {
                val filterName = extractJsonField(json, "filter") ?: "NONE"
                val filterType = resolveFilterType(filterName)
                AgentCommand.SwitchFilter(commandId = commandId, filterType = filterType)
            }
            "switch_style" -> {
                val styleName = extractJsonField(json, "style") ?: "NONE"
                val styleFilter = resolveStyleFilter(styleName)
                AgentCommand.SwitchStyle(commandId = commandId, styleFilter = styleFilter)
            }
            "switch_scene" -> {
                val scene = extractJsonField(json, "scene") ?: "none"
                AgentCommand.SwitchScene(commandId = commandId, sceneName = scene)
            }
            "switch_ratio" -> {
                val ratio = extractJsonField(json, "ratio") ?: "full"
                AgentCommand.SwitchRatio(commandId = commandId, ratio = ratio)
            }
            "adjust_exposure" -> {
                val exposure = extractJsonInt(json, "exposure") ?: 0
                AgentCommand.AdjustExposure(commandId = commandId, exposure = exposure.coerceIn(-2, 2))
            }
            "adjust_zoom" -> {
                val zoom = extractJsonFloat(json, "zoom") ?: 1f
                AgentCommand.AdjustZoom(commandId = commandId, zoomRatio = zoom.coerceAtLeast(0.5f))
            }
            "flip_camera" -> AgentCommand.FlipCamera(commandId = commandId)
            "capture", "photo" -> AgentCommand.CapturePhoto(commandId = commandId)
            "toggle_recording" -> AgentCommand.ToggleRecording(commandId = commandId)
            "delay" -> {
                val delayMs = extractJsonInt(json, "delay_ms") ?: (extractJsonInt(json, "delay_seconds")?.times(1000) ?: 3000)
                AgentCommand.Delay(commandId = commandId, delayMs = delayMs.coerceIn(1, 300000).toLong())
            }
            "switch_mode" -> {
                val modeName = extractJsonField(json, "mode") ?: "PHOTO"
                val mode = runCatching { MediaType.valueOf(modeName) }.getOrDefault(MediaType.PHOTO)
                AgentCommand.SwitchMode(commandId = commandId, mode = mode)
            }

            // ===== Gallery 命令 =====
            "view_media" -> {
                val mediaId = extractJsonField(json, "media_id")
                AgentCommand.ViewMedia(commandId = commandId, mediaId = mediaId)
            }
            "delete_media" -> {
                val mediaIds = extractJsonStringList(json, "media_ids")
                AgentCommand.DeleteMedia(commandId = commandId, mediaIds = mediaIds)
            }
            "share_media" -> {
                val mediaIds = extractJsonStringList(json, "media_ids")
                AgentCommand.ShareMedia(commandId = commandId, mediaIds = mediaIds)
            }
            "select_media" -> {
                val mediaId = extractJsonField(json, "media_id") ?: ""
                val selected = extractJsonBoolean(json, "selected") ?: true
                AgentCommand.SelectMedia(commandId = commandId, mediaId = mediaId, selected = selected)
            }
            "search_media" -> {
                val query = extractJsonField(json, "query") ?: ""
                AgentCommand.SearchMedia(commandId = commandId, query = query)
            }
            "refine_media_search" -> {
                val constraint = extractJsonField(json, "constraint")
                    ?: extractJsonField(json, "query") ?: ""
                AgentCommand.RefineMediaSearch(commandId = commandId, constraint = constraint)
            }
            "feedback" -> {
                val targetStr = extractJsonField(json, "target") ?: "last"
                val actionStr = extractJsonField(json, "action") ?: "like"
                AgentCommand.RecordMediaFeedback(
                    commandId = commandId,
                    target = parseFeedbackTarget(targetStr),
                    action = com.mamba.picme.agent.core.model.command.FeedbackAction.valueOf(actionStr.uppercase())
                )
            }
            "more" -> {
                val targetStr = extractJsonField(json, "target") ?: "last"
                AgentCommand.MoreLikeThis(
                    commandId = commandId,
                    target = parseFeedbackTarget(targetStr)
                )
            }
            "exclude" -> {
                val constraint = extractJsonField(json, "constraint") ?: ""
                AgentCommand.ExcludeConstraint(commandId = commandId, constraint = constraint)
            }
            "switch_view_mode" -> {
                val mode = extractJsonField(json, "mode") ?: "grid"
                AgentCommand.SwitchViewMode(commandId = commandId, mode = mode)
            }
            "favorite_media" -> {
                val mediaId = extractJsonField(json, "media_id") ?: ""
                val favorite = extractJsonBoolean(json, "favorite") ?: true
                AgentCommand.FavoriteMedia(commandId = commandId, mediaId = mediaId, favorite = favorite)
            }

            // ===== 导航命令 =====
            "navigate_to" -> {
                val destination = extractJsonField(json, "destination") ?: ""
                AgentCommand.NavigateTo(commandId = commandId, destination = destination)
            }
            "go_back" -> AgentCommand.GoBack(commandId = commandId)

            // ===== 编辑命令 =====
            "ai_optimize" -> {
                val imageUri = extractJsonField(json, "image_uri") ?: ""
                val mode = extractJsonField(json, "mode") ?: "fast"
                AgentCommand.AiOptimize(commandId = commandId, imageUri = imageUri, mode = mode)
            }

            // ===== 相册摘要命令 =====
            "get_gallery_summary" -> {
                val includeDetails = extractJsonBoolean(json, "include_details") ?: false
                AgentCommand.GetGallerySummary(
                    commandId = commandId,
                    includeDetails = includeDetails
                )
            }

            // ===== TAG 扫描控制命令 =====
            "start_tag_scan" -> {
                val action = extractJsonField(json, "action") ?: "query"
                val taskType = extractJsonField(json, "task_type")
                val mode = extractJsonField(json, "mode")
                AgentCommand.StartTagScan(
                    commandId = commandId,
                    action = action,
                    taskType = taskType,
                    mode = mode
                )
            }

            // ===== 设置命令 =====
            "change_theme" -> {
                val theme = extractJsonField(json, "theme") ?: "system"
                AgentCommand.ChangeTheme(commandId = commandId, theme = theme)
            }
            "change_language" -> {
                val language = extractJsonField(json, "language") ?: "zh"
                AgentCommand.ChangeLanguage(commandId = commandId, language = language)
            }
            "download_model" -> {
                val modelId = extractJsonField(json, "model_id") ?: ""
                AgentCommand.DownloadModel(commandId = commandId, modelId = modelId)
            }
            "switch_face_engine" -> {
                val engine = extractJsonField(json, "engine") ?: "mlkit"
                AgentCommand.SwitchFaceEngine(commandId = commandId, engine = engine)
            }
            "toggle_setting" -> {
                val key = extractJsonField(json, "key") ?: ""
                val enabled = extractJsonBoolean(json, "enabled") ?: true
                AgentCommand.ToggleSetting(commandId = commandId, settingKey = key, enabled = enabled)
            }

            // ===== 系统/外部 App 命令 =====
            "launch_app" -> {
                val packageName = extractJsonField(json, "package_name")
                val appName = extractJsonField(json, "app_name")
                val activityClass = extractJsonField(json, "activity_class")
                AgentCommand.LaunchApp(
                    commandId = commandId,
                    packageName = packageName,
                    appName = appName,
                    activityClass = activityClass
                )
            }
            "open_system_settings" -> {
                val setting = extractJsonField(json, "setting") ?: ""
                AgentCommand.OpenSystemSettings(commandId = commandId, setting = setting)
            }

            // ===== 默认文本回复 =====
            else -> {
                val message = extractJsonField(json, "message")
                    ?: fallbackText.ifBlank { "收到，有什么其他需要帮忙的吗？" }
                AgentCommand.TextReply(commandId = commandId, message = message)
            }
        }
    }

    /**
     * 兜底提取：从原始响应中直接提取 JSON 对象。
     *
     * 当 think 标签清理导致内容丢失时使用。
     * 扫描原始文本中第一个 `{...}` 结构，且中间包含 "method"。
     */
    private fun tryExtractJsonFromRaw(raw: String): String? {
        // 策略：找到第一个 `{` 和最后一个 `}`，且中间包含 "method"
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val candidate = raw.substring(jsonStart, jsonEnd + 1)
            if (containsJsonMethodKey(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun containsJsonMethodKey(content: String): Boolean {
        return content.contains("\"method\"") || content.contains("\"action\"") || content.trim().startsWith("[")
    }

    /**
     * 从 JSON 字符串中提取指定 key 的对象值
     *
     * 用于提取 params 对象。
     */
    fun extractJsonObject(json: String, key: String): String? {
        val keyIndex = json.indexOf("\"$key\"")
        if (keyIndex == -1) return null

        val colonIndex = json.indexOf(':', keyIndex)
        if (colonIndex == -1) return null

        var braceStart = colonIndex + 1
        while (braceStart < json.length && json[braceStart].isWhitespace()) braceStart++

        if (braceStart >= json.length || json[braceStart] != '{') return null

        var depth = 1
        var pos = braceStart + 1
        while (pos < json.length && depth > 0) {
            when (json[pos]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    // 跳过字符串
                    pos++
                    while (pos < json.length && json[pos] != '"') {
                        if (json[pos] == '\\' && pos + 1 < json.length) pos++
                        pos++
                    }
                }
            }
            pos++
        }

        return if (depth == 0) json.substring(braceStart, pos) else null
    }

    /**
     * 将 params 对象的内容合并到顶层 JSON 中
     *
     * 参数在 params 对象内，但现有解析逻辑期望扁平字段。
     * 此方法将 params 的键值对提升到顶层。
     */
    private fun mergeParamsIntoJson(originalJson: String, paramsJson: String): String {
        // 简单策略：将 originalJson 中 "params":{...} 替换为空，
        // 然后将 paramsJson 的内容（去掉外层 {}）追加到顶层
        val paramsContent = paramsJson.removePrefix("{").removeSuffix("}")
        if (paramsContent.isBlank()) return originalJson

        // 移除 originalJson 中的 params 字段
        val paramsPattern = """"params"\s*:\s*\{[^{}]*\}""".toRegex()
        val withoutParams = originalJson.replace(paramsPattern, "").trim()

        // 在最后一个 } 前插入 params 内容
        val lastBrace = withoutParams.lastIndexOf('}')
        return if (lastBrace > 0) {
            val prefix = withoutParams.substring(0, lastBrace).trimEnd()
            val separator = if (prefix.endsWith(',')) "" else ","
            "$prefix$separator$paramsContent}"
        } else {
            originalJson
        }
    }

    /**
     * 关键词兜底解析：当 LLM 不输出 JSON 时，通过关键词理解用户意图。
     *
     * 适用于小模型（如 Qwen3-0.6B）无法严格遵循 system prompt 指令的场景。
     */
    private fun tryParseByKeywords(text: String): AgentCommand? {
        val lower = text.lowercase()
        return when {
            // 拍照相关
            lower.contains("拍照") || lower.contains("拍张") || lower.contains("拍照片") ||
                lower.contains("拍一下") || lower.contains("快门") -> AgentCommand.CapturePhoto()
            // 翻转摄像头
            lower.contains("翻转") || lower.contains("切换摄像头") || lower.contains("前后") ||
                lower.contains("前置") || lower.contains("后置") || lower.contains("前摄") -> AgentCommand.FlipCamera()
            // 美颜相关
            lower.contains("调高美颜") || lower.contains("增强美颜") || lower.contains("提亮美颜") ->
                AgentCommand.AdjustBeauty(
                    settings = BeautySettings(enabled = true, smoothing = 65f, whitening = 65f)
                )
            lower.contains("美颜") && (lower.contains("关") || lower.contains("关闭")) ->
                AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = false))
            lower.contains("美颜") || lower.contains("开美颜") ->
                AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true))
            // 冷调滤镜
            lower.contains("冷调") || lower.contains("冷色") || lower.contains("冷滤镜") ||
                (lower.contains("冷") && lower.contains("滤镜")) ->
                AgentCommand.SwitchFilter(filterType = FilterType.COOL)
            // 暖调滤镜
            lower.contains("暖调") || lower.contains("暖色") || lower.contains("暖滤镜") ||
                (lower.contains("暖") && lower.contains("滤镜")) ->
                AgentCommand.SwitchFilter(filterType = FilterType.WARM)
            // 录像
            lower.contains("录像") || lower.contains("录制") || lower.contains("拍视频") -> AgentCommand.ToggleRecording()
            // 导航相关 - 去相册（最高优先级，多种说法）
            lower.contains("去相册") || lower.contains("打开相册") || lower.contains("看照片") ||
                lower.contains("图库") || lower.contains("照片库") ||
                (lower.contains("相册") && (lower.contains("去") || lower.contains("打开") || lower.contains("看"))) ->
                AgentCommand.NavigateTo(destination = "gallery")
            // 导航相关 - 去设置
            lower.contains("去设置") || lower.contains("打开设置") || lower.contains("设置页") ||
                lower.contains("app设置") || lower.contains("应用设置") ||
                (lower.contains("设置") && (lower.contains("去") || lower.contains("打开"))) ->
                AgentCommand.NavigateTo(destination = "settings")
            // 导航相关 - 去相机
            lower.contains("去相机") || lower.contains("回相机") || lower.contains("打开相机") ||
                lower.contains("回拍照") || lower.contains("去拍照") ||
                (lower.contains("相机") && (lower.contains("去") || lower.contains("打开") || lower.contains("回"))) ->
                AgentCommand.NavigateTo(destination = "camera")
            // 导航相关 - 去调试
            lower.contains("去调试") || lower.contains("打开调试") || lower.contains("debug") ->
                AgentCommand.NavigateTo(destination = "debug")
            // 导航相关 - 返回
            lower.contains("返回") || lower.contains("回去") || lower.contains("上一页") ||
                lower.contains("后退") || lower.contains("回退") ->
                AgentCommand.GoBack()
            // 复合指令：延迟 + 滤镜/美颜 + 拍照
            (lower.contains("延迟") || lower.contains("延时") || lower.contains("倒计时") ||
                lower.contains("几秒后") || lower.contains("秒后") || lower.contains("稍后")) &&
                (lower.contains("拍") || lower.contains("照")) &&
                (lower.contains("滤镜") || lower.contains("美颜") || lower.contains("磨皮") ||
                    lower.contains("美白") || lower.contains("瘦脸") || lower.contains("风格")) -> {
                val delaySeconds = extractDelaySeconds(lower)
                val commands = mutableListOf<AgentCommand>(
                    AgentCommand.Delay(delayMs = delaySeconds * 1000L)
                )
                // 识别滤镜
                when {
                    lower.contains("暖") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.WARM))
                    lower.contains("冷") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.COOL))
                    lower.contains("徕卡") && lower.contains("经典") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.LEICA_CLASSIC))
                    lower.contains("徕卡") && (lower.contains("鲜艳") || lower.contains("vibrant")) -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.LEICA_VIBRANT))
                    lower.contains("徕卡") && (lower.contains("黑白") || lower.contains("bw")) -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.LEICA_BW))
                    lower.contains("胶片") && lower.contains("金") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.FILM_GOLD))
                    lower.contains("胶片") && lower.contains("富士") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.FILM_FUJI))
                    lower.contains("复古") -> commands.add(AgentCommand.SwitchFilter(filterType = FilterType.VINTAGE))
                }
                commands.add(AgentCommand.CapturePhoto())
                AgentCommand.BatchExecute(commands = commands)
            }
            // 纯延迟拍摄
            (lower.contains("延迟") || lower.contains("延时") || lower.contains("倒计时") ||
                lower.contains("几秒后") || lower.contains("秒后") || lower.contains("稍后")) &&
                (lower.contains("拍") || lower.contains("照")) -> {
                val delaySeconds = extractDelaySeconds(lower)
                AgentCommand.BatchExecute(
                    commands = listOf(
                        AgentCommand.Delay(delayMs = delaySeconds * 1000L),
                        AgentCommand.CapturePhoto()
                    )
                )
            }
            // Gallery 相关
            lower.contains("删除") || lower.contains("删掉") ->
                AgentCommand.DeleteMedia(mediaIds = emptyList())
            lower.contains("分享") ->
                AgentCommand.ShareMedia(mediaIds = emptyList())
            // AI 一键优化（兜底：无 JSON 时通过关键词触发；image_uri 由 Capability 从 PageContext 补全）
            lower.contains("优化") || lower.contains("修一下") || lower.contains("修图") ||
                lower.contains("美化") || lower.contains("增强") || lower.contains("调一下") ||
                lower.contains("帮我修") || lower.contains("智能优化") || lower.contains("一键优化") ||
                (lower.contains("修") && lower.contains("照片")) ||
                (lower.contains("修") && lower.contains("图")) ||
                (lower.contains("调") && lower.contains("照片")) ->
                AgentCommand.AiOptimize(imageUri = "", mode = "fast")
            else -> null
        }
    }

    /**
     * 将 LLM 输出的 filter 名称解析为 FilterType
     */
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

    /**
     * 将 LLM 输出的 style 名称解析为 StyleFilter
     */
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

    private fun extractJsonField(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val regex = """"$key"\s*:\s*(-?\d+\.?\d*)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val regex = """"$key"\s*:\s*(-?\d+)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractJsonBoolean(json: String, key: String): Boolean? {
        val regex = """"$key"\s*:\s*(true|false)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    /**
     * 提取字符串列表
     */
    private fun extractJsonStringList(json: String, key: String): List<String> {
        // 尝试解析数组格式
        val arrayRegex = """"$key"\s*:\s*\[([^\]]*)\]""".toRegex()
        val arrayMatch = arrayRegex.find(json)
        if (arrayMatch != null) {
            val content = arrayMatch.groupValues[1]
            return content.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
        }

        // 尝试解析逗号分隔的字符串
        val stringValue = extractJsonField(json, key)
        if (stringValue != null) {
            return stringValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        return emptyList()
    }

    /**
     * 从文本中提取延迟秒数
     */
    private fun extractDelaySeconds(text: String): Int {
        val numberMap = mapOf(
            "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
        )
        // 先尝试匹配阿拉伯数字
        val digitRegex = "(\\d+)\\s*[\\u79d2s]".toRegex()
        val digitMatch = digitRegex.find(text)
        if (digitMatch != null) {
            return digitMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 30) ?: 3
        }
        // 再尝试匹配中文数字
        for ((cn, num) in numberMap) {
            if (text.contains(cn + "\u79d2") || text.contains(cn + "\u79d2\u540e")) {
                return num.coerceIn(1, 30)
            }
        }
        return 3
    }

    /**
     * 解析简单字符串键值对对象
     */
    private fun parseStringMap(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = """"([^"]+)"\s*:\s*"([^"]*)"""".toRegex()
        regex.findAll(json).forEach { matchResult ->
            result[matchResult.groupValues[1]] = matchResult.groupValues[2]
        }
        return result
    }

    private fun extractJsonFloatMap(json: String, key: String): Map<String, Float> {
        val result = mutableMapOf<String, Float>()

        // 尝试找到 key 对应的值（对象格式）
        val objectRegex = """"$key"\s*:\s*\{([^}]*)\}""".toRegex()
        val objectMatch = objectRegex.find(json)

        if (objectMatch != null) {
            val content = objectMatch.groupValues[1]
            // 解析内部的 key: value 对
            val pairRegex = """"([^"]+)"\s*:\s*(-?\d+\.?\d*)""".toRegex()
            pairRegex.findAll(content).forEach { matchResult ->
                val paramKey = matchResult.groupValues[1]
                val paramValue = matchResult.groupValues[2].toFloatOrNull()
                if (paramValue != null) {
                    result[paramKey] = paramValue
                }
            }
        }

        return result
    }

    /**
     * 解析反馈目标字符串为 FeedbackTarget。
     *
     * 支持格式：ordinal:3, desc:海边, last, mediaId:img_001
     */
    private fun parseFeedbackTarget(target: String): com.mamba.picme.agent.core.model.command.FeedbackTarget {
        return when {
            target == "last" -> com.mamba.picme.agent.core.model.command.FeedbackTarget.LastShown
            target.startsWith("ordinal:") -> com.mamba.picme.agent.core.model.command.FeedbackTarget.Ordinal(target.removePrefix("ordinal:").toInt())
            target.startsWith("desc:") -> com.mamba.picme.agent.core.model.command.FeedbackTarget.Description(target.removePrefix("desc:"))
            target.startsWith("mediaId:") -> com.mamba.picme.agent.core.model.command.FeedbackTarget.MediaId(target.removePrefix("mediaId:"))
            else -> com.mamba.picme.agent.core.model.command.FeedbackTarget.Description(target)
        }
    }
}