package com.mamba.picme.agent.core.inference.remote.log

/**
 * traceId 跨边界持有器。
 *
 * LLM 调用经 langchain4j ChatModelListener 上报，listener 拿不到 AgentContext，
 * 故由调用方（[com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgent]）
 * 在每轮任务开始时写入 [value]，listener 在 onResponse/onError 时读取，落入 [LlmCallRecord.traceId]。
 *
 * 每个 RemoteReActAgent 持有自己的 holder（其 chatModel 与 listener 也是 per-agent 懒建），
 * 且 agent 用单线程 executor 串行执行任务 → 单 agent 内无并发竞态。
 */
class TraceIdHolder {
    @Volatile
    var value: String? = null
}
