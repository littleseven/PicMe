package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Android 端 [DispatcherProvider] 实现。
 *
 * 逻辑逐行搬自旧 runtime-core `ThreadPoolManager`（线程池类型/数量/线程名完全一致，
 * 仅去掉 `getInstance()` 单例壳——单例语义改由 commonMain 的 [SharedDispatcherProvider] 承载）：
 * - DataStore：单线程（PoLang-DataStore-Thread）
 * - 模型：单线程（PoLang-LLM-Model-Thread）
 * - 网络：单线程（PoLang-Network-Thread）
 * - 编排：双线程（PoLang-Orchestrator-Thread）
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
