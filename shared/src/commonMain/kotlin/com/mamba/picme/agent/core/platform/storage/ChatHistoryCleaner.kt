package com.mamba.picme.agent.core.platform.storage

/**
 * 旧 langchain4j 键空间（`memory_$sessionId`）会话历史清理抽象。
 *
 * Koog 迁移后会话读/写已由 [ChatMemoryStore]（`koog_memory_` 键前缀）承担，
 * 本接口仅剩重置会话时的历史清理语义。Android actual = `MemoryManager`（DataStore，
 * shared androidMain）；iOS actual 属 Phase 6。
 */
fun interface ChatHistoryCleaner {
    /** 清空指定 session 的旧键空间对话历史。 */
    suspend fun clearHistory(sessionId: String)
}
