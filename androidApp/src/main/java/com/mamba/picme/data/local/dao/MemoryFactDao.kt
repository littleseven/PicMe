package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mamba.picme.data.local.entity.MemoryFactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryFactDao {

    @Insert
    suspend fun insert(fact: MemoryFactEntity): Long

    @Query("UPDATE memory_facts SET content = :content, category = :category, updatedAt = :now WHERE factId = :factId")
    suspend fun update(factId: Long, content: String, category: String?, now: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM memory_facts WHERE factId = :factId")
    suspend fun deleteById(factId: Long): Int

    @Query("DELETE FROM memory_facts")
    suspend fun clearAll()

    /** 按内容 LIKE 模糊检索（v1 召回方式，无 FTS） */
    @Query("SELECT * FROM memory_facts WHERE content LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun findByContentLike(query: String): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE factId = :factId")
    suspend fun getById(factId: Long): MemoryFactEntity?

    @Query("SELECT * FROM memory_facts ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryFactEntity>

    /** 管理界面列表驱动源 */
    @Query("SELECT * FROM memory_facts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryFactEntity>>
}
