package com.mamba.picme.features.common.avatar

import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "AvatarCapture"

/**
 * 头像拍摄落库后的封面设置。
 *
 * 取舍说明（V1）：相机拍照链路 `ImageProcessor.takePhoto` 的 `onPhotoFinished` 回调不携带
 * 新照片的 mediaId/uri（且 `MediaViewModel.insertMedia` 为异步 fire-and-forget），因此这里采用
 * 「按快门时间戳轮询最新一张媒体」的兜底策略：以按下快门前记录的 [captureStartMs] 为下界，
 * 轮询 `findLatestCapturedMediaId` 直到 Room 中出现新行。极端情况（拍照后立刻又有一张其他
 * 来源照片入库）可能错认，概率极低且后果只是封面指错，可接受；后续若拍照回调透出 mediaId
 * 应改走精确链路。
 *
 * 全部外部依赖以 suspend lambda 注入，纯 JVM 可单测。
 */
class AvatarCaptureFinisher(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 查询 captureDate >= notBeforeMs 的最新媒体 id；查不到返回 null */
    private val findLatestCapturedMediaId: suspend (notBeforeMs: Long) -> Long?,
    /** 当前「我」标记的 personId；未标记返回 null */
    private val getSelfPersonId: suspend () -> Long?,
    /** 复用 PersonRepository.updateCover 链路 */
    private val updateCover: suspend (personId: Long, mediaId: Long) -> Unit,
    private val pollAttempts: Int = DEFAULT_POLL_ATTEMPTS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val delayMs: suspend (Long) -> Unit = { ms -> delay(ms) }
) {

    /**
     * 把 [captureStartMs] 之后新入库的照片设为 [target] 的封面。
     *
     * @return true=封面已设置；false=失败（拍照失败 / 新照片未入库 / 目标是 Self 但未标记「我」）
     */
    suspend fun finish(
        target: AvatarCaptureTarget,
        success: Boolean,
        captureStartMs: Long
    ): Boolean = withContext(ioDispatcher) {
        if (!success) {
            Logger.w(TAG, "Avatar capture failed at shutter, skip cover update")
            return@withContext false
        }

        val mediaId = pollLatestMediaId(captureStartMs)
        if (mediaId == null) {
            Logger.w(TAG, "Avatar capture photo not found in Room after polling, skip cover update")
            return@withContext false
        }

        val personId = when (target) {
            is AvatarCaptureTarget.Person -> target.personId
            AvatarCaptureTarget.Self -> getSelfPersonId()
        }
        if (personId == null) {
            Logger.w(TAG, "Avatar target is Self but no person marked as self, skip cover update")
            return@withContext false
        }

        runCatching { updateCover(personId, mediaId) }
            .onSuccess {
                Logger.i(TAG, "Avatar cover updated: personId=$personId, mediaId=$mediaId")
            }
            .onFailure { error ->
                Logger.e(TAG, "Failed to update avatar cover: personId=$personId, mediaId=$mediaId", error)
            }
            .isSuccess
    }

    private suspend fun pollLatestMediaId(captureStartMs: Long): Long? {
        repeat(pollAttempts) { attempt ->
            val mediaId = findLatestCapturedMediaId(captureStartMs)
            if (mediaId != null) {
                return mediaId
            }
            if (attempt < pollAttempts - 1) {
                delayMs(pollIntervalMs)
            }
        }
        return null
    }

    companion object {
        /** insertMedia 为异步 Room 写入，轮询覆盖其典型完成窗口 */
        private const val DEFAULT_POLL_ATTEMPTS = 10
        private const val DEFAULT_POLL_INTERVAL_MS = 200L
    }
}
