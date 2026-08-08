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
import com.mamba.picme.agent.core.runtime.capability.CommandExecutor
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * IM 远程控制 RPA 工具服务（飞书/Telegram 等 IM 通道的远程 ReAct agent；:agent-core → Koog 迁移 Phase 5）。
 *
 * **Koog 工具表面**：实现 [ToolSet]，用 Koog `@Tool(customName=...)`（保 LLM-facing 蛇形工具名，确定性）
 * + 方法级/参数级 `@LLMDescription`。Koog ToolRegistry 经反射直接派发 @Tool 方法拿到类型化参数，
 * **无需** langchain4j 期的 `callTool(toolName, argsJson)` 手写 when 分发（已删）。
 *
 * 暴露 UI 自动化（click/scroll/input，走 Accessibility，不进 CapabilityRegistry）+ 相机控制 +
 * 部分相册语义工具（后者经 [dispatchCommand] 回 CapabilityRegistry）。
 *
 * 路由定位见 `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` §2.4（飞书 RPA 入口）。与
 * [ChatToolService]（App 内 chat 会话 agent）同名但描述不同的工具是按 agent 故意差异化，
 * 非漂移；逐字节相同的描述（如 `draw_chart`）抽到 [GalleryToolDocs] 共享。
 *
 * **重要**：@Tool 参数**不能用 Kotlin 默认值**（同 [ChatToolService] 的 R8/反射约束）——所有参数
 * 必填，可选语义用空串（或坐标 -1）表示「不使用」，由 @LLMDescription 描述说明。LLM-facing 工具名
 * 与方法级描述首句与迁移前逐字节一致（ToolInventory 确定性，DeepSeek 上下文缓存依赖）。
 */
class RemoteControlToolService(
    private val windowManager: WindowManager
) : ToolSet {

    companion object {
        private const val TAG = "RemoteControlToolService"

        /** UI 操作后等待屏幕稳定的时间（毫秒） */
        private const val UI_SETTLE_DELAY_MS = 300L

        /** 导航操作后等待屏幕稳定的时间（毫秒） */
        private const val NAVIGATION_SETTLE_DELAY_MS = 500L

        /** UI 线程同步操作的默认超时（毫秒） */
        private const val UI_THREAD_TIMEOUT_MS = 5000L

        /** dispatch 等待 CapabilityRegistry 执行的超时（毫秒） */
        private const val DISPATCH_TIMEOUT_MS = 5000L

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

    @Tool(customName = "get_screen_info")
    @LLMDescription("获取当前屏幕的 UI 层级树信息（紧凑 JSON），包含所有可见元素的 class/text/content_desc/bounds/clickable/scrollable/editable 等属性。这是感知 UI 状态的唯一途径。若无障碍服务已开启，可识别 Compose 语义节点；否则仅返回 View 层级树。")
    fun getScreenInfo(): String {
        return dumpScreenState()
    }

    @Tool(customName = "click")
    @LLMDescription("点击屏幕上的元素。必须且只能使用以下两种方式之一：1) 传 x 和 y 坐标；2) 传 text 按可见文本查找。坐标应从 get_screen_info 返回的 bounds 取中心点。无障碍服务开启时优先使用 Accessibility 点击，支持 Compose 语义节点。")
    fun click(
        @LLMDescription("X coordinate (use with y; -1 = 不使用坐标, mutually exclusive with text)") x: Int,
        @LLMDescription("Y coordinate (use with x; -1 = 不使用坐标, mutually exclusive with text)") y: Int,
        @LLMDescription("Click element by visible text (空串 = 不按文本, mutually exclusive with x/y)") text: String
    ): String {
        val actionResult = when {
            text.isNotBlank() -> clickByText(text)
            x >= 0 && y >= 0 -> clickByCoordinates(x, y)
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

    @Tool(customName = "input_text")
    @LLMDescription("在输入框中输入文本。输入前必须先点击输入框获取焦点；无障碍服务开启时支持 Compose TextField，否则仅支持原生 EditText。")
    fun inputText(
        @LLMDescription("要输入的文本内容") text: String,
        @LLMDescription("是否先清空现有文本，true=清空后输入，false=追加") clearFirst: Boolean
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

    @Tool(customName = "scroll")
    @LLMDescription("在屏幕上滑动滚动。direction 为 up（向上滑，显示下方内容）或 down（向下滑，显示上方内容）；distance 为 page 或 small。无障碍服务开启时支持 Compose 可滚动列表，否则仅支持 RecyclerView/ScrollView。")
    fun scroll(
        @LLMDescription("滚动方向: up|down") direction: String,
        @LLMDescription("滚动距离: page|small，不确定时传 page") distance: String
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

    @Tool(customName = "search_photos")
    @LLMDescription("在相册中搜索照片。调用前必须先用 navigate_to(gallery) 进入相册。参数 query 为自然语言搜索词，如'去年夏天小孩'。")
    suspend fun searchPhotos(
        @LLMDescription("搜索关键词，例如'去年夏天小孩'") query: String
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

    @Tool(customName = "get_gallery_summary")
    @LLMDescription("获取本地相册摘要，包括照片数、人脸数、人物数、已/未打标数量以及扫描建议。参数 include_details 为 true 时返回剩余 Pass 1/Pass 3/ML Kit 任务数。")
    suspend fun getGallerySummary(
        @LLMDescription("是否返回包含剩余任务数的完整摘要，true=完整，false=简要") includeDetails: Boolean
    ): String {
        return dispatchCommand(AgentCommand.GetGallerySummary(includeDetails = includeDetails))
    }

    @Tool(customName = "run_gallery_script")
    @LLMDescription("在端侧沙箱执行 JavaScript 做相册盘点/统计分析（取数类 handler 只读、数据不出端；删除/收藏等写操作走 capability.dispatch，会弹窗经用户确认）。所有 handler 均为异步，**必须用 await bridge.callAsync(name, args) 调用**（bridge.call 已禁用，调用会报错）。可用 handler： gallery.summary → 相册聚合统计（totalPhotos/totalVideos/totalMedia/hasFaceCount/personClusterCount/namedPersonCount/labeledCount/unlabeledCount/semanticEncodedCount/remainingPass1/remainingPass3/isScanning/currentPass/recommendation）； gallery.query({label?,ocr?,location?,fromMs?,toMs?,hasFace?,limit?}) → 结构化过滤命中，返回 {ids:[...], total:N}（多维 AND，全可选；ids 已截断到 limit，total 为未截断真实数）； gallery.tags → 实际打标标签分布 {标签:照片数}（按计数降序 top 50）； gallery.timeline({fromMs?,toMs?,bucketMs?}) → 按时间分桶统计 {\"桶起始时间戳\":照片数}（默认按月，bucketMs=2592000000=月/31536000000=年）； gallery.intersect({idsA:[...],idsB:[...],op:\"intersect|union|diff\"}) → 集合交并差，返回 {ids:[...],total:N}（用于多次 query 结果交叉，如旅行+人脸）； media.meta(id) → 单张元数据 {id,type,captureMs,fileName,labels:[...],locationName,hasFace,faceId}（不含路径/GPS/OCR/向量）； media.batch_meta([id1,id2,...]) → 批量元数据 [{...},...]（上限 50，避免循环调 media.meta）； gallery.stats_by_tag({label?,hasFace?,fromMs?,toMs?}) → 条件过滤后的标签分布（如人像照片内的场景标签）； face.cluster({topN?}) → 人脸聚类盘点 {clusterCount,namedCount,totalEmbeddings,unassignedEmbeddings,topPersons:[{personId,name,faceCount,coverMediaId}]}（topN 默认 10 上限 50，不含 embedding 原始数据）； tag.audit({topN?}) → 打标覆盖审计 {totalMedia,unlabeledCount,neverScannedCount,lastScanAt,outOfVocabTags:{标签:照片数}}（词表外标签 topN 默认 10 上限 50）。 可并发取数：var r=await Promise.all([bridge.callAsync('gallery.summary',{}),bridge.callAsync('gallery.tags',{})]); var s=r[0],t=r[1]; 在 JS 内组合计算（如某标签占比 = query.total / summary.totalMedia；环比 = 本月/上月-1），return 结果对象回传给你做总结。 示例：var s=await bridge.callAsync('gallery.summary',{}); var t=await bridge.callAsync('gallery.tags',{}); return {total:s.totalMedia, topTags:t};")
    suspend fun runGalleryScript(
        @LLMDescription("JS 源码；用 await bridge.callAsync 取数据（gallery.summary/tags/timeline/query/stats_by_tag/intersect, media.meta/batch_meta, face.cluster, tag.audit），return 结果对象") code: String
    ): String {
        return dispatchCommand(AgentCommand.ExecuteScript(code = code))
    }

    @Tool(customName = "draw_chart")
    @LLMDescription(GalleryToolDocs.DRAW_CHART)
    suspend fun drawChart(
        @LLMDescription("图表类型：bar(柱状)/line(折线)/pie(饼图)") type: String,
        @LLMDescription("图表标题") title: String,
        @LLMDescription("分类/x 轴标签，英文逗号分隔，如 '1月,2月,3月' 或 '人像,风景,美食'") labels: String,
        @LLMDescription("每个标签对应的数值，英文逗号分隔，与 labels 等长，如 '12,8,21'") values: String,
        @LLMDescription("数值单位，如 '张'；无则空串") unit: String
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

    @Tool(customName = "click_gallery_item")
    @LLMDescription("点击相册网格中的第 N 个媒体项。必须先进入相册并完成搜索。index 从 1 开始，按屏幕可见项的顺序计数。")
    fun clickGalleryItem(
        @LLMDescription("从 1 开始的照片序号") index: Int
    ): String {
        val actionResult = if (AccessibilityServiceHolder.isActive()) {
            val accessibilityRoot = AccessibilityServiceHolder.getRootNode()
            if (accessibilityRoot != null) {
                val prefixes = listOf("照片", "Photo", "视频", "Video", "文档", "Document")
                val ok = AccessibilityActionPerformer.clickGalleryItem(accessibilityRoot, index, prefixes)
                accessibilityRoot.recycle()
                if (ok) {
                    "Clicked gallery item at index $index"
                } else {
                    "Error: Failed to click gallery item at index $index"
                }
            } else {
                "Error: Accessibility service root not available"
            }
        } else {
            "Error: Accessibility service is not active"
        }

        if (actionResult.startsWith("Error:")) {
            return actionResult
        }

        waitForUiSettle()
        return capturePostActionState(actionResult)
    }

    @Tool(customName = "go_back")
    @LLMDescription("返回上一页")
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

    @Tool(customName = "navigate_to")
    @LLMDescription("导航到指定页面。可选值：camera（相机）、gallery（相册）、settings（设置）、debug（调试）")
    suspend fun navigateTo(
        @LLMDescription("目标页面: camera|gallery|settings|debug") destination: String
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

    @Tool(customName = "capture")
    @LLMDescription("拍照并保存到相册")
    suspend fun capture(): String {
        return executeCameraCommand("capture", emptyMap())
    }

    @Tool(customName = "flip_camera")
    @LLMDescription("切换前后摄像头")
    suspend fun flipCamera(): String {
        return executeCameraCommand("flip_camera", emptyMap())
    }

    @Tool(customName = "toggle_recording")
    @LLMDescription("切换录像状态（开始或停止录像）")
    suspend fun toggleRecording(): String {
        return executeCameraCommand("toggle_recording", emptyMap())
    }

    @Tool(customName = "switch_mode")
    @LLMDescription("切换拍摄模式。可选值：PHOTO（拍照）、VIDEO（录像）、PRO（专业模式）、DOCUMENT（文档模式）")
    suspend fun switchMode(
        @LLMDescription("拍摄模式: PHOTO|VIDEO|PRO|DOCUMENT") mode: String
    ): String {
        val valid = setOf("PHOTO", "VIDEO", "PRO", "DOCUMENT")
        if (mode.uppercase() !in valid) {
            return "Error: Invalid mode: '$mode'"
        }
        return executeCameraCommand("switch_mode", mapOf("mode" to mode.uppercase()))
    }

    @Tool(customName = "adjust_beauty")
    @LLMDescription("调整美颜参数。只传入需要调整的参数，未传入的参数保持不变。")
    suspend fun adjustBeauty(
        @LLMDescription("磨皮程度 0~100，留空=不变") smoothing: String,
        @LLMDescription("美白程度 0~100，留空=不变") whitening: String,
        @LLMDescription("瘦脸 -50~50，留空=不变") slimFace: String,
        @LLMDescription("大眼 0~100，留空=不变") bigEyes: String,
        @LLMDescription("唇色 0~100，留空=不变") lipColor: String,
        @LLMDescription("腮红 0~100，留空=不变") blush: String,
        @LLMDescription("眉毛 0~100，留空=不变") eyebrow: String
    ): String {
        val params = mutableMapOf<String, Any>()
        smoothing.toDoubleOrNull()?.let { params["smoothing"] = it }
        whitening.toDoubleOrNull()?.let { params["whitening"] = it }
        slimFace.toDoubleOrNull()?.let { params["slim_face"] = it }
        bigEyes.toDoubleOrNull()?.let { params["big_eyes"] = it }
        lipColor.toDoubleOrNull()?.let { params["lip_color"] = it }
        blush.toDoubleOrNull()?.let { params["blush"] = it }
        eyebrow.toDoubleOrNull()?.let { params["eyebrow"] = it }
        return executeCameraCommand("adjust_beauty", params)
    }

    @Tool(customName = "adjust_exposure")
    @LLMDescription("调整曝光补偿，范围 -2 到 2")
    suspend fun adjustExposure(
        @LLMDescription("曝光补偿 -2~2") exposure: Int
    ): String {
        return executeCameraCommand("adjust_exposure", mapOf("exposure" to exposure.coerceIn(-2, 2)))
    }

    @Tool(customName = "adjust_zoom")
    @LLMDescription("调整变焦倍数，最小 0.5x，最大 10.0x")
    suspend fun adjustZoom(
        @LLMDescription("变焦比例 0.5~10.0") zoom: Double
    ): String {
        return executeCameraCommand("adjust_zoom", mapOf("zoom" to zoom.coerceIn(0.5, 10.0)))
    }

    @Tool(customName = "switch_filter")
    @LLMDescription("切换相机滤镜。可选值：NONE、LEICA_CLASSIC、LEICA_VIBRANT、LEICA_BW、FILM_GOLD、FILM_FUJI、VINTAGE、COOL、WARM")
    suspend fun switchFilter(
        @LLMDescription("滤镜名称: NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM") filter: String
    ): String {
        val valid = setOf("NONE", "LEICA_CLASSIC", "LEICA_VIBRANT", "LEICA_BW", "FILM_GOLD", "FILM_FUJI", "VINTAGE", "COOL", "WARM")
        if (filter.uppercase() !in valid) {
            return "Error: Invalid filter: '$filter'"
        }
        return executeCameraCommand("switch_filter", mapOf("filter" to filter.uppercase()))
    }

    @Tool(customName = "switch_style")
    @LLMDescription("切换艺术风格。可选值：NONE、TOON、SKETCH、POSTERIZE、EMBOSS、CROSSHATCH")
    suspend fun switchStyle(
        @LLMDescription("风格特效名称: NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH") style: String
    ): String {
        val valid = setOf("NONE", "TOON", "SKETCH", "POSTERIZE", "EMBOSS", "CROSSHATCH")
        if (style.uppercase() !in valid) {
            return "Error: Invalid style: '$style'"
        }
        return executeCameraCommand("switch_style", mapOf("style" to style.uppercase()))
    }

    @Tool(customName = "switch_scene")
    @LLMDescription("切换场景模式。可选值：night（夜景）、moon（月亮）、none（普通）")
    suspend fun switchScene(
        @LLMDescription("场景模式: night|moon|none") scene: String
    ): String {
        val valid = setOf("night", "moon", "none")
        if (scene.lowercase() !in valid) {
            return "Error: Invalid scene: '$scene'"
        }
        return executeCameraCommand("switch_scene", mapOf("scene" to scene.lowercase()))
    }

    @Tool(customName = "switch_ratio")
    @LLMDescription("切换画面比例。可选值：4:3、16:9、full（全屏）")
    suspend fun switchRatio(
        @LLMDescription("画幅比例: 4:3|16:9|full") ratio: String
    ): String {
        val valid = setOf("4:3", "16:9", "full")
        if (ratio !in valid) {
            return "Error: Invalid ratio: '$ratio'"
        }
        return executeCameraCommand("switch_ratio", mapOf("ratio" to ratio))
    }

    @Tool(customName = "finish")
    @LLMDescription("当任务完成时调用此工具，提供任务完成摘要")
    fun finish(
        @LLMDescription("任务完成摘要") summary: String
    ): String {
        return summary
    }

    // ==================== 内部方法 ====================

    // suspend 直通（Task 13）：全链路已 suspend（CapabilityRegistry.dispatch 为挂起函数），
    // 原 `dispatchScope.future{}.get(5s)` 阻塞桥改 `withTimeout` 结构化等待——超时经协程取消
    // 级联终止底层 dispatch（语义对齐旧 TimeoutException 分支），外部取消透传不吞。
    // 与 CameraToolHelper.executeCameraCommand 的 Task 7 模式一致。
    private suspend fun dispatchCommand(command: AgentCommand): String {
        return try {
            val result = withTimeout(DISPATCH_TIMEOUT_MS) {
                CapabilityRegistry.getInstance().dispatch(command, AgentContext(scene = AgentScene.CHAT), null)
            }
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
        } catch (e: TimeoutCancellationException) {
            // 等待 dispatch 5s 超时：withTimeout 已级联取消底层 dispatch 协程，无协程裸跑
            // 记调用方视角的等待超时（命令若最终完成仍由 CommandExecutor 记录）
            Logger.w(TAG, "dispatchCommand wait timed out: ${command::class.simpleName}")
            CommandExecutor.recordDispatchEvent(
                capability = "(remote_control_tool)",
                commandType = AgentCommand.getMethodName(command),
                success = false,
                errorCode = CommandExecutor.ERROR_CODE_TIMEOUT,
                errorMessage = "dispatch wait timed out after 5s",
                traceId = null
            )
            "Error: ${e.message}"
        } catch (e: CancellationException) {
            // 外部取消（agent cancel）：结构化并发要求透传，不吞为错误字符串。
            throw e
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // suspend（Task 7 涟漪）：CameraToolHelper.executeCameraCommand 已 suspend 化
    //（future.get 阻塞桥 → withTimeout），相机 @Tool 方法随之 suspend（Koog 支持 suspend
    // 工具函数；LLM-facing 工具名/描述不变）。
    private suspend fun executeCameraCommand(method: String, params: Map<String, Any>): String {
        return try {
            CameraToolHelper.executeCameraCommand(
                method = method,
                params = params,
                onSuccess = { "OK: $method executed" },
                onError = { "Error: $method failed: $it" }
            )
        } catch (e: CancellationException) {
            // 外部取消（agent cancel）：结构化并发要求透传，不吞为错误字符串。
            throw e
        } catch (e: Exception) {
            "Error: ${e.message}"
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
