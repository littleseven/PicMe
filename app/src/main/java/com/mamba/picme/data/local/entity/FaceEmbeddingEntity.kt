package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 人脸 Embedding 实体 —— 存储 Glint360K R100 提取的 512 维特征向量
 *
 * 支持增量聚类：新的人脸 embedding 可与已有 person 质心做余弦距离匹配。
 * 每 N 个增量触发一次全量 DBSCAN 重聚以保证聚类质量。
 */
@Entity(
    tableName = "face_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("personId")]
)
data class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    val embeddingId: Long = 0,
    val mediaId: Long,
    val personId: Long? = null,
    val embedding: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
)
