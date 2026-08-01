package com.mamba.picme.core.agenttools

import com.mamba.picme.core.diag.DiagSanitizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * app_tool_request 的采集分发器（spec §3.1）：按工具采集 → 脱敏 → 截断（≤32KB）→ 返回 payload。
 *
 * 全部依赖以函数/接口注入，纯 JVM 可测；Android 接线（Logger 环缓冲、CrashTraceStore、
 * Room DAO、UserSettingsRepository、GetGallerySummaryUseCase）在 ViewModel 层的工厂完成。
 */
class AppToolExecutor(
    private val logProvider: () -> String,
    private val crashTraceReader: () -> String?,
    private val chatHistoryLoader: suspend (sessionId: String?, limit: Int) -> List<Pair<String, String>>,
    private val runtimeStateProvider: RuntimeStateProvider,
    private val gallerySummaryLoader: () -> JSONObject,
) {
    /** 执行一次采集，返回可直接放入 postToolResult payload 的 JSON。 */
    suspend fun execute(tool: AppTool, args: JSONObject): JSONObject {
        val payload = when (tool) {
            AppTool.GET_LOGS -> collectLogs(args)
            AppTool.GET_CRASH_TRACE -> collectCrash()
            AppTool.GET_CHAT_HISTORY -> collectChatHistory(args)
            AppTool.GET_RUNTIME_STATE -> runtimeStateProvider.snapshot()
            AppTool.GET_GALLERY_SUMMARY -> gallerySummaryLoader()
        }
        return truncate(payload)
    }

    private fun collectLogs(args: JSONObject): JSONObject {
        val filter = args.optString("filter").takeIf { it.isNotBlank() }
        val lines = args.optInt("lines", DEFAULT_LOG_LINES).coerceIn(1, MAX_LOG_LINES)
        val all = logProvider().lines()
            .let { l -> if (filter != null) l.filter { it.contains(filter) } else l }
        if (all.isEmpty()) return emptyPayload("no_matching_logs")
        return JSONObject().put("empty", false)
            .put("logs", DiagSanitizer.sanitize(all.take(lines).joinToString("\n")))
    }

    private fun collectCrash(): JSONObject {
        val trace = crashTraceReader()?.takeIf { it.isNotBlank() }
            ?: return emptyPayload("no_crash_trace")
        return JSONObject().put("empty", false).put("crashTrace", DiagSanitizer.sanitize(trace))
    }

    private suspend fun collectChatHistory(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", DEFAULT_HISTORY_LIMIT).coerceIn(1, MAX_HISTORY_LIMIT)
        val sessionId = args.optString("sessionId").takeIf { it.isNotBlank() }
        val history = chatHistoryLoader(sessionId, limit)
        if (history.isEmpty()) return emptyPayload("no_chat_history")
        val arr = JSONArray()
        history.forEach { (type, content) ->
            arr.put(JSONObject().put("type", type).put("content", DiagSanitizer.sanitize(content)))
        }
        return JSONObject().put("empty", false).put("messages", arr)
    }

    private fun emptyPayload(reason: String) =
        JSONObject().put("empty", true).put("reason", reason)

    /**
     * 超 32KB 时的截断（宁可截断也不撑爆 MCP tool result）：
     * - String 字段：截断文本 + 标记；
     * - JSONArray 字段：逐条裁剪条目直到总长回到预算内（保 JSON 结构，
     *   绝不把数组腐蚀成字符串——否则 chat history 的 messages 会变成一段文本）。
     */
    private fun truncate(payload: JSONObject): JSONObject {
        if (payload.toString().length <= MAX_PAYLOAD_BYTES) return payload.put("truncated", false)
        val keys = payload.keys().asSequence().toList()
        val biggest = keys.maxByOrNull { key ->
            when (val v = payload.opt(key)) {
                is String -> v.length
                is JSONArray -> v.toString().length
                else -> 0
            }
        } ?: return payload.put("truncated", true)
        when (val v = payload.opt(biggest)) {
            is String -> {
                val budget = (MAX_PAYLOAD_BYTES - 1024).coerceAtLeast(1024)
                payload.put(biggest, v.take(budget) + "…[truncated]")
            }
            is JSONArray -> {
                while (v.length() > 0 && payload.toString().length > MAX_PAYLOAD_BYTES) {
                    v.remove(v.length() - 1)
                }
            }
            else -> Unit
        }
        return payload.put("truncated", true)
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 32 * 1024
        private const val MAX_LOG_LINES = 500
        private const val DEFAULT_LOG_LINES = 200
        private const val MAX_HISTORY_LIMIT = 50
        private const val DEFAULT_HISTORY_LIMIT = 20
    }
}
