package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO：聊天会话元数据
 */
@Dao
interface ChatSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :updatedAt WHERE sessionId = :sessionId")
    suspend fun updateTitle(
        sessionId: String,
        title: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * 更新会话的 updatedAt，不修改标题
     */
    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE sessionId = :sessionId")
    suspend fun touchSession(
        sessionId: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT * FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    /** 快照导出：全量会话（备份用） */
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessionsNow(): List<ChatSessionEntity>
}
