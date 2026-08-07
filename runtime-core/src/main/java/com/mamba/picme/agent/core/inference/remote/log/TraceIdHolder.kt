package com.mamba.picme.agent.core.inference.remote.log

/**
 * traceId 跨边界持有器。
 *
 * Koog 链路的 LLM 调用经 EventHandler（onLLMCallCompleted）上报，handler 拿不到 AgentContext，
 * 故由调用方（[com.mamba.picme.agent.core.inference.remote.koog.KoogChatAgent] /
 * [com.mamba.picme.agent.core.inference.remote.koog.KoogReActAgent]）
 * 在每轮任务开始时写入 [value]，handler 录制时读取，落入 [LlmCallRecord.traceId]。
 *
 * 每个 Koog agent 持有自己的 holder，且 agent 串行执行任务 → 单 agent 内无并发竞态。
 */
class TraceIdHolder {
    @Volatile
    var value: String? = null
}
