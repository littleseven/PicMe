package com.mamba.picme.data.repository

import com.mamba.picme.data.local.dao.ChatImageCacheDao
import com.mamba.picme.data.local.entity.ChatImageCacheEntity

/** 内存版 DAO，供 ChatImageStoreImplTest 验证 LRU/对账逻辑（无需 Room/Robolectric）。 */
class FakeChatImageCacheDao : ChatImageCacheDao {
    private val rows = mutableMapOf<String, ChatImageCacheEntity>()

    override suspend fun upsert(row: ChatImageCacheEntity) { rows[row.filePath] = row }
    override suspend fun updateStatus(filePath: String, status: String) {
        rows[filePath]?.let { rows[filePath] = it.copy(status = status) }
    }
    override suspend fun updateLastAccessed(filePath: String, ts: Long) {
        rows[filePath]?.let { rows[filePath] = it.copy(lastAccessedAt = ts) }
    }
    override suspend fun sumSizeWhereActive(): Long =
        rows.values.filter { it.status == "ACTIVE" }.sumOf { it.sizeBytes }
    override suspend fun oldestActive(limit: Int): List<ChatImageCacheEntity> =
        rows.values.filter { it.status == "ACTIVE" }.sortedBy { it.lastAccessedAt }.take(limit)
    override suspend fun getByPath(filePath: String): ChatImageCacheEntity? = rows[filePath]
    override suspend fun getActiveBySession(sessionId: String): List<ChatImageCacheEntity> =
        rows.values.filter { it.sessionId == sessionId && it.status == "ACTIVE" }
    override suspend fun allRows(): List<ChatImageCacheEntity> = rows.values.toList()
    override suspend fun allFilePaths(): List<String> = rows.keys.toList()
    override suspend fun deleteByPath(filePath: String) { rows.remove(filePath) }
    override suspend fun pruneTerminalRows() {
        rows.values.removeAll { it.status in setOf("SAVED", "EVICTED") }
    }
}
