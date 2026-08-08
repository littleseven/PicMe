package com.mamba.picme.domain.repository

import com.mamba.picme.agent.core.model.context.MediaAsset
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    val allMedia: Flow<List<MediaAsset>>

    /** 相册访问授权状态（双端统一抽象；Android 实现按 READ_MEDIA_* 权限映射，AddOnly 仅 iOS） */
    val accessState: Flow<AccessState>

    suspend fun insertMedia(mediaAsset: MediaAsset): Long

    suspend fun deleteMedia(mediaAsset: MediaAsset)

    suspend fun deleteMediaByIds(ids: List<Long>)

    suspend fun getMediaById(id: Long): MediaAsset?

    suspend fun refreshMediaLibrary()

    /** 轻量刷新:bump refreshVersion 触发 allMedia 重 emit(不重载 MediaStore)。单张 retag 后用。 */
    fun refreshLabels()

    /**
     * 获取需要用户授权删除的 URI 字面值列表（Android 11+）
     */
    fun getPendingDeleteUris(): List<String>

    /**
     * 清除待删除的 URI 列表
     */
    fun clearPendingDeleteUris()

    /**
     * 在用户授权后执行删除操作
     */
    suspend fun executePendingDeletes()
}
