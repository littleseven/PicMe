package com.mamba.picme.domain.tag

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.room.withTransaction
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.mamba.picme.beauty.api.facedetect.DevicePreference
import com.mamba.picme.beauty.api.facedetect.FaceDetectorFactory
import com.mamba.picme.beauty.api.facedetect.InferenceBackendType
import com.mamba.picme.beauty.api.facedetect.LandmarkDetectorType
import com.mamba.picme.beauty.api.facedetect.RoiDetectorType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * 用于全量重聚类时保留命名人信息的快照。
 *
 * @param personId 原人物 ID
 * @param name 用户为该人物设置的名称
 * @param centroid 该人物所有 face embedding 的质心
 */
data class NamedPersonSnapshot(
    val personId: Long,
    val name: String,
    val centroid: FloatArray
)

/**
 * 标签生成批处理调度器
 *
 * 管理全量扫描和单张处理的调度，提供进度回调和取消支持。
 *
 * ## 架构
 * - 通过 [dispatcher] 参数控制执行线程（Service 场景传入单线程调度器保证串行）
 * - 通过 [guard] 在每张照片处理前检查是否允许继续（电池/热状态守卫）
 *
 * ## 触发方式
 * - [scanAll]：全量扫描所有照片
 * - [scanIncremental]：增量扫描未标记照片
 * - [processSingle]：处理单张新照片
 * - [cancel]：取消进行中的扫描
 */
class TagGenerationScheduler(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val guard: suspend () -> GuardResult = { GuardResult.ALLOW },
    private val getThrottleMs: () -> Long = { 1000L },
    private val getPass3CooldownMs: () -> Long = { 800L },
    private val userSettingsRepository: UserSettingsRepository = UserPreferencesRepository(context)
) {

    /** 当前打标模型 key（由用户设置解析；默认 qwen3_vl_2b，Debug 可切 smolvlm_500m） */
    private val taggerModelKey: String
        get() = TaggerModelSelector.resolve(userSettingsRepository.getTaggerModelKeyBlocking())

    companion object {
        private const val TAG = "TagScheduler"

        /** 批次大小：每处理此数量照片后强制冷却 */
        private const val BATCH_SIZE = 10

        /** 批次间强制冷却时间（ms） */
        private const val BATCH_COOLDOWN_MS = 15_000L

        /** 增量扫描单次最大处理量 */
        private const val INCREMENTAL_MAX_PHOTOS = 50

        /** hasFace 清理时批量加载 MediaEntity 的大小，防止 Java Heap OOM */
        private const val CLEANUP_BATCH_SIZE = 100
    }

    /**
     * 守卫检查结果
     */
    enum class GuardResult {
        /** 允许继续 */
        ALLOW,
        /** 暂停等待（增大节流间隔） */
        PAUSE,
        /** 终止扫描 */
        ABORT
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var currentJob: Job? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _progress = MutableStateFlow<TagScanProgress?>(null)
    val progress: StateFlow<TagScanProgress?> = _progress.asStateFlow()

    private val _lastScanMessage = MutableStateFlow<String?>(null)
    val lastScanMessage: StateFlow<String?> = _lastScanMessage.asStateFlow()

    private val db = AppDatabase.getDatabase(context)
    private val personDao = db.personDao()
    private val vocab = ControlledVocab.loadFromAssets(context)
    private val normalizer = TagNormalizer(vocab)
    private val faceClusterEngine = FaceClusterEngine(context)

    private val openClGuardian: OpenClGuardian by lazy {
        OpenClGuardian(
            context = context,
            engine = AgentOrchestrator.getInstance(context).getLlmEngine(),
            prefs = userSettingsRepository,
            modelId = taggerModelKey
        )
    }

    private val pipeline: TagGenerationPipeline by lazy {
        val faceDetector = FaceDetectorFactory.create(context)
        // 【关键修复】必须调用 updatePipelineConfig()，否则 FaceDetectorManager
        // 的 isPipelineInitialized 保持 false，所有 detectPhoto() 静默返回 null。
        // 方案 B：ROI 与 2D106 landmark 均走 MNN + OpenCL GPU（FORCE_GPU）。
        faceDetector.updatePipelineConfig(DetectionPipelineConfig(
            roiDetector = RoiDetectorType.DET10G,
            landmarkDetector = LandmarkDetectorType.INSIGHTFACE_2D106,
            roiEngine = InferenceBackendType.MNN,
            landmarkEngine = InferenceBackendType.MNN,
            roiDevice = DevicePreference.FORCE_GPU,
            landmarkDevice = DevicePreference.FORCE_GPU
        ))
        val llmEngine = AgentOrchestrator.getInstance(context).getLlmEngine()
        val mobileClip = MobileClipEngine(context)
        val tokenizer = MobileClipTokenizer(context)
        val classifier = MobileClipTagClassifier(mobileClip, tokenizer, vocab)
        TagGenerationPipeline(
            context = context,
            faceDetector = faceDetector,
            llmEngine = llmEngine,
            faceClusterEngine = faceClusterEngine,
            normalizer = normalizer,
            openClGuardian = openClGuardian,
            userSettingsRepository = userSettingsRepository,
            mobileClipEngine = mobileClip,
            mobileClipTagClassifier = classifier
        )
    }

    /**
     * 触发全量 3-Pass 混合扫描
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.scheduleAutoScan] / [TagScanOrchestrator.schedulePass]。
     * 旧方法基于局部计数器，与任务队列系统的进度不同源，会导致统计不一致。
     */
    @Deprecated(
        "Use TagScanOrchestrator.scheduleAutoScan() or schedulePass() instead",
        ReplaceWith("TagScanOrchestrator(context, this).scheduleAutoScan()")
    )
    fun scanAll(progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }) {
        throw NotImplementedError(
            "scanAll is deprecated. Use TagScanOrchestrator.scheduleAutoScan() or schedulePass() instead."
        )
    }

    /** 处理单张新照片 */
    suspend fun processSingle(uri: String, mediaId: Long) {
        scope.launch {
            try {
                if (!ensureModelLoaded()) return@launch

                val resultJson = pipeline.processPhoto(
                    uri = uri,
                    lensFacing = CameraSelector.LENS_FACING_BACK,
                    mediaId = mediaId
                )

                if (resultJson.isNotEmpty()) {
                    db.mediaDao().updateLabels(mediaId, resultJson)
                }
                Log.d(TAG, "Single photo processed: mediaId=$mediaId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to process single photo $mediaId: ${e.message}")
            }
        }
    }

    /**
     * 单张同步处理(照片信息弹窗「重新打标」用):走完整 Pass3 pipeline(与集中扫描同源),
     * 返回结构化标签 JSON(scene/activity/objects/tags/summary),并写入 db。
     *
     * 与 [processSingle] 区别:同步 suspend 返回 resultJson(供 UI 即时刷新),
     * 不经 dispatcher 队列/批控节流(单张用户显式触发)。
     *
     * @return resultJson(结构化 Object);null 表示失败(模型未加载 / 媒体不存在 / 空结果)。
     */
    suspend fun processSingleSync(uri: String): String? = withContext(Dispatchers.IO) {
        if (!ensureModelLoaded()) return@withContext null
        val entity = db.mediaDao().getMediaByUri(uri) ?: return@withContext null
        val resultJson = pipeline.processPhoto(
            uri = uri,
            lensFacing = CameraSelector.LENS_FACING_BACK,
            mediaId = entity.id
        )
        if (resultJson.isNotEmpty()) {
            db.mediaDao().updateLabels(entity.id, resultJson)
        }
        resultJson.ifEmpty { null }
    }

    /**
     * 取消进行中的扫描
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.cancel]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.cancel() instead",
        ReplaceWith("TagScanOrchestrator(context, this).cancel()")
    )
    fun cancel() {
        throw NotImplementedError(
            "cancel() is deprecated. Use TagScanOrchestrator.cancel() instead."
        )
    }

    /**
     * 增量扫描：3-Pass 混合模型，仅处理未标记标签的照片
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.scheduleAutoScan]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.scheduleAutoScan() instead",
        ReplaceWith("TagScanOrchestrator(context, this).scheduleAutoScan()")
    )
    fun scanIncremental(
        maxPhotos: Int = INCREMENTAL_MAX_PHOTOS,
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanIncremental is deprecated. Use TagScanOrchestrator.scheduleAutoScan() instead."
        )
    }

    // ═══════════════════════════════════════════════════
    //  独立阶段扫描（分阶段批量控制）
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 1 独立执行] 全量人脸检测 + 人脸 Embedding + MobileCLIP 语义编码（内联合并）
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.schedulePass]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.schedulePass(TagScanPass.FACE_DETECTION, mode=FULL) instead",
        ReplaceWith("TagScanOrchestrator(context, this).schedulePass(TagScanPass.FACE_DETECTION, mode = FULL)")
    )
    fun scanPass1(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanPass1 is deprecated. Use TagScanOrchestrator.schedulePass(TagScanPass.FACE_DETECTION, mode = FULL)."
        )
    }

    /**
     * [Pass 2 独立执行] DBSCAN 全局聚类
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.schedulePass]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.schedulePass(TagScanPass.DBSCAN) instead",
        ReplaceWith("TagScanOrchestrator(context, this).schedulePass(TagScanPass.DBSCAN)")
    )
    fun scanPass2(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanPass2 is deprecated. Use TagScanOrchestrator.schedulePass(TagScanPass.DBSCAN)."
        )
    }

    /**
     * [Pass 3 独立执行] 仅进行 Qwen 图像理解标签生成
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.schedulePass]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.schedulePass(TagScanPass.QWEN_TAGGING) instead",
        ReplaceWith("TagScanOrchestrator(context, this).schedulePass(TagScanPass.QWEN_TAGGING)")
    )
    fun scanPass3(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanPass3 is deprecated. Use TagScanOrchestrator.schedulePass(TagScanPass.QWEN_TAGGING)."
        )
    }

    /**
     * [Pass 3 重新生成] 清空已有标签后全量重标
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.schedulePass]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.schedulePass(TagScanPass.QWEN_TAGGING, mode=FULL) instead",
        ReplaceWith("TagScanOrchestrator(context, this).schedulePass(TagScanPass.QWEN_TAGGING, mode = FULL)")
    )
    fun scanPass3Full(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanPass3Full is deprecated. Use TagScanOrchestrator.schedulePass(TagScanPass.QWEN_TAGGING, mode = FULL)."
        )
    }

    // ═══════════════════════════════════════════════════
    //  守卫检查
    // ═══════════════════════════════════════════════════

    /** 电池/热状态守卫检查 */
    private suspend fun guardCheck(): Boolean {
        when (guard()) {
            GuardResult.ABORT -> {
                Log.i(TAG, "Guard ABORT")
                return false
            }
            GuardResult.PAUSE -> {
                Log.d(TAG, "Guard PAUSE, extended throttle (${getThrottleMs()}ms)")
                delay(getThrottleMs())
                // PAUSE 不跳过当前照片，仅增加节流
            }
            GuardResult.ALLOW -> { /* continue */ }
        }
        return true
    }

    // ═══════════════════════════════════════════════════
    //  Pass 2: 人脸聚类（方案 A DBSCAN / 方案 B 密度自适应 k-NN）
    // ═══════════════════════════════════════════════════

    /**
     * 基于 face_embeddings 表执行人脸聚类。
     * 当前由 [ClusteringConfig.USE_ADAPTIVE_CLUSTERING] 控制使用方案 B 或方案 A。
     *
     * @param namedPersonSnapshots 全量重聚类时传入的已命名人物质心快照，
     *                             用于将新簇与旧人物匹配并复用 personId/name。
     */
    private suspend fun runDbscanClustering(
        dao: com.mamba.picme.data.local.MediaDao,
        namedPersonSnapshots: List<NamedPersonSnapshot> = emptyList()
    ) {
        val unassigned = personDao.getUnassignedEmbeddings()
        if (unassigned.isEmpty()) {
            Log.i(TAG, "DBSCAN: no unassigned embeddings, skipping")
            return
        }

        // 按 mediaId 分组，同时记录每个 (mediaId, faceIndex) 对应的 embeddingId
        val embeddingsMap = mutableMapOf<Long, MutableList<FloatArray>>()
        val embeddingIdMap = mutableMapOf<Pair<Long, Int>, Long>()
        for (emb in unassigned) {
            val feature = byteArrayToFloatArray(emb.embedding)
            val list = embeddingsMap.getOrPut(emb.mediaId) { mutableListOf() }
            val faceIndex = list.size
            list.add(feature)
            embeddingIdMap[emb.mediaId to faceIndex] = emb.embeddingId
        }

        if (embeddingsMap.size < 2) {
            // 仅一张照片有 face：直接建单簇
            if (embeddingsMap.size == 1) {
                val singleMediaId = embeddingsMap.keys.first()
                val personId = personDao.insertPerson(
                    com.mamba.picme.data.local.entity.PersonEntity(
                        faceCount = embeddingsMap[singleMediaId]!!.size,
                        coverMediaId = singleMediaId
                    )
                )
                dao.updateFaceId(singleMediaId, personId.toString())
                personDao.assignEmbeddingByMediaId(mediaId = singleMediaId, personId = personId)
                Log.i(TAG, "DBSCAN: single media with faces -> personId=$personId")
            }
            return
        }

        // 展平索引
        val flatIndex = mutableListOf<Pair<Long, Int>>()
        for ((mediaId, faceEmbs) in embeddingsMap) {
            for (i in faceEmbs.indices) {
                flatIndex.add(mediaId to i)
            }
        }

        // 诊断：分析 embedding 两两相似度分布
        logEmbeddingSimilarityDistribution(embeddingsMap, flatIndex)

        // 选择聚类策略：方案 B（密度自适应 k-NN 图聚类）或方案 A（DBSCAN）
        val clusters = if (ClusteringConfig.USE_ADAPTIVE_CLUSTERING) {
            AdaptiveFaceClusterer.cluster(
                embeddingsMap = embeddingsMap,
                flatIndex = flatIndex,
                k = ClusteringConfig.KNN_K,
                minSimilarity = ClusteringConfig.KNN_MIN_SIMILARITY,
                minClusterSize = ClusteringConfig.KNN_MIN_CLUSTER_SIZE
            ).also {
                Log.i(TAG, "Adaptive k-NN clustering: ${it.size} cluster keys from ${flatIndex.size} face embeddings")
            }
        } else {
            var dbscanClusters = dbscanCluster(embeddingsMap, flatIndex, ClusteringConfig.DBSCAN_EPS, ClusteringConfig.DBSCAN_MIN_PTS)
            Log.i(TAG, "DBSCAN: ${dbscanClusters.size} clusters from ${flatIndex.size} face embeddings")
            // 验证簇内部一致性，分裂不健康的簇
            validateAndSplitClusters(dbscanClusters, embeddingsMap)
        }

        // 分配 personId：按簇批量写入，避免逐条 UPDATE 阻塞协程/线程
        val sorted = clusters.entries
            .filter { it.key != -1 }
            .sortedByDescending { it.value.size }

        // 预计算各簇质心，用于后续噪声点归并
        val clusterCentroids = sorted.associate { entry ->
            entry.key to computeClusterCentroid(entry.value, embeddingsMap)
        }

        Log.i(TAG, "DBSCAN assignment start: ${sorted.size} clusters to persist")
        var assignedCount = 0
        var reusedCount = 0
        val clusterKeyToPersonId = mutableMapOf<Int, Long>()
        val availableSnapshots = namedPersonSnapshots.toMutableList()
        db.withTransaction {
            for ((index, entry) in sorted.withIndex()) {
                val mediaIds = entry.value.map { it.first }.distinct()
                val totalFaces = entry.value.size
                val centroid = clusterCentroids[entry.key]
                Log.d(TAG, "DBSCAN persisting cluster #$index (key=${entry.key}, $totalFaces faces, ${mediaIds.size} media)")

                val personId = findOrCreatePersonForCluster(
                    mediaIds = mediaIds,
                    totalFaces = totalFaces,
                    centroid = centroid,
                    availableSnapshots = availableSnapshots
                )
                if (personId in namedPersonSnapshots.map { it.personId }) {
                    reusedCount++
                }
                clusterKeyToPersonId[entry.key] = personId
                Log.d(TAG, "DBSCAN assigned personId=$personId for cluster #$index")
                // 批量更新 media_assets.faceId（同一人物的所有媒体一次性写入）
                dao.updateFaceIdBatch(mediaIds, personId.toString())
                assignedCount += entry.value.size
                // 批量更新 face_embeddings.personId
                if (mediaIds.isNotEmpty()) {
                    personDao.assignEmbeddingsByMediaIds(mediaIds, personId)
                }
            }
        }

        // 处理噪声点：仅将明显归属于已有簇的边界点回收，
        // 不再为每个噪声点创建单例，避免照片较少的人脸产生过多碎片簇。
        val noisePoints = clusters[-1] ?: emptyList()
        var mergedNoiseCount = 0
        if (noisePoints.isNotEmpty()) {
            db.withTransaction {
                for ((mediaId, faceIndex) in noisePoints) {
                    val emb = embeddingsMap[mediaId]?.getOrNull(faceIndex) ?: continue
                    val embeddingId = embeddingIdMap[mediaId to faceIndex] ?: continue

                    // 找到最近簇质心
                    var bestKey: Int? = null
                    var bestSim = -1f
                    for ((key, centroid) in clusterCentroids) {
                        val sim = 1f - cosineDistance(emb, centroid)
                        if (sim > bestSim) {
                            bestSim = sim
                            bestKey = key
                        }
                    }

                    val mergeThreshold = if (ClusteringConfig.USE_ADAPTIVE_CLUSTERING) {
                        ClusteringConfig.KNN_MIN_SIMILARITY
                    } else {
                        1f - ClusteringConfig.DBSCAN_EPS
                    }
                    val targetPersonId = if (bestKey != null && bestSim >= mergeThreshold) {
                        clusterKeyToPersonId[bestKey]
                    } else null

                    if (targetPersonId != null) {
                        // 归并到已有簇
                        personDao.incrementFaceCount(targetPersonId)
                        personDao.assignEmbedding(embeddingId, targetPersonId)
                        val existingFaceId = dao.getFaceIdByMediaId(mediaId)
                        if (existingFaceId.isNullOrBlank()) {
                            dao.updateFaceId(mediaId, targetPersonId.toString())
                        }
                        mergedNoiseCount++
                    }
                }
            }
        }

        val noiseCount = noisePoints.size
        Log.i(TAG, "DBSCAN done: $assignedCount media clustered into ${sorted.size} persons, " +
            "reused=$reusedCount, noise=$noiseCount (merged=$mergedNoiseCount, remaining=${noiseCount - mergedNoiseCount})")

        // 【关键修复】校验 hasFace 标记：清理有 hasFace=true 但无有效 embedding 的媒体
        // 这些媒体可能是之前误检（RetinaFace 误报）或零向量过滤后的残留
        cleanupInvalidHasFace(dao)
    }

    /**
     * 为新聚类簇寻找或创建对应的人物记录。
     *
     * 优先将新簇质心与 [availableSnapshots] 中已命名人质的心做余弦相似度匹配，
     * 相似度 ≥ [ClusteringConfig.NAME_PRESERVE_MIN_SIMILARITY] 时复用旧 personId 与 name，
     * 否则插入新人物。每个旧人物最多被复用一次。
     */
    private suspend fun findOrCreatePersonForCluster(
        mediaIds: List<Long>,
        totalFaces: Int,
        centroid: FloatArray?,
        availableSnapshots: MutableList<NamedPersonSnapshot>
    ): Long {
        val coverMediaId = mediaIds.firstOrNull()

        // 没有可用快照或无法计算质心时直接新建
        if (centroid == null || availableSnapshots.isEmpty()) {
            return personDao.insertPerson(
                com.mamba.picme.data.local.entity.PersonEntity(
                    faceCount = totalFaces,
                    coverMediaId = coverMediaId
                )
            )
        }

        var bestIndex = -1
        var bestSim = -1f
        availableSnapshots.forEachIndexed { index, snapshot ->
            val sim = 1f - cosineDistance(centroid, snapshot.centroid)
            if (sim > bestSim) {
                bestSim = sim
                bestIndex = index
            }
        }

        return if (bestIndex >= 0 && bestSim >= ClusteringConfig.NAME_PRESERVE_MIN_SIMILARITY) {
            val snapshot = availableSnapshots.removeAt(bestIndex)
            personDao.updatePersonStats(
                personId = snapshot.personId,
                faceCount = totalFaces,
                coverMediaId = coverMediaId
            )
            Log.i(TAG, "Reused named person id=${snapshot.personId} name=${snapshot.name} " +
                "for new cluster (sim=${String.format("%.3f", bestSim)})")
            snapshot.personId
        } else {
            personDao.insertPerson(
                com.mamba.picme.data.local.entity.PersonEntity(
                    faceCount = totalFaces,
                    coverMediaId = coverMediaId
                )
            )
        }
    }

    /**
     * 清理无效的 hasFace 标记
     *
     * 对 hasFace=true 但 face_embeddings 表中没有对应记录的照片，
     * 重置 hasFace=false 并清除 faceRoiResult，避免误检照片进入人脸分组。
     */
    private suspend fun cleanupInvalidHasFace(dao: com.mamba.picme.data.local.MediaDao) {
        val allHasFaceIds = dao.getHasFaceIds()
        val mediaWithEmbeddings = personDao.getAllEmbeddings().map { it.mediaId }.toSet()

        var cleanedCount = 0
        allHasFaceIds.chunked(CLEANUP_BATCH_SIZE).forEach { batchIds ->
            val batchEntities = dao.getMediaByIds(batchIds)
            for (media in batchEntities) {
                if (media.id !in mediaWithEmbeddings) {
                    // 无有效 embedding：重置 hasFace 并清除 faceRoiResult
                    dao.updateFaceRoiResult(media.id, "", false)
                    cleanedCount++
                    Log.w(TAG, "Cleanup invalid hasFace: mediaId=${media.id} has no valid embedding, reset hasFace=false")
                }
            }
        }

        if (cleanedCount > 0) {
            Log.w(TAG, "Cleanup invalid hasFace: $cleanedCount media reset from hasFace=true to false")
        }
    }

    /**
     * 验证簇内部一致性：计算簇内所有点对的平均余弦相似度。
     * 低于 ClusteringConfig.CLUSTER_COHESION_MIN 则用更严格的 eps 递归分裂。
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
            Log.d(TAG, "Cluster $clusterId (${members.size} faces) avg similarity: ${String.format("%.3f", avgSimilarity)}")
            if (avgSimilarity < ClusteringConfig.CLUSTER_COHESION_MIN) {
                Log.w(TAG, "Cluster $clusterId cohesion too low (${String.format("%.3f", avgSimilarity)}), splitting")
                val subClusters = dbscanCluster(embeddings, members, ClusteringConfig.DBSCAN_EPS * 0.75f, ClusteringConfig.DBSCAN_MIN_PTS)
                var newId = clusterId * 1000
                for ((_, subMembers) in subClusters) {
                    result[newId++] = subMembers
                }
            } else {
                result[clusterId] = members
            }
        }
        return result
    }

    /** DBSCAN 核心算法 */
    private fun dbscanCluster(
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
     * 诊断：统计所有 embedding 两两余弦相似度的分布。
     *
     * 采样计算（最多 3000 对），输出最小/最大/平均/标准差和直方图，
     * 用于判断 embedding 是否有区分度。
     */
    private fun logEmbeddingSimilarityDistribution(
        embeddings: Map<Long, List<FloatArray>>,
        flatIndex: List<Pair<Long, Int>>
    ) {
        if (flatIndex.size < 2) return

        val random = java.util.Random(42)
        val similarities = mutableListOf<Float>()
        val maxSamples = 3000
        var attempts = 0
        val totalPairs = flatIndex.size * (flatIndex.size - 1L) / 2

        while (similarities.size < maxSamples && attempts < maxSamples * 3 && totalPairs > 0) {
            val i = random.nextInt(flatIndex.size)
            var j = random.nextInt(flatIndex.size)
            if (i == j) {
                attempts++
                continue
            }
            val embI = embeddings[flatIndex[i].first]?.getOrNull(flatIndex[i].second) ?: run { attempts++; continue }
            val embJ = embeddings[flatIndex[j].first]?.getOrNull(flatIndex[j].second) ?: run { attempts++; continue }
            val sim = 1f - cosineDistance(embI, embJ)
            similarities.add(sim)
            attempts++
        }

        if (similarities.isEmpty()) return

        similarities.sort()
        val minSim = similarities.first()
        val maxSim = similarities.last()
        val meanSim = similarities.average().toFloat()
        val variance = similarities.map { (it - meanSim) * (it - meanSim) }.average().toFloat()
        val stdSim = kotlin.math.sqrt(variance)

        // 直方图：按相似度区间统计
        val buckets = IntArray(10) // [0,0.1), [0.1,0.2), ..., [0.9,1.0]
        for (sim in similarities) {
            val idx = (sim * 10).toInt().coerceIn(0, 9)
            buckets[idx]++
        }

        Log.i(TAG, "Embedding similarity distribution: pairs=${similarities.size}, " +
            "min=${String.format("%.3f", minSim)}, max=${String.format("%.3f", maxSim)}, " +
            "mean=${String.format("%.3f", meanSim)}, std=${String.format("%.3f", stdSim)}")
        Log.i(TAG, "Similarity histogram: ${buckets.joinToString(" ") { "%.1f:%d".format(it / 10.0, it) }}")
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
        val similarity = dot / (sqrt(normA) * sqrt(normB))
        return (1f - similarity).coerceAtLeast(0f)
    }

    /** 计算簇的质心（成员 embedding 的算术平均） */
    private fun computeClusterCentroid(
        members: List<Pair<Long, Int>>,
        embeddings: Map<Long, List<FloatArray>>
    ): FloatArray {
        val dim = embeddings.values.firstOrNull()?.firstOrNull()?.size ?: 512
        val sum = FloatArray(dim)
        var count = 0
        for ((mediaId, faceIndex) in members) {
            val emb = embeddings[mediaId]?.getOrNull(faceIndex) ?: continue
            for (i in emb.indices) {
                sum[i] += emb[i]
            }
            count++
        }
        if (count == 0) return FloatArray(dim)
        for (i in sum.indices) {
            sum[i] /= count
        }
        return sum
    }

    // ═══════════════════════════════════════════════════
    //  序列化辅助
    // ═══════════════════════════════════════════════════

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

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            val bits = ((bytes[i * 4].toInt() and 0xFF) shl 24) or
                    ((bytes[i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i * 4 + 3].toInt() and 0xFF)
            floats[i] = java.lang.Float.intBitsToFloat(bits)
        }
        return floats
    }

    private fun unifiedTagToJson(result: UnifiedTagResult): String {
        val obj = JSONObject()
        val face = JSONObject()
        face.put("count", result.face.count)
        face.put("selfie", result.face.selfie)
        face.put("groupPhoto", result.face.groupPhoto)
        face.put("personIds", JSONArray(result.face.personIds))
        obj.put("face", face)
        obj.put("scene", result.scene)
        obj.put("activity", result.activity)
        obj.put("objects", JSONArray(result.objects))
        obj.put("tags", JSONArray(result.tags))
        obj.put("summary", result.summary)
        return obj.toString()
    }

    private suspend fun ensureModelLoaded(): Boolean {
        val orchestrator = AgentOrchestrator.getInstance(context)
        val engine = orchestrator.getLlmEngine()

        if (!engine.isModelAvailable(taggerModelKey, context)) {
            Log.w(TAG, "Model not downloaded: $taggerModelKey")
            return false
        }

        // 由 OpenClGuardian 决定使用 OpenCL 还是 CPU（含黑名单、降级冷却、用户偏好）
        val useCpu = openClGuardian.shouldUseCpu()

        // 如果已按 Guardian 策略加载了正确后端，直接复用，避免 Pass 3 每张照片都卸载重装。
        if (engine.isLoaded && engine.isLoadedAs(taggerModelKey, useOpencl = !useCpu)) {
            Log.i(TAG, "Model already loaded with requested backend, reusing")
            return true
        }

        // 如果已加载但后端不匹配（如 OpenCL 与 CPU 切换），先完整卸载再重新加载。
        if (engine.isLoaded) {
            Log.i(TAG, "Model loaded with different backend, unloading before reload")
            engine.unload()
            // unload 通过 backgroundScope.launch 投递到 modelDispatcher 异步执行，
            // 给 modelDispatcher 时间处理 unload 后再投递 loadModel
            delay(1000)
        }

        // OpenCL GPU 路径（如果允许）→ 失败后降级 CPU
        if (!useCpu) {
            Log.i(TAG, "Loading LLM model with OpenCL (GPU): $taggerModelKey")
            val openclResult = orchestrator.ensureModelLoaded(
                modelId = taggerModelKey,
                useOpencl = true,
                caller = "TagGenerationScheduler:OpenCL"
            )
            if (openclResult.isSuccess) {
                Log.i(TAG, "Model loaded with OpenCL (GPU) acceleration")
                // 加载成功后执行轻量 warmup，验证 OpenCL 可实际推理
                val health = openClGuardian.warmup()
                if (health == OpenClHealth.Healthy) {
                    return true
                }
                Log.w(TAG, "OpenCL warmup failed ($health), falling back to CPU")
                engine.unload()
                delay(1000)
            } else {
                Log.w(TAG, "OpenCL load failed: ${openclResult.exceptionOrNull()?.message}, " +
                    "falling back to CPU")
            }
        }

        // CPU 加载（默认路径 + OpenCL 失败/降级）
        Log.i(TAG, "Loading LLM model with CPU: $taggerModelKey")
        val cpuResult = orchestrator.ensureModelLoaded(
            modelId = taggerModelKey,
            useOpencl = false,
            caller = "TagGenerationScheduler:CPU"
        )
        return if (cpuResult.isSuccess) {
            Log.i(TAG, "Model loaded with CPU")
            true
        } else {
            Log.w(TAG, "CPU load failed: ${cpuResult.exceptionOrNull()?.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════
    //  原子任务执行器（供 TagScanOrchestrator 调用）
    // ═══════════════════════════════════════════════════

    /**
     * [原子任务] Pass 1：单张媒体的人脸检测 + Embedding 提取
     */
    suspend fun executeFaceDetection(mediaId: Long) {
        val dao = db.mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return

        val result = pipeline.stage1WithEmbeddings(
            uri = entity.uri,
            lensFacing = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
            mediaId = entity.id
        )

        // 若任务已被取消，丢弃本次结果，避免取消后仍写入数据库
        currentCoroutineContext().ensureActive()

        // 【关键修复】只有当检测到有效 embedding 时才标记 hasFace=true
        val hasValidFace = result.faceRoiJson != null && result.embeddings.isNotEmpty()
        if (result.faceRoiJson != null) {
            dao.updateFaceRoiResult(entity.id, result.faceRoiJson, hasValidFace)
        }

        // 先清除该媒体旧 embedding，避免全量重扫时产生重复记录
        personDao.deleteEmbeddingsByMedia(entity.id)

        // 批量写入 embedding，减少 Room WAL checkpoint 和事务开销。
        if (result.embeddings.isNotEmpty()) {
            val embeddingEntities = result.embeddings.map { embedding ->
                com.mamba.picme.data.local.entity.FaceEmbeddingEntity(
                    mediaId = entity.id,
                    personId = null,
                    embedding = floatArrayToByteArray(embedding)
                )
            }
            personDao.insertEmbeddings(embeddingEntities)
        }

        // MobileCLIP 语义编码已在 stage1WithEmbeddings 中复用 faceBitmap 完成
        val semanticEmbedding = result.semanticEmbedding
        if (semanticEmbedding != null) {
            try {
                dao.updateSemanticEmbedding(entity.id, semanticEmbedding)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist semantic embedding for media ${entity.id}: ${e.message}")
            }
        }

        // 固定轮询间隔（TagScanOrchestrator.POLL_INTERVAL_MS）已提供任务间散热间隙，
        // 原子任务内部不再额外节流，避免把 Pass 1 实际推理时间放大数倍。
    }

    /**
     * [原子任务] Pass 2：人脸聚类（密度自适应 / DBSCAN）
     *
     * @param preserveNamedPersons 是否在聚类前保存已命名人物质心，
     *                             并在聚类后尝试复用 personId/name。
     * @param isFullRescan 是否全量重聚类。为 true 时会在捕获快照后清空旧人物簇、
     *                     重置 embedding 分配并清除媒体上的 faceId。
     */
    suspend fun executeDbscan(preserveNamedPersons: Boolean = false, isFullRescan: Boolean = false) {
        val dao = db.mediaDao()
        val snapshots = if (preserveNamedPersons) buildNamedPersonSnapshots() else emptyList()

        if (isFullRescan) {
            Log.i(TAG, "DBSCAN full rescan: clearing old persons/assignments/faceIds after snapshot capture")
            db.personDao().clearAllPersons()
            db.personDao().resetAllEmbeddingAssignments()
            dao.resetAllFaceIds()
        }

        runDbscanClustering(dao, snapshots)
    }

    /**
     * 构建已命名人物质心快照，供全量重聚类时复用 personId/name。
     */
    private suspend fun buildNamedPersonSnapshots(): List<NamedPersonSnapshot> {
        val persons = personDao.getAllPersons().filter { !it.name.isNullOrBlank() }
        if (persons.isEmpty()) return emptyList()

        val snapshots = mutableListOf<NamedPersonSnapshot>()
        for (person in persons) {
            val embeddings = personDao.getEmbeddingsByPerson(person.personId)
            if (embeddings.isEmpty()) continue
            val centroid = computeCentroid(embeddings.map { byteArrayToFloatArray(it.embedding) })
            snapshots.add(
                NamedPersonSnapshot(
                    personId = person.personId,
                    name = person.name!!,
                    centroid = centroid
                )
            )
        }
        Log.i(TAG, "Built ${snapshots.size} named person snapshots for clustering preservation")
        return snapshots
    }

    /** 计算一组 embedding 的算术平均质心 */
    private fun computeCentroid(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(512)
        val dim = embeddings.first().size
        val sum = FloatArray(dim)
        for (emb in embeddings) {
            for (i in 0 until dim) {
                sum[i] += emb[i]
            }
        }
        for (i in 0 until dim) {
            sum[i] /= embeddings.size
        }
        return sum
    }

    /**
     * [原子任务] Pass 3：单张媒体的 Qwen 标签生成
     *
     * 质量优先方案：使用 SmolVLM/Qwen 视觉语言模型生成 scene/activity/objects/tags/summary。
     * 相比 ML Kit，标签语义更准确，能区分"纸"、"墙"等无意义背景与真实主体。
     */
    suspend fun executeQwenTagging(mediaId: Long) {
        // 接回守卫：热 SEVERE / 电量危机时 ABORT，抛异常 → 任务 FAILED → handleTaskFailure 退避重试（自带散热窗口）。
        // 热 MODERATE / 电量低时 guardCheck 内部已 delay(getThrottleMs())，不抛异常。
        if (!guardCheck()) {
            throw IllegalStateException("[Pass 3] Guard ABORT (thermal/battery) mediaId=$mediaId")
        }

        val dao = db.mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return

        val startMs = System.currentTimeMillis()

        if (!ensureModelLoaded()) {
            // 模型未就绪时抛出异常，使任务标记为 FAILED 并可重试，避免静默跳过导致照片永远未打标。
            throw IllegalStateException("[Pass 3] Model not loaded for mediaId=$mediaId")
        }

        val qwenResult = pipeline.stage3QwenTagging(
            uri = entity.uri,
            faceRoiJson = entity.faceRoiResult
        )

        currentCoroutineContext().ensureActive()

        val faceInfo = parseFaceRoiForUnifiedResult(entity.faceRoiResult, entity.faceId)

        val unified = UnifiedTagResult(
            face = faceInfo,
            scene = qwenResult.scene,
            activity = qwenResult.activity,
            objects = qwenResult.objects,
            tags = qwenResult.tags,
            summary = qwenResult.summary
        )
        dao.updateLabels(entity.id, unifiedTagToJson(unified))

        if (qwenResult.tags.isEmpty() && qwenResult.scene.isBlank() && qwenResult.summary.isBlank()) {
            Log.w(TAG, "[Pass 3] SmolVLM returned empty result for mediaId=$mediaId, " +
                "but labels JSON still written with face info")
        }

        Log.d(TAG, "[Benchmark] Pass 3 (Qwen) done: mediaId=$mediaId, " +
            "durationMs=${System.currentTimeMillis() - startMs}, tags=${qwenResult.tags}")

        // Pass3 连续执行发热严重：每张推理后自适应散热（热状态越高间歇越长）。
        // SEVERE 及以上已由上面的 guardCheck ABORT 兜底，不会走到这里。
        delay(getPass3CooldownMs())
    }

    /**
     * 从 Pass 1 持久化的 faceRoiResult 恢复人脸上下文，并结合 Pass 2 写入的 faceId
     * 组装最终 labels.face 字段。
     */
    private fun parseFaceRoiForUnifiedResult(
        faceRoiJson: String?,
        faceId: String?
    ): FaceTagInfo {
        if (faceRoiJson.isNullOrEmpty()) return FaceTagInfo()
        return try {
            val obj = JSONObject(faceRoiJson)
            val personIds = faceId?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
            FaceTagInfo(
                count = obj.optInt("faceCount", 0),
                selfie = obj.optBoolean("isSelfie", false),
                groupPhoto = obj.optBoolean("isGroupPhoto", false),
                personIds = personIds
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse faceRoi JSON: ${e.message}")
            FaceTagInfo()
        }
    }

    /**
     * [单独重编码任务] 单张媒体的 MobileCLIP 语义编码。
     *
     * 注意：常规扫描已将该阶段内联合并到 Pass 1。此方法保留用于：
     * - 历史 [TagScanPass.MOBILE_CLIP_ENCODING] 任务兼容
     * - 单独对某张媒体重新生成语义编码
     */
    suspend fun executeMobileClipEncoding(mediaId: Long) {
        val dao = db.mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return

        val embedding = pipeline.stage4MobileClipEncoding(entity.uri, entity.id)

        // 若任务已被取消，丢弃本次结果
        currentCoroutineContext().ensureActive()

        if (embedding != null) {
            dao.updateSemanticEmbedding(entity.id, embedding)
        }
    }

    /**
     * 批量 Pass 3 前准备：确保 Qwen 模型已加载
     */
    suspend fun prepareQwenModel(): Boolean = ensureModelLoaded()

    /**
     * 预热 MobileCLIP 标签分类器。
     * 应在 Pass 3 会话开始前调用一次，避免每张图片都重复初始化/预计算文本 embedding。
     */
    fun warmUpMobileClipClassifier(): Boolean {
        return pipeline.warmUpMobileClipClassifier()
    }

    /**
     * 卸载 LLM 模型释放 ~4GB 内存
     *
     * 扫描完成后调用，防止后台服务持续占用内存和散热资源。
     * 使用 [LocalLlmEngine.trimMemory] 清理 KV cache，保留模型权重以
     * 供后续扫描复用。再次进入 Pass 3 时 [ensureModelLoaded] 会负责
     * 按用户偏好选择后端重新加载。
     */
    private fun unloadLlm() {
        try {
            val engine = AgentOrchestrator.getInstance(context).getLlmEngine()
            if (engine.isLoaded) {
                Log.i(TAG, "Unloading LLM model to free memory")
                engine.trimMemory()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unload LLM: ${e.message}")
        }
    }
}
