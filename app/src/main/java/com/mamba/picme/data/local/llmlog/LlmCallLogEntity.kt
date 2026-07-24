package com.mamba.picme.data.local.llmlog

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 远程 LLM 调用日志实体（独立数据库 polang_llm_log，与主库 AppDatabase 零耦合）。
 *
 * 仅 DEBUG 构建写入；用于排查远程 LLM 请求/响应问题。保留最近 200 条。
 * 所有列均为普通列，无外键、无与其它表的关联。
 */
@Entity(tableName = "llm_call_log")
data class LlmCallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val source: String,
    val model: String?,
    val success: Boolean,
    val latencyMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val requestJson: String,
    val responseJson: String?,
    val errorMessage: String?
)
