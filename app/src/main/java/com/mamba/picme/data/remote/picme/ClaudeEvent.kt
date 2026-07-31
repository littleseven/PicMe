package com.mamba.picme.data.remote.picme

import org.json.JSONObject

/** spec §6 事件（claude-tunnel 网关 → server SSE 透传 → app 消费）。 */
sealed class ClaudeEvent {
    data class Session(val sid: String) : ClaudeEvent()
    data class AssistantText(val delta: String) : ClaudeEvent()
    data class ToolUse(val tool: String, val input: JSONObject) : ClaudeEvent()
    data class ToolResult(val ok: Boolean, val summary: String) : ClaudeEvent()
    data class FileChange(val path: String, val action: String) : ClaudeEvent()
    data class Error(val message: String) : ClaudeEvent()
    data object Done : ClaudeEvent()
}
