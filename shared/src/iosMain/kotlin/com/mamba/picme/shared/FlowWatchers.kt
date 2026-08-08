package com.mamba.picme.shared

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
 */
fun <T> Flow<T>.watch(onEach: (T) -> Unit): FlowWatcher {
    val job = CoroutineScope(Dispatchers.Default).launch {
        collect { onEach(it) }
    }
    return FlowWatcher(job)
}
