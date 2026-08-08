package com.mamba.picme.agent.core.inference.remote.log

/**
 * 远程 LLM 调用记录的接收端。
 *
 * runtime-core 仅定义此接口与 [LlmCallRecord]；具体持久化（Room 独立库）由 :app 提供，
 * 在 Application 启动时注入到
 * [com.mamba.picme.agent.core.remote.config.RemoteModelFactory.recorder]。
 *
 * 实现要求：
 * - 非阻塞：内部自行切到后台线程，不得阻塞 LLM 主调用；
 * - 不抛异常：吞掉自身异常，绝不把失败冒泡到 LLM 调用链。
 */
fun interface LlmCallRecorder {
    fun record(record: LlmCallRecord)
}
