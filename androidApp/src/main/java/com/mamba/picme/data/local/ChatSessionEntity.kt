package com.mamba.picme.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity：聊天会话元数据
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val sessionId: String,

    /**
     * 用户可编辑的会话标题
     */
    val title: String,

    /**
     * 创建时间
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * 最后更新时间（用于排序）
     */
    val updatedAt: Long = System.currentTimeMillis()
)
