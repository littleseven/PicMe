package com.mamba.picme.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dedup_hash")
data class DedupHashEntity(
    @PrimaryKey val uri: String,
    val sizeBytes: Long,
    val mime: String,
    val modifiedAt: Long,
    val md5: String?,
    val phash: Long?,
    val pixelArea: Int,
    val hashedAt: Long,
)
