package com.mamba.picme.core.diag

import android.content.Context
import java.io.File

/**
 * 崩溃栈落盘（A3）：全局 UncaughtExceptionHandler 把未处理异常栈写入
 * `filesDir/diag/last_crash.txt`；下次诊断上报时随包携带（补上主设计 §6.1 的 crashTrace），
 * 上报成功后删除。目录/文件操作全部 best-effort，绝不影响主流程与既有 handler。
 */
object CrashTraceStore {
    private const val DIR_NAME = "diag"
    private const val FILE_NAME = "last_crash.txt"

    /** 崩溃栈上报长度上限（诊断包是纯文本，控制体量）。 */
    private const val MAX_TRACE_LEN = 8000

    private fun file(dir: File): File = File(File(dir, DIR_NAME), FILE_NAME)

    /** 安装全局 handler（链式调用既有 handler，不吞异常）。在 Application.onCreate 尽早调用。 */
    fun install(context: Context) {
        val dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { save(dir, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 落盘崩溃栈（覆盖写；只保留最近一次）。 */
    fun save(dir: File, throwable: Throwable) {
        val f = file(dir)
        f.parentFile?.mkdirs()
        f.writeText(throwable.stackTraceToString())
    }

    /** 读取崩溃栈（无文件/读失败 → null；截断 ≤ [MAX_TRACE_LEN]）。 */
    fun read(dir: File): String? = runCatching {
        file(dir).takeIf { it.exists() }?.readText()?.take(MAX_TRACE_LEN)
    }.getOrNull()

    /** 删除落盘文件（上报成功后调用）。 */
    fun delete(dir: File) {
        runCatching { file(dir).delete() }
    }
}
