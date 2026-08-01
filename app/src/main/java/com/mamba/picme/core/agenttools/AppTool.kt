package com.mamba.picme.core.agenttools

/** 云主机 MCP server 暴露给 Claude 的 App 数据工具（spec §2.2）。穷举分发，新增工具编译期可检查。 */
enum class AppTool(val toolName: String) {
    GET_LOGS("app_get_logs"),
    GET_CRASH_TRACE("app_get_crash_trace"),
    GET_CHAT_HISTORY("app_get_chat_history"),
    GET_RUNTIME_STATE("app_get_runtime_state"),
    GET_GALLERY_SUMMARY("app_get_gallery_summary"),
    ;

    companion object {
        fun fromName(name: String): AppTool? = entries.firstOrNull { it.toolName == name }
    }
}
