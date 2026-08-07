package com.mamba.picme.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 数据库实体：聊天消息
 *
 * 对应表：chat_messages
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,

    /**
     * 会话 ID，当前仅支持单会话（default），后续可扩展多会话
     */
    val sessionId: String = "default",

    /**
     * 消息类型：user_text, agent_text, user_image, agent_image, command, plan_preview
     */
    val type: String,

    /**
     * 文本内容或图片路径（图片消息存储本地文件路径）
     */
    val content: String,

    /**
     * 消息时间戳
     */
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * 生成该消息的模型标识：local_qwen3.5_2b / remote_deepseek 等
     */
    val modelUsed: String? = null,

    /**
     * 扩展 JSON 字段，用于存储额外元数据
     */
    val metadata: String? = null
)
