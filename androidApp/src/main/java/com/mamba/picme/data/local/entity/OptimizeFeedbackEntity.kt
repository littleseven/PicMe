package com.mamba.picme.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 优化抽卡反馈记录（见 spec §7）。
 *
 * v1 只记录不学习；Phase 2 按 scene 聚合 user pick 相对 base 的参数偏移，
 * 用于收窄采样中心（个性化）。
 */
@Entity(tableName = "optimize_feedback")
data class OptimizeFeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    /** 图片 URI 的 SHA-256 前 16 位（不存原始路径） */
    @ColumnInfo(name = "image_key") val imageKey: String,
    /** Scene 枚举名 */
    @ColumnInfo(name = "scene") val scene: String,
    /** 4 卡参数 + NIMA 分 + 护栏淘汰标记（JSON 数组） */
    @ColumnInfo(name = "candidates_json") val candidatesJson: String,
    /** 选中的卡序号；-1 = KeepOriginal */
    @ColumnInfo(name = "selected_index") val selectedIndex: Int,
    /** auto（NIMA 选优）/ user（换一组手选）/ dismiss（换一组后未选关闭） */
    @ColumnInfo(name = "selection_source") val selectionSource: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
