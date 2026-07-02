# PicMe AccessibilityService UI 自动化 Phase 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `:app` 模块的 `src/debug/` 源集中实现一个基于 Local Socket 的 AccessibilityService，提供结构化 UI dump 和基础输入操作，并配套 PC 端 Python 驱动客户端，跑通最小闭环。

**Architecture:** Android 端由 `PicMeAccessibilityService` 启动一个 localhost TCP socket server（端口 27183），PC 端通过 `adb forward` 连接后用 Line-Delimited JSON-RPC 2.0 调用 `ui.dump` / `ui.find` / `action.click` 等方法。所有服务端代码仅存在于 `app/src/debug/`，release 构建不包含。

**Tech Stack:** Kotlin (Android AccessibilityService + Coroutines), `org.json` (内置), Python 3.9+, `adb`

---

## 文件映射

| 文件 | 职责 |
|------|------|
| `app/src/debug/java/com/mamba/picme/testing/accessibility/model/UiNode.kt` | UI 节点数据类与 JSON 序列化 |
| `app/src/debug/java/com/mamba/picme/testing/accessibility/model/UiWindow.kt` | 窗口元数据 |
| `app/src/debug/java/com/mamba/picme/testing/accessibility/model/RpcModels.kt` | JSON-RPC 请求/响应模型 |
| `app/src/debug/java/com/mamba/picme/testing/accessibility/AccessibilityNodeSerializer.kt` | 把 `AccessibilityNodeInfo` 树序列化为 `UiNode` |
| `app/src/debug/java/com/mamba/picme/testing/accessibility/AccessibilityActionPerformer.kt` | 点击、滑动、输入、返回、等待空闲 |
| `app/src/debug/java/com/mamba/picme/testing/accessibility/UiAutomationRpcServer.kt` | Socket server + JSON-RPC 分发 |
| `app/src/debug/java/com/mamba/picme/testing/accessibility/PicMeAccessibilityService.kt` | AccessibilityService 生命周期管理 |
| `app/src/debug/AndroidManifest.xml` | debug 合并用 Service 声明 |
| `app/src/debug/res/xml/accessibility_service_config.xml` | 无障碍服务配置 |
| `app/src/debug/res/values/accessibility_service_strings.xml` | 服务描述字符串 |
| `app/src/testDebug/java/com/mamba/picme/testing/accessibility/UiNodeSerializationTest.kt` | `UiNode` 序列化单元测试 |
| `app/src/testDebug/java/com/mamba/picme/testing/accessibility/RpcRequestParseTest.kt` | RPC 请求解析单元测试 |
| `scripts/ui_driver.py` | PC 端 `UiDriverClient` |
| `scripts/verify_ui_driver.py` | 最小集成验证脚本 |

---

## Task 1: 创建数据模型

**Files:**
- Create: `app/src/debug/java/com/mamba/picme/testing/accessibility/model/UiNode.kt`
- Create: `app/src/debug/java/com/mamba/picme/testing/accessibility/model/UiWindow.kt`
- Create: `app/src/debug/java/com/mamba/picme/testing/accessibility/model/RpcModels.kt`

- [ ] **Step 1: 编写 `UiNode.kt`**

```kotlin
package com.mamba.picme.testing.accessibility.model

import org.json.JSONArray
import org.json.JSONObject

data class UiNode(
    val id: String,
    val packageName: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val bounds: Bounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val children: List<UiNode> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        putOpt("packageName", packageName)
        putOpt("className", className)
        putOpt("text", text)
        putOpt("contentDescription", contentDescription)
        putOpt("hint", hint)
        put("bounds", bounds.toJson())
        put("clickable", clickable)
        put("longClickable", longClickable)
        put("scrollable", scrollable)
        put("enabled", enabled)
        put("checked", checked)
        put("selected", selected)
        put("focused", focused)
        put("children", JSONArray(children.map { it.toJson() }))
    }
}

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
    }

    fun center(): Pair<Int, Int> = ((left + right) / 2) to ((top + bottom) / 2)
}
```

- [ ] **Step 2: 编写 `UiWindow.kt`**

```kotlin
package com.mamba.picme.testing.accessibility.model

import org.json.JSONObject

data class UiWindow(
    val title: String?,
    val width: Int,
    val height: Int,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        putOpt("title", title)
        put("width", width)
        put("height", height)
        put("timestampMs", timestampMs)
    }
}
```

- [ ] **Step 3: 编写 `RpcModels.kt`**

```kotlin
package com.mamba.picme.testing.accessibility.model

import org.json.JSONObject

data class RpcRequest(
    val jsonrpc: String,
    val id: Int?,
    val method: String,
    val params: JSONObject?
) {
    companion object {
        fun parse(json: JSONObject): RpcRequest = RpcRequest(
            jsonrpc = json.optString("jsonrpc", "2.0"),
            id = json.optInt("id", -1).takeIf { json.has("id") },
            method = json.getString("method"),
            params = json.optJSONObject("params")
        )
    }
}

data class RpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int?,
    val result: JSONObject? = null,
    val error: JSONObject? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("jsonrpc", jsonrpc)
        id?.let { put("id", it) }
        result?.let { put("result", it) }
        error?.let { put("error", it) }
    }

    companion object {
        fun success(id: Int?, result: JSONObject): RpcResponse =
            RpcResponse(id = id, result = result)

        fun error(id: Int?, code: Int, message: String, data: JSONObject? = null): RpcResponse =
            RpcResponse(
                id = id,
                error = JSONObject().apply {
                    put("code", code)
                    put("message", message)
                    data?.let { put("data", it) }
                }
            )
    }
}
```

- [ ] **Step 4: 提交 Task 1**

```bash
git add app/src/debug/java/com/mamba/picme/testing/accessibility/model/
git commit -m "feat(test): Accessibility UI automation data models"
```

---

## Task 2: 添加模型序列化与 RPC 解析单元测试

**Files:**
- Create: `app/src/testDebug/java/com/mamba/picme/testing/accessibility/UiNodeSerializationTest.kt`
- Create: `app/src/testDebug/java/com/mamba/picme/testing/accessibility/RpcRequestParseTest.kt`

- [ ] **Step 1: 编写 `UiNodeSerializationTest.kt`**

```kotlin
package com.mamba.picme.testing.accessibility

import com.mamba.picme.testing.accessibility.model.Bounds
import com.mamba.picme.testing.accessibility.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiNodeSerializationTest {

    @Test
    fun serializeUiNodeToJson() {
        val node = UiNode(
            id = "0",
            packageName = "com.mamba.picme",
            className = "android.widget.Button",
            text = "相册",
            contentDescription = null,
            hint = null,
            bounds = Bounds(0, 100, 200, 300),
            clickable = true,
            longClickable = false,
            scrollable = false,
            enabled = true,
            checked = false,
            selected = false,
            focused = false,
            children = emptyList()
        )

        val json = node.toJson()
        assertEquals("0", json.getString("id"))
        assertEquals("com.mamba.picme", json.getString("packageName"))
        assertEquals("android.widget.Button", json.getString("className"))
        assertEquals("相册", json.getString("text"))
        assertTrue(json.getBoolean("clickable"))
        assertEquals(0, json.getJSONObject("bounds").getInt("left"))
        assertEquals(100, json.getJSONObject("bounds").getInt("top"))
    }
}
```

- [ ] **Step 2: 编写 `RpcRequestParseTest.kt`**

```kotlin
package com.mamba.picme.testing.accessibility

import com.mamba.picme.testing.accessibility.model.RpcRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RpcRequestParseTest {

    @Test
    fun parseDumpRequest() {
        val json = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "ui.dump")
            put("params", JSONObject().apply {
                put("package", "com.mamba.picme")
                put("maxDepth", 50)
            })
        }

        val request = RpcRequest.parse(json)
        assertEquals("2.0", request.jsonrpc)
        assertEquals(1, request.id)
        assertEquals("ui.dump", request.method)
        assertEquals("com.mamba.picme", request.params?.getString("package"))
        assertEquals(50, request.params?.getInt("maxDepth"))
    }
}
```

- [ ] **Step 3: 运行单元测试**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.testing.accessibility.UiNodeSerializationTest" --tests "com.mamba.picme.testing.accessibility.RpcRequestParseTest"
```

Expected: BUILD SUCCESSFUL with 2 tests passing.

- [ ] **Step 4: 提交 Task 2**

```bash
git add app/src/testDebug/java/com/mamba/picme/testing/accessibility/
git commit -m "test(test): add UiNode serialization and RPC parse tests"
```

---

## Task 3: 创建 AccessibilityNodeSerializer

**Files:**
- Create: `app/src/debug/java/com/mamba/picme/testing/accessibility/AccessibilityNodeSerializer.kt`

- [ ] **Step 1: 编写 `AccessibilityNodeSerializer.kt`**

```kotlin
package com.mamba.picme.testing.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.mamba.picme.testing.accessibility.model.Bounds
import com.mamba.picme.testing.accessibility.model.UiNode
import com.mamba.picme.testing.accessibility.model.UiWindow
import org.json.JSONObject

class AccessibilityNodeSerializer(private val targetPackage: String? = null) {

    fun dump(service: AccessibilityService, maxDepth: Int = 50): JSONObject {
        val root = service.rootInActiveWindow
            ?: return JSONObject().apply {
                put("window", UiWindow(title = null, width = 0, height = 0).toJson())
                put("nodes", UiNode(
                    id = "0",
                    packageName = null,
                    className = null,
                    text = null,
                    contentDescription = null,
                    hint = null,
                    bounds = Bounds(0, 0, 0, 0),
                    clickable = false,
                    longClickable = false,
                    scrollable = false,
                    enabled = false,
                    checked = false,
                    selected = false,
                    focused = false,
                    children = emptyList()
                ).toJson())
            }

        val windowInfo = service.windows.firstOrNull { it.isActive }
        val metrics = service.resources.displayMetrics
        val window = UiWindow(
            title = windowInfo?.title?.toString(),
            width = metrics.widthPixels,
            height = metrics.heightPixels
        )

        val rootNode = serializeNode(root, "0", 0, maxDepth)
        root.recycle()

        return JSONObject().apply {
            put("window", window.toJson())
            put("nodes", rootNode.toJson())
        }
    }

    private fun serializeNode(
        node: AccessibilityNodeInfo,
        id: String,
        depth: Int,
        maxDepth: Int
    ): UiNode {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val children = mutableListOf<UiNode>()
        if (depth < maxDepth) {
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                if (targetPackage != null && child.packageName?.toString() != targetPackage) {
                    child.recycle()
                    continue
                }
                children.add(serializeNode(child, "$id.$i", depth + 1, maxDepth))
                child.recycle()
            }
        }

        return UiNode(
            id = id,
            packageName = node.packageName?.toString(),
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            bounds = Bounds(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom
            ),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            checked = node.isChecked,
            selected = node.isSelected,
            focused = node.isFocused,
            children = children
        )
    }
}
```

- [ ] **Step 2: 编译 debug**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 提交 Task 3**

```bash
git add app/src/debug/java/com/mamba/picme/testing/accessibility/AccessibilityNodeSerializer.kt
git commit -m "feat(test): AccessibilityNodeInfo to UiNode serializer"
```

---

## Task 4: 创建 AccessibilityActionPerformer

**Files:**
- Create: `app/src/debug/java/com/mamba/picme/testing/accessibility/AccessibilityActionPerformer.kt`

- [ ] **Step 1: 编写 `AccessibilityActionPerformer.kt`**

```kotlin
package com.mamba.picme.testing.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AccessibilityActionPerformer(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "AccessibilityAction"
        private const val DEFAULT_SWIPE_DURATION_MS = 300L
        private const val DEFAULT_POLL_MS = 200L
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun longClick(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun click(bounds: Rect): Boolean {
        val x = (bounds.left + bounds.right) / 2
        val y = (bounds.top + bounds.bottom) / 2
        return tap(x, y)
    }

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture)
    }

    fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture)
    }

    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            Logger.w(TAG, "Failed to focus node before input")
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    suspend fun waitForIdle(timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var previousDump = ""
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(DEFAULT_POLL_MS)
            val current = service.rootInActiveWindow?.let { root ->
                val serializer = AccessibilityNodeSerializer()
                val json = serializer.dump(service, maxDepth = 3).toString()
                root.recycle()
                json
            } ?: ""
            if (previousDump.isNotEmpty() && current == previousDump) {
                return@withContext true
            }
            previousDump = current
        }
        false
    }

    private fun dispatchGesture(gesture: GestureDescription): Boolean {
        return service.dispatchGesture(gesture, null, null)
    }
}
```

- [ ] **Step 2: 编译 debug**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 提交 Task 4**

```bash
git add app/src/debug/java/com/mamba/picme/testing/accessibility/AccessibilityActionPerformer.kt
git commit -m "feat(test): Accessibility action performer for click/swipe/input/back"
```

---

## Task 5: 创建 UiAutomationRpcServer

**Files:**
- Create: `app/src/debug/java/com/mamba/picme/testing/accessibility/UiAutomationRpcServer.kt`

- [ ] **Step 1: 编写 `UiAutomationRpcServer.kt`**

```kotlin
package com.mamba.picme.testing.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.mamba.picme.core.common.Logger
import com.mamba.picme.testing.accessibility.model.RpcRequest
import com.mamba.picme.testing.accessibility.model.RpcResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class UiAutomationRpcServer(
    private val service: AccessibilityService,
    private val port: Int = 27183
) {
    companion object {
        private const val TAG = "UiAutomationRpcServer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    fun start() {
        stop()
        serverSocket = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).also { socket ->
            Logger.i(TAG, "Server started on port $port")
            scope.launch {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        scope.launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (!socket.isClosed) {
                            Logger.e(TAG, "Accept failed", e)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to close server socket", e)
        }
        serverSocket = null
    }

    private suspend fun handleClient(client: Socket) {
        Logger.i(TAG, "Client connected: ${client.inetAddress}")
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val response = handleRequest(line!!)
                writer.println(response.toJson().toString())
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Client handling error", e)
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to close client socket", e)
            }
        }
    }

    private suspend fun handleRequest(line: String): RpcResponse = withContext(Dispatchers.Main) {
        val json = try {
            JSONObject(line)
        } catch (e: Exception) {
            return@withContext RpcResponse.error(
                id = null,
                code = -32700,
                message = "Parse error: ${e.message}"
            )
        }

        val request = try {
            RpcRequest.parse(json)
        } catch (e: Exception) {
            return@withContext RpcResponse.error(
                id = json.optInt("id", -1).takeIf { json.has("id") },
                code = -32600,
                message = "Invalid request: ${e.message}"
            )
        }

        try {
            when (request.method) {
                "ping" -> RpcResponse.success(request.id, JSONObject().apply { put("pong", true) })
                "ui.dump" -> handleDump(request)
                "ui.find" -> handleFind(request)
                "action.click" -> handleClick(request)
                "action.longClick" -> handleLongClick(request)
                "action.swipe" -> handleSwipe(request)
                "action.input" -> handleInput(request)
                "action.pressBack" -> handlePressBack(request)
                "action.waitForIdle" -> handleWaitForIdle(request)
                else -> RpcResponse.error(
                    request.id,
                    code = -32601,
                    message = "Method not found: ${request.method}"
                )
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Request handling error", e)
            RpcResponse.error(
                request.id,
                code = -32603,
                message = "Internal error: ${e.message}"
            )
        }
    }

    private fun handleDump(request: RpcRequest): RpcResponse {
        val params = request.params ?: JSONObject()
        val packageName = params.optString("package", null)
        val maxDepth = params.optInt("maxDepth", 50)
        val serializer = AccessibilityNodeSerializer(targetPackage = packageName)
        val result = serializer.dump(service, maxDepth)
        return RpcResponse.success(request.id, result)
    }

    private fun handleFind(request: RpcRequest): RpcResponse {
        val params = request.params ?: JSONObject()
        val text = params.optString("text", null)
        val contentDescription = params.optString("contentDescription", null)
        val className = params.optString("className", null)
        val clickable = if (params.has("clickable")) params.getBoolean("clickable") else null
        val scrollable = if (params.has("scrollable")) params.getBoolean("scrollable") else null

        val root = service.rootInActiveWindow
            ?: return RpcResponse.error(request.id, code = -32001, message = "No active window")

        val matches = findNodes(root, text, contentDescription, className, clickable, scrollable)
        val array = JSONArray(matches.map { nodeToJson(it) })
        matches.forEach { it.recycle() }
        root.recycle()
        return RpcResponse.success(request.id, JSONObject().apply { put("nodes", array) })
    }

    private fun findNodes(
        root: AccessibilityNodeInfo,
        text: String?,
        contentDescription: String?,
        className: String?,
        clickable: Boolean?,
        scrollable: Boolean?
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (matches(node, text, contentDescription, className, clickable, scrollable)) {
                result.add(AccessibilityNodeInfo.obtain(node))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return result
    }

    private fun matches(
        node: AccessibilityNodeInfo,
        text: String?,
        contentDescription: String?,
        className: String?,
        clickable: Boolean?,
        scrollable: Boolean?
    ): Boolean {
        if (text != null && node.text?.toString()?.contains(text) != true) return false
        if (contentDescription != null && node.contentDescription?.toString()?.contains(contentDescription) != true) return false
        if (className != null && node.className?.toString()?.contains(className) != true) return false
        if (clickable != null && node.isClickable != clickable) return false
        if (scrollable != null && node.isScrollable != scrollable) return false
        return true
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return JSONObject().apply {
            put("text", node.text?.toString())
            put("contentDescription", node.contentDescription?.toString())
            put("className", node.className?.toString())
            put("packageName", node.packageName?.toString())
            put("clickable", node.isClickable)
            put("scrollable", node.isScrollable)
            put("enabled", node.isEnabled)
            put("bounds", JSONObject().apply {
                put("left", bounds.left)
                put("top", bounds.top)
                put("right", bounds.right)
                put("bottom", bounds.bottom)
            })
        }
    }

    private fun handleClick(request: RpcRequest): RpcResponse {
        return performAction(request, AccessibilityActionPerformer(service)::click)
    }

    private fun handleLongClick(request: RpcRequest): RpcResponse {
        return performAction(request, AccessibilityActionPerformer(service)::longClick)
    }

    private fun handleSwipe(request: RpcRequest): RpcResponse {
        val params = request.params ?: JSONObject()
        val start = params.getJSONObject("start")
        val end = params.getJSONObject("end")
        val durationMs = params.optLong("durationMs", 300)
        val performer = AccessibilityActionPerformer(service)
        val success = performer.swipe(
            start.getInt("x"),
            start.getInt("y"),
            end.getInt("x"),
            end.getInt("y"),
            durationMs
        )
        return RpcResponse.success(request.id, JSONObject().apply { put("success", success) })
    }

    private fun handleInput(request: RpcRequest): RpcResponse {
        val params = request.params ?: JSONObject()
        val value = params.getString("value")
        val node = findSingleNode(params) ?: return RpcResponse.error(
            request.id,
            code = -32002,
            message = "Target node not found"
        )
        val performer = AccessibilityActionPerformer(service)
        val success = performer.inputText(node, value)
        node.recycle()
        return RpcResponse.success(request.id, JSONObject().apply { put("success", success) })
    }

    private fun handlePressBack(request: RpcRequest): RpcResponse {
        val performer = AccessibilityActionPerformer(service)
        val success = performer.pressBack()
        return RpcResponse.success(request.id, JSONObject().apply { put("success", success) })
    }

    private suspend fun handleWaitForIdle(request: RpcRequest): RpcResponse {
        val params = request.params ?: JSONObject()
        val timeoutMs = params.optLong("timeoutMs", 5000)
        val performer = AccessibilityActionPerformer(service)
        val success = performer.waitForIdle(timeoutMs)
        return RpcResponse.success(request.id, JSONObject().apply { put("success", success) })
    }

    private fun performAction(
        request: RpcRequest,
        action: (AccessibilityNodeInfo) -> Boolean
    ): RpcResponse {
        val params = request.params ?: JSONObject()
        val node = findSingleNode(params) ?: return RpcResponse.error(
            request.id,
            code = -32002,
            message = "Target node not found"
        )
        val success = action(node)
        node.recycle()
        return RpcResponse.success(request.id, JSONObject().apply { put("success", success) })
    }

    private fun findSingleNode(params: JSONObject): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val text = params.optString("text", null)
        val boundsObj = params.optJSONObject("bounds")

        val matches = findNodes(
            root,
            text = text,
            contentDescription = null,
            className = null,
            clickable = null,
            scrollable = null
        )
        root.recycle()

        val node = when {
            boundsObj != null -> {
                val bounds = Rect(
                    boundsObj.getInt("left"),
                    boundsObj.getInt("top"),
                    boundsObj.getInt("right"),
                    boundsObj.getInt("bottom")
                )
                matches.firstOrNull {
                    val nodeBounds = Rect().apply { it.getBoundsInScreen(this) }
                    nodeBounds == bounds
                }
            }
            else -> matches.firstOrNull()
        }
        return node
    }
}
```

- [ ] **Step 2: 编译 debug**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 提交 Task 5**

```bash
git add app/src/debug/java/com/mamba/picme/testing/accessibility/UiAutomationRpcServer.kt
git commit -m "feat(test): JSON-RPC socket server for UI automation"
```

---

## Task 6: 创建 PicMeAccessibilityService

**Files:**
- Create: `app/src/debug/java/com/mamba/picme/testing/accessibility/PicMeAccessibilityService.kt`

- [ ] **Step 1: 编写 `PicMeAccessibilityService.kt`**

```kotlin
package com.mamba.picme.testing.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.mamba.picme.core.common.Logger

class PicMeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PicMeAccessibilityService"
        const val DEFAULT_PORT = 27183
    }

    private var rpcServer: UiAutomationRpcServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i(TAG, "Accessibility service connected")
        rpcServer = UiAutomationRpcServer(this, DEFAULT_PORT).apply { start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we poll rootInActiveWindow on demand via RPC.
    }

    override fun onInterrupt() {
        Logger.i(TAG, "Accessibility service interrupted")
        rpcServer?.stop()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Logger.i(TAG, "Accessibility service unbound")
        rpcServer?.stop()
        return super.onUnbind(intent)
    }
}
```

- [ ] **Step 2: 编译 debug**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 提交 Task 6**

```bash
git add app/src/debug/java/com/mamba/picme/testing/accessibility/PicMeAccessibilityService.kt
git commit -m "feat(test): PicMeAccessibilityService lifecycle wrapper"
```

---

## Task 7: 创建 debug 资源与 Manifest

**Files:**
- Create: `app/src/debug/res/xml/accessibility_service_config.xml`
- Create: `app/src/debug/res/values/accessibility_service_strings.xml`
- Create: `app/src/debug/AndroidManifest.xml`

- [ ] **Step 1: 编写 `accessibility_service_config.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowContentChanged|typeWindowsChanged|typeViewClicked"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description" />
```

- [ ] **Step 2: 编写 `accessibility_service_strings.xml`**

```xml
<resources>
    <string name="accessibility_service_description">PicMe UI Automation Test Service - 用于自动化测试提取界面结构</string>
</resources>
```

- [ ] **Step 3: 编写 `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application>
        <service
            android:name=".testing.accessibility.PicMeAccessibilityService"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
    </application>

</manifest>
```

- [ ] **Step 4: 编译 debug 并检查 Manifest 合并**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: 提交 Task 7**

```bash
git add app/src/debug/res/ app/src/debug/AndroidManifest.xml
git commit -m "feat(test): debug accessibility service manifest and config"
```

---

## Task 8: 创建 Python 客户端 `scripts/ui_driver.py`

**Files:**
- Create: `scripts/ui_driver.py`

- [ ] **Step 1: 编写 `scripts/ui_driver.py`**

```python
#!/usr/bin/env python3
"""UiDriverClient - PC端 AccessibilityService 驱动客户端."""

import json
import socket
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class Bounds:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def center_x(self) -> int:
        return (self.left + self.right) // 2

    @property
    def center_y(self) -> int:
        return (self.top + self.bottom) // 2


@dataclass
class UiNode:
    id: str
    package_name: Optional[str]
    class_name: Optional[str]
    text: Optional[str]
    content_description: Optional[str]
    hint: Optional[str]
    bounds: Bounds
    clickable: bool
    long_clickable: bool
    scrollable: bool
    enabled: bool
    checked: bool
    selected: bool
    focused: bool
    children: list["UiNode"]

    @staticmethod
    def from_json(data: dict) -> "UiNode":
        bounds = data.get("bounds", {})
        return UiNode(
            id=data.get("id", ""),
            package_name=data.get("packageName"),
            class_name=data.get("className"),
            text=data.get("text"),
            content_description=data.get("contentDescription"),
            hint=data.get("hint"),
            bounds=Bounds(
                left=bounds.get("left", 0),
                top=bounds.get("top", 0),
                right=bounds.get("right", 0),
                bottom=bounds.get("bottom", 0),
            ),
            clickable=data.get("clickable", False),
            long_clickable=data.get("longClickable", False),
            scrollable=data.get("scrollable", False),
            enabled=data.get("enabled", False),
            checked=data.get("checked", False),
            selected=data.get("selected", False),
            focused=data.get("focused", False),
            children=[UiNode.from_json(c) for c in data.get("children", [])],
        )


class UiDriverError(Exception):
    pass


class UiDriverClient:
    def __init__(
        self,
        device: Optional[str] = None,
        local_port: int = 27183,
        remote_port: int = 27183,
    ):
        self.device = device
        self.local_port = local_port
        self.remote_port = remote_port
        self._socket: Optional[socket.socket] = None
        self._reader: Optional[Any] = None
        self._writer: Optional[Any] = None
        self._seq = 0

    def __enter__(self) -> "UiDriverClient":
        self._ensure_adb_forward()
        self._connect()
        if not self.ping():
            raise UiDriverError("Ping failed")
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()

    def _ensure_adb_forward(self) -> None:
        cmd = ["adb"]
        if self.device:
            cmd.extend(["-s", self.device])
        cmd.extend(["forward", f"tcp:{self.local_port}", f"tcp:{self.remote_port}"])
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            raise UiDriverError(f"adb forward failed: {result.stderr}")

    def _connect(self) -> None:
        self._socket = socket.create_connection(("127.0.0.1", self.local_port), timeout=5.0)
        self._reader = self._socket.makefile("r")
        self._writer = self._socket.makefile("w")

    def _call(self, method: str, params: Optional[dict] = None) -> dict:
        self._seq += 1
        request = {
            "jsonrpc": "2.0",
            "id": self._seq,
            "method": method,
            "params": params or {},
        }
        self._writer.write(json.dumps(request, ensure_ascii=False) + "\n")
        self._writer.flush()
        line = self._reader.readline()
        if not line:
            raise UiDriverError("Empty response from server")
        response = json.loads(line)
        if "error" in response:
            raise UiDriverError(f"RPC error: {response['error']}")
        return response.get("result", {})

    def ping(self) -> bool:
        result = self._call("ping")
        return result.get("pong", False)

    def dump_ui(self, package: Optional[str] = None, max_depth: int = 50) -> UiNode:
        result = self._call("ui.dump", {"package": package, "maxDepth": max_depth})
        return UiNode.from_json(result.get("nodes", {}))

    def find_nodes(
        self,
        text: Optional[str] = None,
        content_description: Optional[str] = None,
        class_name: Optional[str] = None,
        clickable: Optional[bool] = None,
        scrollable: Optional[bool] = None,
    ) -> list[UiNode]:
        params: dict[str, Any] = {}
        if text is not None:
            params["text"] = text
        if content_description is not None:
            params["contentDescription"] = content_description
        if class_name is not None:
            params["className"] = class_name
        if clickable is not None:
            params["clickable"] = clickable
        if scrollable is not None:
            params["scrollable"] = scrollable
        result = self._call("ui.find", params)
        return [UiNode.from_json(n) for n in result.get("nodes", [])]

    def click(self, text: Optional[str] = None, bounds: Optional[Bounds] = None) -> bool:
        params: dict[str, Any] = {}
        if text is not None:
            params["text"] = text
        if bounds is not None:
            params["bounds"] = {
                "left": bounds.left,
                "top": bounds.top,
                "right": bounds.right,
                "bottom": bounds.bottom,
            }
        result = self._call("action.click", params)
        return result.get("success", False)

    def long_click(self, text: Optional[str] = None) -> bool:
        result = self._call("action.longClick", {"text": text})
        return result.get("success", False)

    def swipe(
        self, start: tuple[int, int], end: tuple[int, int], duration_ms: int = 300
    ) -> bool:
        result = self._call(
            "action.swipe",
            {
                "start": {"x": start[0], "y": start[1]},
                "end": {"x": end[0], "y": end[1]},
                "durationMs": duration_ms,
            },
        )
        return result.get("success", False)

    def input_text(self, value: str, text: Optional[str] = None) -> bool:
        result = self._call("action.input", {"value": value, "text": text})
        return result.get("success", False)

    def press_back(self) -> bool:
        result = self._call("action.pressBack")
        return result.get("success", False)

    def wait_for_idle(self, timeout_ms: int = 5000) -> bool:
        result = self._call("action.waitForIdle", {"timeoutMs": timeout_ms})
        return result.get("success", False)

    def wait_for(
        self, text: str, timeout_ms: int = 5000, poll_ms: int = 200
    ) -> Optional[UiNode]:
        deadline = time.time() + timeout_ms / 1000.0
        while time.time() < deadline:
            nodes = self.find_nodes(text=text)
            if nodes:
                return nodes[0]
            time.sleep(poll_ms / 1000.0)
        return None

    def close(self) -> None:
        try:
            if self._reader:
                self._reader.close()
            if self._writer:
                self._writer.close()
            if self._socket:
                self._socket.close()
        except Exception:
            pass


def format_ui_tree(node: UiNode, indent: int = 0) -> str:
    prefix = "  " * indent
    label = node.text or node.content_description or node.class_name or "Unknown"
    info = []
    if node.clickable:
        info.append("clickable")
    if node.scrollable:
        info.append("scrollable")
    info.append(
        f"bounds=({node.bounds.left},{node.bounds.top},{node.bounds.right},{node.bounds.bottom})"
    )
    lines = [f"{prefix}[{node.class_name or 'Node'}] {label} {', '.join(info)}"]
    for child in node.children:
        lines.extend(format_ui_tree(child, indent + 1))
    return "\n".join(lines)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 scripts/ui_driver.py dump|click <args>")
        sys.exit(1)

    command = sys.argv[1]
    with UiDriverClient() as client:
        if command == "dump":
            tree = client.dump_ui(package="com.mamba.picme")
            print(format_ui_tree(tree))
        elif command == "click":
            text = sys.argv[2] if len(sys.argv) > 2 else ""
            ok = client.click(text=text)
            print(f"click result: {ok}")
        else:
            print(f"Unknown command: {command}")
```

- [ ] **Step 2: 运行 Python 语法检查**

Run:
```bash
python3 -m py_compile scripts/ui_driver.py
```

Expected: No output (success).

- [ ] **Step 3: 提交 Task 8**

```bash
git add scripts/ui_driver.py
git commit -m "feat(test): PC-side UiDriverClient with JSON-RPC over adb forward"
```

---

## Task 9: 创建最小集成验证脚本

**Files:**
- Create: `scripts/verify_ui_driver.py`

- [ ] **Step 1: 编写 `scripts/verify_ui_driver.py`**

```python
#!/usr/bin/env python3
"""最小验证脚本：dump 当前 PicMe 界面并尝试点击相册入口."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from ui_driver import UiDriverClient, format_ui_tree


def main():
    print("Connecting to PicMeAccessibilityService...")
    with UiDriverClient() as client:
        print("Dumping UI...")
        tree = client.dump_ui(package="com.mamba.picme")
        print(format_ui_tree(tree))

        gallery = client.find_nodes(text="相册")
        if gallery:
            print(f"Found gallery node: {gallery[0].text}, clicking...")
            client.click(text="相册")
            print("Clicked gallery")
        else:
            print("No '相册' node found, skipping click")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 语法检查**

Run:
```bash
python3 -m py_compile scripts/verify_ui_driver.py
```

Expected: No output.

- [ ] **Step 3: 提交 Task 9**

```bash
git add scripts/verify_ui_driver.py
git commit -m "feat(test): minimal UI driver integration verification script"
```

---

## Task 10: 编译 debug 并运行单元测试

- [ ] **Step 1: 编译 debug**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: 运行 debug 单元测试**

Run:
```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, tests passing.

- [ ] **Step 3: 提交 Task 10**

无新增文件，若只有测试/编译通过：
```bash
git commit --allow-empty -m "chore(test): debug accessibility automation compiles and unit tests pass"
```

---

## Task 11: 设备集成验证

- [ ] **Step 1: 安装 debug APK**

Run:
```bash
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL, APK installed.

- [ ] **Step 2: 检查/开启 AccessibilityService**

Check:
```bash
adb shell settings get secure enabled_accessibility_services | grep com.mamba.picme
```

If empty, open settings:
```bash
adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
```

Then manually启用 `PicMe UI Automation Test Service`.

- [ ] **Step 3: 启动 PicMe 并运行验证脚本**

Run:
```bash
adb shell am start -n com.mamba.picme/.MainActivity
python3 scripts/verify_ui_driver.py
```

Expected: 输出当前相机页结构化 UI；如果能找到“相册”节点，会输出 `Clicked gallery`。

- [ ] **Step 4: 提交 Task 11**

```bash
git commit --allow-empty -m "test(test): manual integration verified on device"
```

---

## Task 12: 最终汇总提交（可选，若前面已分次提交则跳过）

- [ ] **Step 1: 检查变更列表**

Run:
```bash
git status
```

Expected: 所有新增文件均已提交，工作区干净。

- [ ] **Step 2: 汇总提交（如需要）**

如果前面没有分次提交，则一次性提交：
```bash
git add app/src/debug/ app/src/testDebug/ scripts/ui_driver.py scripts/verify_ui_driver.py
git commit -m "feat(test): AccessibilityService-based structured UI automation (Phase 1)

- Add PicMeAccessibilityService in app/src/debug/
- JSON-RPC socket server over localhost:27183
- ui.dump / ui.find / action.click / action.swipe / action.input / action.pressBack
- Python UiDriverClient with adb forward management
- Unit tests for model serialization and RPC parsing"
```

---

## 自我审查

### Spec 覆盖检查

| Spec 要求 | 对应 Task |
|-----------|-----------|
| Local Socket + `adb forward` | Task 5, Task 8 |
| Line-Delimited JSON-RPC 2.0 | Task 5, Task 8 |
| UI 节点模型 | Task 1 |
| `ui.dump` / `ui.find` | Task 5 |
| `action.click` / `action.swipe` / `action.input` / `action.pressBack` | Task 4, Task 5 |
| 仅 debug 源集 | Task 7 manifest |
| Python 客户端 | Task 8 |
| 与现有广播命令不冲突 | 架构边界说明 |
| 手动启用服务指引 | Task 11 |

### Placeholder 扫描

- 无 TBD / TODO。
- 无 "implement later"。
- 每个 Task 包含完整代码和命令。

### 类型一致性检查

- `UiNode.toJson()` 在 Task 1 定义，Task 3 / Task 5 使用。
- `RpcRequest.parse()` 在 Task 1 定义，Task 5 使用。
- `Bounds` 字段名 `left/top/right/bottom` 在 Kotlin 和 Python 中一致。
- RPC 方法名 `ui.dump`, `ui.find`, `action.click` 等在 Kotlin server 和 Python client 中一致。

### 已知限制

- `nodeId` 操作未在 Phase 1 支持，仅支持 `text` 和 `bounds` 定位。
- `action.input` 仅支持通过 `text` 查找目标输入框。
- 无障碍服务必须手动开启一次。
