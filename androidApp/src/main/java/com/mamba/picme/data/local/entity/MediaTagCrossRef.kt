package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 媒体-标签关联表（M:N）
 *
 * 将 [MediaEntity] 与 [TagEntity] 建立多对多关联，
 * 同时记录 ML Kit 给出的置信度。
 */
@Entity(
    tableName = "media_tag_cross_ref",
    primaryKeys = ["mediaId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = com.mamba.picme.data.model.MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["tagId"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId")]
)
data class MediaTagCrossRef(
    val mediaId: Long,
    val tagId: Long,
    val confidence: Float? = null
)
