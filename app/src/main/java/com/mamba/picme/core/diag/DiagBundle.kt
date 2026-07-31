package com.mamba.picme.core.diag

import org.json.JSONObject

/** 脱敏后的纯文本诊断包（与 server 端 DiagRoute.DiagBundle 契约一致）。 */
data class DiagBundle(
    val logs: String,
    val crashTrace: String?,
    val appVersion: String,
    val gitSha: String,
    val deviceModel: String,
    val androidVersion: String,
) {
    fun toJsonObject(): JSONObject {
        val o = JSONObject()
            .put("logs", logs)
            .put("appVersion", appVersion)
            .put("gitSha", gitSha)
            .put("deviceModel", deviceModel)
            .put("androidVersion", androidVersion)
        if (crashTrace != null) o.put("crashTrace", crashTrace)
        return o
    }
}

/** server /diag/jobs/{id} 回传的任务状态（手机端展示用）。未知新状态按非终态处理（继续轮询/超时兜底，不 crash）。 */
data class DiagJobStatus(
    val jobId: Int,
    val status: String,
    val rootCause: String?,
    val fixBranch: String?,
    val compareUrl: String?,
    val tested: Boolean,
    val error: String? = null,    // S2：失败原因（workerLog 尾部 ~500 字符）
    val updatedAt: Long = 0L,     // S2：服务端最后更新时间（ms）
)
