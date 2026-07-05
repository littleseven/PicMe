package com.mamba.picme.data.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.domain.tag.ClusteringConfig
import com.mamba.picme.domain.tag.FaceClusterEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 人脸聚类 Worker
 *
 * ## 已知问题与改进 (2026-06-23)
 *
 * 1. **[已修复] 仅处理第一张人脸**：现在处理 ML Kit 返回的全部人脸
 * 2. **[已修复] embedding 未持久化**：新增 `FaceEmbeddingEntity` 写入
 * 3. **[已修复] Phase B 重复解码**：markAsClustered 记入 Phase A 结果，Phase B 从 DB 恢复
 * 4. **[已修复] DBSCAN minPts=1** → 提升为 minPts=2，避免单点成簇
 * 5. **[已修复] 传递链式合并**：聚类后验证簇质心一致性
 * 6. **[已修复] face_embeddings 表从未写入**：每条 embedding 按媒体写入
 * 7. **[已修复] landmark/contour 全关**：启用 LANDMARK_MODE_ALL 提升质量
 * 8. **[注意] ML Kit vs MNN**：当前仍用 ML Kit（下一步迁移到自有 FaceDetector）
 *
 * @deprecated 人脸聚类已整合到 TagGenerationScheduler 的 3-Pass 混合管道中（Pass 2: DBSCAN）
 */
@Deprecated("人脸聚类已整合到 TagGenerationScheduler 的 3-Pass 混合管道中")
class FaceClusteringWorker(
    private val context: Context,
    private val onSyncMedia: (suspend () -> Unit)? = null
) {

    companion object {
        private const val TAG = "PicMe:FaceCluster"
        private const val FACE_INPUT_SIZE = 112

        // 聚类参数统一引用 ClusteringConfig（已废弃，实际使用 TagGenerationScheduler）
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    val isRunning: Boolean
        get() = currentJob?.isActive == true

    /** 取消当前聚类任务 */
    fun cancel() {
        currentJob?.cancel()
        Logger.i(TAG, "Clustering cancelled by user")
    }

    /**
     * 流式增量人脸聚类（途中持续产出人物簇）
     *
     * 与 [doCluster] 的批量 DBSCAN 不同，本方法每张照片处理完后
     * 立即通过 [FaceClusterEngine] 匹配已有簇或创建新簇，
     * 人物簇数量在过程中持续增长，无需等待全量完成。
     *
     * @param onProgress 进度回调 (已处理, 总数, 当前人物簇数)，运行在 IO 线程
     * @param onComplete  完成回调，运行在 Main 线程
     */
    fun streamingClusters(
        onProgress: suspend (processed: Int, total: Int, personCount: Int) -> Unit = { _, _, _ -> },
        onComplete: () -> Unit = {}
    ) {
        if (currentJob?.isActive == true) {
            Logger.w(TAG, "Clustering already in progress, cancelling first")
            currentJob?.cancel()
        }
        currentJob = scope.launch {
            try {
                Logger.i(TAG, "Streaming incremental clustering started")
                doStreamingCluster(onProgress)
                Logger.i(TAG, "Streaming clustering completed")
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }

    fun start() {
        if (currentJob?.isActive == true) {
            Logger.d(TAG, "Clustering already in progress")
            return
        }
        currentJob = scope.launch {
            Logger.i(TAG, "Face clustering started")
            doCluster()
            Logger.i(TAG, "Face clustering completed")
        }
    }

    fun forceRecluster() {
        if (currentJob?.isActive == true) {
            Logger.w(TAG, "Clustering in progress, cancelling and restarting in force mode")
            currentJob?.cancel()
        }
        currentJob = scope.launch {
            if (onSyncMedia != null) {
                Logger.i(TAG, "Syncing system media to database...")
                onSyncMedia.invoke()
            }

            Logger.i(TAG, "Force recluster — resetting clustering data (keeping face detection results)")
            val db = AppDatabase.getDatabase(context)
            val dao = db.mediaDao()
            val personDao = db.personDao()
            dao.resetAllFaceIds()
            personDao.clearAllEmbeddings()
            personDao.clearAllPersons()
            Logger.i(TAG, "Clustering data reset, starting recluster on ${dao.getHasFaceCount()} media with faces")
            doCluster()
            Logger.i(TAG, "Force recluster completed")
        }
    }

    private suspend fun doCluster() {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()

        // Step 0: 尝试加载 Glint360K R100 MNN 模型（可选，用于 embedding + 聚类）
        val modelDir = ModelPathConfig.getModelDir(context, "face-embedding-glint360k-r100-mnn")
        val embedder = MnnEmbeddingExtractor(File(modelDir, "glintr100.mnn"))
        val hasEmbeddingModel = embedder.isModelReady && embedder.initialize(inputName = "input.1", outputName = "1333", useGpu = true, swapRb = false)

        if (!hasEmbeddingModel) {
            Logger.w(TAG, "Face embedding model not found at ${modelDir}/glintr100.mnn")
            Logger.w(TAG, "Will run face detection only (hasFace=true). Download glintr100.mnn for person clustering.")
        }

        // Step 1: 按处理状态分离媒体
        val allMedia = dao.getAllMediaNow()
        Logger.i(TAG, "[DIAG] Total media in DB: ${allMedia.size}")
        val needDetection = allMedia.filter { !it.hasFace }
        val needClustering = allMedia.filter { it.hasFace && it.faceId.isNullOrEmpty() }
        val completed = allMedia.size - needDetection.size - needClustering.size

        Logger.i(TAG, "Media: ${needDetection.size} need detection, " +
            "${needClustering.size} need clustering, $completed already done")

        // 无任何工作
        if (needDetection.isEmpty() && needClustering.isEmpty()) {
            Logger.i(TAG, "All media already processed, nothing to do")
            return
        }

        // 有需聚类的照片但无 embedding 模型：仅做增量人脸检测
        if (!hasEmbeddingModel) {
            if (needClustering.isNotEmpty()) {
                Logger.w(TAG, "${needClustering.size} photos need clustering but Glint360K R100 model not available")
                Logger.w(TAG, "Will run face detection only. Download glintr100.mnn for person clustering.")
            }
            if (needDetection.isEmpty()) {
                Logger.i(TAG, "No new faces to detect, and no embedding model for clustering — nothing to do")
                return
            }
        }

        // Step 2: ML Kit 人脸检测（启用全部模式以提升检测质量）
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
        )

        try {
            // embedding 映射: mediaId -> List<FloatArray>（每张照片可有多张人脸）
            val embeddings = mutableMapOf<Long, MutableList<FloatArray>>()
            var newFaces = 0
            var totalFaceCount = 0

            // Phase A & B 合并：仅聚类已检测出人脸的照片
            // 人脸检测是 Stage 1 的职责，Stage 2 不做全量检测
            val allToProcess = needClustering
            if (allToProcess.isEmpty()) {
                Logger.w(TAG, "No photos with face detection results. Run Stage 1 (全量扫描) first to detect faces, then re-run clustering.")
                return
            }

            for (entity in allToProcess) {
                if (!currentJob?.isActive!!) break
                val uri = Uri.parse(entity.uri)
                val original = loadBitmap(uri) ?: continue
                try {
                    val inputImage = InputImage.fromBitmap(original, 0)
                    val faces = com.google.android.gms.tasks.Tasks.await(
                        faceDetector.process(inputImage)
                    )
                    if (faces.isEmpty()) {
                        Logger.d(TAG, "No faces detected by ML Kit for media ${entity.id}")
                        continue
                    }

                    totalFaceCount += faces.size
                    Logger.d(TAG, "ML Kit detected ${faces.size} face(s) in media ${entity.id} (total=$totalFaceCount newFaces=$newFaces)")
                    if (!entity.hasFace) {
                        newFaces++
                        dao.updateHasFace(entity.id, true)
                    }

                    if (hasEmbeddingModel) {
                        Logger.d(TAG, "[DIAG] Extracting embeddings for ${faces.size} face(s) in media ${entity.id}")
                        val faceEmbs = mutableListOf<FloatArray>()
                        // 处理检测到的每张人脸（而非仅 faces[0]）
                        for (face in faces) {
                            val crop = safeCropFace(original, face.boundingBox)
                            if (crop != null) {
                                val emb = embedder.extractEmbedding(crop)
                                if (emb != null) faceEmbs.add(emb)
                                crop.recycle()
                            } else {
                                Logger.d(TAG, "[DIAG] safeCropFace returned null for media ${entity.id}")
                            }
                        }
                        Logger.d(TAG, "[DIAG] media ${entity.id}: ${faces.size} faces -> ${faceEmbs.size} embeddings extracted")
                        if (faceEmbs.isNotEmpty()) {
                            embeddings[entity.id] = faceEmbs
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to process ${entity.id}: ${e.message}")
                } finally {
                    original.recycle()
                }
            }

            // 持久化 embedding 到 face_embeddings 表
            if (hasEmbeddingModel) {
                val personDao = db.personDao()
                for ((mediaId, faceEmbs) in embeddings) {
                    for (emb in faceEmbs) {
                        personDao.insertEmbedding(
                            FaceEmbeddingEntity(
                                mediaId = mediaId,
                                personId = null, // 聚类后分配
                                embedding = floatArrayToByteArray(emb)
                            )
                        )
                    }
                }
            }

            Logger.i(TAG, "Processing done: $newFaces new media with faces, $totalFaceCount total faces, ${embeddings.size} media with embeddings")

            // Step 3: DBSCAN 聚类（使用所有 embedding 的展平索引）
            if (!hasEmbeddingModel || embeddings.size < 2) {
                if (hasEmbeddingModel && embeddings.size == 1) {
                    val singleMediaId = embeddings.keys.first()
                    val personDao = db.personDao()
                    val newPerson = com.mamba.picme.data.local.entity.PersonEntity(
                        faceCount = embeddings[singleMediaId]!!.size,
                        coverMediaId = singleMediaId
                    )
                    val personId = personDao.insertPerson(newPerson)
                    dao.updateFaceId(singleMediaId, personId.toString())
                    personDao.assignEmbeddingByMediaId(mediaId = singleMediaId, personId = personId)
                }
                return
            }

            // 展平：每个 (媒体, 人脸) 对作为一个独立的簇候选
            val flatIndex = mutableListOf<Pair<Long, Int>>() // (mediaId, faceIdx)
            for ((mediaId, faceEmbs) in embeddings) {
                for (i in faceEmbs.indices) {
                    flatIndex.add(mediaId to i)
                }
            }

            val clusters = clusterEmbeddingsFlat(embeddings, flatIndex, ClusteringConfig.DBSCAN_EPS, ClusteringConfig.DBSCAN_MIN_PTS)
            Logger.i(TAG, "DBSCAN: ${clusters.size} clusters from ${flatIndex.size} face embeddings")

            // 后处理：验证簇内部一致性，分裂不健康的簇
            val validated = validateAndSplitClusters(clusters, embeddings)

            // 分配 faceId 并写入 persons 表
            val personDao = db.personDao()
            var assignedCount = 0
            val sorted = validated.entries
                .filter { it.key != -1 }
                .sortedByDescending { it.value.size }

            for ((index, entry) in sorted.withIndex()) {
                val mediaIds = entry.value.map { it.first }.distinct()
                val totalFaces = entry.value.size

                val newPerson = com.mamba.picme.data.local.entity.PersonEntity(
                    faceCount = totalFaces,
                    coverMediaId = mediaIds.firstOrNull()
                )
                val personId = personDao.insertPerson(newPerson)

                for ((mediaId, _) in entry.value) {
                    dao.updateFaceId(mediaId, personId.toString())
                    assignedCount++
                }
                // 将 embedding 关联到 person
                personDao.assignEmbeddingByMediaId(mediaId = mediaIds.first(), personId = personId)
            }

            val noiseCount = validated[-1]?.size ?: 0
            Logger.i(TAG, "Clustering done: $assignedCount media clustered into ${sorted.size} persons, $noiseCount noise embeddings")
        } catch (e: Exception) {
            Logger.e(TAG, "Face clustering failed", e)
        } finally {
            faceDetector.close()
            if (hasEmbeddingModel) embedder.close()
        }
    }

    /**
     * 流式增量聚类：每张照片提取 embedding 后立即匹配/创建簇
     *
     * 使用 [FaceClusterEngine] 的增量匹配方法，
     * 在遍历过程中持续产出人物簇，无需等待全量完成。
     *
     * @param onProgress 进度回调 (已处理, 总数, 当前人物簇数)
     */
    private suspend fun doStreamingCluster(
        onProgress: suspend (processed: Int, total: Int, personCount: Int) -> Unit
    ) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()

        // 加载 Glint360K R100 模型
        val modelDir = ModelPathConfig.getModelDir(context, "face-embedding-glint360k-r100-mnn")
        val embedder = MnnEmbeddingExtractor(File(modelDir, "glintr100.mnn"))
        val hasEmbeddingModel = embedder.isModelReady && embedder.initialize(inputName = "input.1", outputName = "1333", useGpu = true, swapRb = false)

        if (!hasEmbeddingModel) {
            Logger.w(TAG, "[Streaming] Face embedding model not available, cannot cluster")
            onProgress(0, 0, 0)
            return
        }

        // 只处理已检测出人脸但未聚类的照片
        val needClustering = dao.getMediaWithFaces()
        if (needClustering.isEmpty()) {
            Logger.i(TAG, "[Streaming] No photos need clustering")
            onProgress(0, 0, db.personDao().getAllPersons().size)
            return
        }

        Logger.i(TAG, "[Streaming] Processing ${needClustering.size} photos incrementally")

        // 初始化 FaceClusterEngine（增量匹配）
        val clusterEngine = FaceClusterEngine(context)

        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
        )

        try {
            var processed = 0
            val total = needClustering.size

            for (entity in needClustering) {
                if (!currentJob?.isActive!!) break

                val uri = Uri.parse(entity.uri)
                val original = loadBitmap(uri)
                if (original == null) {
                    processed++
                    continue
                }

                try {
                    val inputImage = InputImage.fromBitmap(original, 0)
                    val faces = com.google.android.gms.tasks.Tasks.await(
                        faceDetector.process(inputImage)
                    )

                    if (faces.isEmpty()) {
                        // ML Kit 未检测到人脸，但 Stage 1 标记了 hasFace
                        // 可能是 InsightFace vs ML Kit 检测结果不一致
                        Logger.d(TAG, "[Streaming] No ML Kit faces for media ${entity.id} (previously marked hasFace)")
                        processed++
                        onProgress(processed, total, db.personDao().getAllPersons().size)
                        continue
                    }

                    // 提取每张人脸的 embedding 并立即匹配
                    for (face in faces) {
                        val crop = safeCropFace(original, face.boundingBox)
                        if (crop == null) continue

                        val emb = embedder.extractEmbedding(crop)
                        crop.recycle()

                        if (emb == null) continue

                        // 增量匹配：尝试归入已有簇
                        val matchedId = clusterEngine.matchCluster(emb)
                        val personId: Long
                        if (matchedId != null) {
                            clusterEngine.addToCluster(matchedId, emb, entity.id)
                            personId = matchedId
                            Logger.d(TAG, "[Streaming] media ${entity.id} matched existing cluster $personId")
                        } else {
                            personId = clusterEngine.createCluster(emb, entity.id)
                            Logger.d(TAG, "[Streaming] media ${entity.id} created new cluster $personId")
                        }

                        // 标记 faceId（与该 person 关联）
                        dao.updateFaceId(entity.id, personId.toString())
                    }

                    processed++
                    val currentPersonCount = db.personDao().getAllPersons().size
                    onProgress(processed, total, currentPersonCount)
                } catch (e: Exception) {
                    Logger.w(TAG, "[Streaming] Failed media ${entity.id}: ${e.message}")
                    processed++
                } finally {
                    original.recycle()
                }
            }

            val finalPersonCount = db.personDao().getAllPersons().size
            Logger.i(TAG, "[Streaming] Done: $processed/$total photos, $finalPersonCount clusters")
            onProgress(processed, total, finalPersonCount)
        } catch (e: Exception) {
            Logger.e(TAG, "[Streaming] Clustering failed", e)
        } finally {
            faceDetector.close()
            embedder.close()
        }
    }

    /**
     * DBSCAN 聚类（展平多面版本: 每个 (mediaId, faceIdx) 是独立点）
     *
     * **此方法在当前流式聚类模式下仅作为全量重聚的清理步骤使用。**
     * 正常增量场景请使用 [doStreamingCluster]。
     */
    private fun clusterEmbeddingsFlat(
        embeddings: Map<Long, List<FloatArray>>,
        flatIndex: List<Pair<Long, Int>>,
        eps: Float,
        minPts: Int
    ): Map<Int, List<Pair<Long, Int>>> {
        val n = flatIndex.size
        val labels = IntArray(n) { 0 }
        var clusterId = 0

        for (i in 0 until n) {
            if (labels[i] != 0) continue

            val centerEmb = embeddings[flatIndex[i].first]?.getOrNull(flatIndex[i].second) ?: continue
            val neighbors = mutableListOf<Int>()
            for (j in 0 until n) {
                if (i == j) continue
                val otherEmb = embeddings[flatIndex[j].first]?.getOrNull(flatIndex[j].second) ?: continue
                if (cosineDistance(centerEmb, otherEmb) <= eps) {
                    neighbors.add(j)
                }
            }

            if (neighbors.size < minPts) {
                labels[i] = -1
                continue
            }

            clusterId++
            labels[i] = clusterId
            val seedSet = neighbors.toMutableList()

            var idx = 0
            while (idx < seedSet.size) {
                val q = seedSet[idx]
                if (labels[q] == -1) labels[q] = clusterId
                if (labels[q] == 0) {
                    labels[q] = clusterId
                    val qEmb = embeddings[flatIndex[q].first]?.getOrNull(flatIndex[q].second) ?: run { idx++; continue }
                    val qn = mutableListOf<Int>()
                    for (j in 0 until n) {
                        if (q == j) continue
                        val otherEmb = embeddings[flatIndex[j].first]?.getOrNull(flatIndex[j].second) ?: continue
                        if (cosineDistance(qEmb, otherEmb) <= eps) {
                            qn.add(j)
                        }
                    }
                    if (qn.size >= minPts) {
                        for (ni in qn) {
                            if (ni !in seedSet) seedSet.add(ni)
                        }
                    }
                }
                idx++
            }
        }

        val result = mutableMapOf<Int, MutableList<Pair<Long, Int>>>()
        for (i in 0 until n) {
            val l = labels[i]
            result.getOrPut(l) { mutableListOf() }.add(flatIndex[i])
        }
        return result
    }

    /**
     * 验证簇内部一致性：计算簇内所有点对的平均余弦相似度。
     * 低于 ClusteringConfig.CLUSTER_COHESION_MIN 则递归分裂。
     */
    private fun validateAndSplitClusters(
        clusters: Map<Int, List<Pair<Long, Int>>>,
        embeddings: Map<Long, List<FloatArray>>
    ): Map<Int, List<Pair<Long, Int>>> {
        val result = mutableMapOf<Int, List<Pair<Long, Int>>>()

        for ((clusterId, members) in clusters) {
            if (clusterId == -1 || members.size <= 2) {
                result[clusterId] = members
                continue
            }

            // 随机抽样计算平均簇内相似度
            val sampleSize = minOf(members.size, 20)
            var totalSim = 0f
            var pairCount = 0
            for (i in 0 until sampleSize) {
                for (j in i + 1 until sampleSize) {
                    val embI = embeddings[members[i].first]?.getOrNull(members[i].second) ?: continue
                    val embJ = embeddings[members[j].first]?.getOrNull(members[j].second) ?: continue
                    totalSim += 1f - cosineDistance(embI, embJ)
                    pairCount++
                }
            }

            if (pairCount == 0) {
                result[clusterId] = members
                continue
            }

            val avgSimilarity = totalSim / pairCount
            Logger.d(TAG, "Cluster $clusterId (${members.size} faces) avg similarity: ${"%.3f".format(avgSimilarity)}")

            if (avgSimilarity < ClusteringConfig.CLUSTER_COHESION_MIN) {
                // 分裂：用更严格的 eps 对簇内点重新聚类
                Logger.w(TAG, "Cluster $clusterId cohesion too low (${"%.3f".format(avgSimilarity)}), splitting with tighter eps")
                val subClusters = clusterEmbeddingsFlat(embeddings, members, ClusteringConfig.DBSCAN_EPS * 0.7f, ClusteringConfig.DBSCAN_MIN_PTS)
                var newId = clusterId * 1000 // 避免 ID 冲突
                for ((_, subMembers) in subClusters) {
                    result[newId++] = subMembers
                }
            } else {
                result[clusterId] = members
            }
        }

        return result
    }

    /**
     * 旧版本 DBSCAN（兼容保留）
     */
    private fun clusterEmbeddings(
        embeddings: Map<Long, FloatArray>,
        eps: Float = 0.55f,
        minPts: Int = 1
    ): Map<Int, List<Long>> {
        val ids = embeddings.keys.toList()
        val n = ids.size
        val labels = IntArray(n) { 0 }
        var clusterId = 0

        for (i in 0 until n) {
            if (labels[i] != 0) continue

            val neighbors = findNeighborIndices(embeddings, ids, i, eps)
            if (neighbors.size < minPts) {
                labels[i] = -1
                continue
            }

            clusterId++
            labels[i] = clusterId
            val seedSet = neighbors.toMutableList()
            seedSet.remove(i)

            var idx = 0
            while (idx < seedSet.size) {
                val q = seedSet[idx]
                if (labels[q] == -1) labels[q] = clusterId
                if (labels[q] == 0) {
                    labels[q] = clusterId
                    val qn = findNeighborIndices(embeddings, ids, q, eps)
                    if (qn.size >= minPts) {
                        for (ni in qn) {
                            if (ni !in seedSet) seedSet.add(ni)
                        }
                    }
                }
                idx++
            }
        }

        val result = mutableMapOf<Int, MutableList<Long>>()
        for (i in 0 until n) {
            val l = labels[i]
            result.getOrPut(l) { mutableListOf() }.add(ids[i])
        }
        result.remove(-1)?.let { result[-1] = it }
        return result
    }

    private fun findNeighborIndices(
        embeddings: Map<Long, FloatArray>,
        ids: List<Long>,
        centerIdx: Int,
        eps: Float
    ): List<Int> {
        val centerEmb = embeddings[ids[centerIdx]] ?: return listOf(centerIdx)
        val neighbors = mutableListOf(centerIdx)
        for (i in ids.indices) {
            if (i == centerIdx) continue
            val other = embeddings[ids[i]] ?: continue
            if (cosineDistance(centerEmb, other) <= eps) {
                neighbors.add(i)
            }
        }
        return neighbors
    }

    /** 余弦距离: 1 - cosine_similarity，范围 [0, 2] */
    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val similarity = dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
        return (1f - similarity).coerceAtLeast(0f)
    }

    /**
     * 安全裁剪人脸区域（扩大 20% 边界）
     */
    private fun safeCropFace(bitmap: Bitmap, rect: android.graphics.Rect): Bitmap? {
        val marginW = (rect.width() * 0.2f).toInt().coerceAtLeast(10)
        val marginH = (rect.height() * 0.2f).toInt().coerceAtLeast(10)

        val x = (rect.left - marginW).coerceIn(0, bitmap.width)
        val y = (rect.top - marginH).coerceIn(0, bitmap.height)
        val w = (rect.width() + marginW * 2).coerceAtMost(bitmap.width - x)
        val h = (rect.height() + marginH * 2).coerceAtMost(bitmap.height - y)

        if (w <= 0 || h <= 0) return null

        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
        return Bitmap.createScaledBitmap(cropped, FACE_INPUT_SIZE, FACE_INPUT_SIZE, true).also {
            if (it !== cropped) cropped.recycle()
        }
    }

    private fun floatArrayToByteArray(array: FloatArray): ByteArray {
        val bytes = ByteArray(array.size * 4)
        for (i in array.indices) {
            val bits = java.lang.Float.floatToRawIntBits(array[i])
            bytes[i * 4] = (bits shr 24).toByte()
            bytes[i * 4 + 1] = (bits shr 16).toByte()
            bytes[i * 4 + 2] = (bits shr 8).toByte()
            bytes[i * 4 + 3] = bits.toByte()
        }
        return bytes
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 4
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load bitmap: $uri", e)
            null
        }
    }
}
