package com.mamba.picme.agent.core.js

/**
 * 一次 JS 沙盒运行的结构化事件（Agent 终端运行感知层）。
 *
 * Agent First §2.4：事件即数据，可被 AI 消费。与 `llm_call_log`（推理层）、
 * `tool_call_log`（行动层）并列，本事件覆盖**端侧执行层**（JS 沙盒），
 * 三表按时间对齐即可还原一次请求的完整端侧链路。
 *
 * 纯数据类，不依赖 Android / Room / 任何具体 JS 引擎：由 [JsRuntime] 产出，
 * 经 [JsRunRecorder] 上报，:app 侧 RoomJsRunRecorder 持久化到 polang_llm_log.db 的 js_run_log 表。
 *
 * @param source 运行来源标签（chat / debug_page），由 [JsRuntime] 构造注入。
 * @param kind 执行入口：eval / evalAsync / callFunction。
 * @param script 脚本文本（captureContent=true 时记录，cap [SCRIPT_MAX_CHARS]；release 为 null）。
 * @param scriptLength 脚本长度（release 下唯一的内容指标）。
 * @param errorCode 失败分类：[JsBridgeException.errorCode]（SCRIPT_ERROR/SCRIPT_TIMEOUT/HANDLER_*）或 UNKNOWN；成功为 null。
 * @param errorMessage 失败详情（含 JS 栈，cap [ERROR_MAX_CHARS]）；成功为 null。
 * @param resultPreview 结果 JSON 预览（captureContent=true 且成功时记录，cap [RESULT_MAX_CHARS]）。
 */
data class JsRunEvent(
    val createdAt: Long,
    val source: String,
    val kind: String,
    val script: String?,
    val scriptLength: Int,
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val resultPreview: String?,
    val latencyMs: Long,
) {
    companion object {
        const val KIND_EVAL = "eval"
        const val KIND_EVAL_ASYNC = "evalAsync"
        const val KIND_CALL_FUNCTION = "callFunction"

        /** 非 [JsBridgeException] 的异常统一归类。 */
        const val ERROR_UNKNOWN = "UNKNOWN"

        const val SCRIPT_MAX_CHARS = 4000
        const val ERROR_MAX_CHARS = 500
        const val RESULT_MAX_CHARS = 1000
    }
}
