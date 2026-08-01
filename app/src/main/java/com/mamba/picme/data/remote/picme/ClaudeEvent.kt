package com.mamba.picme.data.remote.picme

import org.json.JSONObject

/** spec §6 事件（claude-tunnel 网关 → server SSE 透传 → app 消费）。 */
sealed class ClaudeEvent {
    data class Session(val sid: String) : ClaudeEvent()
    data class AssistantText(val delta: String) : ClaudeEvent()
    data class ToolUse(val tool: String, val input: JSONObject) : ClaudeEvent()
    data class ToolResult(val ok: Boolean, val summary: String) : ClaudeEvent()
    data class FileChange(val path: String, val action: String) : ClaudeEvent()
    /** spec §6 cost：本轮 turns 与费用（分）。可选事件，app 仅用于额度提示。 */
    data class Cost(val turns: Int, val cents: Int) : ClaudeEvent()
    data class Error(val message: String) : ClaudeEvent()
    /** spec §4.4：网关下行的 App 数据请求（MCP tool call → App 采集回传）。 */
    data class AppToolRequest(val requestId: String, val tool: String, val args: JSONObject) : ClaudeEvent()
    data object Done : ClaudeEvent()
}
