package com.mamba.picme.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MediaType {
    PHOTO, VIDEO
}

@Entity(tableName = "media_assets")
data class MediaAsset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val type: MediaType,
    val captureDate: Long,
    val fileName: String,
    val duration: Long? = null,
    val hasFace: Boolean = false,
    val faceId: String? = null // 用于简单的人脸聚类分组
)
