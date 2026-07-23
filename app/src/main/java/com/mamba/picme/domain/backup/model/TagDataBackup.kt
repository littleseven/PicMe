package com.mamba.picme.domain.backup.model

import com.squareup.moshi.JsonClass

/**
 * TAG 相关数据库的备份容器。
 *
 * 以媒体 [uri] 作为跨安装稳定键（content://media/external/...），
 * 重装后通过 URI 在本地 media_assets 表中重新定位 mediaId，再恢复标签、
 * 关联、扫描任务及 TAG 元数据字段。
 *
 * 版本演进：
 * - v1：标签、媒体 TAG 元数据、关联、扫描任务
 * - v2：新增人脸 Embedding、人物聚类、OCR 倒排索引、地理位置关系
 * - v3：新增 DataStore 用户偏好（账号、Token、设置等）
 * - v4：新增媒体反馈（media_feedback，用户 like/dislike 训练数据）
 */
@JsonClass(generateAdapter = true)
data class TagDataBackup(
    val version: Int = 4,
    val exportedAt: Long,
    val tags: List<BackupTag>,
    val mediaTagMetadata: List<BackupMediaTagMetadata>,
    val crossRefs: List<BackupMediaTagCrossRef>,
    val scanTasks: List<BackupTagScanTask>,
    val persons: List<BackupPerson> = emptyList(),
    val faceEmbeddings: List<BackupFaceEmbedding> = emptyList(),
    val ocrWords: List<BackupOcrWord> = emptyList(),
    val ocrWordOccurrences: List<BackupOcrWordOccurrence> = emptyList(),
    val locationHierarchy: List<BackupLocationHierarchy> = emptyList(),
    val mediaLocations: List<BackupMediaLocation> = emptyList(),
    val mediaFeedback: List<BackupMediaFeedback> = emptyList(),
    val preferences: BackupPreferences = BackupPreferences()
)

/**
 * DataStore 用户偏好备份。
 *
 * 以 key-value 形式保存，支持 string / boolean / int / long / float / stringSet。
 * 包含账号、Token、模型配置、相机记忆、主题语言等所有用户设置。
 */
@JsonClass(generateAdapter = true)
data class BackupPreferences(
    val entries: List<BackupPreferenceEntry> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BackupPreferenceEntry(
    val key: String,
    val type: String, // STRING, BOOLEAN, INT, LONG, FLOAT, STRING_SET
    val value: String,
    val stringSetValues: List<String> = emptyList()
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

/** 人物聚类结果（Pass 2 产出） */
@JsonClass(generateAdapter = true)
data class BackupPerson(
    val personId: Long,
    val name: String? = null,
    val coverMediaUri: String? = null,
    val faceCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

/** 人脸 Embedding（Pass 1 产出） */
@JsonClass(generateAdapter = true)
data class BackupFaceEmbedding(
    val mediaUri: String,
    val personId: Long? = null,
    val embedding: String, // ByteArray 的 Base64
    val createdAt: Long
)

/** OCR 词条 */
@JsonClass(generateAdapter = true)
data class BackupOcrWord(
    val wordId: Long,
    val word: String,
    val normalizedWord: String
)

/** OCR 词条出现记录 */
@JsonClass(generateAdapter = true)
data class BackupOcrWordOccurrence(
    val normalizedWord: String,
    val mediaUri: String,
    val confidence: Float? = null,
    val boundingBox: String? = null
)

/** 地理层级信息 */
@JsonClass(generateAdapter = true)
data class BackupLocationHierarchy(
    val locationId: Long,
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val poi: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

/** 媒体-地理位置关联 */
@JsonClass(generateAdapter = true)
data class BackupMediaLocation(
    val mediaUri: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null
)

/**
 * 媒体反馈（用户 like/dislike，影响搜索排序）。
 *
 * media_feedback 表的 media_id 存的是 MediaEntity.id（Long）的字符串形式，
 * 故此处以 mediaUri 作跨安装稳定键，restore 时重定位回新 mediaId。
 */
@JsonClass(generateAdapter = true)
data class BackupMediaFeedback(
    val mediaUri: String,
    val feedbackType: String,
    val queryText: String,
    val sessionId: String,
    val createdAt: Long
)
