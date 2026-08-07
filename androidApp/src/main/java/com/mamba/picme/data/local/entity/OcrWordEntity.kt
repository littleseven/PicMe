package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * OCR 词汇实体 —— 倒排索引的词条表
 *
 * 存储 OCR 提取并分词后的独立词条，[normalizedWord] 用于大小写/全半角归一化匹配。
 */
@Entity(
    tableName = "ocr_words",
    indices = [Index("normalizedWord")]
)
data class OcrWordEntity(
    @PrimaryKey(autoGenerate = true)
    val wordId: Long = 0,
    val word: String,
    val normalizedWord: String
)
