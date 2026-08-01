package com.mamba.picme.domain.search

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.tag.MobileClipEngine
import com.mamba.picme.domain.tag.MobileClipTokenizer
import com.mamba.picme.domain.tag.i18n.ChineseQueryTranslator

/**
 * MobileCLIP 语义搜索引擎
 *
 * 实现文本→图像、图像→图像的跨模态语义搜索：
 * 1. 文本编码：Tokenizer → tokenIds → MobileClipEngine.encodeText() → 512-dim embedding
 * 2. 图像编码：MobileClipEngine.encodeImage() → 512-dim embedding
 * 3. 相似度计算：余弦相似度 → Top-K 排序
 *
 * 与 MediaSearchEngine 协同工作，作为三层混合检索中的"语义召回"层。
 *
 * @param context Application Context（用于初始化 MobileClipEngine 和 MobileClipTokenizer）
 * @param mediaDao 媒体数据访问对象
 * @param mobileClipEngine MobileCLIP 编码引擎（外部注入，可复用已有实例）
 */
private const val SEMANTIC_EMBEDDING_DIM = 512

/**
 * 语义召回最低余弦相似度阈值：低于此值视为噪声丢弃。
 *
 * MobileCLIP 真相关通常 >0.3；无相关时 topK 会被低相似度噪声（如 0.05~0.25 的
 * 「推土机」对「动物」）填满。此阈值过滤噪声，避免「没找到」时返回垃圾误导用户。
 * 定为 0.22：过滤明显噪声，保留一定语义召回余量。
 *
 * 注意：人物/概念查询（如「小孩」）区分力弱——真相关与噪声大量重叠在 0.22~0.30。
 * 设备实测 0.30 过激：「小孩」51→1 张（真孩子大多落 0.22~0.30，被一并滤掉），
 * 即阈值无法干净区分；人物精确召回应走人脸聚类（FaceClusterEngine / Pass 1/2），
 * 不应依赖此处文本相似度。
 */
private const val MIN_SIMILARITY = 0.22f

class SemanticSearchEngine(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val mobileClipEngine: MobileClipEngine? = null,
    private val queryTranslator: ChineseQueryTranslator? = null,
    private val mobileClipTokenizer: MobileClipTokenizer? = null
) {
    companion object {
        private const val TAG = "SemanticSearchEngine"
    }

    /** Tokenizer（延迟加载） */
    private val tokenizer: MobileClipTokenizer by lazy {
        mobileClipTokenizer ?: MobileClipTokenizer(context)
    }

    /** 本地持有的 MobileClipEngine 实例（如果外部未注入） */
    private val engine: MobileClipEngine by lazy {
        mobileClipEngine ?: MobileClipEngine(context)
    }

    /** 中文查询翻译器（如果外部未注入） */
    private val translator: ChineseQueryTranslator by lazy {
        queryTranslator ?: ChineseQueryTranslator(context)
    }

    /** 引擎是否已初始化（vision/text 模型 + tokenizer 均就绪，可用于文本语义搜索） */
    val isReady: Boolean
        get() = engine.isInitialized && engine.isTextLoaded && tokenizer.isReady()

    /**
     * 初始化引擎（加载模型和 tokenizer）
     *
     * @Synchronized：多层召回并行搜索时防止 check-then-act 竞态重复加载
     * MobileCLIP 模型与 tokenizer（每份占数百 MB native + 数十 MB Java 堆）。
     *
     * @param useGpu 是否尝试使用 GPU 加载 vision 模型
     * @return 是否成功
     */
    @Synchronized
    fun initialize(useGpu: Boolean = false): Boolean {
        // 1. 初始化 MobileClipEngine（加载 ONNX 模型）
        if (!engine.isInitialized) {
            if (!engine.initialize(useGpu)) {
                Log.w(TAG, "Failed to initialize MobileClipEngine")
                return false
            }
        }

        // 2. 加载 tokenizer
        if (!tokenizer.isReady()) {
            if (!tokenizer.load()) {
                Log.w(TAG, "Failed to load tokenizer")
                return false
            }
        }

        Log.i(TAG, "SemanticSearchEngine initialized (vocab=${tokenizer.vocabSize()})")
        return true
    }

    /**
     * 文本→图像语义搜索
     *
     * 执行流程：
     * 1. Tokenizer 编码查询文本 → tokenIds
     * 2. MobileClipEngine.encodeText() → textEmbedding
     * 3. 从数据库获取候选集（可选结构化过滤）
     * 4. 计算余弦相似度并排序
     * 5. 返回 Top-K 结果
     *
     * @param query 用户输入的自然语言查询（如"温馨的家庭聚餐"）
     * @param filter 结构化过滤条件（时间/地点/人脸），用于先缩小候选集范围
     * @param topK 返回结果数量上限
     * @return 按语义相似度排序的带分媒体列表
     */
    suspend fun searchByText(
        query: String,
        filter: StructuredFilter? = null,
        topK: Int = 50
    ): List<SemanticScoredMedia> {
        if (!isReady && !initialize()) {
            Log.w(TAG, "Engine not ready, skipping semantic search")
            return emptyList()
        }

        if (query.isBlank()) return emptyList()

        // 1. 中文查询翻译 + 同义扩展（如 "小孩" -> ["child", "kid", "children", ...]）
        val queryCandidates = translator.expandForClip(query)
        if (queryCandidates.isEmpty()) {
            Log.w(TAG, "No query candidates for: $query")
            return emptyList()
        }

        // 2. 对每个候选编码为 text embedding
        val textEmbeddings = queryCandidates.mapNotNull { candidate ->
            encodeTextQuery(candidate)?.also { emb ->
                Log.d(TAG, "Encoded query candidate: '$candidate' dim=${emb.size} " +
                    "norm=${String.format("%.4f", emb.norm())} " +
                    "first5=${emb.take(5).joinToString { String.format("%.3f", it) }}")
            }
        }
        if (textEmbeddings.isEmpty()) {
            Log.w(TAG, "Failed to encode any text query candidates: $queryCandidates")
            return emptyList()
        }

        // 3. 获取候选集
        val candidates = getCandidates(filter)
        if (candidates.isEmpty()) {
            Log.d(TAG, "No candidates found for semantic search")
            return emptyList()
        }

        // 4. 计算余弦相似度：同一图片取多个候选中的最大相似度
        var nanCount = 0
        var zeroNormCount = 0
        val allScores = mutableListOf<Float>()
        val scoredResults = candidates
            .mapNotNull { entity ->
                val imageEmbedding = base64ToFloatArray(entity.semanticEmbedding)
                if (imageEmbedding == null) {
                    Log.w(TAG, "Invalid image embedding for mediaId=${entity.id}")
                    return@mapNotNull null
                }
                if (imageEmbedding.norm() < 1e-6f) {
                    zeroNormCount++
                    return@mapNotNull null
                }
                val maxSimilarity = textEmbeddings.maxOf { engine.cosineSimilarity(it, imageEmbedding) }
                if (maxSimilarity.isNaN()) {
                    nanCount++
                    Log.w(TAG, "NaN similarity for mediaId=${entity.id}")
                    return@mapNotNull null
                }
                allScores.add(maxSimilarity)
                SemanticScoredMedia(entity.toDomain(), maxSimilarity)
            }
            .sortedByDescending { it.score }
            .filter { scored -> scored.score >= MIN_SIMILARITY }
            .take(topK)

        // 日志：展示召回结果详情与相似度分布
        val scoreStats = if (allScores.isNotEmpty()) {
            "min=${String.format("%.3f", allScores.minOrNull() ?: 0f)}, " +
                "max=${String.format("%.3f", allScores.maxOrNull() ?: 0f)}, " +
                "avg=${String.format("%.3f", allScores.average())}, " +
                "median=${String.format("%.3f", allScores.sorted().let { it[it.size / 2] })}"
        } else "no valid scores"
        if (scoredResults.isNotEmpty()) {
            val topResults = scoredResults.take(3).joinToString { "${it.media.fileName}=${String.format("%.3f", it.score)}" }
            Log.i(TAG, "Semantic recall: query='$query' -> candidates=${queryCandidates}, embeddings=${textEmbeddings.size}, imageCandidates=${candidates.size}, returned=${scoredResults.size}, stats=[$scoreStats], nan=$nanCount, zeroNorm=$zeroNormCount, top3=[$topResults]")
        } else {
            Log.w(TAG, "Semantic recall empty: query='$query' -> candidates=${queryCandidates}, imageCandidates=${candidates.size}, stats=[$scoreStats], nan=$nanCount, zeroNorm=$zeroNormCount")
        }

        return scoredResults
    }

    /**
     * 以图搜图
     *
     * @param bitmap 查询图像
     * @param filter 结构化过滤条件
     * @param topK 返回结果数量上限
     * @return 按语义相似度排序的带分媒体列表
     */
    suspend fun searchByImage(
        bitmap: Bitmap,
        filter: StructuredFilter? = null,
        topK: Int = 50
    ): List<SemanticScoredMedia> {
        if (!isReady && !initialize()) {
            Log.w(TAG, "Engine not ready, skipping image search")
            return emptyList()
        }

        // 1. 图像编码
        val imageEmbedding = engine.encodeImage(bitmap) ?: run {
            Log.w(TAG, "Failed to encode query image")
            return emptyList()
        }

        // 2. 获取候选集
        val candidates = getCandidates(filter)
        if (candidates.isEmpty()) return emptyList()

        // 3. 计算余弦相似度并排序
        return candidates
            .mapNotNull { entity ->
                val candidateEmbedding = base64ToFloatArray(entity.semanticEmbedding) ?: return@mapNotNull null
                val similarity = engine.cosineSimilarity(imageEmbedding, candidateEmbedding)
                if (similarity.isNaN()) {
                    Log.w(TAG, "NaN similarity for mediaId=${entity.id}")
                    return@mapNotNull null
                }
                SemanticScoredMedia(entity.toDomain(), similarity)
            }
            .sortedByDescending { it.score }
            .filter { scored -> scored.score >= MIN_SIMILARITY }
            .take(topK)
            .also {
                Log.d(TAG, "Image search: candidates=${candidates.size}, results=${it.size}")
            }
    }

    /**
     * 编码查询文本为 embedding（供外部复用）
     */
    fun encodeTextQuery(query: String): FloatArray? {
        if (!isReady && !initialize()) return null

        // CLIP 训练用的是完整 caption（如 "a photo of a cat"），裸词（如 "Animals."）
        // 处于分布外，余弦相似度会系统性偏低。包成标准 prompt 对齐训练分布，提升召回相似度。
        val concept = query.trim().trimEnd('.', ' ').lowercase()
            .removePrefix("a ").removePrefix("an ").removePrefix("the ")
        val prompted = if (concept.isEmpty()) query else "a photo of a $concept"

        // 1. Tokenizer → tokenIds
        val tokenIds = tokenizer.encode(prompted) ?: return null

        // 2. MobileClipEngine → textEmbedding
        return engine.encodeText(tokenIds)
    }

    /**
     * 获取候选集（支持结构化过滤先缩小范围）
     *
     * 使用 ID-based 分页方式避免 OOM：
     * - 大 gallery（10K+ 照片）不再一次性加载所有实体到内存
     * - 通过 ID 列表 + 分批获取控制内存峰值
     */
    private suspend fun getCandidates(filter: StructuredFilter?): List<MediaEntity> {
        return if (filter != null) {
            getFilteredCandidates(filter)
        } else {
            // 使用 ID-based 方式，防止大 gallery OOM
            val ids = mediaDao.getMediaWithSemanticEmbeddingIds()
            if (ids.isEmpty()) return emptyList()
            mediaDao.getMediaByIds(ids)
        }
    }

    /**
     * 根据结构化过滤条件获取候选集（ID-based + 分页）。
     *
     * 语义召回层必须尊重所有**结构化**过滤条件（时间/人脸/OCR/地点），
     * 否则无关图片可能因 CLIP 相似度偏高进入最终结果。
     *
     * 注意：[filter.keywords] 用于 SQL 标签/文件名搜索，在语义召回中**故意忽略**：
     * 语义搜索的价值正是跨越标签词汇鸿沟，匹配标签里没有出现过的词。
     */
    private suspend fun getFilteredCandidates(filter: StructuredFilter): List<MediaEntity> {
        // 1. 基础候选集：优先用时间范围缩小，否则取全量有 embedding 的 ID
        val candidateIds = if (filter.timeRange != null) {
            mediaDao.getMediaIdsByTimeRange(filter.timeRange.startMs, filter.timeRange.endMs)
                .toSet()
        } else {
            mediaDao.getMediaWithSemanticEmbeddingIds().toSet()
        }

        if (candidateIds.isEmpty()) {
            Log.d(TAG, "No semantic candidates after base filtering")
            return emptyList()
        }

        // 2. 结构化过滤：人脸/OCR/地点在 SQL 层面用 ID 过滤
        val filteredIds = applyStructuredIdFilter(candidateIds, filter)

        if (filteredIds.isEmpty()) return emptyList()

        // 3. 仅获取最终候选集（内存友好，避免加载全量实体）
        return mediaDao.getMediaByIds(filteredIds.toList())
            .filter { !it.semanticEmbedding.isNullOrBlank() }
            .also {
                Log.d(TAG, "Semantic candidate filter: base=${candidateIds.size}, " +
                    "afterStructured=${it.size}, " +
                    "ignoredKeywords=${filter.keywords}")
            }
    }

    /**
     * 在候选 ID 集上应用结构化过滤。
     * 使用 ID-based 交集操作，避免在内存中过滤全量实体。
     */
    private suspend fun applyStructuredIdFilter(
        candidateIds: Set<Long>,
        filter: StructuredFilter
    ): Set<Long> {
        var result = candidateIds

        // 人脸过滤
        if (filter.hasFaces == true) {
            val faceIds = mediaDao.getHasFaceIds().toSet()
            result = result.intersect(faceIds)
            if (result.isEmpty()) return emptySet()
        }

        // OCR 关键词（SQL 层面过滤，不加载实体到内存）
        if (!filter.ocrKeywords.isNullOrEmpty()) {
            val ocrMatchedIds = mutableSetOf<Long>()
            for (keyword in filter.ocrKeywords) {
                if (keyword.isNotBlank()) {
                    mediaDao.searchOcrInIds(result.toList(), keyword)
                        .map { it.id }
                        .forEach { ocrMatchedIds.add(it) }
                }
            }
            result = result.intersect(ocrMatchedIds)
            if (result.isEmpty()) return emptySet()
        }

        // 地点关键词（SQL 层面过滤）
        if (!filter.locationKeywords.isNullOrEmpty()) {
            val locMatchedIds = mutableSetOf<Long>()
            for (keyword in filter.locationKeywords) {
                if (keyword.isNotBlank()) {
                    mediaDao.getMediaIdsByLocationKeyword(keyword)
                        .forEach { locMatchedIds.add(it) }
                }
            }
            result = result.intersect(locMatchedIds)
        }

        return result
    }

    /**
     * 释放引擎资源
     */
    fun release() {
        // 仅释放自己创建的 engine，外部注入的不释放
        if (mobileClipEngine == null) {
            engine.release()
        }
        // 仅释放自己创建的 translator
        if (queryTranslator == null) {
            translator.release()
        }
        Log.i(TAG, "SemanticSearchEngine released")
    }
}

/**
 * 带语义相似度分数的媒体结果（语义搜索专用）
 */
data class SemanticScoredMedia(
    val media: MediaAsset,
    val score: Float
)

/**
 * Base64 编码的 semanticEmbedding → FloatArray 反序列化
 *
 * 编码方案（大端序 float32）：
 * FloatArray → ByteArray（每 float 4 bytes，MSB 先写）→ Base64.NO_WRAP
 */
private fun base64ToFloatArray(base64String: String?): FloatArray? {
    if (base64String.isNullOrBlank()) return null

    return try {
        val bytes = android.util.Base64.decode(base64String, android.util.Base64.NO_WRAP)
        if (bytes.size % 4 != 0) {
            Log.w("SemanticSearchEngine", "Invalid embedding size: ${bytes.size}")
            return null
        }

        val floatCount = bytes.size / 4
        if (floatCount != SEMANTIC_EMBEDDING_DIM) {
            Log.w("SemanticSearchEngine", "Invalid embedding dimension: $floatCount, expected $SEMANTIC_EMBEDDING_DIM")
            return null
        }

        FloatArray(floatCount) { i ->
            val b0 = bytes[i * 4].toInt() and 0xFF
            val b1 = bytes[i * 4 + 1].toInt() and 0xFF
            val b2 = bytes[i * 4 + 2].toInt() and 0xFF
            val b3 = bytes[i * 4 + 3].toInt() and 0xFF
            val bits = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            java.lang.Float.intBitsToFloat(bits)
        }
    } catch (e: Exception) {
        Log.w("SemanticSearchEngine", "Failed to decode embedding", e)
        null
    }
}

/**
 * MediaEntity → MediaAsset 转换（用于搜索结果）
 */
private fun MediaEntity.toDomain() =
    MediaAsset(
        id = id,
        uri = uri,
        type = type,
        captureDate = captureDate,
        fileName = fileName,
        duration = duration,
        hasFace = hasFace,
        faceId = faceId,
        faceFocusY = faceFocusY,
        source = source,
        labels = labels,
        ocrText = ocrText,
        latitude = latitude,
        longitude = longitude,
        locationName = locationName,
        indexedAt = indexedAt
    )

/**
 * FloatArray L2 范数（调试用）
 */
private fun FloatArray.norm(): Float {
    var sum = 0f
    for (v in this) {
        if (!v.isNaN() && !v.isInfinite()) {
            sum += v * v
        }
    }
    return kotlin.math.sqrt(sum)
}
