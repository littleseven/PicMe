package com.mamba.picme.testing.agent.engine

import android.content.Context
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.core.common.Logger
import com.mamba.picme.testing.agent.data.ActionJson
import com.mamba.picme.testing.agent.data.DataDrivenTestCase
import com.mamba.picme.testing.agent.data.DataDrivenTestResult
import com.mamba.picme.testing.agent.data.TestExecutionContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Agent 测试引擎
 *
 * 纯数据驱动，支持动态加载 JSON 测试用例并执行。
 * 直接复用 CapabilityRegistry 分发 AgentCommand，获得结构化执行反馈。
 */
class AgentTestEngine(
    private val registry: CapabilityRegistry,
    private val probe: AgentStateProbe,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "AgentTestEngine"

        /**
         * 步骤间默认观察停顿（毫秒）
         * 设置为 800ms，既不会太慢，也足够人类看清界面变化
         */
        private const val DEFAULT_STEP_PAUSE_MS = 800L

        /**
         * 关键操作后的额外停顿（毫秒）
         * 用于拍照、切换摄像头等需要明显反馈的操作
         */
        private const val KEY_ACTION_PAUSE_MS = 1200L
    }

    /**
     * 执行数据驱动测试用例
     *
     * 执行流程带可观测性设计：
     * - 每个步骤开始/结束都有 INFO 级日志
     * - 关键操作后有固定停顿，方便人工观察
     * - 断言结果实时输出
     * - 截屏/性能采集结果可见
     */
    suspend fun execute(
        case: DataDrivenTestCase,
        agentContext: AgentContext = AgentContext(scene = AgentScene.CAMERA)
    ): DataDrivenTestResult {
        val startTime = System.currentTimeMillis()
        var context = TestExecutionContext(caseId = case.caseId, caseName = case.name)

        Logger.i(TAG, "╔════════════════════════════════════════════════════════════╗")
        Logger.i(TAG, "║  [TEST START] ${case.caseId}: ${case.name}")
        Logger.i(TAG, "║  优先级: ${case.priority}  分类: ${case.category}")
        Logger.i(TAG, "║  步骤数: ${case.steps.size}")
        Logger.i(TAG, "╚════════════════════════════════════════════════════════════╝")

        case.steps.forEachIndexed { index, step ->
            context = context.copy(currentStep = index)
            val stepStart = System.currentTimeMillis()

            Logger.i(TAG, "")
            Logger.i(TAG, "▶ Step ${index + 1}/${case.steps.size}: ${step.description}")

            // ── 1. 条件判断 ──
            if (step.condition != null) {
                Logger.i(TAG, "  ├─ Condition: ${step.condition}")
                val conditionMet = evaluateCondition(step.condition, probe)
                context = context.addLog("Test", "Condition '${step.condition}' = $conditionMet")
                if (!conditionMet) {
                    Logger.i(TAG, "  └─ SKIP (condition not met)")
                    return@forEachIndexed
                }
                Logger.i(TAG, "  └─ Condition PASSED")
            }

            // ── 2. 执行命令序列 ──
            val actions = step.allActions()
            if (actions.isNotEmpty()) {
                Logger.i(TAG, "  ├─ Actions (${actions.size}):")
                actions.forEach { action ->
                    Logger.i(TAG, "  │  → ${action.method} ${action.params?.let { "(params=$it)" } ?: ""}")

                    val command = parseToAgentCommand(action)
                    val result = registry.dispatch(command, agentContext)
                    context = context.addLog(
                        "Test",
                        "Command ${action.method} result: ${result.isSuccess}"
                    )

                    // 记录 AgentAction 反馈
                    result.getOrNull()?.let { actionResult ->
                        when (actionResult) {
                            is AgentAction.Success -> {
                                Logger.i(TAG, "  │    ✓ Success")
                                context = context.addLog("Test", "Action: Success")
                            }
                            is AgentAction.Error -> {
                                Logger.i(TAG, "  │    ✗ Error: ${actionResult.message}")
                                context = context.addLog(
                                    "Test",
                                    "Action: Error - ${actionResult.message}"
                                )
                            }
                            is AgentAction.TextReply -> {
                                Logger.i(TAG, "  │    ℹ TextReply: ${actionResult.message}")
                                context = context.addLog(
                                    "Test",
                                    "Action: TextReply - ${actionResult.message}"
                                )
                            }
                            is AgentAction.BatchResult -> {
                                Logger.i(TAG, "  │    ℹ BatchResult: ${actionResult.results.size} sub-results")
                                context = context.addLog(
                                    "Test",
                                    "Action: BatchResult - ${actionResult.results.size} sub-results, success=${actionResult.isSuccess}"
                                )
                            }
                            is AgentAction.MediaResults -> {
                                Logger.i(TAG, "  │    🖼️ MediaResults: ${actionResult.totalCount}")
                                context = context.addLog(
                                    "Test",
                                    "Action: MediaResults - ${actionResult.totalCount} results"
                                )
                            }
                        }
                    }

                    result.exceptionOrNull()?.let { error ->
                        Logger.e(TAG, "  │    ✗ Exception: ${error.message}", error)
                        context = context.addLog("Test", "Exception: ${error.message}")
                    }

                    // 关键操作后额外停顿，方便观察
                    if (isKeyAction(action.method)) {
                        Logger.i(TAG, "  │  ⏸ Pause ${KEY_ACTION_PAUSE_MS}ms (key action)")
                        delay(KEY_ACTION_PAUSE_MS)
                    }
                }
            }

            // ── 3. 等待条件 ──
            step.wait?.let { wait ->
                Logger.i(TAG, "  ├─ Wait: ${wait.condition} (timeout: ${wait.timeout}ms)")
                val waitStart = System.currentTimeMillis()
                val completed = withTimeoutOrNull(wait.timeout.milliseconds) {
                    while (!evaluateCondition(wait.condition, probe)) {
                        delay(200)
                    }
                    true
                } ?: false
                val waitElapsed = System.currentTimeMillis() - waitStart

                context = context.addLog("Test", "Wait completed: $completed")
                if (!completed) {
                    Logger.e(TAG, "  └─ ✗ TIMEOUT after ${waitElapsed}ms")
                    val failure = DataDrivenTestResult.Failure(
                        caseId = case.caseId,
                        failedStep = index,
                        stepDescription = step.description,
                        reason = "Wait timeout: ${wait.condition}",
                        context = context
                    )
                    Logger.i(TAG, "")
                    Logger.i(TAG, "╔════════════════════════════════════════════════════════════╗")
                    Logger.i(TAG, "║  [TEST FAIL] ${case.caseId} at Step ${index + 1}")
                    Logger.i(TAG, "║  Reason: Wait timeout: ${wait.condition}")
                    Logger.i(TAG, "╚════════════════════════════════════════════════════════════╝")
                    return failure
                }
                Logger.i(TAG, "  └─ ✓ Met in ${waitElapsed}ms")
            }

            // ── 4. 断言验证 ──
            step.assertions?.forEach { (key, expected) ->
                Logger.i(TAG, "  ├─ Assert: $key == '$expected'")
                val passed = evaluateAssertion(key, expected, probe, context)
                context = context.addLog("Test", "Assertion '$key' = '$expected': $passed")
                if (!passed) {
                    Logger.e(TAG, "  └─ ✗ FAILED (actual != '$expected')")
                    val failure = DataDrivenTestResult.Failure(
                        caseId = case.caseId,
                        failedStep = index,
                        stepDescription = step.description,
                        reason = "Assertion failed: $key expected '$expected'",
                        context = context
                    )
                    Logger.i(TAG, "")
                    Logger.i(TAG, "╔════════════════════════════════════════════════════════════╗")
                    Logger.i(TAG, "║  [TEST FAIL] ${case.caseId} at Step ${index + 1}")
                    Logger.i(TAG, "║  Reason: Assertion failed: $key expected '$expected'")
                    Logger.i(TAG, "╚════════════════════════════════════════════════════════════╝")
                    return failure
                }
                Logger.i(TAG, "  └─ ✓ PASSED")
            }

            // ── 5. 截屏标记（由PC端adb执行实际截屏） ──
            step.screenshot?.let { screenshotName ->
                Logger.i(TAG, "  ├─ Screenshot marker: $screenshotName")
                Logger.i(TAG, "  └─ ℹ PC端请执行: adb shell screencap -p /sdcard/${screenshotName}.png")
            }

            // ── 6. 性能采集标记（由PC端adb执行实际采集） ──
            step.collect?.let { collectList ->
                Logger.i(TAG, "  ├─ Collect marker: $collectList")
                Logger.i(TAG, "  └─ ℹ PC端请执行: adb shell dumpsys gfxinfo|meminfo com.mamba.picme")
            }

            // ── 7. 延迟（如果配置了） ──
            step.delayMs?.let { delayMs ->
                Logger.i(TAG, "  ├─ Delay: ${delayMs}ms")
                delay(delayMs)
                Logger.i(TAG, "  └─ ✓ Done")
            }

            // ── 8. 记录状态快照 ──
            val stateSnapshot = captureCurrentState(probe)
            context = context.addStateSnapshot(stateSnapshot)
            val scene = stateSnapshot["scene"] ?: "unknown"
            Logger.i(TAG, "  ├─ State snapshot: scene=$scene")

            // ── 步骤结束 ──
            val stepElapsed = System.currentTimeMillis() - stepStart
            Logger.i(TAG, "  └─ Step completed in ${stepElapsed}ms")

            // ── 步骤间默认停顿（方便人工观察） ──
            if (index < case.steps.size - 1) {
                Logger.i(TAG, "  ⏸ Pause ${DEFAULT_STEP_PAUSE_MS}ms (observation)")
                delay(DEFAULT_STEP_PAUSE_MS)
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Logger.i(TAG, "")
        Logger.i(TAG, "╔════════════════════════════════════════════════════════════╗")
        Logger.i(TAG, "║  [TEST PASS] ${case.caseId} in ${duration}ms")
        Logger.i(TAG, "║  Steps: ${case.steps.size} | Screenshots: ${context.screenshots.size} | Perf: ${context.performanceSnapshots.size}")
        Logger.i(TAG, "╚════════════════════════════════════════════════════════════╝")
        return DataDrivenTestResult.Success(case.caseId, duration)
    }

    /**
     * 将 ActionJson 解析为 AgentCommand
     */
    private fun parseToAgentCommand(action: ActionJson): AgentCommand {
        return when (action.method.lowercase()) {
            "capture" -> AgentCommand.CapturePhoto()
            "flip_camera" -> AgentCommand.FlipCamera()
            "toggle_recording" -> AgentCommand.ToggleRecording()
            "navigate_to" -> AgentCommand.NavigateTo(
                destination = action.stringParam("destination")
                    ?: throw IllegalArgumentException("navigate_to requires destination")
            )
            "go_back" -> AgentCommand.GoBack()
            "adjust_beauty" -> AgentCommand.AdjustBeauty(
                settings = BeautySettings(
                    enabled = true,
                    smoothing = action.floatParam("smoothing") ?: 0f,
                    whitening = action.floatParam("whitening") ?: 0f,
                    slimFace = action.floatParam("slimFace") ?: 0f,
                    bigEyes = action.floatParam("bigEyes") ?: 0f
                )
            )
            "switch_filter" -> AgentCommand.SwitchFilter(
                filterType = parseFilterType(action.stringParam("filter") ?: "none")
            )
            "switch_style" -> AgentCommand.SwitchStyle(
                styleFilter = parseStyleFilter(action.stringParam("style") ?: "none")
            )
            "switch_scene" -> AgentCommand.SwitchScene(
                sceneName = action.stringParam("scene") ?: "none"
            )
            "switch_ratio" -> AgentCommand.SwitchRatio(
                ratio = action.stringParam("ratio") ?: "4_3"
            )
            "adjust_exposure" -> AgentCommand.AdjustExposure(
                exposure = action.intParam("exposure") ?: 0
            )
            "adjust_zoom" -> AgentCommand.AdjustZoom(
                zoomRatio = action.floatParam("zoom") ?: 1.0f
            )
            "switch_mode" -> AgentCommand.SwitchMode(
                mode = parseMediaType(action.stringParam("mode") ?: "photo")
            )
            "switch_face_engine" -> AgentCommand.SwitchFaceEngine(
                engine = action.stringParam("engine")
                    ?: throw IllegalArgumentException("switch_face_engine requires engine")
            )
            "toggle_setting" -> AgentCommand.ToggleSetting(
                settingKey = action.stringParam("settingKey")
                    ?: throw IllegalArgumentException("toggle_setting requires settingKey"),
                enabled = action.booleanParam("enabled") ?: true
            )
            "change_theme" -> AgentCommand.ChangeTheme(
                theme = action.stringParam("theme") ?: "light"
            )
            "change_language" -> AgentCommand.ChangeLanguage(
                language = action.stringParam("language") ?: "zh"
            )
            "download_model" -> AgentCommand.DownloadModel(
                modelId = action.stringParam("modelId")
                    ?: throw IllegalArgumentException("download_model requires modelId")
            )
            "view_media" -> AgentCommand.ViewMedia(mediaId = action.stringParam("mediaId"))
            "delete_media" -> AgentCommand.DeleteMedia(
                mediaIds = action.stringListParam("mediaIds") ?: emptyList()
            )
            "share_media" -> AgentCommand.ShareMedia(
                mediaIds = action.stringListParam("mediaIds") ?: emptyList()
            )
            "select_media" -> AgentCommand.SelectMedia(
                mediaId = action.stringParam("mediaId")
                    ?: throw IllegalArgumentException("select_media requires mediaId"),
                selected = action.booleanParam("selected") ?: true
            )
            "search_media" -> AgentCommand.SearchMedia(
                query = action.stringParam("query")
                    ?: throw IllegalArgumentException("search_media requires query")
            )
            "favorite_media" -> AgentCommand.FavoriteMedia(
                mediaId = action.stringParam("mediaId")
                    ?: throw IllegalArgumentException("favorite_media requires mediaId"),
                favorite = action.booleanParam("favorite") ?: true
            )
            else -> throw IllegalArgumentException("Unknown method: ${action.method}")
        }
    }

    /**
     * 评估条件表达式
     *
     * 支持简单条件：scene == 'CAMERA', lensFacing == 'back', isProcessing == false
     */
    private fun evaluateCondition(condition: String, probe: AgentStateProbe): Boolean {
        return try {
            val state = probe.captureStateSnapshot()

            // 解析简单条件: key == 'value' 或 key != 'value'
            val regex = "(.+?)\\s*(==|!=)\\s*'(.+?)'".toRegex()
            val match = regex.find(condition.trim())

            if (match != null) {
                val (key, op, expected) = match.destructured
                val actual = when (key.trim()) {
                    "scene" -> state["scene"]
                    "lensFacing" -> state["lensFacing"]
                    "captureMode" -> state["captureMode"]
                    "aspectRatio" -> state["aspectRatio"]
                    "currentFilter" -> state["currentFilter"]
                    "currentScene" -> state["currentScene"]
                    "isProcessing" -> state["isProcessing"]
                    "beautyEnabled" -> state["beautyEnabled"]
                    else -> state[key.trim()]
                }

                return when (op.trim()) {
                    "==" -> actual.toString() == expected
                    "!=" -> actual.toString() != expected
                    else -> false
                }
            }

            // 支持无比较的条件：如 "processing == false"（布尔值）
            val boolRegex = "(.+?)\\s*==\\s*(true|false)".toRegex()
            val boolMatch = boolRegex.find(condition.trim())
            if (boolMatch != null) {
                val (key, expected) = boolMatch.destructured
                val actual = state[key.trim()]
                return actual.toString().toBooleanStrictOrNull() == expected.toBooleanStrictOrNull()
            }

            false
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to evaluate condition: $condition", e)
            false
        }
    }

    /**
     * 评估断言
     */
    private fun evaluateAssertion(
        key: String,
        expected: String,
        probe: AgentStateProbe,
        context: TestExecutionContext
    ): Boolean {
        return try {
            when (key) {
                "scene" -> probe.currentScene().name == expected
                "commandResult" -> {
                    // 检查最后一条命令是否返回 Success
                    val lastLog = context.logs.lastOrNull { it.message.contains("Action: Success") }
                    lastLog != null
                }
                "lensFacing" -> {
                    val state = probe.captureStateSnapshot()
                    state["lensFacing"] == expected
                }
                "beautySmooth" -> {
                    val state = probe.captureStateSnapshot()
                    val actual = (state["beautySmooth"] as? Number)?.toFloat() ?: 0f
                    evaluateNumericAssertion(actual, expected)
                }
                "beautyWhiten" -> {
                    val state = probe.captureStateSnapshot()
                    val actual = (state["beautyWhiten"] as? Number)?.toFloat() ?: 0f
                    evaluateNumericAssertion(actual, expected)
                }
                "gpuProcessTimeMs" -> {
                    val state = probe.captureStateSnapshot()
                    val actual = (state["gpuProcessTimeMs"] as? Number)?.toLong() ?: -1L
                    evaluateNumericAssertion(actual.toFloat(), expected)
                }
                "isProcessing" -> {
                    val state = probe.captureStateSnapshot()
                    state["isProcessing"].toString() == expected
                }
                else -> {
                    val state = probe.captureStateSnapshot()
                    state[key].toString() == expected
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Assertion evaluation failed: $key = $expected", e)
            false
        }
    }

    /**
     * 数值断言评估（支持 >, <, >=, <=, ==）
     */
    private fun evaluateNumericAssertion(actual: Float, expected: String): Boolean {
        val regex = "(>=|<=|>|<|==)\\s*(.+)".toRegex()
        val match = regex.find(expected.trim())

        return if (match != null) {
            val (op, valueStr) = match.destructured
            val expectedValue = valueStr.trim().toFloatOrNull() ?: return false
            when (op) {
                ">=" -> actual >= expectedValue
                "<=" -> actual <= expectedValue
                ">" -> actual > expectedValue
                "<" -> actual < expectedValue
                "==" -> actual == expectedValue
                else -> false
            }
        } else {
            // 纯数值比较
            actual == expected.toFloatOrNull()
        }
    }

    /**
     * 捕获当前状态快照
     */
    private fun captureCurrentState(probe: AgentStateProbe): Map<String, Any?> {
        return try {
            probe.captureStateSnapshot()
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }

    // ============================================
    // 辅助函数
    // ============================================

    /**
     * 判断是否为需要额外观察停顿的关键操作
     */
    private fun isKeyAction(method: String): Boolean {
        return method.lowercase() in setOf(
            "capture", "flip_camera", "toggle_recording",
            "navigate_to", "go_back", "switch_scene",
            "switch_ratio", "switch_filter", "switch_style",
            "adjust_beauty", "change_theme", "change_language",
            "toggle_setting", "switch_face_engine"
        )
    }

    private fun parseFilterType(filter: String) = FilterType.valueOf(
        filter.uppercase().replace("LEICA_CLASSIC", "LEICA_CLASSIC")
            .replace("LEICA_VIBRANT", "LEICA_VIBRANT")
            .replace("LEICA_BW", "LEICA_BW")
    )

    private fun parseStyleFilter(style: String) = StyleFilter.valueOf(
        style.uppercase()
    )

    private fun parseMediaType(mode: String) = when (mode.lowercase()) {
        "photo" -> MediaType.PHOTO
        "video" -> MediaType.VIDEO
        else -> MediaType.PHOTO
    }
}
