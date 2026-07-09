package com.mamba.picme.testing.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.JsonWriter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputConnection
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * PicMe 调试专用 AccessibilityService。
 *
 * 在 localhost:27183 暴露一个极简 JSON-RPC 服务，供 PC 端 `scripts/ui_driver.py` 调用：
 * - ping
 * - ui.dump / ui.find
 * - action.click / action.longClick / action.swipe / action.input / action.pressBack / action.waitForIdle
 *
 * ⚠️ 仅用于开发/自动化测试，不面向最终用户。
 */
class PicMeAccessibilityService : AccessibilityService() {

    private val serverThread = Thread({ serverLoop() }, "PicMeAccessibilityServer").apply { isDaemon = true }
    private val clientExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "PicMeAccessibilityClient").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        startServer()
        Log.i(TAG, "Accessibility service connected, server starting on port $PORT")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无需处理事件，按需 dump 即可。
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopServer()
        return super.onUnbind(intent)
    }

    private fun startServer() {
        if (running.getAndSet(true)) return
        serverThread.start()
    }

    private fun serverLoop() {
        try {
            ServerSocket(PORT).use { socket ->
                serverSocket = socket
                while (running.get()) {
                    val client = socket.accept()
                    clientExecutor.execute { handleClient(client) }
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "Server error", e)
            }
        } finally {
            running.set(false)
            serverSocket = null
        }
    }

    private fun stopServer() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clientExecutor.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = OutputStreamWriter(client.getOutputStream())
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val response = processRequest(line!!)
                writer.write(response)
                writer.write("\n")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun processRequest(line: String): String {
        return try {
            val request = org.json.JSONObject(line)
            val id = request.opt("id") ?: org.json.JSONObject.NULL
            val method = request.optString("method", "")
            val params = request.optJSONObject("params") ?: org.json.JSONObject()
            val result = when (method) {
                "ping" -> org.json.JSONObject().put("pong", true)
                "ui.dump" -> handleDump(params)
                "ui.find" -> handleFind(params)
                "action.click" -> handleClick(params, long = false)
                "action.longClick" -> handleClick(params, long = true)
                "action.swipe" -> handleSwipe(params)
                "action.input" -> handleInput(params)
                "action.pressBack" -> handlePressBack()
                "action.waitForIdle" -> handleWaitForIdle(params)
                else -> org.json.JSONObject().put("error", "Unknown method: $method")
            }
            buildResponse(id, result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process request: $line", e)
            buildResponse(org.json.JSONObject.NULL, org.json.JSONObject().put("error", e.message))
        }
    }

    private fun buildResponse(id: Any, result: org.json.JSONObject): String {
        return org.json.JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
            .toString()
    }

    private fun handleDump(params: org.json.JSONObject): org.json.JSONObject {
        val packageName = params.optString("package")
        val maxDepth = params.optInt("maxDepth", 50)
        val root = rootInActiveWindow
            ?: return org.json.JSONObject().put("nodes", org.json.JSONObject())
        val json = serializeNode(root, packageName, maxDepth, 0)
        root.recycle()
        return org.json.JSONObject().put("nodes", json)
    }

    private fun handleFind(params: org.json.JSONObject): org.json.JSONObject {
        val root = rootInActiveWindow
            ?: return org.json.JSONObject().put("nodes", org.json.JSONArray())
        val matches = mutableListOf<org.json.JSONObject>()
        val text = params.optString("text", "")
        val contentDesc = params.optString("contentDescription", "")
        val className = params.optString("className", "")
        val clickable = if (params.has("clickable")) params.optBoolean("clickable") else null
        val scrollable = if (params.has("scrollable")) params.optBoolean("scrollable") else null

        collectMatches(root, text, contentDesc, className, clickable, scrollable, matches)
        root.recycle()
        return org.json.JSONObject().put("nodes", org.json.JSONArray(matches))
    }

    private fun collectMatches(
        node: AccessibilityNodeInfo,
        text: String,
        contentDesc: String,
        className: String,
        clickable: Boolean?,
        scrollable: Boolean?,
        matches: MutableList<org.json.JSONObject>
    ) {
        if (nodeMatches(node, text, contentDesc, className, clickable, scrollable)) {
            matches.add(serializeNode(node, "", 50, 0))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                collectMatches(it, text, contentDesc, className, clickable, scrollable, matches)
            }
        }
    }

    private fun nodeMatches(
        node: AccessibilityNodeInfo,
        text: String,
        contentDesc: String,
        className: String,
        clickable: Boolean?,
        scrollable: Boolean?
    ): Boolean {
        if (text.isNotEmpty() && (node.text?.toString()?.contains(text) != true)) return false
        if (contentDesc.isNotEmpty() && (node.contentDescription?.toString()?.contains(contentDesc) != true)) return false
        if (className.isNotEmpty() && (node.className?.toString()?.contains(className) != true)) return false
        if (clickable != null && node.isClickable != clickable) return false
        if (scrollable != null && node.isScrollable != scrollable) return false
        return true
    }

    private fun handleClick(params: org.json.JSONObject, long: Boolean): org.json.JSONObject {
        val root = rootInActiveWindow
        val node = if (root != null) {
            val target = findTargetNode(root, params)
            if (target == null) {
                root.recycle()
                return org.json.JSONObject().put("success", false)
            }
            root.recycle()
            target
        } else null

        val success = if (node != null) {
            val finalNode = walkUpToClickable(node)
            val clicked = if (!long && finalNode.isClickable) {
                finalNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val rect = Rect()
                finalNode.getBoundsInScreen(rect)
                performClick(rect.centerX(), rect.centerY(), long)
                true
            }
            finalNode.recycle()
            clicked
        } else {
            val bounds = parseBounds(params.optJSONObject("bounds"))
            if (bounds != null) {
                performClick(bounds.centerX(), bounds.centerY(), long)
                true
            } else {
                false
            }
        }
        return org.json.JSONObject().put("success", success)
    }

    private fun walkUpToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable && current.parent != null) {
            val parent = current.parent
            if (current != node) current.recycle()
            current = parent
        }
        return current ?: node
    }

    private fun performClick(x: Int, y: Int, long: Boolean) {
        if (long) {
            dispatchGesture(
                android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) },
                            0,
                            800
                        )
                    )
                    .build(),
                null,
                null
            )
        } else {
            dispatchGesture(
                android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) },
                            0,
                            100
                        )
                    )
                    .build(),
                null,
                null
            )
        }
    }

    private fun handleSwipe(params: org.json.JSONObject): org.json.JSONObject {
        val start = params.optJSONObject("start")
        val end = params.optJSONObject("end")
        val durationMs = params.optInt("durationMs", 300)
        if (start == null || end == null) {
            return org.json.JSONObject().put("success", false)
        }
        val x1 = start.optInt("x")
        val y1 = start.optInt("y")
        val x2 = end.optInt("x")
        val y2 = end.optInt("y")
        val path = android.graphics.Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path,
                        0,
                        durationMs.toLong()
                    )
                )
                .build(),
            null,
            null
        )
        return org.json.JSONObject().put("success", true)
    }

    private fun handleInput(params: org.json.JSONObject): org.json.JSONObject {
        val value = params.optString("value", "")
        val root = rootInActiveWindow
        val node = if (root != null) {
            val target = findTargetNode(root, params)
            root.recycle()
            target
        } else null

        val success = if (node != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            false
        }
        node?.recycle()
        return org.json.JSONObject().put("success", success)
    }

    private fun handlePressBack(): org.json.JSONObject {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        return org.json.JSONObject().put("success", success)
    }

    private fun handleWaitForIdle(params: org.json.JSONObject): org.json.JSONObject {
        val timeoutMs = params.optInt("timeoutMs", 5000)
        // AccessibilityService 没有直接暴露 waitForIdle，简单 sleep 模拟。
        Thread.sleep(min(timeoutMs.toLong(), 5000L))
        return org.json.JSONObject().put("success", true)
    }

    private fun findTargetNode(root: AccessibilityNodeInfo, params: org.json.JSONObject): AccessibilityNodeInfo? {
        val text = params.optString("text", "")
        val contentDesc = params.optString("contentDescription", "")
        val bounds = parseBounds(params.optJSONObject("bounds"))

        if (bounds != null) {
            return findNodeByBounds(root, bounds)
        }
        if (text.isNotEmpty()) {
            return root.findAccessibilityNodeInfosByText(text).firstOrNull()
        }
        if (contentDesc.isNotEmpty()) {
            return findNodeByContentDesc(root, contentDesc)
        }
        return null
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect.contains(bounds.centerX(), bounds.centerY())) {
            for (i in 0 until root.childCount) {
                root.getChild(i)?.let { child ->
                    findNodeByBounds(child, bounds)?.let { return it }
                }
            }
            return root
        }
        return null
    }

    private fun findNodeByContentDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString()?.contains(desc) == true) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findNodeByContentDesc(child, desc)?.let { return it }
            }
        }
        return null
    }

    private fun parseBounds(obj: org.json.JSONObject?): Rect? {
        if (obj == null) return null
        return Rect(
            obj.optInt("left"),
            obj.optInt("top"),
            obj.optInt("right"),
            obj.optInt("bottom")
        )
    }

    private fun serializeNode(
        node: AccessibilityNodeInfo,
        packageFilter: String,
        maxDepth: Int,
        depth: Int
    ): org.json.JSONObject {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val json = org.json.JSONObject()
            .put("id", node.viewIdResourceName ?: "")
            .put("packageName", node.packageName ?: "")
            .put("className", node.className ?: "")
            .put("text", node.text?.toString() ?: "")
            .put("contentDescription", node.contentDescription?.toString() ?: "")
            .put("hint", "")
            .put("bounds", org.json.JSONObject()
                .put("left", rect.left)
                .put("top", rect.top)
                .put("right", rect.right)
                .put("bottom", rect.bottom))
            .put("clickable", node.isClickable)
            .put("longClickable", node.isLongClickable)
            .put("scrollable", node.isScrollable)
            .put("enabled", node.isEnabled)
            .put("checked", node.isChecked)
            .put("selected", node.isSelected)
            .put("focused", node.isFocused)

        val children = org.json.JSONArray()
        if (depth < maxDepth) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    if (packageFilter.isEmpty() || child.packageName == packageFilter) {
                        children.put(serializeNode(child, packageFilter, maxDepth, depth + 1))
                    }
                    child.recycle()
                }
            }
        }
        json.put("children", children)
        return json
    }

    companion object {
        private const val TAG = "PicMeAccessibilitySvc"
        private const val PORT = 27183
    }
}
