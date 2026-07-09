package com.mamba.picme.domain.backup

import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.data.local.dao.TagScanTaskDao
import com.mamba.picme.data.local.entity.MediaTagCrossRef
import com.mamba.picme.data.local.entity.TagEntity
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.data.local.entity.TagScanTaskEntity
import com.mamba.picme.data.local.entity.TagScanTaskStatus
import com.mamba.picme.domain.backup.model.BackupMediaTagCrossRef
import com.mamba.picme.domain.backup.model.BackupMediaTagMetadata
import com.mamba.picme.domain.backup.model.BackupTag
import com.mamba.picme.domain.backup.model.BackupTagScanTask
import com.mamba.picme.domain.backup.model.TagDataBackup
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * TAG 数据库备份/还原仓库。
 *
 * 设计要点：
 * - 以 URI 为跨安装匹配键，不依赖本地自增 mediaId。
 * - 标签以 name 为唯一键重建，避免 tagId 变化导致关联失效。
 * - 还原时先建立 (uri → mediaId) 与 (tagName → tagId) 映射，再批量写入。
 * - 扫描任务统一重置为 PENDING，由调度器根据 lastTagScanAt 去重。
 */
class TagDataBackupRepository(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao,
    private val tagScanTaskDao: TagScanTaskDao,
    moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) {

    private val backupAdapter = moshi.adapter(TagDataBackup::class.java)

    data class ExportResult(
        val file: File,
        val tagCount: Int,
        val mediaCount: Int,
        val crossRefCount: Int,
        val scanTaskCount: Int
    )

    data class RestoreResult(
        val matchedMediaCount: Int,
        val unmatchedUris: List<String>,
        val restoredTagCount: Int,
        val restoredCrossRefCount: Int,
        val restoredScanTaskCount: Int,
        val restoredMetadataCount: Int
    )

    /**
     * 导出 TAG 相关数据到 JSON 文件。
     *
     * @param file 目标文件，父目录需已存在或可创建。
     */
    suspend fun exportToFile(file: File): ExportResult = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()

        val tags = tagDao.getAllTags().map {
            BackupTag(tagId = it.tagId, name = it.name, category = it.category)
        }

        val allMedia = mediaDao.getAllMediaNow()
        val metadataList = allMedia.mapNotNull { media ->
            if (media.isTagRelatedEmpty()) return@mapNotNull null
            BackupMediaTagMetadata(
                uri = media.uri,
                labels = media.labels,
                mlKitLabels = media.mlKitLabels,
                mlKitLabelsZh = media.mlKitLabelsZh,
                ocrText = media.ocrText,
                latitude = media.latitude,
                longitude = media.longitude,
                locationName = media.locationName,
                faceRoiResult = media.faceRoiResult,
                semanticEmbedding = media.semanticEmbedding,
                lastTagScanAt = media.lastTagScanAt,
                lastTagScanPasses = media.lastTagScanPasses,
                hasFace = media.hasFace,
                faceId = media.faceId
            )
        }

        val tagNameToId = tags.associate { it.tagId to it.name }
        val crossRefs = allMedia.flatMap { media ->
            tagDao.getTagsForMedia(media.id).mapNotNull { tag ->
                val tagName = tagNameToId[tag.tagId] ?: return@mapNotNull null
                BackupMediaTagCrossRef(
                    uri = media.uri,
                    tagName = tagName,
                    confidence = null
                )
            }
        }

        val mediaIdToUri = allMedia.associate { it.id to it.uri }
        val scanTasks = tagScanTaskDao.getAllTasks()
            .map { it.toBackupModel(mediaIdToUri[it.mediaId] ?: "") }

        val backup = TagDataBackup(
            exportedAt = System.currentTimeMillis(),
            tags = tags,
            mediaTagMetadata = metadataList,
            crossRefs = crossRefs,
            scanTasks = scanTasks
        )

        file.writeText(backupAdapter.toJson(backup))

        ExportResult(
            file = file,
            tagCount = tags.size,
            mediaCount = metadataList.size,
            crossRefCount = crossRefs.size,
            scanTaskCount = scanTasks.size
        )
    }

    /**
     * 从 JSON 文件还原 TAG 数据。
     *
     * @param file 备份文件。
     * @param dryRun 为 true 时只计算匹配情况，不实际写入数据库。
     */
    suspend fun importFromFile(
        file: File,
        dryRun: Boolean = false
    ): RestoreResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) {
            throw IOException("Backup file not readable: ${file.absolutePath}")
        }

        val backup = backupAdapter.fromJson(file.readText())
            ?: throw IOException("Failed to parse backup file: ${file.absolutePath}")

        // 1. 建立 URI -> 本地 mediaId 映射
        val uriToMediaId = mutableMapOf<String, Long>()
        val unmatchedUris = mutableListOf<String>()
        val allBackupUris = backup.mediaTagMetadata.map { it.uri }
            .plus(backup.crossRefs.map { it.uri })
            .plus(backup.scanTasks.map { it.uri })
            .distinct()

        for (uri in allBackupUris) {
            val media = mediaDao.getMediaByUri(uri)
            if (media != null) {
                uriToMediaId[uri] = media.id
            } else {
                unmatchedUris.add(uri)
            }
        }

        val matchedMediaCount = uriToMediaId.size

        if (dryRun) {
            return@withContext RestoreResult(
                matchedMediaCount = matchedMediaCount,
                unmatchedUris = unmatchedUris,
                restoredTagCount = 0,
                restoredCrossRefCount = 0,
                restoredScanTaskCount = 0,
                restoredMetadataCount = 0
            )
        }

        // 2. 重建标签并建立 name -> 新 tagId 映射
        val existingTags = tagDao.getAllTags().associateBy { it.name }
        val tagsToInsert = backup.tags
            .map { TagEntity(tagId = 0, name = it.name, category = it.category) }
            .filter { it.name !in existingTags }

        val restoredTagCount: Int
        val nameToTagId: Map<String, Long>
        if (tagsToInsert.isEmpty()) {
            restoredTagCount = 0
            nameToTagId = existingTags.mapValues { it.value.tagId }
        } else {
            val insertedIds = tagDao.insertTags(tagsToInsert)
            restoredTagCount = insertedIds.count { it > 0 }
            nameToTagId = existingTags.mapValues { it.value.tagId } +
                tagsToInsert.zip(insertedIds).associate { it.first.name to it.second }
        }

        // 3. 恢复媒体-标签关联
        val crossRefsToInsert = backup.crossRefs.mapNotNull { ref ->
            val mediaId = uriToMediaId[ref.uri] ?: return@mapNotNull null
            val tagId = nameToTagId[ref.tagName] ?: return@mapNotNull null
            MediaTagCrossRef(mediaId = mediaId, tagId = tagId, confidence = ref.confidence)
        }
        if (crossRefsToInsert.isNotEmpty()) {
            crossRefsToInsert.forEach { tagDao.insertMediaTag(it) }
        }

        // 4. 恢复 TAG 扫描任务（重置为 PENDING，避免 RUNNING 状态悬挂）
        val scanTasksToInsert = backup.scanTasks.mapNotNull { task ->
            val mediaId = uriToMediaId[task.uri] ?: return@mapNotNull null
            TagScanTaskEntity(
                id = 0,
                sessionId = task.sessionId,
                mediaId = mediaId,
                pass = parsePass(task.pass),
                tagCategories = task.tagCategories,
                status = TagScanTaskStatus.PENDING,
                priority = task.priority,
                attemptCount = 0,
                createdAt = task.createdAt,
                scheduledAt = null,
                startedAt = null,
                completedAt = null,
                errorMessage = null
            )
        }
        if (scanTasksToInsert.isNotEmpty()) {
            tagScanTaskDao.insertAll(scanTasksToInsert)
        }

        // 5. 恢复媒体 TAG 元数据字段
        var restoredMetadataCount = 0
        for (meta in backup.mediaTagMetadata) {
            val mediaId = uriToMediaId[meta.uri] ?: continue
            mediaDao.updateTagMetadataFromBackup(
                mediaId = mediaId,
                labels = meta.labels,
                mlKitLabels = meta.mlKitLabels,
                mlKitLabelsZh = meta.mlKitLabelsZh,
                ocrText = meta.ocrText,
                latitude = meta.latitude,
                longitude = meta.longitude,
                locationName = meta.locationName,
                faceRoiResult = meta.faceRoiResult,
                semanticEmbedding = meta.semanticEmbedding,
                lastTagScanAt = meta.lastTagScanAt,
                lastTagScanPasses = meta.lastTagScanPasses,
                hasFace = meta.hasFace,
                faceId = meta.faceId
            )
            restoredMetadataCount++
        }

        RestoreResult(
            matchedMediaCount = matchedMediaCount,
            unmatchedUris = unmatchedUris,
            restoredTagCount = restoredTagCount,
            restoredCrossRefCount = crossRefsToInsert.size,
            restoredScanTaskCount = scanTasksToInsert.size,
            restoredMetadataCount = restoredMetadataCount
        )
    }

    private fun parsePass(name: String): TagScanPass = try {
        TagScanPass.valueOf(name)
    } catch (_: IllegalArgumentException) {
        TagScanPass.QWEN_TAGGING
    }

    private fun TagScanTaskEntity.toBackupModel(uri: String): BackupTagScanTask = BackupTagScanTask(
        sessionId = sessionId,
        uri = uri,
        pass = pass.name,
        tagCategories = tagCategories,
        status = status.name,
        priority = priority,
        attemptCount = attemptCount,
        createdAt = createdAt,
        scheduledAt = scheduledAt,
        startedAt = startedAt,
        completedAt = completedAt,
        errorMessage = errorMessage
    )

    private fun com.mamba.picme.data.model.MediaEntity.isTagRelatedEmpty(): Boolean =
        labels.isNullOrBlank() &&
            mlKitLabels.isNullOrBlank() &&
            mlKitLabelsZh.isNullOrBlank() &&
            ocrText.isNullOrBlank() &&
            locationName.isNullOrBlank() &&
            faceRoiResult.isNullOrBlank() &&
            semanticEmbedding.isNullOrBlank() &&
            lastTagScanAt == null &&
            lastTagScanPasses.isNullOrBlank() &&
            !hasFace &&
            faceId.isNullOrBlank()
}
