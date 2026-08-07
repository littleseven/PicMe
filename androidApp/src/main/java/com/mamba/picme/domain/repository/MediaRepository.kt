package com.mamba.picme.domain.repository

import android.net.Uri
import com.mamba.picme.agent.core.model.context.MediaAsset
import kotlinx.coroutines.flow.Flow
import android.content.IntentSender

interface MediaRepository {
    val allMedia: Flow<List<MediaAsset>>

    suspend fun insertMedia(mediaAsset: MediaAsset): Long

    suspend fun deleteMedia(mediaAsset: MediaAsset)

    suspend fun deleteMediaByIds(ids: List<Long>)

    suspend fun getMediaById(id: Long): MediaAsset?

    suspend fun refreshMediaLibrary()

    /** 轻量刷新:bump refreshVersion 触发 allMedia 重 emit(不重载 MediaStore)。单张 retag 后用。 */
    fun refreshLabels()

    /**
     * 获取需要用户授权删除的 URI 列表（Android 11+）
     */
    fun getPendingDeleteUris(): List<Uri>

    /**
     * 清除待删除的 URI 列表
     */
    fun clearPendingDeleteUris()

    /**
     * 获取 Android 10 (API 29) 的单条恢复性删除 IntentSender
     */
    fun getPendingRecoverableIntentSender(): IntentSender?

    /**
     * 清除 Android 10 的恢复性删除状态
     */
    fun clearPendingRecoverable()

    /**
     * 在用户授权后执行删除操作
     */
    suspend fun executePendingDeletes()
}
