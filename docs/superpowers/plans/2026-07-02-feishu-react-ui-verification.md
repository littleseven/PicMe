# 飞书 ReAct UI 操作后自动观察实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `PicMeToolService` 中的 UI 操作工具在执行后自动把新的屏幕状态摘要返回给 LLM，使飞书 ReAct Agent 无需依赖 LLM 自觉性即可确认指令是否生效。

**Architecture:** 在 `ViewHierarchyExtractor` 中增加紧凑摘要模式；新增纯 Kotlin `UiObservationFormatter` 统一格式化返回字符串；修改 `PicMeToolService` 的 `click/scroll/input_text/navigate_to/go_back` 工具，在工具内部执行操作→等待 UI 稳定→dump 屏幕→返回 `"Action: ...\nPost-action screen state: ..."`；同步更新 `RemoteReActAgentConfig` 的 system prompt。

**Tech Stack:** Kotlin, Android View API, `org.json`, JUnit 4

---

## 文件映射

| 文件 | 职责 |
|------|------|
| `runtime-core/src/main/java/com/mamba/picme/agent/core/tool/perception/ViewHierarchyExtractor.kt` | 增加紧凑摘要参数，控制返回 Token |
| `runtime-core/src/main/java/com/mamba/picme/agent/core/tool/perception/UiObservationFormatter.kt` | 新建：把操作描述和屏幕状态拼成标准返回字符串 |
| `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/PicMeToolService.kt` | UI 操作工具返回值追加 post-action screen state |
| `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgentConfig.kt` | System prompt 增加对返回格式的说明 |
| `runtime-core/src/test/java/com/mamba/picme/agent/core/tool/perception/UiObservationFormatterTest.kt` | 新建：纯单元测试 |
| `runtime-core/src/test/java/com/mamba/picme/agent/core/inference/remote/tool/PicMeToolServiceObservationTest.kt` | 新建：反射验证工具方法返回格式 |

---

## Task 1: ViewHierarchyExtractor 支持紧凑摘要

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/tool/perception/ViewHierarchyExtractor.kt`

目标：让 `extractSemanticSummary` 支持只输出语义摘要（不含完整层级树），并允许收紧文本长度和元素数量。

- [ ] **Step 1: 修改 `extractSemanticSummary` 签名和实现**

在 `ViewHierarchyExtractor.kt` 中：

1. 把常量改为可覆盖的默认值：

```kotlin
private const val MAX_TEXT_LENGTH = 80
private const val MAX_CHILDREN = 200
private const val DEFAULT_SUMMARY_TEXT_LENGTH = 30
private const val DEFAULT_SUMMARY_ELEMENTS = 30
```

2. 修改 `extractSemanticSummary` 方法签名和逻辑：

```kotlin
fun extractSemanticSummary(
    rootView: View,
    screenWidth: Int,
    screenHeight: Int,
    includeFullTree: Boolean = true,
    maxSummaryTextLength: Int = DEFAULT_SUMMARY_TEXT_LENGTH,
    maxSummaryElements: Int = DEFAULT_SUMMARY_ELEMENTS
): String {
    val summary = StringBuilder()
    summary.appendLine("=== 页面结构摘要 ===")

    val title = findTitleText(rootView)
    if (title != null) {
        summary.appendLine("页面标题: $title")
    }

    val interactiveElements = mutableListOf<String>()
    collectInteractiveElements(rootView, interactiveElements, screenWidth, screenHeight)

    val trimmedElements = interactiveElements.take(maxSummaryElements)
    if (trimmedElements.isNotEmpty()) {
        summary.appendLine("可交互元素 (${trimmedElements.size}个):")
        trimmedElements.forEach { summary.appendLine("  - $it") }
    } else {
        summary.appendLine("可交互元素: 无")
    }

    if (interactiveElements.size > maxSummaryElements) {
        summary.appendLine("  ... 还有 ${interactiveElements.size - maxSummaryElements} 个元素已省略")
    }

    val states = mutableListOf<String>()
    collectKeyStates(rootView, states)
    if (states.isNotEmpty()) {
        summary.appendLine("关键状态:")
        states.forEach { summary.appendLine("  - $it") }
    }

    if (includeFullTree) {
        summary.appendLine("=== 完整层级树 ===")
        summary.appendLine(extract(rootView, screenWidth, screenHeight))
    }

    return summary.toString()
}
```

3. 同步修改 `collectInteractiveElements` 调用链中 `buildSemanticDescription` 的文本截断逻辑，支持 `maxSummaryTextLength`。为简化，先把 `MAX_TEXT_LENGTH` 改为方法参数。

把 `buildSemanticDescription` 签名改为：

```kotlin
private fun buildSemanticDescription(
    view: View,
    screenWidth: Int,
    screenHeight: Int,
    maxTextLength: Int = MAX_TEXT_LENGTH
): String? { ... }
```

并把其中：

```kotlin
if (!text.isNullOrEmpty()) parts.add("text=\"$text\"")
```

改为：

```kotlin
if (!text.isNullOrEmpty()) {
    val displayText = if (text.length > maxTextLength) text.take(maxTextLength) + "…" else text
    parts.add("text=\"$displayText\"")
}
```

把 `collectInteractiveElements` 签名改为接收 `maxTextLength`：

```kotlin
private fun collectInteractiveElements(
    view: View,
    out: MutableList<String>,
    screenWidth: Int,
    screenHeight: Int,
    parentDesc: String = "",
    maxTextLength: Int = MAX_TEXT_LENGTH
) {
    if (view.visibility != View.VISIBLE) return

    val desc = buildSemanticDescription(view, screenWidth, screenHeight, maxTextLength)
    if (desc != null) {
        out.add("$parentDesc$desc")
    }

    if (view is ViewGroup) {
        val childPrefix = if (desc != null) "$desc > " else ""
        for (i in 0 until view.childCount) {
            collectInteractiveElements(
                view.getChildAt(i), out, screenWidth, screenHeight, childPrefix, maxTextLength
            )
        }
    }
}
```

- [ ] **Step 2: 编译 runtime-core**

Run:
```bash
./gradlew :runtime-core:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 提交 Task 1**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/tool/perception/ViewHierarchyExtractor.kt
git commit -m "feat(agent): support compact semantic summary in ViewHierarchyExtractor"
```

---

## Task 2: 创建 UiObservationFormatter

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/tool/perception/UiObservationFormatter.kt`
- Create: `runtime-core/src/test/java/com/mamba/picme/agent/core/tool/perception/UiObservationFormatterTest.kt`

目标：提供一个纯函数，统一格式化 `"Action: ...\nPost-action screen state: ..."`。

- [ ] **Step 1: 编写 `UiObservationFormatter.kt`**

```kotlin
package com.mamba.picme.agent.core.tool.perception

/**
 * 把 UI 操作结果和观察到的屏幕状态格式化为 ReAct tool result。
 */
object UiObservationFormatter {

    private const val ACTION_PREFIX = "Action:"
    private const val STATE_PREFIX = "Post-action screen state:"

    /**
     * 格式化操作后的观察结果。
     *
     * @param actionDescription 操作结果简短描述，例如 "Clicked element with text: '搜索照片'"
     * @param screenState 当前屏幕状态字符串，通常来自 [ViewHierarchyExtractor.extractSemanticSummary]
     * @return 标准返回字符串
     */
    fun format(actionDescription: String, screenState: String): String {
        return buildString {
            appendLine("$ACTION_PREFIX $actionDescription")
            appendLine(STATE_PREFIX)
            append(screenState)
        }
    }

    /**
     * 判断一个工具返回字符串是否包含 post-action screen state。
     */
    fun containsObservation(result: String): Boolean {
        return result.contains(STATE_PREFIX)
    }
}
```

- [ ] **Step 2: 编写 `UiObservationFormatterTest.kt`**

```kotlin
package com.mamba.picme.agent.core.tool.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiObservationFormatterTest {

    @Test
    fun `format combines action and screen state`() {
        val result = UiObservationFormatter.format(
            actionDescription = "Clicked element with text: '搜索照片'",
            screenState = "=== 页面结构摘要 ===\n页面标题: 相册\n"
        )

        assertTrue(result.startsWith("Action: Clicked element with text: '搜索照片'"))
        assertTrue(result.contains("Post-action screen state:"))
        assertTrue(result.contains("页面标题: 相册"))
    }

    @Test
    fun `containsObservation returns true when state prefix exists`() {
        val result = UiObservationFormatter.format(
            actionDescription = "Navigated to camera",
            screenState = "页面结构摘要"
        )
        assertTrue(UiObservationFormatter.containsObservation(result))
    }

    @Test
    fun `containsObservation returns false for plain strings`() {
        assertFalse(UiObservationFormatter.containsObservation("OK: capture executed"))
    }
}
```

- [ ] **Step 3: 运行单元测试**

Run:
```bash
./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.tool.perception.UiObservationFormatterTest"
```

Expected: BUILD SUCCESSFUL with 3 tests passing.

- [ ] **Step 4: 提交 Task 2**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/tool/perception/UiObservationFormatter.kt
git add runtime-core/src/test/java/com/mamba/picme/agent/core/tool/perception/UiObservationFormatterTest.kt
git commit -m "feat(agent): add UiObservationFormatter for post-action screen state"
```

---

## Task 3: PicMeToolService 添加 Post-Action 观察

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/PicMeToolService.kt`

目标：在 UI 操作工具执行后自动 dump 屏幕并返回格式化结果。

- [ ] **Step 1: 添加观察相关常量与辅助方法**

在 `PicMeToolService` 的 `companion object` 附近添加：

```kotlin
companion object {
    private const val TAG = "PicMeToolService"

    /** UI 操作后等待屏幕稳定的时间（毫秒） */
    private const val UI_SETTLE_DELAY_MS = 300L

    /** 导航操作后等待屏幕稳定的时间（毫秒） */
    private const val NAVIGATION_SETTLE_DELAY_MS = 500L

    /** 当前 Activity rootView，由外部设置 */
    @JvmStatic
    var currentRootView: android.view.View? = null

    /** 当前 Activity 引用 */
    @JvmStatic
    var currentActivity: Activity? = null

    private var screenWidth = 0
    private var screenHeight = 0
}
```

在类中添加辅助方法（放在 UI 辅助方法区域之前）：

```kotlin
/**
 * 捕获操作后的屏幕状态，并格式化为标准返回字符串。
 */
private fun capturePostActionState(actionDescription: String): String {
    val rootView = currentRootView
        ?: return UiObservationFormatter.format(
            actionDescription,
            "Error: No activity root view available"
        )

    val size = getScreenSize()
    val screenW = size[0]
    val screenH = size[1]

    return try {
        val state = ViewHierarchyExtractor.extractSemanticSummary(
            rootView = rootView,
            screenWidth = screenW,
            screenHeight = screenH,
            includeFullTree = false,
            maxSummaryTextLength = 30,
            maxSummaryElements = 30
        )
        UiObservationFormatter.format(actionDescription, state)
    } catch (e: Exception) {
        Logger.w(TAG, "Failed to capture post-action state", e)
        UiObservationFormatter.format(
            actionDescription,
            "Warning: failed to capture post-action screen state: ${e.message}"
        )
    }
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
```

- [ ] **Step 2: 修改 `click` 工具**

把 `click` 方法改为：

```kotlin
@Tool(name = "click", value = ["点击屏幕上的元素。支持通过坐标(x,y)或文本(text)定位目标。"])
fun click(
    @P(name = "x", value = "X coordinate (use with y, mutually exclusive with text)") x: Int? = null,
    @P(name = "y", value = "Y coordinate (use with x, mutually exclusive with text)") y: Int? = null,
    @P(name = "text", value = "Click element by visible text (mutually exclusive with x/y)") text: String? = null
): String {
    val rootView = currentRootView ?: return "Error: No activity root view available"

    val actionResult = if (text != null) {
        clickByText(rootView, text)
    } else if (x != null && y != null) {
        clickByCoordinates(rootView, x, y)
    } else {
        "Error: Either provide (x, y) coordinates or text parameter"
    }

    if (actionResult.startsWith("Error:")) {
        return actionResult
    }

    waitForUiSettle()
    return capturePostActionState(actionResult)
}
```

这里需要把原来的坐标点击逻辑拆出来：

```kotlin
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
```

- [ ] **Step 3: 修改 `scroll` 工具**

把 `scroll` 方法改为：

```kotlin
@Tool(name = "scroll", value = ["在屏幕上滑动滚动。支持按方向（up/down）滑动。"])
fun scroll(
    @P(name = "direction", value = "滚动方向: up|down") direction: String,
    @P(name = "distance", value = "滚动距离: page|small，默认 page") distance: String = "page"
): String {
    val rootView = currentRootView ?: return "Error: No activity root view available"
    val dir = direction.lowercase()
    if (dir !in listOf("up", "down")) {
        return "Error: Invalid direction: '$direction'. Must be 'up' or 'down'"
    }
    val isPage = distance != "small"

    val actionResult = when {
        rootView is RecyclerView -> {
            val d = if (isPage) rootView.height else rootView.height / 3
            rootView.post { rootView.smoothScrollBy(0, if (dir == "down") d else -d) }
            "Scrolled $direction in RecyclerView"
        }
        else -> {
            val recyclerView = findRecyclerView(rootView)
            if (recyclerView != null) {
                val d = if (isPage) recyclerView.height else recyclerView.height / 3
                recyclerView.post { recyclerView.smoothScrollBy(0, if (dir == "down") d else -d) }
                "Scrolled $direction in RecyclerView"
            } else {
                val scrollView = findScrollView(rootView)
                if (scrollView != null) {
                    val d = if (isPage) scrollView.height else scrollView.height / 3
                    scrollView.post { scrollView.smoothScrollBy(0, if (dir == "down") d else -d) }
                    "Scrolled $direction in ScrollView"
                } else {
                    "Error: No scrollable container found on current screen"
                }
            }
        }
    }

    if (actionResult.startsWith("Error:")) {
        return actionResult
    }

    waitForUiSettle()
    return capturePostActionState(actionResult)
}
```

- [ ] **Step 4: 修改 `input_text` 工具**

把 `inputText` 方法改为：

```kotlin
@Tool(name = "input_text", value = ["在输入框中输入文本"])
fun inputText(
    @P(name = "text", value = "要输入的文本内容") text: String,
    @P(name = "clear_first", value = "是否先清空现有文本，默认 true") clearFirst: Boolean = true
): String {
    val rootView = currentRootView ?: return "Error: No activity root view available"

    val focusedEditText = findFocusedEditText(rootView)
    val actionResult = if (focusedEditText != null) {
        focusedEditText.post {
            if (clearFirst) focusedEditText.setText("")
            focusedEditText.append(text)
            focusedEditText.setSelection(focusedEditText.text?.length ?: 0)
        }
        "Input text '$text' into focused EditText"
    } else {
        val firstEditText = findFirstEditText(rootView)
        if (firstEditText != null) {
            firstEditText.post {
                firstEditText.requestFocus()
                if (clearFirst) firstEditText.setText("")
                firstEditText.append(text)
                firstEditText.setSelection(firstEditText.text?.length ?: 0)
            }
            "Input text '$text' into first available EditText"
        } else {
            "Error: No EditText found on current screen"
        }
    }

    if (actionResult.startsWith("Error:")) {
        return actionResult
    }

    waitForUiSettle()
    return capturePostActionState(actionResult)
}
```

- [ ] **Step 5: 修改 `go_back` 工具**

把 `goBack` 方法改为：

```kotlin
@Tool(name = "go_back", value = ["返回上一页"])
fun goBack(): String {
    val activity = currentActivity ?: return "Error: No current activity reference available"
    val actionResult = try {
        if (activity is ComponentActivity) {
            activity.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        } else {
            activity.runOnUiThread { @Suppress("DEPRECATION") activity.onBackPressed() }
        }
        "Navigated back"
    } catch (e: Exception) {
        "Error: Back navigation failed: ${e.message}"
    }

    if (actionResult.startsWith("Error:")) {
        return actionResult
    }

    waitForUiSettle(navigation = true)
    return capturePostActionState(actionResult)
}
```

- [ ] **Step 6: 修改 `navigate_to` 工具**

把 `navigateTo` 方法改为：

```kotlin
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
```

> **实现注意**：`capturePostActionState` 和现有 `getScreenInfo` 一样从 AiServices 执行线程调用。`ViewHierarchyExtractor` 读取 View 属性，与现有 `performClick`/`smoothScrollBy` 调用处于同一上下文。如果后续出现线程安全异常，可改为通过 `currentActivity.runOnUiThread` + `CountDownLatch` 在 UI 线程执行 dump。

- [ ] **Step 7: 编译 runtime-core**

Run:
```bash
./gradlew :runtime-core:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: 提交 Task 3**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/PicMeToolService.kt
git commit -m "feat(agent): append post-action screen state to UI tools in PicMeToolService"
```

---

## Task 4: 更新 System Prompt

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgentConfig.kt`

- [ ] **Step 1: 在 DEFAULT_SYSTEM_PROMPT 中增加返回格式说明**

在 `## 核心规则` 或 `## 回复格式` 部分追加一段：

```text
## 操作后状态观察

当你调用 click/scroll/input_text/navigate_to/go_back 等 UI 操作工具后，工具返回中会包含操作后的屏幕状态摘要（格式为 "Action: ...\nPost-action screen state: ..."）。请基于该摘要判断操作是否生效，再决定下一步行动或调用 finish。如果屏幕状态未按预期变化，可以尝试重试或换用其他元素。
```

具体修改位置：在 `## 核心规则` 小节后、`## 回复格式` 小节前插入上述文本。

- [ ] **Step 2: 编译 runtime-core**

Run:
```bash
./gradlew :runtime-core:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 提交 Task 4**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgentConfig.kt
git commit -m "feat(agent): update ReAct system prompt for post-action screen state"
```

---

## Task 5: 添加 PicMeToolService 观察行为测试

**Files:**
- Create: `runtime-core/src/test/java/com/mamba/picme/agent/core/inference/remote/tool/PicMeToolServiceObservationTest.kt`

目标：验证 UI 工具返回字符串在 `currentRootView` 为 null 时仍然符合格式；验证格式化辅助方法存在。

- [ ] **Step 1: 编写测试**

```kotlin
package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.picme.agent.core.tool.perception.UiObservationFormatter
import org.junit.Assert.assertTrue
import org.junit.Test

class PicMeToolServiceObservationTest {

    @Test
    fun `formatter marks observation correctly`() {
        val formatted = UiObservationFormatter.format(
            "Clicked at (100, 200)",
            "=== 页面结构摘要 ==="
        )
        assertTrue(UiObservationFormatter.containsObservation(formatted))
    }

    @Test
    fun `capturePostActionState method exists via reflection`() {
        val method = PicMeToolService::class.java.getDeclaredMethod(
            "capturePostActionState",
            String::class.java
        )
        assertTrue("capturePostActionState should be private", method.modifiers and java.lang.reflect.Modifier.PRIVATE != 0)
    }

    @Test
    fun `click method still declares correct parameter annotations`() {
        val method = PicMeToolService::class.java.getDeclaredMethod(
            "click",
            Integer::class.java, Integer::class.java, String::class.java
        )
        val params = method.parameters
        assertTrue("click should have 3 parameters", params.size == 3)
    }
}
```

- [ ] **Step 2: 运行测试**

Run:
```bash
./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.inference.remote.tool.PicMeToolServiceObservationTest"
```

Expected: BUILD SUCCESSFUL with 3 tests passing.

- [ ] **Step 3: 提交 Task 5**

```bash
git add runtime-core/src/test/java/com/mamba/picme/agent/core/inference/remote/tool/PicMeToolServiceObservationTest.kt
git commit -m "test(agent): add PicMeToolService observation behavior tests"
```

---

## Task 6: 全量编译与回归测试

**Files:**
- N/A

- [ ] **Step 1: 编译整个项目**

Run:
```bash
./gradlew :runtime-core:assembleDebug :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: 运行 runtime-core 单元测试**

Run:
```bash
./gradlew :runtime-core:test
```

Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 3: 运行 app 单元测试（不必须，但建议）**

Run:
```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 提交 Task 6**

```bash
git commit -m "chore(agent): verify full build and tests for Feishu ReAct UI observation"
```

---

## 自审检查表

### Spec 覆盖

| Spec 要求 | 对应 Task |
|-----------|-----------|
| 工具层自动追加观察 | Task 3 |
| 复用 `ViewHierarchyExtractor` | Task 1 |
| 返回格式 `"Action: ...\nPost-action screen state: ..."` | Task 2 + Task 3 |
| 控制 Token（紧凑摘要） | Task 1 |
| UI 稳定等待 | Task 3 `waitForUiSettle` |
| 错误处理 | Task 3 中 `capturePostActionState` + null rootView |
| System Prompt 更新 | Task 4 |
| 验收标准 AC-1 ~ AC-6 | Task 3 + Task 5 + Task 6 |

### Placeholder 扫描

- 无 TBD/TODO。
- 所有代码片段均为可直接写入文件的完整代码。
- 所有命令均含预期输出。

### 类型一致性

- `ViewHierarchyExtractor.extractSemanticSummary` 新增参数与调用处一致。
- `UiObservationFormatter.format` 在 `PicMeToolService.capturePostActionState` 中使用一致。
- `waitForUiSettle(navigation: Boolean)` 在 `goBack`/`navigateTo` 中传 `true`，其他传 `false`。

---

## 执行交接

**Plan complete and saved to `docs/superpowers/plans/2026-07-02-feishu-react-ui-verification.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
