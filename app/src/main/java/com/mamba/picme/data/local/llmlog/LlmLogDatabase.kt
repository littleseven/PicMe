package com.mamba.picme.data.local.llmlog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 远程 LLM 调用日志 + tool 执行指标**独立数据库**。
 *
 * - 与主库 [com.mamba.picme.data.local.AppDatabase] 完全独立：单独 DB 文件
 *   (polang_llm_log.db)、单独 version / migration，零外键、零共享 schema。
 * - 含 llm_call_log（推理层：LLM 调用）、tool_call_log（行动层：tool 执行指标）、
 *   js_run_log（端侧执行层：JS 沙盒运行事件）三张表——Agent 终端运行感知层三件套。
 * - 全构建写入；release 构建仅落纯指标，不落消息内容（隐私红线）。
 * - 采用 fallbackToDestructiveMigration（诊断数据可丢）。
 */
@Database(
    entities = [LlmCallLogEntity::class, ToolCallLogEntity::class, JsRunLogEntity::class],
    version = 3,
    exportSchema = false
)
abstract class LlmLogDatabase : RoomDatabase() {

    abstract fun llmCallLogDao(): LlmCallLogDao
    abstract fun toolCallLogDao(): ToolCallLogDao
    abstract fun jsRunLogDao(): JsRunLogDao

    companion object {
        @Volatile
        private var INSTANCE: LlmLogDatabase? = null

        fun getDatabase(context: Context): LlmLogDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LlmLogDatabase::class.java,
                    "polang_llm_log.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
