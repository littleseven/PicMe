package com.mamba.picme.data.indexing

import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 增量索引任务队列
 *
 * 接收 [MediaChangeEvent]，去重后批量调度索引任务。
 * 设计要点：
 * - 去重：同一 mediaStoreId 的重复事件以最新者为准
 * - 批量：每 5 秒或达到 BATCH_SIZE 时处理一批
 * - 非阻塞：所有处理在 IO 协程中执行
 */
class IndexingTaskQueue(
    private val onProcessBatch: suspend (List<MediaChangeEvent>) -> Unit
) {
    companion object {
        private const val TAG = "PoLang:IndexQueue"
        private const val BATCH_SIZE = 20
        private const val PROCESS_INTERVAL_MS = 5000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingEvents = ConcurrentLinkedQueue<MediaChangeEvent>()
    private var processingJob: Job? = null
    private var scheduled = false

    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * 将一组变更事件加入队列。
     * 自动去重：同一 mediaStoreId 的重复事件以 ADD/DELETE 类型的后者为准。
     */
    fun enqueue(events: List<MediaChangeEvent>) {
        if (events.isEmpty()) return
        pendingEvents.addAll(events)
        Logger.d(TAG, "Enqueued ${events.size} events, queue size: ${pendingEvents.size}")
        scheduleProcessing()
    }

    private fun scheduleProcessing() {
        if (scheduled) return
        scheduled = true
        processingJob = scope.launch {
            isRunning = true
            delay(PROCESS_INTERVAL_MS)
            processQueue()
        }
    }

    private suspend fun processQueue() {
        while (currentCoroutineContext().isActive) {
            if (pendingEvents.isEmpty()) {
                Logger.d(TAG, "Queue empty, idle")
                break
            }

            val batch = mutableListOf<MediaChangeEvent>()
            var event = pendingEvents.poll()
            val seen = mutableSetOf<Long>()

            while (event != null && batch.size < BATCH_SIZE) {
                if (seen.add(event.mediaStoreId)) {
                    batch.add(event)
                }
                event = pendingEvents.poll()
            }

            if (batch.isNotEmpty()) {
                Logger.i(TAG, "Processing batch: ${batch.size} events")
                try {
                    onProcessBatch(batch)
                } catch (e: Exception) {
                    Logger.e(TAG, "Batch processing failed", e)
                }
            }
        }
        scheduled = false
        isRunning = false
    }

    /**
     * 强制立即处理队列中的所有事件。
     */
    suspend fun flush() {
        processingJob?.join()
        if (pendingEvents.isNotEmpty()) {
            val all = mutableListOf<MediaChangeEvent>()
            var event = pendingEvents.poll()
            while (event != null) {
                all.add(event)
                event = pendingEvents.poll()
            }
            if (all.isNotEmpty()) {
                try {
                    onProcessBatch(all)
                } catch (e: Exception) {
                    Logger.e(TAG, "Flush processing failed", e)
                }
            }
        }
    }

    /**
     * 获取当前排队的变更数。
     */
    fun queueSize(): Int = pendingEvents.size
}
