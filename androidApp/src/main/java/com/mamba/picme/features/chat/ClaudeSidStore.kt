package com.mamba.picme.features.chat

import android.content.Context

/**
 * claude-tunnel 网关 sid 持久化（spec §3.3）：进程重建后 --resume 续上下文，修复失忆。
 * 单槽语义：工程师模式全局一个上下文，同时记录它属于哪个 chat 会话——
 * 重进工程师模式时切回该会话，transcript（Room 消息）与 agent 上下文（--resume）双连续。
 */
interface ClaudeSidStore {
    /** @return (chatSessionId, claudeSid)；无记录 → null */
    fun load(): Pair<String, String>?
    fun save(chatSessionId: String, claudeSid: String)
    fun clear()
}

/** SharedPreferences 实现：claude_sid / claude_chat_session 两个 key 一起写一起清。 */
class PrefsClaudeSidStore(context: Context) : ClaudeSidStore {
    private val prefs = context.getSharedPreferences("claude_tunnel", Context.MODE_PRIVATE)

    override fun load(): Pair<String, String>? {
        val sid = prefs.getString(KEY_SID, null) ?: return null
        val chatSessionId = prefs.getString(KEY_CHAT_SESSION, null) ?: return null
        return chatSessionId to sid
    }

    override fun save(chatSessionId: String, claudeSid: String) {
        prefs.edit()
            .putString(KEY_SID, claudeSid)
            .putString(KEY_CHAT_SESSION, chatSessionId)
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SID).remove(KEY_CHAT_SESSION).apply()
    }

    private companion object {
        const val KEY_SID = "claude_sid"
        const val KEY_CHAT_SESSION = "claude_chat_session"
    }
}
