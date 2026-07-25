package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Transaction
    @Query("SELECT * FROM media_assets ORDER BY captureDate DESC")
    fun getAllMedia(): Flow<List<MediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(mediaEntity: MediaEntity): Long

    @Delete
    suspend fun deleteMedia(mediaEntity: MediaEntity)

    @Query("DELETE FROM media_assets WHERE id IN (:ids)")
    suspend fun deleteMediaByIds(ids: List<Long>)

    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun getMediaById(id: Long): MediaEntity?

    @Query("SELECT * FROM media_assets WHERE id IN (:ids)")
    suspend fun getMediaByIds(ids: List<Long>): List<MediaEntity>

    // ── 搜索查询 ─────────────────────────────────────────────

    /** 按标签搜索（labels JSON 数组模糊匹配） */
    @Query("SELECT * FROM media_assets WHERE labels LIKE '%' || :label || '%' ORDER BY captureDate DESC")
    suspend fun searchByLabel(label: String): List<MediaEntity>

    /** 按 OCR 文本搜索 */
    @Query("SELECT * FROM media_assets WHERE ocrText LIKE '%' || :query || '%' ORDER BY captureDate DESC")
    suspend fun searchByOcrText(query: String): List<MediaEntity>

    /** 按地名搜索 */
    @Query("SELECT * FROM media_assets WHERE locationName LIKE '%' || :place || '%' ORDER BY captureDate DESC")
    suspend fun searchByLocation(place: String): List<MediaEntity>

    /** 按文件名搜索 */
    @Query("SELECT * FROM media_assets WHERE fileName LIKE '%' || :name || '%' ORDER BY captureDate DESC")
    suspend fun searchByFileName(name: String): List<MediaEntity>

    /** 按时间范围搜索 */
    @Query("SELECT * FROM media_assets WHERE captureDate BETWEEN :startMs AND :endMs ORDER BY captureDate DESC")
    suspend fun searchByTimeRange(startMs: Long, endMs: Long): List<MediaEntity>

    /** 综合搜索：标签 + OCR + 地名 + 文件名 */
    @Query(
        """
        SELECT * FROM media_assets WHERE
            labels LIKE '%' || :query || '%' OR
            ocrText LIKE '%' || :query || '%' OR
            locationName LIKE '%' || :query || '%' OR
            fileName LIKE '%' || :query || '%'
        ORDER BY captureDate DESC
        """
    )
    suspend fun searchAll(query: String): List<MediaEntity>

    /** 获取未索引的媒体（indexed_at IS NULL） */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getUnindexedMediaIds()")
    @Query("SELECT * FROM media_assets WHERE indexedAt IS NULL ORDER BY captureDate DESC")
    suspend fun getUnindexedMedia(): List<MediaEntity>

    /** 仅获取未索引的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE indexedAt IS NULL ORDER BY captureDate DESC")
    suspend fun getUnindexedMediaIds(): List<Long>

    /** 按 URI 查找媒体（避免加载全表） */
    @Query("SELECT * FROM media_assets WHERE uri = :uri LIMIT 1")
    suspend fun getMediaByUri(uri: String): MediaEntity?

    /** 更新索引结果 */
    @Query(
        """
        UPDATE media_assets SET
            labels = :labels,
            ocrText = :ocrText,
            latitude = :latitude,
            longitude = :longitude,
            locationName = :locationName,
            indexedAt = :indexedAt
        WHERE id = :mediaId
        """
    )
    suspend fun updateIndexResult(
        mediaId: Long,
        labels: String?,
        ocrText: String?,
        latitude: Double?,
        longitude: Double?,
        locationName: String?,
        indexedAt: Long
    )

    /** 获取已索引媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE indexedAt IS NOT NULL AND indexedAt > 0")
    suspend fun getIndexedCount(): Int

    /** 获取媒体总数 */
    @Query("SELECT COUNT(*) FROM media_assets")
    suspend fun getTotalCount(): Int

    /** 获取照片数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE type = 'PHOTO'")
    suspend fun getPhotoCount(): Int

    /** 获取视频数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE type = 'VIDEO'")
    suspend fun getVideoCount(): Int

    // ── 人脸聚类查询 ──────────────────────────────────────────

    /** 获取所有媒体（非 Flow，用于后台） */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getAllMediaIds() 或分页查询")
    @Query("SELECT * FROM media_assets ORDER BY captureDate DESC")
    suspend fun getAllMediaNow(): List<MediaEntity>

    /** 仅获取所有媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets ORDER BY captureDate DESC")
    suspend fun getAllMediaIds(): List<Long>

    /** 所有非空 labels（JSON 数组字符串），供 gallery.tags 聚合标签分布。轻量：只取 labels 列。 */
    @Query("SELECT labels FROM media_assets WHERE labels IS NOT NULL AND labels != ''")
    suspend fun getAllLabels(): List<String>

    /** 更新 hasFace */
    @Query("UPDATE media_assets SET hasFace = :hasFace WHERE id = :mediaId")
    suspend fun updateHasFace(mediaId: Long, hasFace: Boolean)

    /** 获取有脸但未聚类的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaWithFacesIds()")
    @Query("SELECT * FROM media_assets WHERE hasFace = 1 AND (faceId IS NULL OR faceId = '') ORDER BY captureDate DESC")
    suspend fun getMediaWithFaces(): List<MediaEntity>

    /** 仅获取有脸但未聚类的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE hasFace = 1 AND (faceId IS NULL OR faceId = '') ORDER BY captureDate DESC")
    suspend fun getMediaWithFacesIds(): List<Long>

    /** 更新 faceId */
    @Query("UPDATE media_assets SET faceId = :faceId WHERE id = :mediaId")
    suspend fun updateFaceId(mediaId: Long, faceId: String)

    /** 查询指定媒体的 faceId */
    @Query("SELECT faceId FROM media_assets WHERE id = :mediaId")
    suspend fun getFaceIdByMediaId(mediaId: Long): String?

    /** 批量更新 faceId（Pass 2 DBSCAN 分配人物时避免逐条更新阻塞） */
    @Query("UPDATE media_assets SET faceId = :faceId WHERE id IN (:mediaIds)")
    suspend fun updateFaceIdBatch(mediaIds: List<Long>, faceId: String)

    /** 按 hasFace 搜索 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getHasFaceCount() / getHasFaceIds()")
    @Query("SELECT * FROM media_assets WHERE hasFace = 1 ORDER BY captureDate DESC")
    suspend fun searchByHasFace(): List<MediaEntity>

    /** 有脸的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE hasFace = 1")
    suspend fun getHasFaceCount(): Int

    /** 仅获取有脸的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE hasFace = 1 ORDER BY captureDate DESC")
    suspend fun getHasFaceIds(): List<Long>

    /** 按 faceId 分组统计数据 */
    @Query("SELECT faceId, COUNT(*) as cnt FROM media_assets WHERE faceId IS NOT NULL AND faceId != '' GROUP BY faceId ORDER BY cnt DESC")
    suspend fun getFaceGroups(): List<FaceGroupCount>

    /** 获取某个 faceId 下的所有媒体 */
    @Query("SELECT * FROM media_assets WHERE faceId = :faceId ORDER BY captureDate DESC")
    suspend fun getMediaByFaceId(faceId: Int): List<MediaEntity>

    /** 重置所有人脸数据（含 hasFace + faceId + faceRoiResult，用于全量重新检测+聚类）
     *
     * 同时清空 semanticEmbedding，因为 MobileCLIP 语义编码已内联合并到 Pass 1。
     */
    @Query("UPDATE media_assets SET hasFace = 0, faceId = NULL, faceRoiResult = NULL, semanticEmbedding = NULL")
    suspend fun resetAllFaceData()

    /** 仅重置人脸聚类结果（保留 hasFace 检测标记，用于仅重新聚类） */
    @Query("UPDATE media_assets SET faceId = NULL")
    suspend fun resetAllFaceIds()

    /** 获取未标记 AI 标签的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getUnlabeledMediaIds() / getUnlabeledMediaCount()")
    @Query("SELECT * FROM media_assets WHERE labels IS NULL OR labels = '' ORDER BY captureDate DESC")
    suspend fun getUnlabeledMedia(): List<MediaEntity>

    /** 仅获取未标记 AI 标签的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE labels IS NULL OR labels = '' ORDER BY captureDate DESC")
    suspend fun getUnlabeledMediaIds(): List<Long>

    /** 未标记 AI 标签的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE labels IS NULL OR labels = ''")
    suspend fun getUnlabeledMediaCount(): Int

    /** 更新媒体的 AI 标签 */
    @Query("UPDATE media_assets SET labels = :labels WHERE id = :mediaId")
    suspend fun updateLabels(mediaId: Long, labels: String)

    /** 更新媒体的 ML Kit 英文标签 */
    @Query("UPDATE media_assets SET mlKitLabels = :labels WHERE id = :mediaId")
    suspend fun updateMlKitLabels(mediaId: Long, labels: String)

    /** 更新媒体的 ML Kit 中文翻译标签 */
    @Query("UPDATE media_assets SET mlKitLabelsZh = :labels WHERE id = :mediaId")
    suspend fun updateMlKitLabelsZh(mediaId: Long, labels: String)

    /** 重置所有 AI 标签（用于强制重新标记） */
    @Query("UPDATE media_assets SET labels = NULL")
    suspend fun resetAllLabels()

    /** 重置所有 ML Kit 标签（用于强制重新标记） */
    @Query("UPDATE media_assets SET mlKitLabels = NULL")
    suspend fun resetAllMlKitLabels()

    /** 重置所有 ML Kit 中文标签 */
    @Query("UPDATE media_assets SET mlKitLabelsZh = NULL")
    suspend fun resetAllMlKitLabelsZh()

    /** 按 ML Kit 英文标签搜索（精确匹配 JSON 数组元素，避免 Vacation 命中 cat 等子串误匹配） */
    @Query("SELECT * FROM media_assets WHERE mlKitLabels LIKE '%' || '\"' || :label || '\"' || '%' ORDER BY captureDate DESC")
    suspend fun searchByMlKitLabel(label: String): List<MediaEntity>

    /** 按 ML Kit 中文标签搜索（精确匹配 JSON 数组元素） */
    @Query("SELECT * FROM media_assets WHERE mlKitLabelsZh LIKE '%' || '\"' || :label || '\"' || '%' ORDER BY captureDate DESC")
    suspend fun searchByMlKitLabelZh(label: String): List<MediaEntity>

    /** 未生成 ML Kit 标签的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getUnlabeledMlKitMediaIds() / getUnlabeledMlKitMediaCount()")
    @Query("SELECT * FROM media_assets WHERE mlKitLabels IS NULL OR mlKitLabels = '' ORDER BY captureDate DESC")
    suspend fun getUnlabeledMlKitMedia(): List<MediaEntity>

    /** 仅获取未生成 ML Kit 标签的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE mlKitLabels IS NULL OR mlKitLabels = '' ORDER BY captureDate DESC")
    suspend fun getUnlabeledMlKitMediaIds(): List<Long>

    /** 未生成 ML Kit 标签的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE mlKitLabels IS NULL OR mlKitLabels = ''")
    suspend fun getUnlabeledMlKitMediaCount(): Int

    /** 已有 ML Kit 标签的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE mlKitLabels IS NOT NULL AND mlKitLabels != ''")
    suspend fun getMlKitLabeledCount(): Int

    // ── 人脸 ROI 结果持久化（3-Pass 混合管道）──────────────────

    /** 获取未检测人脸 ROI 的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaWithoutFaceRoiIds() / getMediaWithoutFaceRoiCount()")
    @Query("SELECT * FROM media_assets WHERE faceRoiResult IS NULL ORDER BY captureDate DESC")
    suspend fun getMediaWithoutFaceRoi(): List<MediaEntity>

    /** 仅获取未检测人脸 ROI 的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE faceRoiResult IS NULL ORDER BY captureDate DESC")
    suspend fun getMediaWithoutFaceRoiIds(): List<Long>

    /** 未检测人脸 ROI 的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE faceRoiResult IS NULL")
    suspend fun getMediaWithoutFaceRoiCount(): Int

    /** 获取已检测人脸 ROI 但未生成标签的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaWithFaceRoiWithoutLabelsIds()")
    @Query("SELECT * FROM media_assets WHERE faceRoiResult IS NOT NULL AND (labels IS NULL OR labels = '') ORDER BY captureDate DESC")
    suspend fun getMediaWithFaceRoiWithoutLabels(): List<MediaEntity>

    /** 仅获取已检测人脸 ROI 但未生成标签的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE faceRoiResult IS NOT NULL AND (labels IS NULL OR labels = '') ORDER BY captureDate DESC")
    suspend fun getMediaWithFaceRoiWithoutLabelsIds(): List<Long>

    /** 更新人脸 ROI 检测结果 */
    @Query("UPDATE media_assets SET faceRoiResult = :json, hasFace = :hasFace WHERE id = :mediaId")
    suspend fun updateFaceRoiResult(mediaId: Long, json: String, hasFace: Boolean)

    /** 检查是否有已检测 ROI 但未完成标签的媒体 */
    @Query("SELECT COUNT(*) > 0 FROM media_assets WHERE faceRoiResult IS NOT NULL AND (labels IS NULL OR labels = '')")
    suspend fun hasPendingQwenTagging(): Boolean

    /** 获取 faceRoiResult 字段 */
    @Query("SELECT faceRoiResult FROM media_assets WHERE id = :mediaId")
    suspend fun getFaceRoiResult(mediaId: Long): String?

    // ── MobileCLIP 语义编码（已内联合并到 Pass 1）──────────────────────────

    /** 更新语义 embedding */
    @Query("UPDATE media_assets SET semanticEmbedding = :embedding WHERE id = :mediaId")
    suspend fun updateSemanticEmbedding(mediaId: Long, embedding: String)

    /** 获取未编码语义 embedding 的媒体（已有 labels 但无 semanticEmbedding）。用于单独重编码场景。 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaNeedingSemanticEncodingIds()")
    @Query("SELECT * FROM media_assets WHERE labels IS NOT NULL AND labels != '' AND semanticEmbedding IS NULL ORDER BY captureDate DESC")
    suspend fun getMediaNeedingSemanticEncoding(): List<MediaEntity>

    /** 仅获取未编码语义 embedding 的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE labels IS NOT NULL AND labels != '' AND semanticEmbedding IS NULL ORDER BY captureDate DESC")
    suspend fun getMediaNeedingSemanticEncodingIds(): List<Long>

    /** 获取指定 ID 的语义 embedding */
    @Query("SELECT semanticEmbedding FROM media_assets WHERE id = :mediaId")
    suspend fun getSemanticEmbedding(mediaId: Long): String?

    /** 获取所有有语义 embedding 的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaWithSemanticEmbeddingIds() / getMediaWithSemanticEmbeddingCount()")
    @Query("SELECT * FROM media_assets WHERE semanticEmbedding IS NOT NULL AND semanticEmbedding != '' ORDER BY captureDate DESC")
    suspend fun getMediaWithSemanticEmbedding(): List<MediaEntity>

    /** 仅获取有语义 embedding 的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE semanticEmbedding IS NOT NULL AND semanticEmbedding != '' ORDER BY captureDate DESC")
    suspend fun getMediaWithSemanticEmbeddingIds(): List<Long>

    /** 有语义 embedding 的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE semanticEmbedding IS NOT NULL AND semanticEmbedding != ''")
    suspend fun getMediaWithSemanticEmbeddingCount(): Int

    /** 检查是否有待语义编码的媒体（用于单独重编码场景） */
    @Query("SELECT COUNT(*) > 0 FROM media_assets WHERE labels IS NOT NULL AND labels != '' AND semanticEmbedding IS NULL")
    suspend fun hasPendingSemanticEncoding(): Boolean

    /** 重置所有语义 embedding（用于强制重新编码/清理污染数据） */
    @Query("UPDATE media_assets SET semanticEmbedding = NULL")
    suspend fun resetAllSemanticEmbeddings()

    // ── TAG 扫描去重字段（3-Pass 混合管道）────────────────────

    /** 从未成功 TAG 扫描的媒体数量（lastTagScanAt 为 NULL），供 tag.audit 统计扫描覆盖 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE lastTagScanAt IS NULL")
    suspend fun getNeverTagScannedCount(): Int

    /** 最近一次 TAG 扫描成功时间戳（全表 MAX；无记录返回 null），供 tag.audit */
    @Query("SELECT MAX(lastTagScanAt) FROM media_assets")
    suspend fun getLatestTagScanAt(): Long?

    /** 更新最近一次 TAG 扫描成功记录 */
    @Query(
        """
        UPDATE media_assets
        SET lastTagScanAt = :timestamp, lastTagScanPasses = :passesJson
        WHERE id = :mediaId
        """
    )
    suspend fun updateLastTagScan(mediaId: Long, timestamp: Long, passesJson: String)

    /** 按拍摄时间降序获取候选媒体（newest-first） */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE (lastTagScanAt IS NULL OR lastTagScanAt < :before)
        ORDER BY captureDate DESC, lastTagScanAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaForIncrementalScanNewest(before: Long, limit: Int): List<MediaEntity>

    /** 按拍摄时间升序获取候选媒体（oldest-first） */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE (lastTagScanAt IS NULL OR lastTagScanAt < :before)
        ORDER BY captureDate ASC, lastTagScanAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaForIncrementalScanOldest(before: Long, limit: Int): List<MediaEntity>

    /**
     * 仅获取 id + lastTagScanPasses 的轻量投影，用于自动增量扫描的去重过滤。
     * 避免一次性加载 faceRoiResult / semanticEmbedding 等大字段到 Java Heap。
     */
    data class TagScanCandidateProjection(
        val id: Long,
        val lastTagScanPasses: String?
    )

    /** 按拍摄时间降序获取候选媒体 ID 与已扫描 Passes（newest-first） */
    @Query(
        """
        SELECT id, lastTagScanPasses FROM media_assets
        WHERE (lastTagScanAt IS NULL OR lastTagScanAt < :before)
        ORDER BY captureDate DESC, lastTagScanAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaForIncrementalScanNewestProjection(
        before: Long,
        limit: Int
    ): List<TagScanCandidateProjection>

    /** 按拍摄时间升序获取候选媒体 ID 与已扫描 Passes（oldest-first） */
    @Query(
        """
        SELECT id, lastTagScanPasses FROM media_assets
        WHERE (lastTagScanAt IS NULL OR lastTagScanAt < :before)
        ORDER BY captureDate ASC, lastTagScanAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaForIncrementalScanOldestProjection(
        before: Long,
        limit: Int
    ): List<TagScanCandidateProjection>

    /** 获取指定 ID 中最近扫描时间早于阈值的照片 */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE id IN (:ids)
          AND (lastTagScanAt IS NULL OR lastTagScanAt < :before)
        ORDER BY lastTagScanAt ASC, captureDate ASC
        """
    )
    suspend fun filterMediaNeedingScan(ids: List<Long>, before: Long): List<MediaEntity>

    // ── 显式约束优先搜索：候选集内查询 ─────────────────────

    /** 按时间范围获取媒体 ID */
    @Query("SELECT id FROM media_assets WHERE captureDate BETWEEN :startMs AND :endMs")
    suspend fun getMediaIdsByTimeRange(startMs: Long, endMs: Long): List<Long>

    /** 按地点关键词获取媒体 ID */
    @Query("SELECT id FROM media_assets WHERE locationName LIKE '%' || :keyword || '%'")
    suspend fun getMediaIdsByLocationKeyword(keyword: String): List<Long>

    /** 按人脸标记获取媒体 ID */
    @Query("SELECT id FROM media_assets WHERE hasFace = 1")
    suspend fun getMediaIdsByHasFace(): List<Long>

    /** 在指定 ID 列表中搜索标签 */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND labels LIKE '%' || :keyword || '%'")
    suspend fun searchLabelsInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索 ML Kit 英文标签 */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND mlKitLabels LIKE '%' || :keyword || '%'")
    suspend fun searchMlKitLabelsInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索 ML Kit 中文标签 */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND mlKitLabelsZh LIKE '%' || :keyword || '%'")
    suspend fun searchMlKitLabelsZhInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索 OCR */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND ocrText LIKE '%' || :keyword || '%'")
    suspend fun searchOcrInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索文件名 */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND fileName LIKE '%' || :keyword || '%'")
    suspend fun searchFileNameInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /**
     * 从备份批量更新 TAG 相关元数据字段。
     * 一次性写入 labels / mlKitLabels / mlKitLabelsZh / ocrText / 地理位置 /
     * faceRoiResult / semanticEmbedding / lastTagScanAt / lastTagScanPasses /
     * hasFace / faceId，避免还原时多次 UPDATE。
     */
    @Query(
        """
        UPDATE media_assets SET
            labels = :labels,
            mlKitLabels = :mlKitLabels,
            mlKitLabelsZh = :mlKitLabelsZh,
            ocrText = :ocrText,
            latitude = :latitude,
            longitude = :longitude,
            locationName = :locationName,
            faceRoiResult = :faceRoiResult,
            semanticEmbedding = :semanticEmbedding,
            lastTagScanAt = :lastTagScanAt,
            lastTagScanPasses = :lastTagScanPasses,
            hasFace = :hasFace,
            faceId = :faceId
        WHERE id = :mediaId
        """
    )
    suspend fun updateTagMetadataFromBackup(
        mediaId: Long,
        labels: String?,
        mlKitLabels: String?,
        mlKitLabelsZh: String?,
        ocrText: String?,
        latitude: Double?,
        longitude: Double?,
        locationName: String?,
        faceRoiResult: String?,
        semanticEmbedding: String?,
        lastTagScanAt: Long?,
        lastTagScanPasses: String?,
        hasFace: Boolean,
        faceId: String?
    )
}

data class FaceGroupCount(
    val faceId: Int,
    val cnt: Int
)
