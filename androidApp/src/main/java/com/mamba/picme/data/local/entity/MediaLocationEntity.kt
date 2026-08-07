package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 媒体-位置关联表
 *
 * 将 [MediaEntity] 与 [LocationHierarchyEntity] 关联，
 * 记录拍摄位置的精度（来自 GPS EXIF）。
 */
@Entity(
    tableName = "media_locations",
    primaryKeys = ["mediaId", "locationId"],
    foreignKeys = [
        ForeignKey(
            entity = com.mamba.picme.data.model.MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LocationHierarchyEntity::class,
            parentColumns = ["locationId"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("locationId")]
)
data class MediaLocationEntity(
    val mediaId: Long,
    val locationId: Long,
    val accuracy: Float? = null
)
