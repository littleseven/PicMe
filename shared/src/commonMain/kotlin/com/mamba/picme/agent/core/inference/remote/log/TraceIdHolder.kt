package com.mamba.picme.agent.core.inference.remote.log

import kotlin.concurrent.Volatile

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

/**
 * 接受当轮 traceId 注入的工具集（Koog agent init 期把自身 holder 注入，
 * 使 Koog 链路下的 tool 执行也带 traceId，与 LLM 调用日志关联）。
 *
 * 自 KoogReActAgent/KoogChatAgent 的 `is ChatToolService/CameraToolService` 类型判断收敛而来——
 * KMP 抽取后 agent 在 commonMain，不再反向依赖具体工具集类型，改按本接口匹配
 * （RemoteControlToolService 等不实现本接口的工具集自然跳过，语义对齐旧 when 分支）。
 */
interface TraceIdAware {
    var traceIdHolder: TraceIdHolder?
}
