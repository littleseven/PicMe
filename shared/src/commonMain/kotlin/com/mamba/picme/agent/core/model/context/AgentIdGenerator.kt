package com.mamba.picme.agent.core.model.context

/**
 * Agent 会话/命令 ID 生成器（进程内单调递增，从 1 开始）。
 *
 * 替代 runtime-core `AgentModels.kt` 内的 JVM `AtomicInteger` 实现（KMP 化后 commonMain
 * 不可引用 java.util.concurrent）。ID 0 保留给系统/无效状态。
 *
 * 注：旧实现到达 `Int.MAX_VALUE - 1` 后回绕到 1；新实现按路线图约定简化为纯递增
 * （进程内 2^31 次调用才会溢出，实际不可达）。
 */
expect object AgentIdGenerator {
    fun nextId(): Int
}
