package com.mamba.picme.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Kotlin Flow → Swift 的订阅句柄。Swift 侧持有并在离开时 `cancel()`；
 * 避免 Kotlin `Job` 直接暴露到 ObjC/Swift（FlowWatcher 导出为普通类，方法可直调）。
 */
class FlowWatcher(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}

/**
 * 在默认调度器上收集 Flow，逐元素回调 Swift 闭包。
 * 泛型 T 经 K/N 导出擦除为 Any?，Swift 侧按实际元素类型强转。
 *
 * signal 6 纪律（kmp-ios-interop 铁律 1）：collect/onEach 任何异常都不跨边界逃逸——
 * Swift 闭包内抛错或上游 Flow 异常若穿出协程会 SIGABRT 崩溃且无法诊断，这里兜底吞掉并结束流。
 * CancellationException 属正常取消语义，继续上抛。
 */
fun <T> Flow<T>.watch(onEach: (T) -> Unit): FlowWatcher {
    val job = CoroutineScope(Dispatchers.Default).launch {
        try {
            collect { onEach(it) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // 吞掉并结束流（Swift 侧暂无错误通道；待 SharedBridge 错误事件通道落地后上报）
        }
    }
    return FlowWatcher(job)
}
