package com.mamba.picme.domain.tag

import android.graphics.Bitmap
import android.util.Log
import com.mamba.picme.domain.model.AppLanguage

/**
 * MobileCLIP 零 shot 分类输出
 */
data class MobileClipTags(
    val scene: String,
    val objects: List<String>,
    val tags: List<String>
)

/**
 * MobileCLIP 零 shot TAG 分类器
 *
 * 职责：
 * - 启动时预计算 ControlledVocab 中英候选标签的文本 embedding
 * - 对输入图像编码，与候选标签 embedding 计算余弦相似度
 * - 按字段阈值策略输出 Top-K 标签
 * - 支持中文/英文输出；非中英语言返回 null，由调用方回退到 Qwen 全量输出
 */
class MobileClipTagClassifier(
    private val mobileClipEngine: MobileClipEngine,
    private val tokenizer: MobileClipTokenizer,
    private val vocab: ControlledVocab
) {
    companion object {
        private const val TAG = "MobileClipTagClassifier"

        /** scene 字段：Top-1，阈值 0.30 */
        private const val SCENE_TOP_K = 1
        private const val SCENE_THRESHOLD = 0.30f

        /** objects 字段：Top-3，阈值 0.25 */
        private const val OBJECT_TOP_K = 3
        private const val OBJECT_THRESHOLD = 0.25f

        /** tags 字段：Top-5，阈值 0.20 */
        private const val TAG_TOP_K = 5
        private const val TAG_THRESHOLD = 0.20f

        /**
         * 质量门控：top 标签/场景/物体必须达到的最低置信度。
         * 低于该值说明 MobileCLIP 对这张图的判断不可靠，应回退到 Qwen 全量输出。
         */
        private const val SCENE_MIN_CONFIDENCE = 0.40f
        private const val OBJECT_MIN_CONFIDENCE = 0.32f
        private const val TAG_MIN_CONFIDENCE = 0.32f
    }

    private var isReady = false

    /** 中文候选标签文本 embedding 缓存：label -> FloatArray(512) */
    private val textEmbeddingsZh = mutableMapOf<String, FloatArray>()

    /** 英文候选标签文本 embedding 缓存：label -> FloatArray(512) */
    private val textEmbeddingsEn = mutableMapOf<String, FloatArray>()

    /**
     * 预热：加载 MobileCLIP 模型并预计算中英所有候选标签的文本 embedding
     *
     * @return 是否成功。失败时调用方应回退到 Qwen 全量输出。
     */
    fun warmUp(): Boolean {
        if (isReady) return true

        if (!mobileClipEngine.initializeWithFallback()) {
            Log.w(TAG, "MobileClipEngine initialization failed")
            return false
        }

        if (!tokenizer.load()) {
            Log.w(TAG, "MobileClipTokenizer load failed")
            return false
        }

        val zhCandidates = vocab.sceneCandidates + vocab.objectCandidates + vocab.tagCandidates
        val enCandidates = vocab.sceneCandidatesEn + vocab.objectCandidatesEn + vocab.tagCandidatesEn
        val distinctZh = zhCandidates.distinct()
        val distinctEn = enCandidates.distinct()
        Log.i(TAG, "Precomputing text embeddings: zh=${distinctZh.size}, en=${distinctEn.size}")

        val zhFailed = precomputeEmbeddings(distinctZh, textEmbeddingsZh)
        val enFailed = precomputeEmbeddings(distinctEn, textEmbeddingsEn)

        if (textEmbeddingsZh.isEmpty() && textEmbeddingsEn.isEmpty()) {
            Log.w(TAG, "No text embeddings computed, classifier unusable")
            return false
        }

        if (zhFailed > 0 || enFailed > 0) {
            Log.w(TAG, "Embedding failures: zh=$zhFailed/${distinctZh.size}, en=$enFailed/${distinctEn.size}")
        }

        isReady = true
        Log.i(TAG, "Warmup complete: zh=${textEmbeddingsZh.size}, en=${textEmbeddingsEn.size} text embeddings cached")
        return true
    }

    private fun precomputeEmbeddings(
        candidates: List<String>,
        cache: MutableMap<String, FloatArray>
    ): Int {
        var failed = 0
        for (label in candidates) {
            val tokenIds = tokenizer.encode(label) ?: run {
                failed++
                continue
            }
            val embedding = mobileClipEngine.encodeText(tokenIds) ?: run {
                failed++
                continue
            }
            cache[label] = embedding
        }
        return failed
    }

    /**
     * 对单张图像进行分类（按当前目标语言返回对应语言标签）
     *
     * @param lang 目标语言，文本嵌入仅有中文/英文两套；[AppLanguage.SPANISH] /
     *   [AppLanguage.FRENCH] 回退英文嵌入，其余非中英语言返回 null
     * @return MobileClipTags，失败或不支持的语言返回 null
     */
    fun classify(bitmap: Bitmap, lang: AppLanguage): MobileClipTags? {
        if (!isReady) {
            Log.w(TAG, "Classifier not warmed up")
            return null
        }

        // 西语/法语无独立文本嵌入，回退英文
        val effectiveLang = when (lang) {
            AppLanguage.SPANISH, AppLanguage.FRENCH -> AppLanguage.ENGLISH
            else -> lang
        }
        if (effectiveLang != AppLanguage.CHINESE && effectiveLang != AppLanguage.ENGLISH) {
            Log.w(TAG, "Unsupported language for MobileCLIP classification: $lang")
            return null
        }

        val imageEmbedding = mobileClipEngine.encodeImage(bitmap) ?: run {
            Log.w(TAG, "Failed to encode image")
            return null
        }

        val embeddings = if (effectiveLang == AppLanguage.ENGLISH) textEmbeddingsEn else textEmbeddingsZh
        val blocked = if (effectiveLang == AppLanguage.ENGLISH) vocab.blockedTagsEn else vocab.blockedTags

        val sceneResults = topK(SCENE_TOP_K, SCENE_THRESHOLD, vocab.sceneCandidates(effectiveLang), imageEmbedding, embeddings, blocked)
        val objectResults = topK(OBJECT_TOP_K, OBJECT_THRESHOLD, vocab.objectCandidates(effectiveLang), imageEmbedding, embeddings, blocked)
        val tagResults = topK(TAG_TOP_K, TAG_THRESHOLD, vocab.tagCandidates(effectiveLang), imageEmbedding, embeddings, blocked)

        val scene = sceneResults.firstOrNull()?.first ?: ""
        val sceneScore = sceneResults.firstOrNull()?.second ?: 0f
        val objects = objectResults.map { it.first }
        val objectScore = objectResults.firstOrNull()?.second ?: 0f
        val tags = tagResults.map { it.first }
        val tagScore = tagResults.firstOrNull()?.second ?: 0f

        // 质量门控：top 匹配分数过低时，说明 MobileCLIP 对当前图像不可靠，回退 Qwen 全量输出
        val qualityOk = sceneScore >= SCENE_MIN_CONFIDENCE &&
            (objectResults.isEmpty() || objectScore >= OBJECT_MIN_CONFIDENCE) &&
            (tagResults.isEmpty() || tagScore >= TAG_MIN_CONFIDENCE)
        Log.d(
            TAG,
            "MobileCLIP scores: scene=$scene($sceneScore), " +
                "topObject=$objectScore, topTag=$tagScore, tags=$tags, qualityOk=$qualityOk"
        )
        if (!qualityOk) {
            Log.w(TAG, "MobileCLIP quality gate failed, falling back to Qwen full output")
            return null
        }

        return MobileClipTags(scene = scene, objects = objects, tags = tags)
    }

    /**
     * 从指定候选集中选取与图像相似度最高的 Top-K 标签，过滤低于阈值或被屏蔽的标签。
     * 返回带分数的结果，供质量门控使用。
     */
    private fun topK(
        k: Int,
        threshold: Float,
        candidates: List<String>,
        imageEmbedding: FloatArray,
        embeddings: Map<String, FloatArray>,
        blocked: List<String>
    ): List<Pair<String, Float>> {
        val scored = candidates.mapNotNull { label ->
            if (label in blocked) return@mapNotNull null
            val textEmbedding = embeddings[label] ?: return@mapNotNull null
            val sim = cosineSimilarity(imageEmbedding, textEmbedding)
            if (sim >= threshold) label to sim else null
        }
        return scored.sortedByDescending { it.second }
            .take(k)
    }

    /**
     * 计算两个 L2 归一化向量的余弦相似度
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot.coerceIn(-1f, 1f)
    }
}
