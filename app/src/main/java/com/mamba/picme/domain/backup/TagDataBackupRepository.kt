package com.mamba.picme.domain.backup

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.data.local.dao.TagScanTaskDao
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.LocationHierarchyEntity
import com.mamba.picme.data.local.entity.MediaLocationEntity
import com.mamba.picme.data.local.entity.MediaTagCrossRef
import com.mamba.picme.data.local.entity.OcrWordEntity
import com.mamba.picme.data.local.entity.OcrWordOccurrence
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.TagEntity
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.data.local.entity.TagScanTaskEntity
import com.mamba.picme.data.local.entity.TagScanTaskStatus
import com.mamba.picme.domain.backup.model.BackupFaceEmbedding
import com.mamba.picme.domain.backup.model.BackupLocationHierarchy
import com.mamba.picme.domain.backup.model.BackupMediaLocation
import com.mamba.picme.domain.backup.model.BackupMediaTagCrossRef
import com.mamba.picme.domain.backup.model.BackupMediaTagMetadata
import com.mamba.picme.domain.backup.model.BackupOcrWord
import com.mamba.picme.domain.backup.model.BackupOcrWordOccurrence
import com.mamba.picme.domain.backup.model.BackupPerson
import com.mamba.picme.domain.backup.model.BackupPreferenceEntry
import com.mamba.picme.domain.backup.model.BackupPreferences
import com.mamba.picme.domain.backup.model.BackupTag
import com.mamba.picme.domain.backup.model.BackupTagScanTask
import com.mamba.picme.domain.backup.model.TagDataBackup
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException

/**
 * TAG 数据库备份/还原仓库。
 *
 * 设计要点：
 * - 以 URI 为跨安装匹配键，不依赖本地自增 mediaId。
 * - 标签以 name 为唯一键重建，避免 tagId 变化导致关联失效。
 * - 人脸 Embedding / 人物聚类 / OCR 倒排索引 / 地理位置关系一并备份，
 *   避免重装后重新执行最耗时的 Pass 1~2 与 OCR。
 * - 还原时先建立 (uri → mediaId)、(oldPersonId → newPersonId)、
 *   (normalizedWord → newWordId)、(lat/lon → newLocationId) 映射，再批量写入。
 * - 扫描任务统一重置为 PENDING，由调度器根据 lastTagScanAt 去重。
 */
class TagDataBackupRepository(
    private val database: AppDatabase,
    private val mediaDao: MediaDao,
    private val tagDao: TagDao,
    private val tagScanTaskDao: TagScanTaskDao,
    private val personDao: PersonDao,
    private val ocrWordDao: OcrWordDao,
    private val locationDao: LocationDao,
    private val dataStore: DataStore<Preferences>,
    moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) {

    private val backupAdapter = moshi.adapter(TagDataBackup::class.java)

    data class ExportResult(
        val file: File,
        val tagCount: Int,
        val mediaCount: Int,
        val crossRefCount: Int,
        val scanTaskCount: Int,
        val personCount: Int,
        val faceEmbeddingCount: Int,
        val ocrWordCount: Int,
        val ocrWordOccurrenceCount: Int,
        val locationCount: Int,
        val mediaLocationCount: Int,
        val preferenceCount: Int
    )

    data class RestoreResult(
        val matchedMediaCount: Int,
        val unmatchedUris: List<String>,
        val restoredTagCount: Int,
        val restoredCrossRefCount: Int,
        val restoredScanTaskCount: Int,
        val restoredMetadataCount: Int,
        val restoredPersonCount: Int,
        val restoredFaceEmbeddingCount: Int,
        val restoredOcrWordCount: Int,
        val restoredOcrWordOccurrenceCount: Int,
        val restoredLocationCount: Int,
        val restoredMediaLocationCount: Int,
        val restoredPreferenceCount: Int
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
        val mediaIdToUri = allMedia.associate { it.id to it.uri }

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

        val scanTasks = tagScanTaskDao.getAllTasks()
            .map { it.toBackupModel(mediaIdToUri[it.mediaId] ?: "") }

        val persons = personDao.getAllPersons().map {
            BackupPerson(
                personId = it.personId,
                name = it.name,
                coverMediaUri = it.coverMediaId?.let { id -> mediaIdToUri[id] },
                faceCount = it.faceCount,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt
            )
        }

        val faceEmbeddings = personDao.getAllEmbeddings().map {
            BackupFaceEmbedding(
                mediaUri = mediaIdToUri[it.mediaId] ?: "",
                personId = it.personId,
                embedding = Base64.encodeToString(it.embedding, Base64.NO_WRAP),
                createdAt = it.createdAt
            )
        }

        val ocrWords = ocrWordDao.getAllWords().map {
            BackupOcrWord(
                wordId = it.wordId,
                word = it.word,
                normalizedWord = it.normalizedWord
            )
        }
        val wordIdToNormalized = ocrWords.associate { it.wordId to it.normalizedWord }
        val ocrOccurrences = ocrWordDao.getAllOccurrences().mapNotNull {
            val normalized = wordIdToNormalized[it.wordId] ?: return@mapNotNull null
            BackupOcrWordOccurrence(
                normalizedWord = normalized,
                mediaUri = mediaIdToUri[it.mediaId] ?: "",
                confidence = it.confidence,
                boundingBox = it.boundingBox
            )
        }

        val locations = locationDao.getAllLocations().map {
            BackupLocationHierarchy(
                locationId = it.locationId,
                country = it.country,
                province = it.province,
                city = it.city,
                district = it.district,
                poi = it.poi,
                latitude = it.latitude,
                longitude = it.longitude
            )
        }
        val locationIdToCoordinate = locations.associate {
            it.locationId to coordinateKey(it.latitude, it.longitude)
        }
        val mediaLocations = locationDao.getAllMediaLocations().mapNotNull {
            val coordinate = locationIdToCoordinate[it.locationId] ?: return@mapNotNull null
            BackupMediaLocation(
                mediaUri = mediaIdToUri[it.mediaId] ?: "",
                latitude = coordinateLatitude(coordinate),
                longitude = coordinateLongitude(coordinate),
                accuracy = it.accuracy
            )
        }

        val preferences = exportPreferences()

        val backup = TagDataBackup(
            exportedAt = System.currentTimeMillis(),
            tags = tags,
            mediaTagMetadata = metadataList,
            crossRefs = crossRefs,
            scanTasks = scanTasks,
            persons = persons,
            faceEmbeddings = faceEmbeddings,
            ocrWords = ocrWords,
            ocrWordOccurrences = ocrOccurrences,
            locationHierarchy = locations,
            mediaLocations = mediaLocations,
            preferences = preferences
        )

        // 使用流式 JSON 写入，避免大备份序列化时产生超大字符串导致 OOM
        file.outputStream().sink().buffer().use { sink ->
            backupAdapter.toJson(sink, backup)
        }

        ExportResult(
            file = file,
            tagCount = tags.size,
            mediaCount = metadataList.size,
            crossRefCount = crossRefs.size,
            scanTaskCount = scanTasks.size,
            personCount = persons.size,
            faceEmbeddingCount = faceEmbeddings.size,
            ocrWordCount = ocrWords.size,
            ocrWordOccurrenceCount = ocrOccurrences.size,
            locationCount = locations.size,
            mediaLocationCount = mediaLocations.size,
            preferenceCount = preferences.entries.size
        )
    }

    /**
     * 导出 DataStore 用户偏好为可序列化结构。
     */
    private suspend fun exportPreferences(): BackupPreferences {
        val prefs = dataStore.data.first()
        val entries = prefs.asMap().map { (key, value) ->
            when (value) {
                is String -> BackupPreferenceEntry(key.name, "STRING", value)
                is Boolean -> BackupPreferenceEntry(key.name, "BOOLEAN", value.toString())
                is Int -> BackupPreferenceEntry(key.name, "INT", value.toString())
                is Long -> BackupPreferenceEntry(key.name, "LONG", value.toString())
                is Float -> BackupPreferenceEntry(key.name, "FLOAT", value.toString())
                is Double -> BackupPreferenceEntry(key.name, "DOUBLE", value.toString())
                is Set<*> -> BackupPreferenceEntry(
                    key.name,
                    "STRING_SET",
                    "",
                    stringSetValues = value.filterIsInstance<String>()
                )
                else -> BackupPreferenceEntry(key.name, "STRING", value.toString())
            }
        }
        return BackupPreferences(entries)
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

        // 使用流式 JSON 读取，避免一次性把整个备份文件加载到内存
        val backup = file.source().buffer().use { source ->
            backupAdapter.fromJson(source)
        } ?: throw IOException("Failed to parse backup file: ${file.absolutePath}")

        // 1. 建立 URI -> 本地 mediaId 映射（一次性加载全部媒体，避免 N 次单条查询）
        val currentMedia = mediaDao.getAllMediaNow()
        val uriToMediaId = currentMedia.associate { it.uri to it.id }

        val allBackupUris = buildSet {
            backup.mediaTagMetadata.mapTo(this) { it.uri }
            backup.crossRefs.mapTo(this) { it.uri }
            backup.scanTasks.mapTo(this) { it.uri }
            backup.faceEmbeddings.mapTo(this) { it.mediaUri }
            backup.ocrWordOccurrences.mapTo(this) { it.mediaUri }
            backup.mediaLocations.mapTo(this) { it.mediaUri }
        }

        val unmatchedUris = allBackupUris.filter { it !in uriToMediaId }
        val matchedMediaCount = allBackupUris.size - unmatchedUris.size

        if (dryRun) {
            return@withContext RestoreResult(
                matchedMediaCount = matchedMediaCount,
                unmatchedUris = unmatchedUris,
                restoredTagCount = 0,
                restoredCrossRefCount = 0,
                restoredScanTaskCount = 0,
                restoredMetadataCount = 0,
                restoredPersonCount = 0,
                restoredFaceEmbeddingCount = 0,
                restoredOcrWordCount = 0,
                restoredOcrWordOccurrenceCount = 0,
                restoredLocationCount = 0,
                restoredMediaLocationCount = 0,
                restoredPreferenceCount = 0
            )
        }

        // 2~9 在单个事务中执行，大幅提升写入速度并保证原子性
        var result: RestoreResult? = null
        database.beginTransaction()
        try {
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
            scanTasksToInsert.chunked(1000).forEach { chunk ->
                tagScanTaskDao.insertAll(chunk)
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

            // 6. 恢复人物聚类（Pass 2 产出）
            val oldToNewPersonId = mutableMapOf<Long, Long>()
            var restoredPersonCount = 0
            for (person in backup.persons) {
                val coverMediaId = person.coverMediaUri?.let { uriToMediaId[it] }
                val newId = personDao.insertPerson(
                    PersonEntity(
                        personId = 0,
                        name = person.name,
                        coverMediaId = coverMediaId,
                        faceCount = person.faceCount,
                        createdAt = person.createdAt,
                        updatedAt = person.updatedAt
                    )
                )
                if (newId > 0) {
                    oldToNewPersonId[person.personId] = newId
                    restoredPersonCount++
                }
            }

            // 7. 恢复人脸 Embedding（Pass 1 产出）
            val embeddingsToInsert = backup.faceEmbeddings.mapNotNull { emb ->
                val mediaId = uriToMediaId[emb.mediaUri] ?: return@mapNotNull null
                val newPersonId = emb.personId?.let { oldToNewPersonId[it] }
                FaceEmbeddingEntity(
                    embeddingId = 0,
                    mediaId = mediaId,
                    personId = newPersonId,
                    embedding = Base64.decode(emb.embedding, Base64.NO_WRAP),
                    createdAt = emb.createdAt
                )
            }
            if (embeddingsToInsert.isNotEmpty()) {
                personDao.insertEmbeddings(embeddingsToInsert)
            }
            val restoredFaceEmbeddingCount = embeddingsToInsert.size

            // 8. 恢复 OCR 倒排索引
            val normalizedToNewWordId = mutableMapOf<String, Long>()
            var restoredOcrWordCount = 0
            for (word in backup.ocrWords) {
                val existing = ocrWordDao.getWordByNormalized(word.normalizedWord)
                val wordId = if (existing != null) {
                    existing.wordId
                } else {
                    val inserted = ocrWordDao.insertWord(
                        OcrWordEntity(wordId = 0, word = word.word, normalizedWord = word.normalizedWord)
                    )
                    if (inserted > 0) inserted else null
                }
                if (wordId != null) {
                    normalizedToNewWordId[word.normalizedWord] = wordId
                    if (existing == null) restoredOcrWordCount++
                }
            }

            val occurrencesToInsert = backup.ocrWordOccurrences.mapNotNull { occ ->
                val mediaId = uriToMediaId[occ.mediaUri] ?: return@mapNotNull null
                val wordId = normalizedToNewWordId[occ.normalizedWord] ?: return@mapNotNull null
                OcrWordOccurrence(
                    wordId = wordId,
                    mediaId = mediaId,
                    confidence = occ.confidence,
                    boundingBox = occ.boundingBox
                )
            }
            if (occurrencesToInsert.isNotEmpty()) {
                ocrWordDao.insertOccurrences(occurrencesToInsert)
            }
            val restoredOcrWordOccurrenceCount = occurrencesToInsert.size

            // 9. 恢复地理位置关系
            val coordinateToNewLocationId = mutableMapOf<String, Long>()
            var restoredLocationCount = 0
            for (location in backup.locationHierarchy) {
                val key = coordinateKey(location.latitude, location.longitude)
                if (key.isBlank()) continue
                val existing = locationDao.findByCoordinate(
                    location.latitude ?: 0.0,
                    location.longitude ?: 0.0
                )
                val locationId = if (existing != null) {
                    existing.locationId
                } else {
                    val inserted = locationDao.insertLocation(
                        LocationHierarchyEntity(
                            locationId = 0,
                            country = location.country,
                            province = location.province,
                            city = location.city,
                            district = location.district,
                            poi = location.poi,
                            latitude = location.latitude,
                            longitude = location.longitude
                        )
                    )
                    if (inserted > 0) inserted else null
                }
                if (locationId != null) {
                    coordinateToNewLocationId[key] = locationId
                    if (existing == null) restoredLocationCount++
                }
            }

            val mediaLocationsToInsert = backup.mediaLocations.mapNotNull { ml ->
                val mediaId = uriToMediaId[ml.mediaUri] ?: return@mapNotNull null
                val key = coordinateKey(ml.latitude, ml.longitude)
                val locationId = coordinateToNewLocationId[key] ?: return@mapNotNull null
                MediaLocationEntity(
                    mediaId = mediaId,
                    locationId = locationId,
                    accuracy = ml.accuracy
                )
            }
            if (mediaLocationsToInsert.isNotEmpty()) {
                mediaLocationsToInsert.chunked(500).forEach { chunk ->
                    locationDao.insertMediaLocations(chunk)
                }
            }
            val restoredMediaLocationCount = mediaLocationsToInsert.size

            result = RestoreResult(
                matchedMediaCount = matchedMediaCount,
                unmatchedUris = unmatchedUris,
                restoredTagCount = restoredTagCount,
                restoredCrossRefCount = crossRefsToInsert.size,
                restoredScanTaskCount = scanTasksToInsert.size,
                restoredMetadataCount = restoredMetadataCount,
                restoredPersonCount = restoredPersonCount,
                restoredFaceEmbeddingCount = restoredFaceEmbeddingCount,
                restoredOcrWordCount = restoredOcrWordCount,
                restoredOcrWordOccurrenceCount = restoredOcrWordOccurrenceCount,
                restoredLocationCount = restoredLocationCount,
                restoredMediaLocationCount = restoredMediaLocationCount,
                restoredPreferenceCount = 0 // 在 SQLite 事务外恢复
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        result ?: throw IOException("Restore transaction failed to produce result")

        // 10. 恢复 DataStore 用户偏好（在 SQLite 事务外执行）
        val restoredPreferenceCount = importPreferences(backup.preferences)

        result.copy(restoredPreferenceCount = restoredPreferenceCount)
    }

    /**
     * 恢复 DataStore 用户偏好。
     *
     * @return 实际恢复的键数量。
     */
    private suspend fun importPreferences(preferences: BackupPreferences): Int {
        if (preferences.entries.isEmpty()) return 0
        dataStore.edit { prefs ->
            preferences.entries.forEach { entry ->
                try {
                    when (entry.type) {
                        "STRING" -> prefs[stringPreferencesKey(entry.key)] = entry.value
                        "BOOLEAN" -> prefs[booleanPreferencesKey(entry.key)] = entry.value.toBooleanStrict()
                        "INT" -> prefs[intPreferencesKey(entry.key)] = entry.value.toInt()
                        "LONG" -> prefs[longPreferencesKey(entry.key)] = entry.value.toLong()
                        "FLOAT" -> prefs[floatPreferencesKey(entry.key)] = entry.value.toFloat()
                        "DOUBLE" -> prefs[doublePreferencesKey(entry.key)] = entry.value.toDouble()
                        "STRING_SET" -> prefs[stringSetPreferencesKey(entry.key)] = entry.stringSetValues.toSet()
                    }
                } catch (_: Exception) {
                    // 跳过解析失败的条目，避免单个坏值导致整个恢复失败
                }
            }
        }
        return preferences.entries.size
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

    private fun coordinateKey(lat: Double?, lon: Double?): String {
        return if (lat != null && lon != null) {
            "${"%.4f".format(lat)},${"%.4f".format(lon)}"
        } else {
            ""
        }
    }

    private fun coordinateLatitude(key: String): Double? =
        key.takeIf { it.isNotBlank() }?.substringBefore(",")?.toDoubleOrNull()

    private fun coordinateLongitude(key: String): Double? =
        key.takeIf { it.isNotBlank() }?.substringAfter(",")?.toDoubleOrNull()

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
