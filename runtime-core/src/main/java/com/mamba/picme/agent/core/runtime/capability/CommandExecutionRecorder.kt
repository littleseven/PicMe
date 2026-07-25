package com.mamba.picme.agent.core.runtime.capability

/**
 * tool（Capability 命令）执行指标的接收端。
 *
 * runtime-core 仅定义此接口；具体持久化（Room 独立库 tool_call_log 表）由 :app 提供，
 * 在 Application 启动时注入到 [CommandExecutor.recorder]。
 *
 * 只承载纯指标（capability / commandType / latency / success / errorCode / errorMessage），
 * **不含任何命令参数或业务内容**（隐私红线）。
 *
 * 实现要求：
 * - 非阻塞：内部自行切到后台线程，不得阻塞命令执行主链路；
 * - 不抛异常：吞掉自身异常，绝不把失败冒泡到命令执行链路。
 */
fun interface CommandExecutionRecorder {

    /**
     * @param capability 执行命令的 Capability 名称（如 "gallery"）
     * @param commandType 命令 method 名（如 "search_media"，见 AgentCommand.getMethodName）
     * @param latencyMs 执行耗时（含 capability 内部耗时，不含 recorder 自身）
     * @param success 是否成功（capability 返回 Result.success 视为成功）
     * @param errorCode 失败时的结构化错误码（TIMEOUT=-32002 / EXECUTION_FAILED=-32005）；成功为 null
     * @param errorMessage 失败时的异常信息；成功为 null
     */
    fun record(
        capability: String,
        commandType: String,
        latencyMs: Long,
        success: Boolean,
        errorCode: Int?,
        errorMessage: String?
    )
}
