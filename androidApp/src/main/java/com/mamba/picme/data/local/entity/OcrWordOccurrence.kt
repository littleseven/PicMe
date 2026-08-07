package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * OCR 词汇出现记录 —— 倒排索引的命中表
 *
 * 记录某个词汇在某个媒体文件中出现的位置（boundingBox）和置信度。
 */
@Entity(
    tableName = "ocr_word_occurrences",
    primaryKeys = ["wordId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = OcrWordEntity::class,
            parentColumns = ["wordId"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = com.mamba.picme.data.model.MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("mediaId")]
)
data class OcrWordOccurrence(
    val wordId: Long,
    val mediaId: Long,
    val confidence: Float? = null,
    val boundingBox: String? = null
)
