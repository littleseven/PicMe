package com.mamba.picme.data.local.llmlog

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * tool（Capability 命令）执行指标实体（独立数据库 polang_llm_log，与主库 AppDatabase 零耦合）。
 *
 * 全构建写入；只含纯指标（capability / commandType / latency / success / errorCode /
 * errorMessage），**不含命令参数与业务内容**（隐私红线）。保留最近 200 条。
 * 所有列均为普通列，无外键、无与其它表的关联。
 */
@Entity(tableName = "tool_call_log")
data class ToolCallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val capability: String,
    val commandType: String,
    val latencyMs: Long,
    val success: Boolean,
    val errorCode: Int?,
    val errorMessage: String?,
    /** 关联 ID：一条用户消息一个 traceId；非 chat 来源/老数据为 null（详情页按无关联处理）。 */
    val traceId: String? = null
)
