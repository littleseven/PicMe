package com.mamba.picme.data.local.llmlog

import android.content.Context
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecord
import com.mamba.picme.agent.core.runtime.capability.CommandExecutionRecorder
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Room 实现的 [CommandExecutionRecorder]：把每次 tool（Capability 命令）执行指标
 * 落库到独立库 polang_llm_log 的 tool_call_log 表。
 *
 * - 在后台 IO 协程写入，**绝不阻塞命令执行主链路**；
 * - 写入后做日级 guard 清理（仅保留最近 [KEEP] 条）——"每天检查一次即可"；
 * - errorMessage 截断到 [ERROR_MESSAGE_MAX_CHARS] 字符，防止超长堆栈撑爆本地库；
 * - 任何异常吞掉只打日志，绝不冒泡到命令执行链路。
 *
 * 由 :app 在 Application 启动时（全构建）注入到
 * [com.mamba.picme.agent.core.runtime.capability.CommandExecutor.recorder]。
 */
class RoomToolCallRecorder(
    context: Context
) : CommandExecutionRecorder {

    private val dao = LlmLogDatabase.getDatabase(context).toolCallLogDao()
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun record(
        capability: String,
        commandType: String,
        latencyMs: Long,
        success: Boolean,
        errorCode: Int?,
        errorMessage: String?
    ) {
        scope.launch {
            try {
                dao.insert(
                    ToolCallLogEntity(
                        createdAt = System.currentTimeMillis(),
                        capability = capability,
                        commandType = commandType,
                        latencyMs = latencyMs,
                        success = success,
                        errorCode = errorCode,
                        errorMessage = LlmCallRecord.cap(errorMessage, ERROR_MESSAGE_MAX_CHARS)
                    )
                )
                pruneIfNeeded()
            } catch (e: Exception) {
                Logger.w(TAG, "record failed", e)
            }
        }
    }

    /** 当天首次写入时清理一次，仅保留最近 [KEEP] 条。 */
    private suspend fun pruneIfNeeded() {
        val today = dayFormat.format(Date())
        if (prefs.getString(KEY_LAST_PRUNE_DAY, null) == today) return
        try {
            dao.prune(KEEP)
            prefs.edit().putString(KEY_LAST_PRUNE_DAY, today).apply()
        } catch (e: Exception) {
            Logger.w(TAG, "prune failed", e)
        }
    }

    companion object {
        private const val TAG = "PoLang:ToolCallLog"
        private const val PREFS_NAME = "polang_llm_log_prefs"

        /** 与 RoomLlmCallRecorder 区分，避免共享同一按天 prune 标记互相跳过。 */
        private const val KEY_LAST_PRUNE_DAY = "last_prune_day_tool"
        private const val KEEP = 200
        private const val ERROR_MESSAGE_MAX_CHARS = 500
    }
}
