package com.mamba.picme.agent.core.platform.storage

import ai.koog.prompt.message.Message

/** 对话记忆持久化抽象。Android actual = DataStore；iOS actual 属 Phase 6。 */
interface ChatMemoryStore {
    suspend fun load(sessionId: String): List<Message>
    suspend fun save(sessionId: String, messages: List<Message>)
    suspend fun clear(sessionId: String)
}
