package com.mamba.picme.data.local.llmlog

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * JS 沙盒运行事件实体（独立数据库 polang_llm_log，与主库 AppDatabase 零耦合）。
 *
 * Agent 终端运行感知层的**端侧执行层**事件（与 llm_call_log 推理层、tool_call_log 行动层并列）。
 * 全构建写入；release 构建 script/resultPreview 为 null（仅落指标，隐私红线），
 * DEBUG 构建额外记录脚本文本与结果预览。保留最近 200 条。
 * 所有列均为普通列，无外键、无与其它表的关联。
 */
@Entity(tableName = "js_run_log")
data class JsRunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** 运行来源：chat / debug_page */
    val source: String,
    /** 执行入口：eval / evalAsync / callFunction */
    val kind: String,
    /** 脚本文本（仅 DEBUG，cap 4000；release 为 null） */
    val script: String?,
    val scriptLength: Int,
    val success: Boolean,
    /** SCRIPT_ERROR / SCRIPT_TIMEOUT / HANDLER_* / UNKNOWN；成功为 null */
    val errorCode: String?,
    /** 失败详情（含 JS 栈，cap 500） */
    val errorMessage: String?,
    /** 结果 JSON 预览（仅 DEBUG，cap 1000） */
    val resultPreview: String?,
    val latencyMs: Long,
)
