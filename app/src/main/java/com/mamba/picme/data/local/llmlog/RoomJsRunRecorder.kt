@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.data.local.llmlog

import android.content.Context
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecord
import com.mamba.picme.agent.core.js.JsRunEvent
import com.mamba.picme.agent.core.js.JsRunRecorder
import com.mamba.picme.core.common.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Room 实现的 [JsRunRecorder]：把每次 JS 沙盒运行事件落库到独立库 polang_llm_log 的 js_run_log 表
 * （Agent 终端运行感知层·端侧执行层）。
 *
 * - 在后台 IO 协程写入，**绝不阻塞 JS 执行链路**；
 * - 写入后做日级 guard 清理（仅保留最近 [KEEP] 条）——"每天检查一次即可"；
 * - 任何异常吞掉只打日志，绝不冒泡到执行链路。
 *
 * 由 :app 在 Application 启动时（全构建）注入到
 * [com.mamba.picme.agent.core.js.JsRuntime.recorder]。
 */
class RoomJsRunRecorder(
    context: Context
) : JsRunRecorder {

    private val dao = LlmLogDatabase.getDatabase(context).jsRunLogDao()
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun record(event: JsRunEvent) {
        scope.launch {
            try {
                dao.insert(
                    JsRunLogEntity(
                        createdAt = event.createdAt,
                        source = event.source,
                        kind = event.kind,
                        script = event.script,
                        scriptLength = event.scriptLength,
                        success = event.success,
                        errorCode = event.errorCode,
                        errorMessage = LlmCallRecord.cap(event.errorMessage, JsRunEvent.ERROR_MAX_CHARS),
                        resultPreview = event.resultPreview,
                        latencyMs = event.latencyMs,
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
        private const val TAG = "PoLang:JsRunLog"
        private const val PREFS_NAME = "polang_llm_log_prefs"

        /** 与 LLM/tool recorder 区分，避免共享同一按天 prune 标记互相跳过。 */
        private const val KEY_LAST_PRUNE_DAY = "last_prune_day_js_run"
        private const val KEEP = 200
    }
}
