package com.mamba.picme.data.local.llmlog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * 远程 LLM 调用日志 DAO。
 */
@Dao
interface LlmCallLogDao {

    @Insert
    suspend fun insert(entity: LlmCallLogEntity): Long

    /** 最近 [limit] 条（新→旧）。 */
    @Query("SELECT * FROM llm_call_log ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LlmCallLogEntity>

    @Query("SELECT COUNT(*) FROM llm_call_log")
    suspend fun count(): Int

    /** 仅保留最新的 [keep] 条，删除其余；返回删除行数。 */
    @Query(
        "DELETE FROM llm_call_log WHERE id NOT IN " +
            "(SELECT id FROM llm_call_log ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun prune(keep: Int): Int

    @Query("DELETE FROM llm_call_log")
    suspend fun clearAll(): Int

    @Query("DELETE FROM llm_call_log WHERE id = :id")
    suspend fun delete(id: Long): Int

    /** 同一 traceId 的全部记录（旧→新），供详情页 turn pager 装配。 */
    @Query("SELECT * FROM llm_call_log WHERE traceId = :traceId ORDER BY createdAt ASC")
    suspend fun getByTraceId(traceId: String): List<LlmCallLogEntity>
}
