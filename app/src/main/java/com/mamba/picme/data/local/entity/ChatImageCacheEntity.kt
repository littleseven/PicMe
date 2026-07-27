package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** chat 编辑/优化结果图的私有缓存登记行。文件存于 filesDir/chat_edit_cache/。 */
@Entity(
    tableName = "chat_image_cache",
    indices = [Index("lastAccessedAt"), Index("sessionId")]
)
data class ChatImageCacheEntity(
    /** filesDir/chat_edit_cache/ 下绝对路径，唯一。 */
    @PrimaryKey val filePath: String,
    val sessionId: String,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val sizeBytes: Long,
    /** ACTIVE | SAVED | EVICTED，见 [com.mamba.picme.domain.repository.ChatImageStore.Status]。 */
    val status: String
)
