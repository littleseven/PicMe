package com.mamba.picme.agent.core.inference.remote.prompt

import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * 远程 LLM Prompt 构建器
 *
 * 面向远程大模型（通过 OpenAI 兼容 API），使用标准 OpenAI tool_calls 协议。
 * 提供三种 Prompt 模板：
 * - Batch: L2 批量命令解析（tool_calls）
 * - Plan: L3 计划执行（ExecutionPlan JSON，内部步使用 method/params 格式）
 * - Chat: L4 纯文本对话
 */
class RemotePromptBuilder(
    private val sceneManager: SceneManager
) {

    /**
     * 构建 L2 Batch 模式 Prompt（远程 LLM 使用）
     *
     * 模型通过 tools 参数中的 ToolSpecifications 定义以 tool_calls 协议输出命令。
     * 禁止输出 method/params 格式的文本 JSON，只接受标准 OpenAI tool_calls。
     *
     * **DeepSeek 适配要点**：
     * - 不在 Prompt 中提供具体的 tool_calls JSON 示例，避免模型将 JSON 模仿输出到 content 字段
     * - 依赖原生 function calling 机制，由 API 自动处理 tool_calls 字段
     * - 若使用 DeepSeek V4，API 请求会自动禁用 thinking 模式以确保格式稳定
     */
    fun buildBatchPrompt(userInput: String, context: AgentContext): String {
        return buildString {
            appendLine("你是 PoLang 的指令解析器。使用 function calling 调用工具来响应用户指令。")
            appendLine()
            appendLine("规则：")
            appendLine("1. 当需要执行工具时，直接发起函数调用（function calling），系统会自动解析并执行。")
            appendLine("2. 如果需要先执行 A 再执行 B，在同一个响应中输出多个工具调用。")
            appendLine("3. 用户说包含时间/延迟的指令（如\"3秒后拍照\"、\"5秒后换滤镜\"）时，delay 必须在工具调用列表的第一个位置，后面跟后续函数。")
            appendLine("4. 用户说多个美颜参数（如\"美白50磨皮30\"）时，只调用一次 adjust_beauty，传入所有参数。")
            appendLine("5. 用户输入以\"拍照\"结尾时，最后一次调用必须是 capture。")
            appendLine("6. 用户要求拍多张时（如\"拍三张\"、\"连拍\"），调用多次 capture，中间可以插入 delay。")
            appendLine("7. 如果用户是闲聊或无法用现有函数表达，调用 text_reply 回复。")
            appendLine("8. 不要在回复文本中输出 JSON 格式的工具调用，也不要使用 <think> 标签。")
            appendLine("9. 禁止输出 method/params 格式的 JSON 数组（如 [{\"method\":\"...\",\"params\":{}}]）。")
            if (context.scene == com.mamba.picme.agent.core.model.context.AgentScene.CHAT) {
                appendLine("10. 【聊天页导航硬规则】当前是聊天页。除非用户明确说\"去相机/去相册/去设置/返回/上一页/后退\"，否则不要调用 navigate_to / go_back；模糊表述（如\"我想看看相册\"）请调用 text_reply 回复，禁止自动跳转。")
            }
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, sceneManager.currentScene.value))
            appendLine()
            appendLine("可用函数列表请参考 tools 参数中的定义。")
            appendLine()
            appendLine("【示例说明】（仅描述意图，禁止模仿输出 JSON）")
            appendLine("用户: 3秒后拍照 -> 先调用 delay 等待 3000ms，再调用 capture 拍照")
            appendLine("用户: 5秒后换暖色滤镜拍照 -> 先调用 delay 等待 5000ms，再调用 switch_filter 切换 WARM 滤镜，最后调用 capture 拍照")
            appendLine("用户: 你好 -> 调用 text_reply 回复问候")
            appendLine("用户: 磨皮50美白30 -> 调用 adjust_beauty 同时传入 smoothing=50 和 whitening=30")
        }
    }

    /**
     * 构建 L3 Plan 模式 Prompt
     *
     * 输出格式为 ExecutionPlan JSON，steps 中的命令使用标准 tool_calls 格式（name + arguments）。
     * 与 L2 Batch 保持一致，统一使用 OpenAI tool_calls 协议，不混合 method/params 格式。
     */
    fun buildPlanPrompt(userInput: String, context: AgentContext): String {
        return buildString {
            appendLine("你是 PoLang 的任务编排器。把用户复杂请求转成 ExecutionPlan JSON。")
            appendLine()
            appendLine("输出硬规则：")
            appendLine("1. 只能输出一个 JSON 对象，禁止解释、禁止 markdown、禁止 <thinking>。")
            appendLine("2. 顶层字段固定：plan_id, description, steps。")
            appendLine("3. steps 每项字段固定：step, command, condition, wait_condition, repeat_count, description, delayMs。")
            appendLine("4. command 字段是标准 tool_calls 格式：{name: 命令名, arguments: 参数对象}。")
            appendLine("5. 禁止在 command 中使用 method/params 格式，必须使用 name/arguments 格式。")
            appendLine("6. wait_condition 仅支持：duration(delay_ms), face_detected(timeout_ms), smile_detected(timeout_ms), user_confirm(prompt)。")
            appendLine("7. repeat_count >= 1，delayMs >= 0。")
            appendLine("8. 导航动作严格使用 navigate_to/go_back。")
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, sceneManager.currentScene.value))
            appendLine()
            appendLine("【命令全集】")
            appendLine(buildCapabilitiesSection(scene = null, forPlan = true))
            appendLine()
            appendLine("【示例】")
            appendLine("用户: 去相机后等1秒连拍3张")
            appendLine("-> {plan_id:plan_1,description:切到相机后连拍,steps:[{step:1,command:{name:navigate_to,arguments:{destination:camera}},condition:null,wait_condition:null,repeat_count:1,description:切换到相机,delayMs:0},{step:2,command:{name:text_reply,arguments:{message:准备连拍}},condition:null,wait_condition:{type:duration,delay_ms:1000},repeat_count:1,description:等待1秒,delayMs:0},{step:3,command:{name:capture,arguments:{}},condition:null,wait_condition:null,repeat_count:3,description:连拍3张,delayMs:500}]}")
            appendLine("用户: 3秒后调暖色调拍照")
            appendLine("-> {plan_id:plan_2,description:延迟后调暖色调拍照,steps:[{step:1,command:{name:delay,arguments:{delay_ms:3000}},condition:null,wait_condition:null,repeat_count:1,description:等待3秒,delayMs:0},{step:2,command:{name:switch_filter,arguments:{filter:WARM}},condition:null,wait_condition:null,repeat_count:1,description:切换暖色调,delayMs:0},{step:3,command:{name:capture,arguments:{}},condition:null,wait_condition:null,repeat_count:1,description:拍照,delayMs:0}]}")
        }
    }

    /**
     * 构建 L4 Chat 模式 Prompt
     *
     * 纯文本对话，不输出 JSON。
     */
    fun buildChatPrompt(
        userInput: String,
        context: AgentContext,
        history: List<String> = emptyList()
    ): String {
        return buildString {
            appendLine("你是 PoLang 的摄影助手小浪。当前是聊天模式。")
            appendLine()
            appendLine("回复规则：")
            appendLine("1. 只输出中文自然语言，不要 JSON，不要 markdown。")
            appendLine("2. 语气简洁友好，优先给出可执行建议。")
            appendLine("3. 用户问能力范围时，聚焦相机/相册/设置可控能力。")
            appendLine("4. 与产品无关的问题，礼貌引导回拍摄与编辑场景。")
            appendLine()
            appendLine("【当前状态】")
            appendLine(buildStateSection(context, sceneManager.currentScene.value))

            if (history.isNotEmpty()) {
                appendLine()
                appendLine("【最近对话】")
                history.takeLast(5).forEachIndexed { index, message ->
                    appendLine("${index + 1}. $message")
                }
            }
        }
    }

    // ── 内部辅助方法 ────────────────────────────────────────────

    /**
     * Prompt 示例中的动态时间戳生成器。
     * 避免写死时间戳导致 LLM 在不同年份照搬过期数值。
     */
    internal val exampleTimestamps = ExampleTimestamps()

    /**
     * [exampleTimestamps] 的实现（命名类——匿名 object 成员在非 private 属性上不可见，
     * internal 可见性供 commonTest 锁行为）。
     *
     * kotlinx-datetime 化，语义与旧 java.time 实现逐处对齐：本地时区、毫秒时间戳。
     */
    internal class ExampleTimestamps(
        private val zone: TimeZone = TimeZone.currentSystemDefault(),
    ) {
        private fun today(): LocalDate =
            Clock.System.now().toLocalDateTime(zone).date

        fun lastYearSummer(): Pair<Long, Long> {
            val lastYear = today().year - 1
            val start = LocalDate(lastYear, 6, 1).atStartOfDayIn(zone).toEpochMilliseconds()
            val end = LocalDateTime(lastYear, 8, 31, 23, 59, 59, 999_000_000)
                .toInstant(zone).toEpochMilliseconds()
            return start to end
        }

        fun pastHalfYear(): Pair<Long, Long> {
            val now = today()
            val sixMonthsAgo = now.minus(6, DateTimeUnit.MONTH)
            val start = LocalDate(sixMonthsAgo.year, sixMonthsAgo.month, 1)
                .atStartOfDayIn(zone).toEpochMilliseconds()
            val end = LocalDateTime(now.year, now.month, now.dayOfMonth, 23, 59, 59, 999_000_000)
                .toInstant(zone).toEpochMilliseconds()
            return start to end
        }
    }

    /**
     * `now=` 状态段的当前时间串。格式与旧 java.time 实现逐字节对齐：
     * `yyyy-MM-dd 周X HH:mm`（java.time LocalTime.toString 在 second=0 时省略秒，
     * 故手动 padStart 而非用 kotlinx LocalTime.toString——后者恒输出 HH:mm:ss）。
     */
    internal fun nowString(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[now.dayOfWeek.ordinal]
        val hh = now.hour.toString().padStart(2, '0')
        val mm = now.minute.toString().padStart(2, '0')
        return "${now.date} $week $hh:$mm"
    }

    private fun buildStateSection(
        context: AgentContext,
        currentScene: SceneManager.Scene? = null
    ): String {
        val sceneName = currentScene?.name ?: context.scene.name
        return buildString {
            append("now=")
            append(nowString())
            append(", scene=")
            append(sceneName)
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
            appendLine("命令白名单（只能从下列 name 选择，参数放在 arguments 对象中）：")

            if (includeCamera) {
                appendLine("- camera: capture, toggle_recording, flip_camera, switch_mode")
                appendLine("- camera_adjust: adjust_beauty, adjust_exposure, adjust_zoom")
                appendLine("- camera_style: switch_filter, switch_style, switch_scene, switch_ratio")
                appendLine("- delay: delay(arguments.delay_ms) — 通用延迟原语，必须与其他命令组合使用。用户说\"X秒后做某事\"时，delay 必须是数组第一个元素。例：3秒后拍照 -> [{name:delay,arguments:{delay_ms:3000}},{name:capture,arguments:{}}]；5秒后换暖色滤镜拍照 -> [{name:delay,arguments:{delay_ms:5000}},{name:switch_filter,arguments:{filter:WARM}},{name:capture,arguments:{}}]")
            }

            if (includeGallery) {
                appendLine("- gallery: view_media, delete_media, share_media, select_media, search_media(params.query, params.intent), switch_view_mode, favorite_media")
                appendLine("  search_media: 自然语言搜索照片。query 参数填用户原话；当查询含时间/地点/人物/人脸等可结构化条件时，必须在 params.intent 中输出标准化条件：")
                appendLine("    - intent.time_range: {start_ms: 开始时间戳, end_ms: 结束时间戳}。当前时间见【当前状态】now=。必须把近半年/去年/上个月等相对时间换算成时间戳。")
                appendLine("    - intent.keywords: 场景/物体/标签内容词数组。注意：时间词（去年、夏天、近半年、上个月等）一旦用 time_range 表达，就不要再放进 keywords / location_keywords / ocr_keywords；keywords 只保留非时间内容词，整句只有时间词时可填 [] 或省略。")
                appendLine("    - intent.location_keywords: 地点词数组。")
                appendLine("    - intent.ocr_keywords: OCR 文字词数组。")
                appendLine("    - intent.person_name: 具体人物名，不确定时省略。")
                appendLine("    - intent.has_faces: true/false，用户明确找有人脸/合影/自拍时填 true。")
                val (lastSummerStart, lastSummerEnd) = exampleTimestamps.lastYearSummer()
                val (pastHalfYearStart, pastHalfYearEnd) = exampleTimestamps.pastHalfYear()
                appendLine("    例：\"去年夏天的照片\" -> {\"method\":\"search_media\",\"params\":{\"query\":\"去年夏天的照片\",\"intent\":{\"time_range\":{\"start_ms\":$lastSummerStart,\"end_ms\":$lastSummerEnd},\"keywords\":[]}}}")
                appendLine("    例：\"找出去年夏天的猫\" -> {\"method\":\"search_media\",\"params\":{\"query\":\"去年夏天的猫\",\"intent\":{\"time_range\":{\"start_ms\":$lastSummerStart,\"end_ms\":$lastSummerEnd},\"keywords\":[\"猫\"]}}}")
                appendLine("    例：\"近半年小孩的照片\" -> {\"method\":\"search_media\",\"params\":{\"query\":\"近半年小孩的照片\",\"intent\":{\"time_range\":{\"start_ms\":$pastHalfYearStart,\"end_ms\":$pastHalfYearEnd},\"keywords\":[\"小孩\"],\"has_faces\":true}}}")
            }

            if (includeSettings) {
                appendLine("- settings: change_theme, change_language, download_model, switch_face_engine, toggle_setting")
            }

            if (includeSystem) {
                appendLine("- system: launch_app(arguments.package_name|app_name), open_system_settings(arguments.setting=wifi|bluetooth|display|location|app_notifications)")
            }

            appendLine("- navigation: navigate_to(arguments.destination=camera|gallery|settings|debug), go_back")
            appendLine("- fallback: text_reply(arguments.message)")
            appendLine("arguments 约束: exposure=-2..2, zoom=0.5..10, ratio=4:3|16:9|full, mode=PHOTO|VIDEO|PRO|DOCUMENT")
            appendLine("滤镜: NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM")
            appendLine("风格: NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH")
            appendLine("滤镜映射: 冷调/冷色/冷滤镜->COOL; 暖调/暖色/暖滤镜->WARM; 复古/怀旧->VINTAGE; 胶片金->FILM_GOLD; 胶片富士/富士->FILM_FUJI")
            appendLine("导航映射: 去相机/回相机/打开相机/去拍照->arguments.destination=camera; 去相册/打开相册->arguments.destination=gallery; 去设置/打开设置->arguments.destination=settings; 返回/上一页/后退->go_back")
            appendLine("系统映射: 打开微信/启动支付宝/打开淘宝->launch_app(app_name=...); 打开WiFi设置/蓝牙设置/通知设置->open_system_settings(setting=wifi|bluetooth|app_notifications)")
            appendLine("导航示例: {\"name\":\"navigate_to\",\"arguments\":{\"destination\":\"camera\"}}")
            appendLine("系统示例: {\"name\":\"launch_app\",\"arguments\":{\"app_name\":\"微信\"}}")

            if (forPlan) {
                appendLine("Plan 字段约束: step(Int), command(Object{name,arguments}), condition(String|null), wait_condition(Object|null), repeat_count(Int>=1), description(String), delayMs(Long>=0)")
                appendLine("wait_condition 示例: {\"type\":\"duration\",\"delay_ms\":1000}")
            }
        }
    }
}
