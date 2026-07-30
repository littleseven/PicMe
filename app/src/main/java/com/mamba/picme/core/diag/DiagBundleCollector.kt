package com.mamba.picme.core.diag

import com.mamba.picme.core.common.Logger

/**
 * 收集纯文本诊断包并脱敏。version/gitSha/deviceInfo 由调用方注入
 *（BuildConfig 在 JVM 单测不可用，故不在此直接读）。
 *
 * 日志来自既有 [Logger.logs] 内存环形缓冲（最多 500 条，最新在前）。
 */
object DiagBundleCollector {
    private const val MAX_LOG_LINES = 1000

    fun collect(
        appVersion: String,
        gitSha: String,
        deviceModel: String,
        androidVersion: String,
        crashTrace: String? = null,
    ): DiagBundle {
        val logs = Logger.logs.value
            .take(MAX_LOG_LINES)
            .joinToString("\n") { e -> "${e.timestamp} ${e.level} PoLang:${e.tag}: ${e.message}" }
        return DiagBundle(
            logs = DiagSanitizer.sanitize(logs),
            crashTrace = crashTrace?.let { DiagSanitizer.sanitize(it) },
            appVersion = appVersion,
            gitSha = gitSha,
            deviceModel = deviceModel,
            androidVersion = androidVersion,
        )
    }
}
