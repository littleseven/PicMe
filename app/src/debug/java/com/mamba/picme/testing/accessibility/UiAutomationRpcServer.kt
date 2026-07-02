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
