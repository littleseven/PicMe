package com.mamba.picme.data.indexing

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MediaStore 内容监听器
 *
 * 通过 ContentObserver 监听系统媒体库变化（新增/删除/更新），
 * 2 秒去抖后批量通知 [IndexingTaskQueue] 进行增量索引。
 *
 * 生命周期：注册于 Gallery 页面进入时，取消注册于离开时。
 * 线程模型：onChange 回调在主线程，实际处理通过协程切换到 IO。
 */
class MediaStoreObserver(
    private val contentResolver: ContentResolver,
    private val onChange: (List<MediaChangeEvent>) -> Unit
) {
    companion object {
        private const val TAG = "PoLang:MediaObs"
        private const val DEBOUNCE_MS = 2000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var debounceJob: Job? = null
    private val pendingUris = mutableSetOf<Uri>()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri != null) {
                pendingUris.add(uri)
            }
            scheduleDebounce()
        }
    }

    private fun scheduleDebounce() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            flushChanges()
        }
    }

    private fun flushChanges() {
        if (pendingUris.isEmpty()) return
        val uris = pendingUris.toList()
        pendingUris.clear()

        scope.launch(Dispatchers.IO) {
            val events = uris.mapNotNull { uri -> resolveChange(uri) }
            if (events.isNotEmpty()) {
                Logger.d(TAG, "MediaStore changes detected: ${events.size} events")
                onChange(events)
            }
        }
    }

    /**
     * 根据 URI 判断变更类型。
     *
     * 策略：取 URI 的 lastPathSegment 作为 MediaStore ID，
     * 尝试查询是否存在来判断是 ADD 还是 DELETE。
     */
    private fun resolveChange(uri: Uri): MediaChangeEvent? {
        val idStr = uri.lastPathSegment ?: return null
        val mediaStoreId = idStr.toLongOrNull() ?: return null

        val exists = try {
            val cursor = contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns._ID),
                null, null, null
            )
            cursor?.use { it.count > 0 } ?: false
        } catch (e: SecurityException) {
            Logger.w(TAG, "Permission denied querying: $uri")
            false
        }

        val changeType = if (exists) {
            ChangeType.ADDED_OR_UPDATED
        } else {
            ChangeType.DELETED
        }

        return MediaChangeEvent(
            type = changeType,
            mediaStoreUri = uri,
            mediaStoreId = mediaStoreId
        )
    }

    /**
     * 注册 ContentObserver 到指定的 MediaStore URI。
     */
    fun startObserving() {
        Logger.i(TAG, "Starting MediaStore observation")
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
    }

    /**
     * 取消注册 ContentObserver。
     */
    fun stopObserving() {
        Logger.i(TAG, "Stopping MediaStore observation")
        debounceJob?.cancel()
        contentResolver.unregisterContentObserver(observer)
    }
}

enum class ChangeType {
    ADDED_OR_UPDATED,
    DELETED
}

data class MediaChangeEvent(
    val type: ChangeType,
    val mediaStoreUri: Uri,
    val mediaStoreId: Long
)
