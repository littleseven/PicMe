package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 人物关系实体 —— 人物关系图谱的一条边（subject -predicate-> object）
 *
 * 锚定人脸簇：声明"小宝是我的女儿"即 (小宝.personId, CHILD, 我.personId)。
 * 两端人物删除时级联删除（CASCADE）。
 *
 * [predicate] 存储领域层 RelationPredicate 枚举名；[source] 存储 RelationSource 枚举名，
 * 数据层不依赖领域枚举，映射由 PersonRepository 完成。
 *
 * [customLabel] 为用户自由输入的称呼（如"发小""二儿子"），可空：
 * 非空时优先于谓词用于查询解析与展示（两层关系模型：粗谓词 + 自定义称呼）。
 */
@Entity(
    tableName = "person_relations",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["subjectPersonId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["objectPersonId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["subjectPersonId", "predicate", "objectPersonId"], unique = true),
        Index(value = ["subjectPersonId"]),
        Index(value = ["objectPersonId"])
    ]
)
data class PersonRelationEntity(
    @PrimaryKey(autoGenerate = true)
    val relationId: Long = 0,
    val subjectPersonId: Long,
    val objectPersonId: Long,
    val predicate: String,
    val source: String,
    val customLabel: String? = null,
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
