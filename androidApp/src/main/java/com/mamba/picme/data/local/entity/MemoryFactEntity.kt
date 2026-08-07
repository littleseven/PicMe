package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 通用事实记忆实体 —— 用户通过"帮我记住…"显式声明的事实
 *
 * [source] 存储领域层 MemorySource 枚举名（CHAT_TOOL / JS_DISPATCH），
 * 数据层不依赖领域枚举，映射由 MemoryRepository 完成。
 */
@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true)
    val factId: Long = 0,
    val content: String,
    val category: String? = null,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
