package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * iOS 端 [DispatcherProvider] 实现（最小可用）。
 *
 * 四个 dispatcher 统一落到 `Dispatchers.Default`：Kotlin/Native 无 `java.util.concurrent`，
 * 命名隔离线程池需经 `DispatchQueue`/NSThread 自建，隔离度细化留待 iOS 落地阶段按需处理。
 *
 * ⚠️ **串行语义缺失**：`modelDispatcher` 在此为共享池，**不保证串行**——android/jvm 端
 * 「模型操作单线程串行」的并发保护在 iOS 不存在。iOS 引擎落地时须自建单线程 dispatcher
 * 或用 Mutex 保护，不可直接依赖 dispatcher 隐式串行化。
 */
actual class DispatcherProvider actual constructor() {
    actual val dataStoreDispatcher: CoroutineDispatcher = Dispatchers.Default
    actual val modelDispatcher: CoroutineDispatcher = Dispatchers.Default
    actual val networkDispatcher: CoroutineDispatcher = Dispatchers.Default
    actual val orchestratorDispatcher: CoroutineDispatcher = Dispatchers.Default

    actual fun shutdown() {
        // Dispatchers.Default 为全局共享调度器，无需关闭
    }
}
