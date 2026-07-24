package com.mamba.picme.data.local.llmlog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 远程 LLM 调用日志**独立数据库**。
 *
 * - 与主库 [com.mamba.picme.data.local.AppDatabase] 完全独立：单独 DB 文件
 *   (polang_llm_log.db)、单独 version / migration，零外键、零共享 schema。
 * - 仅含 llm_call_log 一张表。
 * - 仅 DEBUG 构建使用；采用 fallbackToDestructiveMigration（调试数据可丢）。
 */
@Database(
    entities = [LlmCallLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LlmLogDatabase : RoomDatabase() {

    abstract fun llmCallLogDao(): LlmCallLogDao

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
