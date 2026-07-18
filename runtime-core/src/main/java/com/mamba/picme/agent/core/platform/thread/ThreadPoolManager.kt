package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 集中化线程池管理器
 *
 * **设计目标**：将 PoLang 应用中所有专有线程池（DataStore、LLM 推理、网络、编排）
 * 集中到一处管理，避免各模块各自创建和管理线程池带来的生命周期不一致问题。
 *
 * **线程模型**（沿用现有四线程池架构）：
 * - **DataStore 线程**（PoLang-DataStore-Thread）：单线程，串行化所有 DataStore 读写
 * - **LLM 推理线程**（PoLang-LLM-Model-Thread）：单线程，串行化所有模型操作
 * - **网络线程**（PoLang-Network-Thread）：单线程，隔离同步 HTTP 调用
 * - **编排线程**（PoLang-Orchestrator-Thread）：双线程，处理用户输入编排生命周期
 *
 * 四者完全隔离，无直接依赖关系。数据持久化为 fire-and-forget 异步操作。
 *
 * **使用方式**：
 * ```kotlin
 * val dispatcher = ThreadPoolManager.getInstance().dataStoreDispatcher
 * withContext(dispatcher) { ... }
 * ```
 *
 * **生命周期**：
 * - 通过 [getInstance] 获取全局单例，各模块按需引用对应的 [CoroutineDispatcher]
 * - 应用退出时调用 [shutdown] 释放所有线程资源
 */
class ThreadPoolManager private constructor() {

    companion object {
        @Volatile
        private var instance: ThreadPoolManager? = null

        fun getInstance(): ThreadPoolManager {
            return instance ?: synchronized(this) {
                instance ?: ThreadPoolManager().also { instance = it }
            }
        }
    }

    // ── DataStore 线程池 ────────────────────────────────────────

    private val dataStoreExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PoLang-DataStore-Thread").apply { isDaemon = true }
    }

    /**
     * DataStore 专用单线程调度器。
     * 所有 DataStore 读写操作在此线程上串行执行，
     * 与 LLM 推理线程和网络线程完全隔离。
     */
    val dataStoreDispatcher: CoroutineDispatcher = dataStoreExecutor.asCoroutineDispatcher()

    // ── LLM 推理线程池 ──────────────────────────────────────────

    private val modelExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PoLang-LLM-Model-Thread").apply { isDaemon = true }
    }

    /**
     * LLM 模型专用单线程调度器。
     * 所有模型操作（load/unload/trimMemory/generate）在此线程上串行执行，
     * 避免多线程竞争和 Compose 协程重组取消导致 MNN 全局状态冲突。
     */
    val modelDispatcher: CoroutineDispatcher = modelExecutor.asCoroutineDispatcher()

    // ── 网络线程池 ──────────────────────────────────────────────

    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PoLang-Network-Thread").apply { isDaemon = true }
    }

    /**
     * 网络专用单线程调度器。
     * 所有同步 HTTP 调用在此线程上执行，与编排线程和 DataStore 线程完全隔离。
     * 即使网络请求超时或挂起，也不会阻塞编排逻辑或数据库操作。
     */
    val networkDispatcher: CoroutineDispatcher = networkExecutor.asCoroutineDispatcher()

    // ── 编排线程池 ──────────────────────────────────────────────

    private val orchestratorExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "PoLang-Orchestrator-Thread").apply { isDaemon = true }
    }

    /**
     * 编排专用双线程调度器。
     * 负责 LLM 推理调用、响应解析、命令分发。不参与 IO 操作。
     */
    val orchestratorDispatcher: CoroutineDispatcher = orchestratorExecutor.asCoroutineDispatcher()

    /**
     * 关闭所有线程池，释放资源。
     *
     * 应在应用退出时调用（如 [android.app.Application.onTerminate]）。
     * 调用后所有 [CoroutineDispatcher] 将不再接受新任务。
     */
    fun shutdown() {
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
