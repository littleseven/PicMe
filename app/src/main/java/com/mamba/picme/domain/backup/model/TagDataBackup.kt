package com.mamba.picme.domain.backup.model

import com.squareup.moshi.JsonClass

/**
 * TAG 相关数据库的备份容器。
 *
 * 以媒体 [uri] 作为跨安装稳定键（content://media/external/...），
 * 重装后通过 URI 在本地 media_assets 表中重新定位 mediaId，再恢复标签、
 * 关联、扫描任务及 TAG 元数据字段。
 */
@JsonClass(generateAdapter = true)
data class TagDataBackup(
    val version: Int = 1,
    val exportedAt: Long,
    val tags: List<BackupTag>,
    val mediaTagMetadata: List<BackupMediaTagMetadata>,
    val crossRefs: List<BackupMediaTagCrossRef>,
    val scanTasks: List<BackupTagScanTask>
)

@JsonClass(generateAdapter = true)
data class BackupTag(
    val tagId: Long,
    val name: String,
    val category: String = "scene"
)

@JsonClass(generateAdapter = true)
data class BackupMediaTagMetadata(
    val uri: String,
    val labels: String? = null,
    val mlKitLabels: String? = null,
    val mlKitLabelsZh: String? = null,
    val ocrText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val faceRoiResult: String? = null,
    val semanticEmbedding: String? = null,
    val lastTagScanAt: Long? = null,
    val lastTagScanPasses: String? = null,
    val hasFace: Boolean = false,
    val faceId: String? = null
)

@JsonClass(generateAdapter = true)
data class BackupMediaTagCrossRef(
    val uri: String,
    val tagName: String,
    val confidence: Float? = null
)

@JsonClass(generateAdapter = true)
data class BackupTagScanTask(
    val sessionId: String,
    val uri: String,
    val pass: String,
    val tagCategories: String? = null,
    val status: String,
    val priority: Int = 0,
    val attemptCount: Int = 0,
    val createdAt: Long,
    val scheduledAt: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null
)
