package com.mamba.picme.data.indexing

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 媒体元数据索引器
 *
 * 后台扫描未索引的图片（indexedAt IS NULL），
 * 提取 ML Kit 标签、OCR 文字、GPS 位置并写入 Room DB。
 *
 * 支持两种模式：
 * - 全量模式：扫描所有 indexedAt IS NULL 的记录
 * - 增量模式：处理指定的 URI 列表（由 [MediaStoreObserver] 触发）
 *
 * 批量处理（每批 20 张），支持断点续扫。
 * 使用协程后台执行，不阻塞主线程。
 */
class MediaIndexingWorker(
    private val context: Context,
    private val llmEngine: LocalLlmEngine? = null
) {

    companion object {
        private const val TAG = "PoLang:MediaIndex"
        private const val BATCH_SIZE = 20
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    /** 身份证识别器（Qwen 多模态 + 图像增强），null 降级为纯 ML Kit */
    private val idCardRecognizer: IdCardRecognizer? =
        if (llmEngine != null) IdCardRecognizer(context, llmEngine) else null

    /** 索引是否正在运行 */
    val isRunning: Boolean
        get() = currentJob?.isActive == true

    /**
     * 启动全量后台索引（如已有任务运行则忽略）
     */
    fun start() {
        if (currentJob?.isActive == true) {
            Logger.d(TAG, "Indexing already in progress, skipping")
            return
        }
        currentJob = scope.launch {
            Logger.i(TAG, "Full indexing started")
            doFullIndex()
            Logger.i(TAG, "Full indexing completed")
        }
    }

    /**
     * 增量索引：处理 [MediaStoreObserver] 捕获的变更事件
     *
     * @param uris 变更的媒体 URI 列表
     */
    fun indexIncremental(uris: List<Uri>) {
        scope.launch {
            Logger.d(TAG, "Incremental indexing: ${uris.size} URIs")
            for (uri in uris) {
                if (currentJob?.isActive != true && currentJob != null) break
                try {
                    indexSingleUri(uri)
                } catch (e: Exception) {
                    Logger.w(TAG, "Incremental index failed for $uri: ${e.message}")
                }
            }
        }
    }

    /**
     * 取消正在运行的索引
     */
    fun cancel() {
        currentJob?.cancel()
        Logger.i(TAG, "Indexing cancelled")
    }

    // ── 全量索引 ──────────────────────────────────────────

    private suspend fun doFullIndex() {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()

        if (!waitForModelReady()) {
            Logger.w(TAG, "ML Kit model not ready, deferring indexing")
            return
        }

        val extractor = MetadataExtractor(context, idCardRecognizer)
        val ocrIndexUpdater = OcrIndexUpdater(db.ocrWordDao())
        val tagIndexUpdater = TagIndexUpdater(db.tagDao())
        val locationIndexUpdater = LocationIndexUpdater(db.locationDao())

        try {
            var indexedCount = 0
            var cancelled = false
            while (!cancelled) {
                val batchIds = dao.getUnindexedMediaIds().take(BATCH_SIZE)
                if (batchIds.isEmpty()) {
                    Logger.i(TAG, "All media indexed, total: $indexedCount")
                    break
                }

                val batch = dao.getMediaByIds(batchIds)
                for (entity in batch) {
                    if (currentJob?.isActive != true) {
                        cancelled = true
                        break
                    }

                    try {
                        val uri = Uri.parse(entity.uri)
                        val inputImage = InputImage.fromFilePath(context, uri)
                        val result = extractor.extract(uri, inputImage)

                        val now = System.currentTimeMillis()
                        dao.updateIndexResult(
                            mediaId = entity.id,
                            labels = result.labelsJson,
                            ocrText = result.ocrText,
                            latitude = result.latitude,
                            longitude = result.longitude,
                            locationName = result.locationName,
                            indexedAt = now
                        )

                        // 同步更新规范化索引表
                        tagIndexUpdater.updateIndex(entity.id, result.labelsJson)
                        locationIndexUpdater.updateIndex(
                            mediaId = entity.id,
                            latitude = result.latitude,
                            longitude = result.longitude,
                            locationName = result.locationName
                        )
                        if (!result.ocrText.isNullOrBlank()) {
                            ocrIndexUpdater.updateIndex(entity.id, result.ocrText)
                        }

                        indexedCount++
                    } catch (e: Exception) {
                        Logger.w(TAG, "Index failed for media ${entity.id}: ${e.message}")
                        dao.updateIndexResult(
                            mediaId = entity.id,
                            labels = entity.labels,
                            ocrText = entity.ocrText,
                            latitude = entity.latitude,
                            longitude = entity.longitude,
                            locationName = entity.locationName,
                            indexedAt = -1L
                        )
                    }
                }

                Logger.d(TAG, "Batch complete: $indexedCount indexed")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Indexing failed", e)
        } finally {
            extractor.close()
        }
    }

    // ── 增量索引 ──────────────────────────────────────────

    private suspend fun indexSingleUri(uri: Uri) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()

        val extractor = MetadataExtractor(context, idCardRecognizer)
        val ocrIdxUpdater = OcrIndexUpdater(db.ocrWordDao())
        val tagIdxUpdater = TagIndexUpdater(db.tagDao())
        val locationIdxUpdater = LocationIndexUpdater(db.locationDao())

        try {
            val inputImage = InputImage.fromFilePath(context, uri)
            val result = extractor.extract(uri, inputImage)
            val now = System.currentTimeMillis()

            // 查找或创建 Room 记录
            val existingMedia = dao.getMediaByUri(uri.toString())
            val mediaId: Long = if (existingMedia != null) {
                dao.updateIndexResult(
                    mediaId = existingMedia.id,
                    labels = result.labelsJson,
                    ocrText = result.ocrText,
                    latitude = result.latitude,
                    longitude = result.longitude,
                    locationName = result.locationName,
                    indexedAt = now
                )
                existingMedia.id
            } else {
                // 新文件：创建基础记录
                val fileName = uri.lastPathSegment ?: "unknown"
                val entity = com.mamba.picme.data.model.MediaEntity(
                    uri = uri.toString(),
                    type = com.mamba.picme.agent.core.model.context.MediaType.PHOTO,
                    captureDate = now,
                    fileName = fileName,
                    labels = result.labelsJson,
                    ocrText = result.ocrText,
                    latitude = result.latitude,
                    longitude = result.longitude,
                    locationName = result.locationName,
                    indexedAt = now
                )
                dao.insertMedia(entity)
            }

            // 同步更新规范化索引表
            tagIdxUpdater.updateIndex(mediaId, result.labelsJson)
            locationIdxUpdater.updateIndex(
                mediaId = mediaId,
                latitude = result.latitude,
                longitude = result.longitude,
                locationName = result.locationName
            )
            if (!result.ocrText.isNullOrBlank()) {
                ocrIdxUpdater.updateIndex(mediaId, result.ocrText)
            }

            Logger.d(TAG, "Incremental index complete for: $uri")
        } catch (e: Exception) {
            Logger.w(TAG, "Incremental index failed for $uri: ${e.message}")
        } finally {
            extractor.close()
        }
    }

    // ── 模型预热 ──────────────────────────────────────────

    private suspend fun waitForModelReady(): Boolean {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()
        val tempExtractor = MetadataExtractor(context)
        try {
            val firstMediaId = dao.getUnindexedMediaIds().firstOrNull() ?: return true
            val firstMedia = dao.getMediaById(firstMediaId) ?: return true
            val uri = Uri.parse(firstMedia.uri)
            val image = try {
                InputImage.fromFilePath(context, uri)
            } catch (e: Exception) {
                return true
            }

            repeat(10) { attempt ->
                try {
                    tempExtractor.extractLabels(image)
                    Logger.i(TAG, "ML Kit model ready (attempt ${attempt + 1})")
                    return true
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    val causeMsg = e.cause?.message ?: ""
                    if (msg.contains("download") || msg.contains("optional module") ||
                        causeMsg.contains("download") || causeMsg.contains("optional module")
                    ) {
                        Logger.d(TAG, "Waiting for model download (attempt ${attempt + 1}/10)...")
                        delay(3000)
                    } else {
                        Logger.w(TAG, "Model warm-up failed: $msg")
                        return false
                    }
                }
            }
            Logger.w(TAG, "ML Kit model not ready after 10 attempts")
            return false
        } finally {
            tempExtractor.close()
        }
    }
}
