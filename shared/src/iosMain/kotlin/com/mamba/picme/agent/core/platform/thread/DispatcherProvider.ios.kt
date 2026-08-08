package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * iOS 端 [DispatcherProvider] 实现（最小可用）。
 *
 * 四个 dispatcher 统一落到 `Dispatchers.Default`：Kotlin/Native 无 `java.util.concurrent`，
 * 命名隔离线程池需经 `DispatchQueue`/NSThread 自建，隔离度细化留待 iOS 落地阶段按需处理。
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
