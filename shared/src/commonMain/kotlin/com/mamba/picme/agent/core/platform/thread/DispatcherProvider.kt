package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 平台命名 dispatcher 提供者。
 *
 * 语义对齐旧 `ThreadPoolManager`（runtime-core，已随 KMP 抽取删除）的 4 个隔离线程池：
 * - **DataStore 线程**：单线程，串行化所有 DataStore 读写
 * - **模型线程**：单线程，串行化所有模型操作（load/unload/trimMemory/generate）
 * - **网络线程**：单线程，隔离同步 HTTP 调用
 * - **编排线程**：双线程，处理用户输入编排生命周期
 *
 * 四者完全隔离，无直接依赖关系。数据持久化为 fire-and-forget 异步操作。
 *
 * **生命周期**：进程内共享实例见 [SharedDispatcherProvider]（对齐旧 `getInstance()` 单例语义）；
 * 测试可独立构造。[shutdown] 当前生产路径无调用方——daemon 线程随进程退出自动终止，
 * 保留供测试清理与未来按需调用。
 */
expect class DispatcherProvider() {
    /** DataStore 专用单线程调度器：所有 DataStore 读写在此线程上串行执行。 */
    val dataStoreDispatcher: CoroutineDispatcher

    /** 模型专用单线程调度器：所有模型操作串行执行，避免多线程竞争导致推理引擎全局状态冲突。 */
    val modelDispatcher: CoroutineDispatcher

    /** 网络专用单线程调度器：同步 HTTP 调用隔离执行，不阻塞编排或 DataStore。 */
    val networkDispatcher: CoroutineDispatcher

    /** 编排专用双线程调度器：负责推理调用、响应解析、命令分发，不参与 IO 操作。 */
    val orchestratorDispatcher: CoroutineDispatcher

    /**
     * 关闭所有线程池，释放资源。调用后所有 dispatcher 不再接受新任务。
     *
     * **生命周期说明**：当前生产路径无调用方（旧 `ThreadPoolManager` 同样无人调用）——
     * android/jvm actual 的线程均为 daemon，随进程退出自动终止。保留本方法供测试清理
     * 与未来按需调用（如组合根收口后的显式生命周期管理）。
     */
    fun shutdown()
}

/**
 * 进程级共享 [DispatcherProvider]。
 *
 * 对齐旧 `ThreadPoolManager.getInstance()` 的单例语义：所有调用点共享同一组线程池，
 * 避免各自建池导致线程数膨胀与生命周期不一致。注入式改造（组合根收口）由后续
 * 组合根任务统一处理，此前调用点经此 holder 取实例。
 */
object SharedDispatcherProvider {
    val instance: DispatcherProvider by lazy { DispatcherProvider() }
}
