package com.mamba.picme.agent.core.inference.remote.tool

/**
 * 聊天/飞书每轮被动注入的"已记住"快照供给者。
 *
 * [snapshot] 必须**非阻塞、线程安全**：langchain4j 的 `systemMessageProvider` 在每轮请求
 * 时同步回调本方法；实现方需用 Flow 预热一份内存缓存，[snapshot] 只读缓存。无内容返回 ""。
 */
interface MemoryContextProvider {
    fun snapshot(): String
}
