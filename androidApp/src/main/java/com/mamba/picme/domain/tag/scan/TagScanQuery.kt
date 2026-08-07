package com.mamba.picme.domain.tag.scan

import com.mamba.picme.domain.tag.TagCategory

/**
 * TAG 扫描批量查询条件
 *
 * 用于按范围选择需要处理的媒体。
 */
data class TagScanQuery(
    /** 指定媒体 ID 列表 */
    val mediaIds: List<Long>? = null,

    /** 拍摄时间范围起点（含） */
    val startTimeMs: Long? = null,

    /** 拍摄时间范围终点（含） */
    val endTimeMs: Long? = null,

    /** 相册 bucketId */
    val albumBucketId: String? = null,

    /** 是否含人脸 */
    val hasFace: Boolean? = null,

    /** 指定人物簇 ID */
    val personIds: List<Long>? = null,

    /** 只处理缺少任意指定类别的媒体 */
    val missingAnyCategory: Set<TagCategory>? = null
)
