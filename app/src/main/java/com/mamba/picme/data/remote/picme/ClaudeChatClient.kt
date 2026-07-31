package com.mamba.picme.data.remote.picme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** SSE 文本 → ClaudeEvent 解析（spec §6）。纯逻辑，单测覆盖。 */
object ClaudeSseParser {
    fun parse(sse: String): List<ClaudeEvent> {
        val events = mutableListOf<ClaudeEvent>()
        val blocks = sse.split("\n\n")
        for (block in blocks) {
            var type: String? = null
            var data: String? = null
            for (line in block.split("\n")) {
                when {
                    line.startsWith("event:") -> type = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> data = line.removePrefix("data:").trim()
                }
            }
            val t = type ?: continue
            val json = try {
                JSONObject(data ?: "{}")
            } catch (_: Throwable) {
                continue
            }
            val ev = when (t) {
                "session" -> json.optString("sid").takeIf { it.isNotBlank() }?.let { ClaudeEvent.Session(it) }
                "assistant_text" -> ClaudeEvent.AssistantText(json.optString("delta"))
                "tool_use" -> ClaudeEvent.ToolUse(
                    json.optString("tool"),
                    json.optJSONObject("input") ?: JSONObject(),
                )
                "tool_result" -> ClaudeEvent.ToolResult(json.optBoolean("ok"), json.optString("summary"))
                "file_change" -> ClaudeEvent.FileChange(json.optString("path"), json.optString("action"))
                "cost" -> ClaudeEvent.Cost(json.optInt("turns", 0), json.optInt("cents", 0))
                "error" -> ClaudeEvent.Error(json.optString("message"))
                "done" -> ClaudeEvent.Done
                else -> null
            }
            ev?.let { events.add(it) }
        }
        return events
    }
}

/**
 * claude-tunnel chat 客户端。镜像 [DiagClient] 风格（OkHttp + X-App-Token + org.json），
 * 加 SSE 流式读：POST /v1/claude-chat，逐 chunk 累积，按双换行切事件，回调 onEvent。
 */
class ClaudeChatClient(private val baseUrl: String = DEFAULT_BASE_URL) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接，不超时（靠网关 CT_PHASE_TIMEOUT 兜底）
        .build()
    private val jsonMedia = "application/json".toMediaType()

    /** 流式 chat：onEvent 在 IO 线程回调每个 §6 事件；返回 session 事件给的 sid（多轮用）。 */
    suspend fun chat(
        token: String,
        message: String,
        sid: String? = null,
        onEvent: (ClaudeEvent) -> Unit,
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("message", message).also {
                sid?.takeIf { s -> s.isNotBlank() }?.let { s -> it.put("sid", s) }
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/v1/claude-chat")
                .header("X-App-Token", token)
                .post(body.toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
            }
            val source = resp.body?.byteStream() ?: throw RuntimeException("empty body")
            val sb = StringBuilder()
            var sessionSid: String? = null
            val buf = ByteArray(4 * 1024)
            while (true) {
                val n = source.read(buf)
                if (n == -1) break
                sb.append(String(buf, 0, n))
                while (true) {
                    val idx = sb.indexOf("\n\n")
                    if (idx == -1) break
                    val block = sb.substring(0, idx)
                    sb.delete(0, idx + 2)
                    for (ev in ClaudeSseParser.parse(block + "\n\n")) {
                        if (ev is ClaudeEvent.Session) sessionSid = ev.sid
                        onEvent(ev)
                    }
                }
            }
            sessionSid
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
