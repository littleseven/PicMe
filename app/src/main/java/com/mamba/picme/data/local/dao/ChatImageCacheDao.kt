package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.ChatImageCacheEntity

@Dao
interface ChatImageCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ChatImageCacheEntity)

    @Query("UPDATE chat_image_cache SET status = :status WHERE filePath = :filePath")
    suspend fun updateStatus(filePath: String, status: String)

    @Query("UPDATE chat_image_cache SET lastAccessedAt = :ts WHERE filePath = :filePath")
    suspend fun updateLastAccessed(filePath: String, ts: Long)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM chat_image_cache WHERE status = 'ACTIVE'")
    suspend fun sumSizeWhereActive(): Long

    @Query("SELECT * FROM chat_image_cache WHERE status = 'ACTIVE' ORDER BY lastAccessedAt ASC LIMIT :limit")
    suspend fun oldestActive(limit: Int): List<ChatImageCacheEntity>

    @Query("SELECT * FROM chat_image_cache WHERE filePath = :filePath")
    suspend fun getByPath(filePath: String): ChatImageCacheEntity?

    @Query("SELECT * FROM chat_image_cache WHERE sessionId = :sessionId AND status = 'ACTIVE'")
    suspend fun getActiveBySession(sessionId: String): List<ChatImageCacheEntity>

    @Query("SELECT * FROM chat_image_cache")
    suspend fun allRows(): List<ChatImageCacheEntity>

    @Query("SELECT filePath FROM chat_image_cache")
    suspend fun allFilePaths(): List<String>

    @Query("DELETE FROM chat_image_cache WHERE filePath = :filePath")
    suspend fun deleteByPath(filePath: String)

    @Query("DELETE FROM chat_image_cache WHERE status IN ('SAVED','EVICTED')")
    suspend fun pruneTerminalRows()
}
