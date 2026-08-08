package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JVM 端 [DispatcherProvider] 实现。
 *
 * 与 androidMain 完全相同（同为 JVM 平台，供 `:shared:jvmTest` 与桌面/服务端场景使用）：
 * 线程池类型/数量/线程名逐行对齐旧 runtime-core `ThreadPoolManager`。
 */
actual class DispatcherProvider actual constructor() {

    // ── DataStore 线程池 ────────────────────────────────────────

    private val dataStoreExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PoLang-DataStore-Thread").apply { isDaemon = true }
    }

    actual val dataStoreDispatcher: CoroutineDispatcher = dataStoreExecutor.asCoroutineDispatcher()

    // ── 模型线程池 ──────────────────────────────────────────────

    private val modelExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PoLang-LLM-Model-Thread").apply { isDaemon = true }
    }

    actual val modelDispatcher: CoroutineDispatcher = modelExecutor.asCoroutineDispatcher()

    // ── 网络线程池 ──────────────────────────────────────────────

    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PoLang-Network-Thread").apply { isDaemon = true }
    }

    actual val networkDispatcher: CoroutineDispatcher = networkExecutor.asCoroutineDispatcher()

    // ── 编排线程池 ──────────────────────────────────────────────

    private val orchestratorExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "PoLang-Orchestrator-Thread").apply { isDaemon = true }
    }

    actual val orchestratorDispatcher: CoroutineDispatcher = orchestratorExecutor.asCoroutineDispatcher()

    actual fun shutdown() {
        listOf(dataStoreExecutor, modelExecutor, networkExecutor, orchestratorExecutor).forEach { executor ->
            executor.shutdown()
        }
        listOf(dataStoreExecutor, modelExecutor, networkExecutor, orchestratorExecutor).forEach { executor ->
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                executor.shutdownNow()
            }
        }
    }
}
