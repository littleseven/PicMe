package com.mamba.picme.features.chat

import android.content.Context

/** claude-tunnel 网关 sid 持久化（spec §3.3）：进程重建后 --resume 续上下文，修复失忆。 */
interface ClaudeSidStore {
    fun load(sessionId: String): String?
    fun save(sessionId: String, sid: String)
    fun clear(sessionId: String)
}

/** SharedPreferences 实现：key = claude_sid_<sessionId>。 */
class PrefsClaudeSidStore(context: Context) : ClaudeSidStore {
    private val prefs = context.getSharedPreferences("claude_tunnel", Context.MODE_PRIVATE)

    override fun load(sessionId: String): String? =
        prefs.getString(key(sessionId), null)

    override fun save(sessionId: String, sid: String) {
        prefs.edit().putString(key(sessionId), sid).apply()
    }

    override fun clear(sessionId: String) {
        prefs.edit().remove(key(sessionId)).apply()
    }

    private fun key(sessionId: String) = "claude_sid_$sessionId"
}
