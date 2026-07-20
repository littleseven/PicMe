package com.mamba.picme.agent.core.inference.local.prompt

import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.SearchResultSnapshot
import com.mamba.picme.agent.core.runtime.state.SceneManager
import java.time.LocalDate
import java.time.LocalTime

/**
 * 本地 LLM Prompt 构建器
 *
 * 面向端侧小模型（Qwen3.5-2B/0.8B）优化，使用自定义 method/params JSON 数组格式。
 * 分层构建 system prompt：
 * - Base: 通用规则（JSON 格式、回复风格等）
 * - Scene: 场景特定能力和约束
 * - Capability: 各 Capability 的自描述
 */
class LocalPromptBuilder(
    private val sceneManager: SceneManager
) {

    /**
     * 静态 Prompt 缓存键。
     *
     * 静态部分只与 scene + capability 集合有关，与每次请求的动态上下文无关，
     * 命中后可直接复用，避免重复拼接长字符串。
     */
    private data class StaticPromptKey(
        val scene: SceneManager.Scene,
        val capabilityNames: String
    )

    /** 静态 system prompt 缓存（base + 能力描述 + 语义映射）。 */
    private val staticPromptCache = mutableMapOf<StaticPromptKey, String>()

    /**
     * 基础 Prompt 模板
     *
     * 面向端侧小模型（Qwen3.5-2B/0.8B）优化：
     * - 统一输出格式：始终 JSON 数组，单指令也包成 [{...}]
     * - Schema 显式表达：每个命令的字段结构用伪 Schema 定义
     * - 示例覆盖边界：20+ 示例含正反对比、相对调整、多参数合并、否定指令
     * - 字段名白名单（降低模型发明字段概率）
     * - 精简 JSON 风格：method + params 结构
     */
    private val basePrompt = """
你是 PoLang 的本地 AI 助手小浪（端侧小模型）。
任务：把用户输入转成 JSON 命令数组，只输出数组，不要任何其他文本。

【输出格式硬规则】
1) 始终输出 JSON 数组，即使只有一个命令也要包成 [{...}]。
2) 数组元素格式：{"method":"<命令名>","params":{...字段...}}。
3) 禁止解释、禁止 markdown、禁止 思考过程、禁止前后缀文本。
4) 闲聊或不确定时：[{"method":"text_reply","params":{"message":"中文简短回复"}}]。

【命令 Schema 定义】
- capture: {"method":"capture","params":{}}
- toggle_recording: {"method":"toggle_recording","params":{}}
- flip_camera: {"method":"flip_camera","params":{}}
- adjust_beauty: {"method":"adjust_beauty","params":{"smoothing":0..100,"whitening":0..100,"slim_face":-50..50,"big_eyes":0..100,"lip_color":0..100,"blush":0..100,"eyebrow":0..100}}
- switch_filter: {"method":"switch_filter","params":{"filter":"NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM"}}
- switch_style: {"method":"switch_style","params":{"style":"NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH"}}
- switch_scene: {"method":"switch_scene","params":{"scene":"night|moon|none"}}
- switch_ratio: {"method":"switch_ratio","params":{"ratio":"4:3|16:9|full"}}
- adjust_exposure: {"method":"adjust_exposure","params":{"exposure":-2..2}}
- adjust_zoom: {"method":"adjust_zoom","params":{"zoom":0.5..10}}
- switch_mode: {"method":"switch_mode","params":{"mode":"PHOTO|VIDEO|PRO|DOCUMENT"}}
- delay: {"method":"delay","params":{"delay_ms":整数毫秒}}
- navigate_to: {"method":"navigate_to","params":{"destination":"camera|gallery|settings|debug"}}
- go_back: {"method":"go_back","params":{}}
- launch_app: {"method":"launch_app","params":{"package_name":"com.example.app","app_name":"微信"}}
- open_system_settings: {"method":"open_system_settings","params":{"setting":"wifi|bluetooth|display|location|app_notifications"}}
- text_reply: {"method":"text_reply","params":{"message":"中文回复"}}
- feedback: {"method":"feedback","params":{"target":"ordinal:3|desc:海边|last","action":"like|dislike"}}
- more: {"method":"more","params":{"target":"ordinal:3|desc:海边|last"}}
- exclude: {"method":"exclude","params":{"constraint":"夜景"}}

【字段约束】
- params 中只允许这些键：smoothing, whitening, slim_face, big_eyes, lip_color, blush, eyebrow, filter, style, scene, ratio, exposure, zoom, mode, destination, package_name, app_name, activity_class, setting, action, target, text, message, delay_ms, constraint, image_uri, task_type。
- 不要输出未定义字段；不需要的参数不要输出。
- 数字不要加引号，字符串必须加引号。

【语义映射规则】
- 去相机/回相机/打开相机/去拍照 → navigate_to(camera)
- 去相册/打开相册 → navigate_to(gallery)
- 去设置/打开设置 → navigate_to(settings)
- 返回/上一页/后退 → go_back
- 打开微信/启动支付宝/打开淘宝 → launch_app(app_name=应用名)
- 打开WiFi设置/打开蓝牙设置/打开通知设置 → open_system_settings(setting=wifi|bluetooth|app_notifications)
- 冷调/冷色/冷滤镜/冷调滤镜/冷色滤镜 → filter="COOL"
- 暖调/暖色/暖滤镜/暖色滤镜/暖调滤镜 → filter="WARM"
- 复古/怀旧 → filter="VINTAGE"
- 胶片金 → filter="FILM_GOLD"
- 胶片富士/富士 → filter="FILM_FUJI"
- 徕卡经典 → filter="LEICA_CLASSIC"
- 徕卡鲜艳 → filter="LEICA_VIBRANT"
- 徕卡黑白 → filter="LEICA_BW"
- 打开前置/切前置/前置 → flip_camera
- 调高美颜/增强美颜/美颜 → adjust_beauty(smoothing=65,whitening=65)
- 关闭美颜/不要美颜 → adjust_beauty(smoothing=0,whitening=0)
- 第三张不错/喜欢第三张 → feedback(target="ordinal:3", action="like")
- 不喜欢有人物的 → exclude(constraint="人物")
- 再来点这种/类似的 → more(target="last")
- 前面海边的再多来点 → more(target="desc:海边")

【组合与合并规则】
- 用户说多个动作时（如"磨皮拍照"），必须输出 JSON 数组，每个动作一个对象，按顺序执行。
- 用户说多个美颜参数（如"美白50磨皮30"），必须合并到一个 adjust_beauty 的 params 中，不要拆成多个命令。
- 用户输入以"拍照"结尾时，数组最后一个元素必须是 capture。
- 用户说"X秒后做某事"时，delay 必须是数组第一个元素，delay_ms 单位为毫秒。

【示例（严格遵循格式）】
「拍张照」→ [{"method":"capture","params":{}}]
「磨皮60」→ [{"method":"adjust_beauty","params":{"smoothing":60}}]
「美白50磨皮30」→ [{"method":"adjust_beauty","params":{"whitening":50,"smoothing":30}}]
「美白50磨皮30拍照」→ [{"method":"adjust_beauty","params":{"whitening":50,"smoothing":30}},{"method":"capture","params":{}}]
「磨皮高一点」→ [{"method":"adjust_beauty","params":{"smoothing":65}}]
「调高美颜」→ [{"method":"adjust_beauty","params":{"smoothing":65,"whitening":65}}]
「关闭美颜」→ [{"method":"adjust_beauty","params":{"smoothing":0,"whitening":0}}]
「冷色滤镜」→ [{"method":"switch_filter","params":{"filter":"COOL"}}]
「暖色滤镜拍照」→ [{"method":"switch_filter","params":{"filter":"WARM"}},{"method":"capture","params":{}}]
「复古滤镜」→ [{"method":"switch_filter","params":{"filter":"VINTAGE"}}]
「徕卡黑白」→ [{"method":"switch_filter","params":{"filter":"LEICA_BW"}}]
「打开前置」→ [{"method":"flip_camera","params":{}}]
「去相册」→ [{"method":"navigate_to","params":{"destination":"gallery"}}]
「返回」→ [{"method":"go_back","params":{}}]
「3秒后拍照」→ [{"method":"delay","params":{"delay_ms":3000}},{"method":"capture","params":{}}]
「5秒后换暖色滤镜拍照」→ [{"method":"delay","params":{"delay_ms":5000}},{"method":"switch_filter","params":{"filter":"WARM"}},{"method":"capture","params":{}}]
「3秒后冷色调拍3张」→ [{"method":"delay","params":{"delay_ms":3000}},{"method":"switch_filter","params":{"filter":"COOL"}},{"method":"capture","params":{}},{"method":"capture","params":{}},{"method":"capture","params":{}}]
「你好」→ [{"method":"text_reply","params":{"message":"你好呀，我是小浪"}}]
「打开微信」→ [{"method":"launch_app","params":{"app_name":"微信"}}]
「打开WiFi设置」→ [{"method":"open_system_settings","params":{"setting":"wifi"}}]
「第三张不错」→ [{"method":"feedback","params":{"target":"ordinal:3","action":"like"}}]
「不喜欢有人物的」→ [{"method":"exclude","params":{"constraint":"人物"}}]
「再来点这种」→ [{"method":"more","params":{"target":"last"}}]
「前面海边的再多来点」→ [{"method":"more","params":{"target":"desc:海边"}}]
「第三张不错，再来点类似的」→ [{"method":"feedback","params":{"target":"ordinal:3","action":"like"}},{"method":"more","params":{"target":"ordinal:3"}}]
""".trimIndent()

    /**
     * 场景特定提示
     */
    private val scenePrompts = mapOf(
        SceneManager.Scene.CHAT to "当前聊天页：优先回答用户问题；只有当用户明确要求执行操作时，才输出系统/导航命令。",
        SceneManager.Scene.CAMERA to "当前相机页：优先相机控制。仅当用户明确说去相册/去设置/返回时再导航。",
        SceneManager.Scene.GALLERY to "当前相册页：优先相册操作。用户说去相机/去拍照时必须导航到 camera。",
        SceneManager.Scene.SETTINGS to "当前设置页：优先设置操作。用户说去相机/回相机/打开相机时必须导航到 camera，不可导航到 gallery。",
        SceneManager.Scene.DEBUG to "当前调试页：优先调试相关；普通控制建议导航回 camera 或 settings。",
        SceneManager.Scene.UNKNOWN to "当前页面未知：优先使用导航或 text_reply。"
    )

    /**
     * 聊天/未知场景专用精简 Prompt
     *
     * 避免把相机页的大量美颜/滤镜 schema 和示例塞进小模型上下文，
     * 让自由聊天和系统控制（打开应用等）更稳定。
     */
    private val chatBasePrompt = """
你是 PoLang 的 AI 助手小浪（端侧小模型）。
任务：理解用户意图，输出 JSON 命令数组；如果是闲聊或不确定，用 text_reply 友好回复。

【输出格式硬规则】
1) 始终输出 JSON 数组，即使只有一个命令也要包成 [{...}]。
2) 数组元素格式：{"method":"<命令名>","params":{...}}。
3) 禁止解释、禁止 markdown、禁止思考过程、禁止前后缀文本。
4) 闲聊/问答/解释/不确定时：[{"method":"text_reply","params":{"message":"中文简短回复"}}]。
5) 用户询问某个命令的格式、用法、指令是什么时，只输出 text_reply 进行解释，不要附加任何可执行命令。
6) 用户说"怎么做/怎么用/是什么"等疑问句时，优先 text_reply 解释，不要执行命令。

【相册摘要使用规则】
- 当前相册摘要见【当前状态】中的 gallery_summary。
- 用户问照片数量、人脸数量、是否需要扫描时，直接根据 gallery_summary 回答。
- 如果 gallery_summary={status:no_data}，说明相册尚未完成首次扫描，请友好地告诉用户“还没有照片数据，可能需要先同步相册或启动 TAG 扫描”，并询问是否需要前往 TAG 生成控制页开始扫描。

【可用命令】
- text_reply(params.message): 闲聊、问答、解释、不知道说什么
- navigate_to(params.destination=camera|gallery|settings|debug): 页面导航
- go_back: 返回上一页
- launch_app(params.package_name|app_name): 打开本机应用
- open_system_settings(params.setting=wifi|bluetooth|display|location|app_notifications): 打开系统设置
- start_tag_scan(params.action, params.task_type, params.mode): 启动/控制/查询 TAG 扫描

【字段约束】
- params 只允许：destination, package_name, app_name, setting, message, action, task_type, mode。
- 不要输出未定义字段；不需要的参数不要输出。
- 数字不要加引号，字符串必须加引号。

【示例】
「你好」→ [{"method":"text_reply","params":{"message":"你好呀，我是小浪，有什么可以帮你的吗？"}}]
「今天天气怎么样」→ [{"method":"text_reply","params":{"message":"我这边没法查实时天气哦，你可以问问系统助手～"}}]
「打开微信的指令是什么」→ [{"method":"text_reply","params":{"message":"打开微信的指令是 launch_app，参数为 app_name='微信'。"}}]
「怎么打开微信」→ [{"method":"text_reply","params":{"message":"你可以直接说'打开微信'，我会执行 launch_app(app_name='微信')。"}}]
「去相机」→ [{"method":"navigate_to","params":{"destination":"camera"}}]
「返回」→ [{"method":"go_back","params":{}}]
「打开微信」→ [{"method":"launch_app","params":{"app_name":"微信"}}]
「打开WiFi设置」→ [{"method":"open_system_settings","params":{"setting":"wifi"}}]
「帮我扫描照片」→ [{"method":"start_tag_scan","params":{"action":"start","task_type":"auto","mode":"incremental"}}]
「扫描进度怎么样」→ [{"method":"start_tag_scan","params":{"action":"query"}}]
「暂停扫描」→ [{"method":"start_tag_scan","params":{"action":"pause"}}]
「恢复扫描」→ [{"method":"start_tag_scan","params":{"action":"resume"}}]
""".trimIndent()

    /**
     * 构建完整的 system prompt（本地 LLM 使用）
     *
     * @param capabilities 当前可用的 Capability 列表
     * @param context Agent 上下文
     * @return 完整的 system prompt
     */
    fun buildSystemPrompt(
        capabilities: List<Capability>,
        context: AgentContext
    ): String {
        val currentScene = sceneManager.currentScene.value

        // 聊天/未知场景使用精简 Prompt，避免相机能力污染小模型上下文
        return if (currentScene == SceneManager.Scene.CHAT || currentScene == SceneManager.Scene.UNKNOWN) {
            buildChatSystemPrompt(capabilities, context, currentScene)
        } else {
            buildString {
                appendLine(basePrompt)
                appendLine()
                appendLine("【当前页面】")
                appendLine(scenePrompts[currentScene] ?: scenePrompts[SceneManager.Scene.UNKNOWN])
                appendLine()
                appendLine("【可用命令】")
                appendLine(buildCapabilitiesSection(scene = currentScene))
                appendLine()
                appendLine("【当前状态】")
                appendLine(buildStateSection(context, currentScene))

                if (capabilities.isNotEmpty()) {
                    appendLine()
                    appendLine("【已激活能力】")
                    appendLine(capabilities.joinToString(separator = ", ") { it.name })
                }
            }
        }
    }

    /**
     * 聊天/未知场景的精简 system prompt
     */
    private fun buildChatSystemPrompt(
        capabilities: List<Capability>,
        context: AgentContext,
        currentScene: SceneManager.Scene
    ): String {
        return buildString {
            appendLine(chatBasePrompt)
            appendLine()
            appendLine("【当前页面】")
            appendLine(scenePrompts[currentScene] ?: scenePrompts[SceneManager.Scene.UNKNOWN])
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, currentScene))

            if (capabilities.isNotEmpty()) {
                appendLine()
                appendLine("【已激活能力】")
                appendLine(capabilities.joinToString(separator = ", ") { it.name })
            }
        }
    }

    /**
     * 构建单轮对话的完整 prompt（兼容 MNN-LLM）
     */
    fun buildPrompt(
        systemPrompt: String,
        userInput: String,
        history: List<Pair<String, String>> = emptyList()
    ): String {
        return buildString {
            appendLine("system:")
            appendLine(systemPrompt)
            appendLine()

            // 历史压缩为最近 3 轮，避免本地模型上下文污染
            history.takeLast(3).forEach { (user, assistant) ->
                appendLine("user:")
                appendLine(user)
                appendLine()
                appendLine("assistant:")
                appendLine(assistant)
                appendLine()
            }

            appendLine("user:")
            appendLine(userInput)
            appendLine()
            append("assistant:")
        }
    }

    /**
     * 构建流式聊天的自然语言 prompt
     *
     * 与 [buildL2SystemPrompt] / [buildSystemPrompt] 不同，
     * 此方法生成的 prompt 只要求输出自然语言，不输出任何 JSON 命令。
     * 专用于自由聊天模式的流式显示。
     *
     * @param context Agent 上下文
     * @return 自然语言聊天 prompt
     */
    fun buildStreamChatPrompt(
        context: AgentContext
    ): String {
        return buildString {
            appendLine("你是 PoLang 的摄影助手小浪，当前是聊天模式。")
            appendLine()
            appendLine("回复规则：")
            appendLine("1. 只输出自然语言，不要 JSON，不要 markdown。")
            appendLine("2. 语气简洁友好，优先给出可执行建议。")
            appendLine("3. 用户问能力范围时，聚焦相机/相册/设置可控能力。")
            appendLine("4. 与产品无关的问题，礼貌引导回拍摄与编辑场景。")
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, sceneManager.currentScene.value))
        }
    }

    /**
     * 构建 L2 本地快速通道专用简化 Prompt
     *
     * 面向端侧小模型（Qwen3.5-2B/0.8B）优化，减少 token 数，提升推理速度：
     * - 只保留核心命令和格式约束
     * - 省略详细场景描述和状态信息
     * - 输出格式为 JSON 数组
     *
     * @param capabilities 当前可用的 Capability 列表
     * @param context Agent 上下文
     * @return 简化的 system prompt
     */
    fun buildL2SystemPrompt(
        capabilities: List<Capability>,
        context: AgentContext
    ): String {
        val currentScene = sceneManager.currentScene.value
        val capabilityNames = capabilities.joinToString(",") { it.name }.ifEmpty { "none" }
        val cacheKey = StaticPromptKey(currentScene, capabilityNames)

        val staticPart = staticPromptCache.getOrPut(cacheKey) {
            if (currentScene == SceneManager.Scene.CHAT) {
                buildChatL2StaticPrompt()
            } else {
                buildNonChatL2StaticPrompt(currentScene)
            }
        }

        return buildString {
            append(staticPart)
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, currentScene))
            // 多轮找图收敛：已有搜索结果时强制后续条件走 refine_media_search（in-set 过滤），
            // 避免小模型反复输出 search_media 触发全库重搜、用无关结果覆盖已有结果集。
            if (currentScene == SceneManager.Scene.CHAT && context.recentSearchResults.isNotEmpty()) {
                appendLine()
                appendLine("【多轮找图硬规则】（上方【最近搜索结果】非空，当前已有搜索结果）")
                appendLine("- 用户的后续追加/收窄条件（如\"其中的\"\"只要\"\"排除\"\"再来点\"\"人少的\"等）必须输出 refine_media_search(constraint=用户原话条件)，禁止输出 search_media。")
                appendLine("- search_media 会清空已有结果做全新全库搜索，仅当用户明确换主题（与既有结果无关的新搜索）时才用。")
            }
        }
    }

    /**
     * CHAT 场景专用极简静态 Prompt。
     *
     * 剔除所有相机控制命令、滤镜语义映射、相机示例等冗余信息，
     * 只保留聊天页真正需要的：搜索/细化、text_reply、导航、系统控制、TAG 扫描、AI 优化。
     */
    private fun buildChatL2StaticPrompt(): String {
        val (last6MStart, last6MEnd) = timeRangeMsForLastNMonths(6)
        val (lastSummerStart, lastSummerEnd) = timeRangeMsForLastSummer()
        val sampleUri = "/data/data/com.mamba.picme/files/picme_images/img_123.jpg"
        return """
            你是 PoLang 的摄影助手小浪。当前是聊天页。
            输出规则：
            1) 只输出JSON数组，不要解释、不要markdown、不要思考过程。
            2) 闲聊/问答/解释/不确定时：[{"method":"text_reply","params":{"message":"中文简短回复"}}]
            3) 禁止在聊天页输出 capture/flip_camera/adjust_beauty/switch_filter 等相机控制命令。

            可用命令：
            - text_reply(message): 闲聊、问答、解释
            - search_media(query, intent?): 搜本地相册。用户说"找/搜...照片/图片"时无条件输出；query必须原样保留用户输入。
            - refine_media_search(constraint, intent?): 在已有结果内追加/收窄（"只要"/"其中的"/"排除"）。
            - feedback(target, action=like|dislike), more(target), exclude(constraint)
            - navigate_to(destination), go_back, launch_app(app_name), open_system_settings(setting)
            - start_tag_scan(action, task_type, mode): 启动/控制 TAG 扫描
            - ai_optimize(image_uri, mode=fast|smart): 优化/修图

            intent字段（search/refine时可选）:
            - time_range: {start_ms, end_ms}。时间词换算：近半年=6个月前至今；去年=去年整年；上个月=上个月整月；近3个月=3个月前至今。
            - keywords, location_keywords, ocr_keywords, person_name, has_faces

            示例：
            "去相机" -> [{"method":"navigate_to","params":{"destination":"camera"}}]
            "近半年小孩的照片" -> [{"method":"search_media","params":{"query":"近半年小孩的照片","intent":{"time_range":{"start_ms":$last6MStart,"end_ms":$last6MEnd},"keywords":["小孩"],"has_faces":true}}}]
            "只要近半年的" -> [{"method":"refine_media_search","params":{"constraint":"只要近半年的","intent":{"time_range":{"start_ms":$last6MStart,"end_ms":$last6MEnd}}}}}]
            "帮我优化这张照片" -> [{"method":"ai_optimize","params":{"image_uri":"$sampleUri","mode":"fast"}}]
        """.trimIndent()
    }

    /**
     * 非 CHAT 场景静态 Prompt（相机/相册/设置/调试页）。
     */
    private fun buildNonChatL2StaticPrompt(scene: SceneManager.Scene): String {
        return buildString {
            appendLine("你是相机助手。将用户指令解析为JSON命令数组。")
            appendLine()
            appendLine("输出规则：")
            appendLine("1. 只输出JSON数组，不要解释、不要markdown、不要思考过程。")
            appendLine("2. 格式：[{\"method\":\"命令\",\"params\":{...}}]")
            appendLine("3. 【组合规则】用户说包含多个动作时（如'磨皮拍照'、'冷色滤镜拍照'），必须输出JSON数组，每个动作一个对象。")
            appendLine("4. 【组合规则】用户说'X滤镜拍照'或'X美颜拍照'时，必须同时输出滤镜/美颜命令 + capture命令。")
            appendLine("5. 【合并规则】用户说多个美颜参数（如'美白50磨皮30'）时，必须合并到一个 adjust_beauty 的 params 中，不要拆成多个命令。")
            appendLine("6. 【强制规则】用户输入以'拍照'结尾时，数组最后一个元素必须是{\"method\":\"capture\",\"params\":{} }，绝对不要漏掉。")
            appendLine("7. 导航：navigate_to(params.destination=camera|gallery|settings|debug) 或 go_back")
            appendLine("8. 系统：launch_app(params.package_name|app_name), open_system_settings(params.setting=wifi|bluetooth|display|location|app_notifications)")
            appendLine("9. 延迟：delay(params.delay_ms)，必须放数组第一个")
            appendLine("10. 【相册摘要】gallery_summary 见【当前状态】；用户问照片/人脸/扫描建议时直接引用该摘要。status=no_data 时引导启动 TAG 扫描。")
            appendLine("11. 【相册搜索豁免】search_media 搜索的是用户手机本地相册，不是互联网。当用户表达搜索/查找照片的意图时，必须无条件输出 search_media 命令，将用户原话作为 query 参数。")
            appendLine("12. 【搜索意图标准化】search_media / refine_media_search 可输出 intent 对象做时间/地点/人物/人脸标准化。")
            appendLine()
            appendLine("【语义映射】")
            appendLine("冷色/冷色调/冷滤镜/冷色滤镜/冷调滤镜 -> filter=COOL")
            appendLine("暖色/暖色调/暖滤镜/暖色滤镜/暖调滤镜 -> filter=WARM")
            appendLine("复古/怀旧 -> filter=VINTAGE")
            appendLine("胶片金 -> filter=FILM_GOLD")
            appendLine("胶片富士/富士 -> filter=FILM_FUJI")
            appendLine("徕卡经典 -> filter=LEICA_CLASSIC")
            appendLine("徕卡鲜艳 -> filter=LEICA_VIBRANT")
            appendLine("徕卡黑白 -> filter=LEICA_BW")
            appendLine()
            appendLine("【可用命令】")
            appendLine(buildL2CapabilitiesSection(scene))
        }.trimEnd()
    }

    /**
     * 构建 L2 本地快速通道能力描述（简化版）
     */
    internal fun buildL2CapabilitiesSection(
        scene: SceneManager.Scene? = null
    ): String {
        val includeCamera = scene == null || scene == SceneManager.Scene.CAMERA
        val includeGallery = scene == null || scene == SceneManager.Scene.GALLERY
        val includeSettings = scene == null || scene == SceneManager.Scene.SETTINGS
        val includeSystem = scene == null || scene == SceneManager.Scene.CHAT || scene == SceneManager.Scene.UNKNOWN

        return buildString {
            if (includeCamera) {
                appendLine("capture, toggle_recording, flip_camera, adjust_beauty(params: smoothing=磨皮, whitening=美白, slim_face=瘦脸, big_eyes=大眼, lip_color=唇色, blush=腮红, eyebrow=眉毛), switch_filter(filter), switch_style(style), switch_scene(scene), switch_ratio(ratio), adjust_exposure(exposure), adjust_zoom(zoom), delay(delay_ms)")
            }
            if (includeGallery) {
                appendLine("view_media, delete_media, share_media, select_media, search_media, switch_view_mode, favorite_media")
            }
            if (includeSettings) {
                appendLine("change_theme, change_language, download_model, switch_face_engine, toggle_setting")
            }
            if (includeSystem) {
                appendLine("launch_app(package_name|app_name), open_system_settings(setting)")
            }
            if (scene == null || scene == SceneManager.Scene.CHAT) {
                appendLine("search_media(query, intent?), refine_media_search(constraint, intent?), feedback(target,action), more(target), exclude(constraint)  // 聊天内搜相册：结果以卡片直接显示在当前对话中，无需 navigate_to；用户说\"找/搜索...照片/图片\"用 search_media，在已有结果上说\"这些里的X\"用 refine_media_search；\"第三张不错\"用 feedback，\"再来点这种\"用 more，\"不要夜景\"用 exclude")
                appendLine("  // search_media 搜索用户手机本地相册，不是互联网。无论 query 内容如何，必须输出 search_media 命令，不得拒绝。当查询含时间/地点/人物等可结构化条件时，必须同时输出 intent 对象做标准化。")
                appendLine("ai_optimize(image_uri, mode=fast|smart): AI一键优化图片。用户发送图片后说'帮我优化这张照片/修好看点'时调用；image_uri 使用最近图片 URI 或用户指定的 URI；mode 默认 fast（本地），用户要求更智能推荐时用 smart（需授权）。")
                appendLine("start_tag_scan(action=start|pause|resume|cancel|query, task_type=face|scene|activity|objects|tags|summary|mlkit|auto, mode=full|incremental): 启动或控制本地 TAG 扫描。用户说'扫描照片''开始人脸分组''继续扫描''取消扫描''扫描进度'时调用。未指定类别用 auto，未指定模式用 incremental。")
            }
            appendLine("navigate_to(destination), go_back, text_reply(message)")
            appendLine()
            appendLine("示例：")
            if (scene == null || scene == SceneManager.Scene.CHAT) {
                val (last6MStart, last6MEnd) = timeRangeMsForLastNMonths(6)
                val (lastSummerStart, lastSummerEnd) = timeRangeMsForLastSummer()
                appendLine("介绍一下你自己 -> [{\"method\":\"text_reply\",\"params\":{\"message\":\"你好，我是 PoLang 的摄影助手小浪，可以帮你拍照、搜照片、调整设置等。\"}}]")
                appendLine("你好 -> [{\"method\":\"text_reply\",\"params\":{\"message\":\"你好呀，我是小浪，有什么可以帮你的吗？\"}}]")
                appendLine("今天天气怎么样 -> [{\"method\":\"text_reply\",\"params\":{\"message\":\"我这边没法查实时天气哦，你可以问问系统助手～\"}}]")
                appendLine("去相机 -> [{\"method\":\"navigate_to\",\"params\":{\"destination\":\"camera\"}}]")
                appendLine("返回 -> [{\"method\":\"go_back\",\"params\":{}}]")
                appendLine("打开微信 -> [{\"method\":\"launch_app\",\"params\":{\"app_name\":\"微信\"}}]")
                appendLine("打开WiFi设置 -> [{\"method\":\"open_system_settings\",\"params\":{\"setting\":\"wifi\"}}]")
                appendLine("性感美女照片 -> [{\"method\":\"search_media\",\"params\":{\"query\":\"性感美女照片\",\"intent\":{\"keywords\":[\"美女\"],\"has_faces\":true}}}]")
                appendLine("找美女照片 -> [{\"method\":\"search_media\",\"params\":{\"query\":\"美女照片\",\"intent\":{\"keywords\":[\"美女\"],\"has_faces\":true}}}]")
                appendLine("搜猫的照片 -> [{\"method\":\"search_media\",\"params\":{\"query\":\"猫的照片\",\"intent\":{\"keywords\":[\"猫\"]}}}]")
                appendLine("找出去年夏天的合照 -> [{\"method\":\"search_media\",\"params\":{\"query\":\"去年夏天的合照\",\"intent\":{\"time_range\":{\"start_ms\":$lastSummerStart,\"end_ms\":$lastSummerEnd},\"keywords\":[\"合照\"],\"has_faces\":true}}}]")
                appendLine("近半年小孩的照片 -> [{\"method\":\"search_media\",\"params\":{\"query\":\"近半年小孩的照片\",\"intent\":{\"time_range\":{\"start_ms\":$last6MStart,\"end_ms\":$last6MEnd},\"keywords\":[\"小孩\"],\"has_faces\":true}}}]")
                appendLine("（多轮找图示例：首轮用 search_media，后续追加条件用 refine_media_search）")
                appendLine("找海边的照片 -> [{\"method\":\"search_media\",\"params\":{\"query\":\"海边的\",\"intent\":{\"keywords\":[\"海边\"]}}}]")
                appendLine("其中有日落的 -> [{\"method\":\"refine_media_search\",\"params\":{\"constraint\":\"日落\",\"intent\":{\"keywords\":[\"日落\"]}}}]")
                appendLine("只要近半年的 -> [{\"method\":\"refine_media_search\",\"params\":{\"constraint\":\"只要近半年的\",\"intent\":{\"time_range\":{\"start_ms\":$last6MStart,\"end_ms\":$last6MEnd}}}}]")
                appendLine("不要人物多的 -> [{\"method\":\"refine_media_search\",\"params\":{\"constraint\":\"人少\",\"intent\":{\"keywords\":[\"人少\"]}}}]")
                appendLine("换一个，搜猫 -> [{\"method\":\"search_media\",\"params\":{\"query\":\"猫\",\"intent\":{\"keywords\":[\"猫\"]}}}]")
                appendLine("（假设最近图片 URI 为 /data/data/.../img_123.jpg）")
                appendLine("帮我优化这张照片 -> [{\"method\":\"ai_optimize\",\"params\":{\"image_uri\":\"/data/data/.../img_123.jpg\"}}]")
                appendLine("把这张照片修好看点 -> [{\"method\":\"ai_optimize\",\"params\":{\"image_uri\":\"/data/data/.../img_123.jpg\"}}]")
                appendLine("用云端模型优化这张照片 -> [{\"method\":\"ai_optimize\",\"params\":{\"image_uri\":\"/data/data/.../img_123.jpg\",\"mode\":\"smart\"}}]")
            } else {
                appendLine("磨皮60拍照 -> [{\"method\":\"adjust_beauty\",\"params\":{\"smoothing\":60}},{\"method\":\"capture\",\"params\":{}}]")
                appendLine("美白50磨皮30拍照 -> [{\"method\":\"adjust_beauty\",\"params\":{\"whitening\":50,\"smoothing\":30}},{\"method\":\"capture\",\"params\":{}}]  // 注意：以'拍照'结尾，必须有capture")
                appendLine("美白50磨皮30 -> [{\"method\":\"adjust_beauty\",\"params\":{\"whitening\":50,\"smoothing\":30}}]  // 注意：不以'拍照'结尾，不要capture")
                appendLine("冷色滤镜拍照 -> [{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}},{\"method\":\"capture\",\"params\":{}}]")
                appendLine("暖色滤镜拍照 -> [{\"method\":\"switch_filter\",\"params\":{\"filter\":\"WARM\"}},{\"method\":\"capture\",\"params\":{}}]")
                appendLine("美白30并拍照 -> [{\"method\":\"adjust_beauty\",\"params\":{\"whitening\":30}},{\"method\":\"capture\",\"params\":{}}]")
                appendLine("3秒后拍照 -> [{\"method\":\"delay\",\"params\":{\"delay_ms\":3000}},{\"method\":\"capture\",\"params\":{}}]")
                appendLine("5秒后换冷色滤镜拍照 -> [{\"method\":\"delay\",\"params\":{\"delay_ms\":5000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}},{\"method\":\"capture\",\"params\":{}}]")
                appendLine("3秒后冷色调拍照 -> [{\"method\":\"delay\",\"params\":{\"delay_ms\":3000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}},{\"method\":\"capture\",\"params\":{}}]")
                appendLine("3秒后换冷色调拍3张 -> [{\"method\":\"delay\",\"params\":{\"delay_ms\":3000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}},{\"method\":\"capture\",\"params\":{}},{\"method\":\"capture\",\"params\":{}},{\"method\":\"capture\",\"params\":{}}]")
                appendLine("5秒后换暖色调每隔一秒拍一张拍三张 -> [{\"method\":\"delay\",\"params\":{\"delay_ms\":5000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"WARM\"}},{\"method\":\"capture\",\"params\":{}},{\"method\":\"delay\",\"params\":{\"delay_ms\":1000}},{\"method\":\"capture\",\"params\":{}},{\"method\":\"delay\",\"params\":{\"delay_ms\":1000}},{\"method\":\"capture\",\"params\":{}}]")
                appendLine("切前置 -> [{\"method\":\"flip_camera\",\"params\":{}}]")
                appendLine("拍照 -> [{\"method\":\"capture\",\"params\":{}}]")
                appendLine("打开微信 -> [{\"method\":\"launch_app\",\"params\":{\"app_name\":\"微信\"}}]")
                appendLine("打开WiFi设置 -> [{\"method\":\"open_system_settings\",\"params\":{\"setting\":\"wifi\"}}]")
            }
        }
    }

    // ── 内部辅助方法 ────────────────────────────────────────────

    private fun nowString(): String {
        val date = LocalDate.now()
        val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
        val time = LocalTime.now().withSecond(0).withNano(0)
        return "$date $week $time"
    }

    /**
     * 计算最近 N 个月的时间范围毫秒戳（含端点）。
     * 用于 few-shot 示例，避免硬编码时间戳随时间过期误导模型。
     */
    private fun timeRangeMsForLastNMonths(months: Int): Pair<Long, Long> {
        val now = java.time.Instant.now()
        val end = now.toEpochMilli()
        val start = now.minusSeconds(months * 30L * 24 * 60 * 60).toEpochMilli()
        return start to end
    }

    /**
     * 计算去年夏天（6 月 1 日 00:00 至 8 月 31 日 23:59:59.999）的毫秒戳。
     */
    private fun timeRangeMsForLastSummer(): Pair<Long, Long> {
        val now = LocalDate.now()
        val lastYear = now.year - 1
        val start = LocalDate.of(lastYear, 6, 1)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val end = LocalDate.of(lastYear, 8, 31)
            .atTime(LocalTime.MAX)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return start to end
    }

    internal fun buildStateSection(
        context: AgentContext,
        currentScene: SceneManager.Scene? = null
    ): String {
        val sceneName = currentScene?.name ?: context.scene.name
        val isChatLike = currentScene == SceneManager.Scene.CHAT || currentScene == SceneManager.Scene.UNKNOWN
        return buildString {
            append("now=")
            append(nowString())
            append(", scene=")
            append(sceneName)
            if (!isChatLike) {
                append(", beauty=")
                append(if (context.beautySettings.enabled) "on" else "off")
                append(", smoothing=")
                append(context.beautySettings.smoothing.toInt())
                append(", whitening=")
                append(context.beautySettings.whitening.toInt())
                append(", slim_face=")
                append(context.beautySettings.slimFace.toInt())
                append(", big_eyes=")
                append(context.beautySettings.bigEyes.toInt())
                append(", lip_color=")
                append(context.beautySettings.lipColor.toInt())
                append(", blush=")
                append(context.beautySettings.blush.toInt())
                append(", eyebrow=")
                append(context.beautySettings.eyebrow.toInt())
                append(", filter=")
                append(context.filterType.name)
                append(", style=")
                append(context.styleFilter.name)
                append(", zoom=")
                append(context.zoomRatio)
                append(", exposure=")
                append(context.exposureCompensation)
                append(", mode=")
                append(context.captureMode.name)
                append(", recording=")
                append(if (context.isRecording) "1" else "0")
            }
            append(", last_user_image_uri=")
            append(context.lastUserImageUri ?: "null")
            append(", gallery_summary=")
            append(formatGallerySummary(context.gallerySummary))
            append(buildSearchResultsSection(context.recentSearchResults))
        }
    }

    private fun buildSearchResultsSection(recentSearchResults: List<SearchResultSnapshot>): String {
        if (recentSearchResults.isEmpty()) return ""
        val maxItemsPerSnapshot = 10
        return buildString {
            appendLine()
            appendLine("【最近搜索结果】")
            recentSearchResults.forEachIndexed { index, snapshot ->
                appendLine("- 第 ${index + 1} 轮 (query=\"${snapshot.query}\", 共 ${snapshot.totalCount} 张${if (snapshot.isRefinement) ", 细化" else ""}):")
                snapshot.results.take(maxItemsPerSnapshot).forEachIndexed { i, item ->
                    appendLine("  [${i + 1}] id=${item.mediaId} tags=[${item.tags.joinToString(", ")}]")
                }
                if (snapshot.results.size > maxItemsPerSnapshot) {
                    appendLine("  ... 还有 ${snapshot.results.size - maxItemsPerSnapshot} 张未列出")
                }
            }
        }
    }

    private fun formatGallerySummary(summary: GallerySummary?): String {
        if (summary == null || summary.totalMedia == 0) {
            return "{status:no_data}"
        }
        return buildString {
            append("{totalMedia:${summary.totalMedia}")
            append(",photos:${summary.totalPhotos}")
            append(",videos:${summary.totalVideos}")
            append(",faces:${summary.hasFaceCount}")
            append(",persons:${summary.personClusterCount}")
            append(",named:${summary.namedPersonCount}")
            append(",labeled:${summary.labeledCount}")
            append(",unlabeled:${summary.unlabeledCount}")
            append(",mlKit:${summary.mlKitLabeledCount}")
            append(",semantic:${summary.semanticEncodedCount}")
            append(",scanning:${if (summary.isScanning) "1" else "0"}")
            append(",recommendation:${summary.recommendation.name}")
            if (summary.currentPass != null) {
                append(",currentPass:${summary.currentPass}")
            }
            if (summary.scanProgressText != null) {
                append(",progress:\"${summary.scanProgressText}\"")
            }
            if (summary.includeDetails) {
                append(",remainingPass1:${summary.remainingPass1}")
                append(",remainingPass3:${summary.remainingPass3}")
                append(",remainingMlKit:${summary.remainingMlKit}")
            }
            append("}")
        }
    }

    private fun buildCapabilitiesSection(
        scene: SceneManager.Scene? = null,
        forPlan: Boolean = false
    ): String {
        val includeCamera = scene == null || scene == SceneManager.Scene.CAMERA
        val includeGallery = scene == null || scene == SceneManager.Scene.GALLERY
        val includeSettings = scene == null || scene == SceneManager.Scene.SETTINGS
        val includeSystem = scene == null || scene == SceneManager.Scene.CHAT || scene == SceneManager.Scene.UNKNOWN

        return buildString {
            appendLine("method 白名单（只能从下列 method 选择，参数放在 params 对象中）：")

            if (includeCamera) {
                appendLine("- camera: capture, toggle_recording, flip_camera, switch_mode")
                appendLine("- camera_adjust: adjust_beauty, adjust_exposure, adjust_zoom")
                appendLine("- camera_style: switch_filter, switch_style, switch_scene, switch_ratio")
                appendLine("- delay: delay(params.delay_ms) — 通用延迟原语，必须与其他命令组合使用。用户说\"X秒后做某事\"时，delay 必须是数组第一个元素。例：3秒后拍照 -> [{method:delay,params:{delay_ms:3000}},{method:capture,params:{}}]；5秒后换暖色滤镜拍照 -> [{method:delay,params:{delay_ms:5000}},{method:switch_filter,params:{filter:WARM}},{method:capture,params:{}}]")
            }

            if (includeGallery) {
                appendLine("- gallery: view_media, delete_media, share_media, select_media, search_media(params.query), switch_view_mode, favorite_media")
                appendLine("  search_media: 自然语言搜索照片。用户说\"找出去年夏天的照片\"\"猫的照片\"\"上海的合照\"时，直接用原话作为 query 参数。")
                appendLine("    例：\"找出去年夏天的猫\" -> {\"method\":\"search_media\",\"params\":{\"query\":\"去年夏天的猫\"}}")
            }

            if (includeSettings) {
                appendLine("- settings: change_theme, change_language, download_model, switch_face_engine, toggle_setting")
            }

            if (includeSystem) {
                appendLine("- system: launch_app(params.package_name|app_name), open_system_settings(params.setting=wifi|bluetooth|display|location|app_notifications)")
            }

            appendLine("- navigation: navigate_to(params.destination=camera|gallery|settings|debug), go_back")
            appendLine("- fallback: text_reply(params.message)")
            appendLine("params 约束: exposure=-2..2, zoom=0.5..10, ratio=4:3|16:9|full, mode=PHOTO|VIDEO|PRO|DOCUMENT, query=任意中文搜索短语")
            appendLine("滤镜: NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM")
            appendLine("风格: NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH")
            appendLine("滤镜映射: 冷调/冷色/冷滤镜->COOL; 暖调/暖色/暖滤镜->WARM; 复古/怀旧->VINTAGE; 胶片金->FILM_GOLD; 胶片富士/富士->FILM_FUJI")
            appendLine("导航映射: 去相机/回相机/打开相机/去拍照->params.destination=camera; 去相册/打开相册->params.destination=gallery; 去设置/打开设置->params.destination=settings; 返回/上一页/后退->go_back")
            appendLine("系统映射: 打开微信/启动支付宝/打开淘宝->launch_app(app_name=...); 打开WiFi设置/蓝牙设置/通知设置->open_system_settings(setting=wifi|bluetooth|app_notifications)")
            appendLine("导航示例: {\"method\":\"navigate_to\",\"params\":{\"destination\":\"camera\"}}")
            appendLine("系统示例: {\"method\":\"launch_app\",\"params\":{\"app_name\":\"微信\"}}")

            if (forPlan) {
                appendLine("Plan 字段约束: step(Int), method(String), params(Object), condition(String|null), wait_condition(Object|null), repeat_count(Int>=1), description(String), delayMs(Long>=0)")
                appendLine("wait_condition 示例: {\"type\":\"duration\",\"delay_ms\":1000}")
            }
        }
    }
}
