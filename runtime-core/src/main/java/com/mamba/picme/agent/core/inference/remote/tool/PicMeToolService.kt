package com.mamba.picme.agent.core.inference.remote.tool

import android.app.Activity
import android.os.Looper
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.tool.perception.UiObservationFormatter
import com.mamba.picme.agent.core.tool.perception.ViewHierarchyExtractor
import com.mamba.picme.agent.core.tool.CameraToolHelper
import com.mamba.picme.agent.core.tool.accessibility.AccessibilityActionPerformer
import com.mamba.picme.agent.core.tool.accessibility.AccessibilityNodeDumper
import com.mamba.picme.agent.core.tool.accessibility.AccessibilityServiceHolder
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.tool.P
import com.mamba.tool.Tool
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PicMe 应用工具服务。
 *
 * 使用 @Tool 注解定义所有可被远程 LLM 调用的工具，直接通过方法签名生成 ToolSpecification。
 */
class PicMeToolService(
    private val windowManager: WindowManager
) {

    companion object {
        private const val TAG = "PicMeToolService"

        /** UI 操作后等待屏幕稳定的时间（毫秒） */
        private const val UI_SETTLE_DELAY_MS = 300L

        /** 导航操作后等待屏幕稳定的时间（毫秒） */
        private const val NAVIGATION_SETTLE_DELAY_MS = 500L

        /** UI 线程同步操作的默认超时（毫秒） */
        private const val UI_THREAD_TIMEOUT_MS = 5000L

        /** 当前 Activity 引用 */
        @JvmStatic
        var currentActivity: Activity? = null

        private var screenWidth = 0
        private var screenHeight = 0
    }

    /**
     * 在主线程同步执行 [block]，并等待其完成。
     *
     * 如果当前已经在主线程，则直接执行。否则通过 [Activity.runOnUiThread] 投递并在
     * [timeoutMs] 内等待 [CountDownLatch]。所有 View / Compose / Lifecycle 操作必须
     * 经过此辅助函数，以避免在后台线程调用 UI 导致的崩溃。
     */
    private fun <T> runOnUiThreadAndWait(timeoutMs: Long = UI_THREAD_TIMEOUT_MS, block: () -> T): T? {
        val activity = currentActivity
        if (activity == null) {
            Logger.w(TAG, "runOnUiThreadAndWait skipped: no current activity")
            return null
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        val latch = CountDownLatch(1)
        var result: T? = null
        var exception: Throwable? = null
        activity.runOnUiThread {
            try {
                result = block()
            } catch (e: Throwable) {
                exception = e
            } finally {
                latch.countDown()
            }
        }
        val success = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!success) {
            throw IllegalStateException("UI thread operation timed out after ${timeoutMs}ms")
        }
        if (exception != null) {
            throw exception!!
        }
        return result
    }

    /**
     * 确保屏幕尺寸已初始化。
     */
    private fun ensureScreenSize() {
        if (screenWidth <= 0 || screenHeight <= 0) {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
    }

    /**
     * 获取当前屏幕结构化描述。
     *
     * 优先使用 Accessibility 树（能识别 Compose 语义节点）；如果无障碍服务未开启，
     * 回退到传统 View 层级树。
     */
    private fun dumpScreenState(): String {
        ensureScreenSize()

        val accessibilityRoot = AccessibilityServiceHolder.getRootNode()
        if (accessibilityRoot != null) {
            return try {
                AccessibilityNodeDumper.dump(accessibilityRoot, screenWidth, screenHeight)
            } catch (e: Exception) {
                Logger.w(TAG, "Accessibility dump failed, falling back to view hierarchy", e)
                dumpViewHierarchyState()
            } finally {
                accessibilityRoot.recycle()
            }
        }
        return dumpViewHierarchyState()
    }

    private fun dumpViewHierarchyState(): String {
        return runOnUiThreadAndWait {
            val rootView = currentActivity?.window?.decorView?.rootView
            if (rootView == null) {
                "Error: No activity root view available"
            } else {
                try {
                    ViewHierarchyExtractor.extract(rootView, screenWidth, screenHeight)
                } catch (e: Exception) {
                    "Error: Failed to extract screen info: ${e.message}"
                }
            }
        } ?: "Error: No current activity reference available"
    }

    /**
     * 捕获操作后的屏幕状态，并格式化为标准返回字符串。
     */
    private fun capturePostActionState(actionDescription: String): String {
        val state = try {
            dumpScreenState()
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to capture post-action state", e)
            "Warning: failed to capture post-action screen state: ${e.message}"
        }
        return UiObservationFormatter.format(actionDescription, state)
    }

    /**
     * 等待 UI 稳定。当前实现使用固定延迟，后续可替换为 frame callback 或导航监听器。
     */
    private fun waitForUiSettle(navigation: Boolean = false) {
        val delayMs = if (navigation) NAVIGATION_SETTLE_DELAY_MS else UI_SETTLE_DELAY_MS
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ==================== UI 感知工具 ====================

    @Tool(name = "get_screen_info", value = ["获取当前屏幕的 UI 层级树信息（紧凑 JSON），包含所有可见元素的 class/text/content_desc/bounds/clickable/scrollable/editable 等属性。这是感知 UI 状态的唯一途径。若无障碍服务已开启，可识别 Compose 语义节点；否则仅返回 View 层级树。"])
    fun getScreenInfo(): String {
        return dumpScreenState()
    }

    @Tool(name = "click", value = ["点击屏幕上的元素。必须且只能使用以下两种方式之一：1) 传 x 和 y 坐标；2) 传 text 按可见文本查找。坐标应从 get_screen_info 返回的 bounds 取中心点。无障碍服务开启时优先使用 Accessibility 点击，支持 Compose 语义节点。"])
    fun click(
        @P(name = "x", value = "X coordinate (use with y, mutually exclusive with text)") x: Int? = null,
        @P(name = "y", value = "Y coordinate (use with x, mutually exclusive with text)") y: Int? = null,
        @P(name = "text", value = "Click element by visible text (mutually exclusive with x/y)") text: String? = null
    ): String {
        val actionResult = when {
            text != null -> clickByText(text)
            x != null && y != null -> clickByCoordinates(x, y)
            else -> "Error: Either provide (x, y) coordinates or text parameter"
        }

        if (actionResult.startsWith("Error:")) {
            return actionResult
        }

        waitForUiSettle()
        return capturePostActionState(actionResult)
    }

    private fun clickByText(text: String): String {
        val accessibilityRoot = AccessibilityServiceHolder.getRootNode()
        if (accessibilityRoot != null) {
            val ok = AccessibilityActionPerformer.clickByText(accessibilityRoot, text)
            accessibilityRoot.recycle()
            return if (ok) {
                "Clicked element with text: '$text' via accessibility"
            } else {
                "Error: No accessible element with text: '$text'"
            }
        }

        return runOnUiThreadAndWait {
            val rootView = currentActivity?.window?.decorView?.rootView
                ?: return@runOnUiThreadAndWait "Error: No activity root view available"
            clickByText(rootView, text)
        } ?: "Error: No current activity reference available"
    }

    private fun clickByCoordinates(x: Int, y: Int): String {
        val accessibilityRoot = AccessibilityServiceHolder.getRootNode()
        if (accessibilityRoot != null) {
            val ok = AccessibilityActionPerformer.clickByCoordinate(accessibilityRoot, x, y)
            accessibilityRoot.recycle()
            return if (ok) {
                "Clicked at ($x, $y) via accessibility"
            } else {
                "Error: Accessibility click failed at ($x, $y)"
            }
        }

        return runOnUiThreadAndWait {
            val rootView = currentActivity?.window?.decorView?.rootView
                ?: return@runOnUiThreadAndWait "Error: No activity root view available"
            clickByCoordinates(rootView, x, y)
        } ?: "Error: No current activity reference available"
    }

    private fun clickByCoordinates(root: android.view.View, x: Int, y: Int): String {
        val size = getScreenSize()
        if (x < 0 || x >= size[0] || y < 0 || y >= size[1]) {
            return "Error: Coordinates ($x, $y) out of screen bounds (${size[0]}x${size[1]})"
        }

        return try {
            val targetView = findViewAtPosition(root, x, y)
            if (targetView != null && targetView.isClickable) {
                targetView.performClick()
                "Clicked at ($x, $y) on ${targetView.javaClass.simpleName}"
            } else if (targetView != null) {
                var parent = targetView.parent
                while (parent is android.view.View) {
                    if (parent.isClickable) {
                        parent.performClick()
                        return "Clicked parent at ($x, $y) on ${parent.javaClass.simpleName}"
                    }
                    parent = parent.parent
                }
                dispatchTap(targetView, x, y)
                "Dispatched tap at ($x, $y) on ${targetView.javaClass.simpleName}"
            } else {
                "Error: No clickable view found at ($x, $y)"
            }
        } catch (e: Exception) {
            "Error: Click failed: ${e.message}"
        }
    }

    @Tool(name = "input_text", value = ["在输入框中输入文本。输入前必须先点击输入框获取焦点；无障碍服务开启时支持 Compose TextField，否则仅支持原生 EditText。"])
    fun inputText(
        @P(name = "text", value = "要输入的文本内容") text: String,
        @P(name = "clear_first", value = "是否先清空现有文本，默认 true") clearFirst: Boolean = true
    ): String {
        val actionResult = if (AccessibilityServiceHolder.isActive()) {
            val accessibilityRoot = AccessibilityServiceHolder.getRootNode()
            if (accessibilityRoot != null) {
                val ok = AccessibilityActionPerformer.inputText(accessibilityRoot, text, clearFirst)
                accessibilityRoot.recycle()
                if (ok) {
                    "Input text '$text' via accessibility"
                } else {
                    "Error: Accessibility input failed"
                }
            } else {
                "Error: Accessibility service root not available"
            }
        } else {
            runOnUiThreadAndWait {
                val rootView = currentActivity?.window?.decorView?.rootView
                    ?: return@runOnUiThreadAndWait "Error: No activity root view available"

                val focusedEditText = findFocusedEditText(rootView)
                if (focusedEditText != null) {
                    if (clearFirst) focusedEditText.setText("")
                    focusedEditText.append(text)
                    focusedEditText.setSelection(focusedEditText.text?.length ?: 0)
                    "Input text '$text' into focused EditText"
                } else {
                    val firstEditText = findFirstEditText(rootView)
                    if (firstEditText != null) {
                        firstEditText.requestFocus()
                        if (clearFirst) firstEditText.setText("")
                        firstEditText.append(text)
                        firstEditText.setSelection(firstEditText.text?.length ?: 0)
                        "Input text '$text' into first available EditText"
                    } else {
                        "Error: No EditText found on current screen"
                    }
                }
            } ?: "Error: No current activity reference available"
        }

        if (actionResult.startsWith("Error:")) {
            return actionResult
        }

        waitForUiSettle()
        return capturePostActionState(actionResult)
    }

    @Tool(name = "scroll", value = ["在屏幕上滑动滚动。direction 为 up（向上滑，显示下方内容）或 down（向下滑，显示上方内容）；distance 为 page 或 small。无障碍服务开启时支持 Compose 可滚动列表，否则仅支持 RecyclerView/ScrollView。"])
    fun scroll(
        @P(name = "direction", value = "滚动方向: up|down") direction: String,
        @P(name = "distance", value = "滚动距离: page|small，默认 page") distance: String = "page"
    ): String {
        val dir = direction.lowercase()
        if (dir !in listOf("up", "down")) {
            return "Error: Invalid direction: '$direction'. Must be 'up' or 'down'"
        }
        @Suppress("UNUSED_VARIABLE")
        val isPage = distance != "small"

        val actionResult = if (AccessibilityServiceHolder.isActive()) {
            val accessibilityRoot = AccessibilityServiceHolder.getRootNode()
            if (accessibilityRoot != null) {
                val ok = AccessibilityActionPerformer.scroll(accessibilityRoot, dir)
                accessibilityRoot.recycle()
                if (ok) {
                    "Scrolled $direction via accessibility"
                } else {
                    "Error: Accessibility scroll failed"
                }
            } else {
                "Error: Accessibility service root not available"
            }
        } else {
            runOnUiThreadAndWait {
                val rootView = currentActivity?.window?.decorView?.rootView
                    ?: return@runOnUiThreadAndWait "Error: No activity root view available"

                when {
                    rootView is RecyclerView -> {
                        val d = if (isPage) rootView.height else rootView.height / 3
                        rootView.smoothScrollBy(0, if (dir == "down") d else -d)
                        "Scrolled $direction in RecyclerView"
                    }
                    else -> {
                        val recyclerView = findRecyclerView(rootView)
                        if (recyclerView != null) {
                            val d = if (isPage) recyclerView.height else recyclerView.height / 3
                            recyclerView.smoothScrollBy(0, if (dir == "down") d else -d)
                            "Scrolled $direction in RecyclerView"
                        } else {
                            val scrollView = findScrollView(rootView)
                            if (scrollView != null) {
                                val d = if (isPage) scrollView.height else scrollView.height / 3
                                scrollView.smoothScrollBy(0, if (dir == "down") d else -d)
                                "Scrolled $direction in ScrollView"
                            } else {
                                "Error: No scrollable container found on current screen"
                            }
                        }
                    }
                }
            } ?: "Error: No current activity reference available"
        }

        if (actionResult.startsWith("Error:")) {
            return actionResult
        }

        waitForUiSettle()
        return capturePostActionState(actionResult)
    }

    @Tool(name = "search_photos", value = ["在相册中搜索照片。调用前必须先用 navigate_to(gallery) 进入相册。参数 query 为自然语言搜索词，如'去年夏天小孩'。"])
    fun searchPhotos(
        @P(name = "query", value = "搜索关键词，例如'去年夏天小孩'") query: String
    ): String {
        if (query.isBlank()) {
            return "Error: query cannot be empty"
        }

        val dispatchResult = dispatchCommand(AgentCommand.SearchMedia(query = query))
        if (dispatchResult.startsWith("Error:")) {
            return dispatchResult
        }

        return dispatchResult
    }

    @Tool(name = "go_back", value = ["返回上一页"])
    fun goBack(): String {
        val actionResult = runOnUiThreadAndWait {
            val activity = currentActivity ?: return@runOnUiThreadAndWait "Error: No current activity reference available"
            try {
                if (activity is ComponentActivity) {
                    activity.onBackPressedDispatcher.onBackPressed()
                } else {
                    @Suppress("DEPRECATION")
                    activity.onBackPressed()
                }
                "Navigated back"
            } catch (e: Exception) {
                "Error: Back navigation failed: ${e.message}"
            }
        } ?: "Error: No current activity reference available"

        if (actionResult.startsWith("Error:")) {
            return actionResult
        }

        waitForUiSettle(navigation = true)
        return capturePostActionState(actionResult)
    }

    // ==================== 导航工具 ====================

    @Tool(name = "navigate_to", value = ["导航到指定页面。可选值：camera（相机）、gallery（相册）、settings（设置）、debug（调试）"])
    fun navigateTo(
        @P(name = "destination", value = "目标页面: camera|gallery|settings|debug") destination: String
    ): String {
        val valid = setOf("camera", "gallery", "settings", "debug")
        if (destination !in valid) {
            return "Error: Invalid destination: '$destination'. Must be one of: ${valid.joinToString()}"
        }

        val dispatchResult = dispatchCommand(AgentCommand.NavigateTo(destination = destination))
        if (dispatchResult.startsWith("Error:")) {
            return dispatchResult
        }

        waitForUiSettle(navigation = true)
        return capturePostActionState("Navigated to $destination")
    }

    // ==================== 相机控制工具 ====================

    @Tool(name = "capture", value = ["拍照并保存到相册"])
    fun capture(): String {
        return executeCameraCommand("capture", emptyMap())
    }

    @Tool(name = "flip_camera", value = ["切换前后摄像头"])
    fun flipCamera(): String {
        return executeCameraCommand("flip_camera", emptyMap())
    }

    @Tool(name = "toggle_recording", value = ["切换录像状态（开始或停止录像）"])
    fun toggleRecording(): String {
        return executeCameraCommand("toggle_recording", emptyMap())
    }

    @Tool(name = "switch_mode", value = ["切换拍摄模式。可选值：PHOTO（拍照）、VIDEO（录像）、PRO（专业模式）、DOCUMENT（文档模式）"])
    fun switchMode(
        @P(name = "mode", value = "拍摄模式: PHOTO|VIDEO|PRO|DOCUMENT") mode: String
    ): String {
        val valid = setOf("PHOTO", "VIDEO", "PRO", "DOCUMENT")
        if (mode.uppercase() !in valid) {
            return "Error: Invalid mode: '$mode'"
        }
        return executeCameraCommand("switch_mode", mapOf("mode" to mode.uppercase()))
    }

    @Tool(name = "adjust_beauty", value = ["调整美颜参数。只传入需要调整的参数，未传入的参数保持不变。"])
    fun adjustBeauty(
        @P(name = "smoothing", value = "磨皮程度 0~100") smoothing: Double? = null,
        @P(name = "whitening", value = "美白程度 0~100") whitening: Double? = null,
        @P(name = "slim_face", value = "瘦脸 -50~50") slimFace: Double? = null,
        @P(name = "big_eyes", value = "大眼 0~100") bigEyes: Double? = null,
        @P(name = "lip_color", value = "唇色 0~100") lipColor: Double? = null,
        @P(name = "blush", value = "腮红 0~100") blush: Double? = null,
        @P(name = "eyebrow", value = "眉毛 0~100") eyebrow: Double? = null
    ): String {
        val params = mutableMapOf<String, Any>()
        smoothing?.let { params["smoothing"] = it }
        whitening?.let { params["whitening"] = it }
        slimFace?.let { params["slim_face"] = it }
        bigEyes?.let { params["big_eyes"] = it }
        lipColor?.let { params["lip_color"] = it }
        blush?.let { params["blush"] = it }
        eyebrow?.let { params["eyebrow"] = it }
        return executeCameraCommand("adjust_beauty", params)
    }

    @Tool(name = "adjust_exposure", value = ["调整曝光补偿，范围 -2 到 2"])
    fun adjustExposure(
        @P(name = "exposure", value = "曝光补偿 -2~2") exposure: Int
    ): String {
        return executeCameraCommand("adjust_exposure", mapOf("exposure" to exposure.coerceIn(-2, 2)))
    }

    @Tool(name = "adjust_zoom", value = ["调整变焦倍数，最小 0.5x，最大 10.0x"])
    fun adjustZoom(
        @P(name = "zoom", value = "变焦比例 0.5~10.0") zoom: Double
    ): String {
        return executeCameraCommand("adjust_zoom", mapOf("zoom" to zoom.coerceIn(0.5, 10.0)))
    }

    @Tool(name = "switch_filter", value = ["切换相机滤镜。可选值：NONE、LEICA_CLASSIC、LEICA_VIBRANT、LEICA_BW、FILM_GOLD、FILM_FUJI、VINTAGE、COOL、WARM"])
    fun switchFilter(
        @P(name = "filter", value = "滤镜名称: NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM") filter: String
    ): String {
        val valid = setOf("NONE", "LEICA_CLASSIC", "LEICA_VIBRANT", "LEICA_BW", "FILM_GOLD", "FILM_FUJI", "VINTAGE", "COOL", "WARM")
        if (filter.uppercase() !in valid) {
            return "Error: Invalid filter: '$filter'"
        }
        return executeCameraCommand("switch_filter", mapOf("filter" to filter.uppercase()))
    }

    @Tool(name = "switch_style", value = ["切换艺术风格。可选值：NONE、TOON、SKETCH、POSTERIZE、EMBOSS、CROSSHATCH"])
    fun switchStyle(
        @P(name = "style", value = "风格特效名称: NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH") style: String
    ): String {
        val valid = setOf("NONE", "TOON", "SKETCH", "POSTERIZE", "EMBOSS", "CROSSHATCH")
        if (style.uppercase() !in valid) {
            return "Error: Invalid style: '$style'"
        }
        return executeCameraCommand("switch_style", mapOf("style" to style.uppercase()))
    }

    @Tool(name = "switch_scene", value = ["切换场景模式。可选值：night（夜景）、moon（月亮）、none（普通）"])
    fun switchScene(
        @P(name = "scene", value = "场景模式: night|moon|none") scene: String
    ): String {
        val valid = setOf("night", "moon", "none")
        if (scene.lowercase() !in valid) {
            return "Error: Invalid scene: '$scene'"
        }
        return executeCameraCommand("switch_scene", mapOf("scene" to scene.lowercase()))
    }

    @Tool(name = "switch_ratio", value = ["切换画面比例。可选值：4:3、16:9、full（全屏）"])
    fun switchRatio(
        @P(name = "ratio", value = "画幅比例: 4:3|16:9|full") ratio: String
    ): String {
        val valid = setOf("4:3", "16:9", "full")
        if (ratio !in valid) {
            return "Error: Invalid ratio: '$ratio'"
        }
        return executeCameraCommand("switch_ratio", mapOf("ratio" to ratio))
    }

    @Tool(name = "finish", value = ["当任务完成时调用此工具，提供任务完成摘要"])
    fun finish(
        @P(name = "summary", value = "任务完成摘要") summary: String
    ): String {
        return summary
    }

    // ==================== 内部方法 ====================

    private fun dispatchCommand(command: AgentCommand): String {
        return try {
            @OptIn(DelicateCoroutinesApi::class)
            val deferred = GlobalScope.future {
                CapabilityRegistry.getInstance().dispatch(command, AgentContext(scene = AgentScene.CHAT), null)
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
                onFailure = { "Error: ${it.message}" }
            )
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun executeCameraCommand(method: String, params: Map<String, Any>): String {
        return try {
            CameraToolHelper.executeCameraCommand(
                method = method,
                params = params,
                buildCommandJson = { "" }, // 不再使用 JSON 中间格式
                onSuccess = { "OK: $method executed" },
                onError = { "Error: $method failed: $it" }
            )
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * 根据工具名和 JSON 参数调用对应工具方法。
     * 用于 RemoteReActAgent 等外部调用者通过字符串方式调用工具。
     */
    fun callTool(toolName: String, argsJson: String): String {
        val args = try {
            org.json.JSONObject(argsJson)
        } catch (_: Exception) {
            org.json.JSONObject()
        }

        return when (toolName) {
            "get_screen_info" -> getScreenInfo()
            "click" -> click(
                x = args.optInt("x", -1).takeIf { it >= 0 },
                y = args.optInt("y", -1).takeIf { it >= 0 },
                text = args.optString("text", "").takeIf { it.isNotBlank() }
            )
            "input_text" -> inputText(
                text = args.optString("text", ""),
                clearFirst = args.optBoolean("clear_first", true)
            )
            "scroll" -> scroll(
                direction = args.optString("direction", "down"),
                distance = args.optString("distance", "page")
            )
            "navigate_to" -> navigateTo(args.optString("destination", ""))
            "search_photos" -> searchPhotos(args.optString("query", ""))
            "go_back" -> goBack()
            "capture" -> capture()
            "flip_camera" -> flipCamera()
            "toggle_recording" -> toggleRecording()
            "switch_mode" -> switchMode(args.optString("mode", "PHOTO"))
            "adjust_beauty" -> adjustBeauty(
                smoothing = args.optDouble("smoothing", -1.0).takeIf { it >= 0 },
                whitening = args.optDouble("whitening", -1.0).takeIf { it >= 0 },
                slimFace = args.optDouble("slim_face", -100.0).takeIf { it >= -50 },
                bigEyes = args.optDouble("big_eyes", -1.0).takeIf { it >= 0 },
                lipColor = args.optDouble("lip_color", -1.0).takeIf { it >= 0 },
                blush = args.optDouble("blush", -1.0).takeIf { it >= 0 },
                eyebrow = args.optDouble("eyebrow", -1.0).takeIf { it >= 0 }
            )
            "adjust_exposure" -> adjustExposure(args.optInt("exposure", 0))
            "adjust_zoom" -> adjustZoom(args.optDouble("zoom", 1.0))
            "switch_filter" -> switchFilter(args.optString("filter", "NONE"))
            "switch_style" -> switchStyle(args.optString("style", "NONE"))
            "switch_scene" -> switchScene(args.optString("scene", "none"))
            "switch_ratio" -> switchRatio(args.optString("ratio", "full"))
            "finish" -> finish(args.optString("summary", "任务完成"))
            else -> "Error: Unknown tool: $toolName"
        }
    }

    // ==================== UI 辅助方法 ====================

    private fun getScreenSize(): IntArray {
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    private fun clickByText(root: android.view.View, text: String): String {
        return try {
            val found = findViewByText(root, text)
            if (found != null) {
                if (found.isClickable) {
                    found.performClick()
                    return "Clicked element with text: '$text'"
                }
                var parent = found.parent
                while (parent is android.view.View) {
                    if (parent.isClickable) {
                        parent.performClick()
                        return "Clicked parent of text '$text'"
                    }
                    parent = parent.parent
                }
                val location = IntArray(2)
                found.getLocationOnScreen(location)
                dispatchTap(found, location[0] + found.width / 2, location[1] + found.height / 2)
                "Dispatched tap on text '$text' at center coordinate"
            } else {
                "Error: No view found with text containing: '$text'"
            }
        } catch (e: Exception) {
            "Error: Click by text failed: ${e.message}"
        }
    }

    private fun findViewAtPosition(root: android.view.View, x: Int, y: Int): android.view.View? {
        if (root is android.view.ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                val location = IntArray(2)
                child.getLocationOnScreen(location)
                if (x >= location[0] && x <= location[0] + child.width &&
                    y >= location[1] && y <= location[1] + child.height
                ) {
                    val found = findViewAtPosition(child, x, y)
                    if (found != null) return found
                    return child
                }
            }
        }
        return if (root.visibility == android.view.View.VISIBLE) root else null
    }

    private fun findViewByText(root: android.view.View, text: String): android.view.View? {
        if (root is android.widget.TextView) {
            val viewText = root.text?.toString() ?: ""
            if (viewText.contains(text, ignoreCase = true)) return root
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findViewByText(root.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun dispatchTap(view: android.view.View, x: Int, y: Int) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(
            downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0
        )
        view.dispatchTouchEvent(down)
        down.recycle()

        val upTime = android.os.SystemClock.uptimeMillis()
        val up = android.view.MotionEvent.obtain(
            downTime, upTime, android.view.MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
        )
        view.dispatchTouchEvent(up)
        up.recycle()
    }

    private fun findFocusedEditText(root: android.view.View): EditText? {
        if (root is EditText && root.isFocused) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFocusedEditText(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findFirstEditText(root: android.view.View): EditText? {
        if (root is EditText && root.visibility == android.view.View.VISIBLE) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstEditText(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findRecyclerView(root: android.view.View): RecyclerView? {
        if (root is RecyclerView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findRecyclerView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findScrollView(root: android.view.View): android.widget.ScrollView? {
        if (root is android.widget.ScrollView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findScrollView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
