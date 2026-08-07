package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 场景标签实体 —— 规范化的 ML Kit 图像标注标签
 *
 * 标签按 [category] 分类：scene（场景）、animal（动物）、object（物体）、
 * food（食物）、person（人物）、other（其他）。
 */
@Entity(
    tableName = "tags",
    indices = [Index("name", unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    val tagId: Long = 0,
    val name: String,
    val category: String = "scene"
)
