package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.beauty.api.facedetect.FaceDetection
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.tag.prompt.DefaultTagPromptProvider
import com.mamba.picme.domain.tag.prompt.TagPromptProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 三阶段 Tag 生成管道
 *
 * ```
 * Stage 1 (Face ROI) ─── 有人脸? ──→ YES ──→ Stage 2 (Face Cluster)
 *      │                                         │
 *      │ NO                                      │
 *      ↓                                         ↓
 * Stage 3 (Qwen without face context)    Stage 3 (Qwen with face context)
 * ```
 *
 * 依赖注入：
 * - [faceDetector]：Stage 1 使用，RetinaFace Det500M
 * - [llmEngine]：Stage 3 使用，Qwen3.5-2B MNN
 * - [faceClusterEngine]：Stage 2 使用，MobileFaceNet + 增量聚类
 * - [normalizer]：标签后处理规范化
 * - [openClGuardian]：OpenCL 超时守卫（可选）
 * - [userSettingsRepository]：用户设置（语言偏好等）
 * - [promptProvider]：Prompt 生成策略
 * - [mobileClipEngine]：MobileCLIP 语义编码（可选）
 */
class TagGenerationPipeline(
    private val context: Context,
    private val faceDetector: FaceDetector,
    private val llmEngine: LocalLlmEngine,
    private val faceClusterEngine: FaceClusterEngine,
    private val normalizer: TagNormalizer,
    private val openClGuardian: OpenClGuardian? = null,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val promptProvider: TagPromptProvider = DefaultTagPromptProvider(),
    private val mobileClipEngine: MobileClipEngine? = null,
    private val mlKitTagExtractor: MlKitTagExtractor? = null
) {

    companion object {
        private const val TAG = "TagPipeline"

        /** 人脸检测前的图片最长边缩放 */
        private const val MAX_FACE_DETECT_SIZE = 640

        /** Qwen 图像推理的图片最长边缩放 */
        private const val MAX_VISION_SIZE = 512

        /** Qwen Stage 3 最大输出 token 数（从 128 增至 256 以支持丰富标签） */
        private const val QWEN_MAX_TOKENS = 256
    }

    /** 当前生成目标语言，由用户设置决定 */
    private val targetLanguage: AppLanguage
        get() = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

    private val stage3SystemPrompt: String
        get() = promptProvider.systemPrompt(targetLanguage)

    /**
     * 单张照片完整处理管道
     *
     * @param uri 照片 Content URI
     * @param lensFacing 镜头方向（CameraSelector.LENS_FACING_BACK/FRONT）
     * @param mediaId 数据库中的媒体 ID
     * @return 最终写入 labels 字段的 JSON 字符串
     */
    suspend fun processPhoto(
        uri: String,
        lensFacing: Int,
        mediaId: Long
    ): String {
        Log.d(TAG, "=== Pipeline start: mediaId=$mediaId ===")

        // 一次性加载 640px Bitmap，Stage 1 和 Stage 2 共用
        val faceBitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE)
        if (faceBitmap == null) {
            Log.w(TAG, "Failed to load bitmap for mediaId=$mediaId")
            return """{"face":{"count":0}}"""
        }

        val stage1Result: Stage1Result
        val stage2Result: Stage2Result?

        try {
            // ── Stage 1: 轻量人脸 ROI 检测（复用 faceBitmap）───
            stage1Result = stage1FaceDetection(faceBitmap)
            Log.d(TAG, "Stage 1 done: hasFace=${stage1Result.hasFace}, count=${stage1Result.faceCount}")

            // ── Stage 2: 人脸聚类（复用同一个 faceBitmap，不再重新解码）───
            stage2Result = if (stage1Result.hasFace) {
                stage2FaceCluster(faceBitmap, mediaId, stage1Result)
            } else {
                null
            }
            Log.d(TAG, "Stage 2 done: personIds=${stage2Result?.personIds ?: "N/A"}")
        } finally {
            faceBitmap.recycle()
        }

        // ── Stage 3: Qwen 图像理解（独立加载 512px Bitmap，尺寸要求不同）───
        val stage3Result = stage3QwenTagging(uri, stage1Result, stage2Result)
        Log.d(TAG, "Stage 3 done: scene=${stage3Result.scene}, tags=${stage3Result.tags}")

        // ── 组装最终结果 ───────────────────────────────────
        val faceIds = stage2Result?.personIds ?: emptyList()
        val unified = UnifiedTagResult(
            face = FaceTagInfo(
                count = stage1Result.faceCount,
                selfie = stage1Result.isSelfie,
                groupPhoto = stage1Result.isGroupPhoto,
                personIds = faceIds
            ),
            scene = stage3Result.scene,
            activity = stage3Result.activity,
            objects = stage3Result.objects,
            tags = stage3Result.tags,
            qwenSummary = stage3Result.summary
        )

        val resultJson = toJsonString(unified)
        Log.d(TAG, "=== Pipeline done: $resultJson ===")
        return resultJson
    }

    // ═══════════════════════════════════════════════════
    //  [Pass 1] 人脸检测 + Embedding 提取（供 3-Pass 混合扫描用）
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 1] 单张照片的人脸检测 + MobileFaceNet Embedding 提取
     *
     * 结果持久化（faceRoiJson 字段）供 Pass 3 构造人脸上下文。
     * Embedding 由调度器写入 face_embeddings 表供 Pass 2 DBSCAN。
     *
     * @param uri 照片 Content URI
     * @param lensFacing 镜头方向
     * @param mediaId 媒体 ID
     * @return 包含 faceRoi JSON 和每张人脸的 embedding 列表
     */
    suspend fun stage1WithEmbeddings(
        uri: String,
        lensFacing: Int,
        mediaId: Long
    ): Stage1WithEmbeddingsResult {
        val faceBitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE)
        if (faceBitmap == null) {
            Log.w(TAG, "[Pass 1] Failed to load bitmap for mediaId=$mediaId")
            return Stage1WithEmbeddingsResult(null, emptyList())
        }

        try {
            val stage1Result = stage1FaceDetection(faceBitmap)
            Log.d(TAG, "[Pass 1] Stage 1 done: hasFace=${stage1Result.hasFace}, count=${stage1Result.faceCount}")

            val faceRoiJson = faceRoiToJson(stage1Result)

            if (!stage1Result.hasFace) {
                return Stage1WithEmbeddingsResult(faceRoiJson, emptyList())
            }

            // 提取每张人脸的 512 维 embedding，过滤零向量
            val embeddings = mutableListOf<FloatArray>()
            for (face in stage1Result.faces) {
                val feature = faceClusterEngine.extractFeature(
                    faceBitmap, face.roi, face.landmarks5
                )
                if (!isZeroVector(feature)) {
                    embeddings.add(feature)
                } else {
                    Log.w(TAG, "[Pass 1] Zero vector embedding skipped for mediaId=$mediaId, roi=${face.roi}")
                }
            }

            // 复用同一张 faceBitmap 做 MobileCLIP 语义编码，避免二次解码图片
            val semanticEmbedding = stage4MobileClipEncoding(
                uri = uri,
                mediaId = mediaId,
                reuseBitmap = faceBitmap
            )

            Log.d(TAG, "[Pass 1] Extracted ${embeddings.size} valid embeddings for mediaId=$mediaId, " +
                "semanticEmbedding=${if (semanticEmbedding != null) "ok" else "null"}")
            return Stage1WithEmbeddingsResult(faceRoiJson, embeddings, semanticEmbedding)
        } finally {
            faceBitmap.recycle()
        }
    }

    // ═══════════════════════════════════════════════════
    //  [Pass 3] Qwen 图像理解标签生成（可断点续扫）
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 3] Qwen3.5-2B 图像理解标签生成
     *
     * 使用 Pass 1 持久化的 faceRoiJson 恢复人脸上下文。
     * 不依赖传递性 Stage1Result 对象，天然支持断点续扫。
     *
     * @param uri 照片 Content URI
     * @param faceRoiJson Pass 1 持久化的人脸 ROI JSON（null=解码失败）
     * @return 规范化后的标签结果
     */
    suspend fun stage3QwenTagging(
        uri: String,
        faceRoiJson: String?
    ): QwenTagsNormalized {
        val bitmap = loadBitmap(uri, MAX_VISION_SIZE)
        if (bitmap == null) {
            Log.w(TAG, "[Pass 3] Failed to load bitmap, returning empty tags")
            return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
        }

        return try {
            if (!llmEngine.isLoaded) {
                Log.w(TAG, "[Pass 3] LLM not loaded, skipping Qwen tagging")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
            }

            // 从 JSON 恢复人脸上下文
            val faceRoi = faceRoiJson?.let { parseFaceRoi(it) }

            // 构造 user prompt（融入人脸上下文）
            val userPrompt = if (faceRoi != null && faceRoi.hasFace) {
                promptProvider.userPrompt(targetLanguage, faceRoi.faceCount, faceRoi.isGroupPhoto)
            } else {
                promptProvider.userPrompt(targetLanguage, 0, false)
            }

            val response = runVisionInference(bitmap, userPrompt)

            if (response.isBlank()) {
                Log.w(TAG, "[Pass 3] empty response from LLM")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
            }

            Log.d(TAG, "[Pass 3] raw response: $response")

            val jsonPart = extractJson(response)
            if (jsonPart == null) {
                Log.w(TAG, "[Pass 3] failed to extract JSON from response")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "", listOf(response))
            }

            val qwenTags = parseQwenResponse(jsonPart)
            if (qwenTags == null) {
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "", listOf(response))
            }

            normalizer.normalize(qwenTags)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 释放 MobileCLIP 引擎资源
     */
    fun releaseMobileClip() {
        mobileClipEngine?.release()
    }

    /**
     * 提取 ML Kit Image Labeler 英文标签
     *
     * @param uri 照片 Content URI
     * @return 英文标签列表，失败返回空列表
     */
    fun extractMlKitLabels(uri: String): List<String> {
        val extractor = mlKitTagExtractor ?: run {
            Log.w(TAG, "[ML Kit] MlKitTagExtractor not available")
            return emptyList()
        }
        return extractor.extract(uri)
    }

    // ═══════════════════════════════════════════════════
    //  MobileCLIP 语义编码（已内联合并到 Pass 1，保留方法用于单独重编码）
    // ═══════════════════════════════════════════════════

    /**
     * MobileCLIP 语义编码
     *
     * 使用 MobileCLIP-S2 生成 512 维 L2 归一化图像 embedding，
     * 存储为 Base64 字符串供语义搜索使用。
     *
     * 说明：常规扫描已将该阶段内联合并到 Pass 1。此方法保留用于：
     * - Pass 1 内联调用
     * - 单独对某张或某批媒体重新生成语义编码
     *
     * 已优化：支持复用已加载的 Bitmap，避免重复解码。
     *
     * @param uri 照片 Content URI
     * @param mediaId 媒体 ID
     * @param reuseBitmap 复用的 Bitmap（如 Pass 1 已加载的 640px Bitmap），null 则重新加载
     * @return Base64 编码的 embedding 字符串，失败返回 null
     */
    suspend fun stage4MobileClipEncoding(
        uri: String,
        mediaId: Long,
        reuseBitmap: Bitmap? = null
    ): String? {
        val engine = mobileClipEngine ?: run {
            Log.w(TAG, "[MobileCLIP] MobileClipEngine not available")
            return null
        }

        if (!engine.isInitialized) {
            Log.w(TAG, "[MobileCLIP] MobileClipEngine not initialized, attempting init")
            if (!engine.initializeWithFallback()) {
                Log.w(TAG, "[MobileCLIP] Failed to initialize MobileClipEngine")
                return null
            }
        }

        val bitmap = reuseBitmap ?: loadBitmap(uri, MAX_VISION_SIZE)
        if (bitmap == null) {
            Log.w(TAG, "[MobileCLIP] Failed to load bitmap for mediaId=$mediaId")
            return null
        }

        return try {
            val embedding = engine.encodeImage(bitmap)
            if (embedding == null) {
                Log.w(TAG, "[MobileCLIP] encodeImage returned null for mediaId=$mediaId")
                return null
            }

            // 二次校验：确保入库前 embedding 是有效且已归一化的
            if (!isValidEmbedding(embedding)) {
                Log.w(TAG, "[MobileCLIP] Invalid embedding rejected for mediaId=$mediaId")
                return null
            }

            // 编码为 Base64 字符串存储
            val base64 = floatArrayToBase64(embedding)
            Log.d(TAG, "[MobileCLIP] Encoded embedding for mediaId=$mediaId, dim=${embedding.size}, base64_len=${base64.length}")
            base64
        } finally {
            // 仅当 bitmap 是自己加载的才回收，外部传入的不负责回收
            if (reuseBitmap == null) {
                bitmap.recycle()
            }
        }
    }

    /**
     * 校验 embedding 是否可用于入库。
     *
     * 注意：MobileClipEngine 已在返回前做强制 L2 归一化，这里做最终守门检查。
     */
    private fun isValidEmbedding(embedding: FloatArray): Boolean {
        if (embedding.size != 512) return false
        var norm = 0f
        for (v in embedding) {
            if (v.isNaN() || v.isInfinite()) return false
            norm += v * v
        }
        // L2 归一化后 norm 应接近 1.0；允许小误差，拒绝零向量
        return norm > 0.8f
    }

    /**
     * 将 FloatArray 编码为 Base64 字符串
     */
    private fun floatArrayToBase64(array: FloatArray): String {
        val bytes = ByteArray(array.size * 4)
        for (i in array.indices) {
            val bits = java.lang.Float.floatToRawIntBits(array[i])
            bytes[i * 4] = (bits shr 24).toByte()
            bytes[i * 4 + 1] = (bits shr 16).toByte()
            bytes[i * 4 + 2] = (bits shr 8).toByte()
            bytes[i * 4 + 3] = bits.toByte()
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    // ═══════════════════════════════════════════════════
    //  JSON 序列化/反序列化辅助
    // ═══════════════════════════════════════════════════

    /**
     * 将 Stage 1 结果序列化为 JSON（用于 DB 持久化）
     *
     * 始终返回非 null JSON，以便 caller 能区分"已处理但无人脸"和"尚未处理"。
     * hasFace 字段仍由 [hasValidFace] 控制，不会误标为 true。
     */
    private fun faceRoiToJson(result: Stage1Result): String {
        return """{"hasFace":${result.hasFace},"faceCount":${result.faceCount},"isSelfie":${result.isSelfie},"isGroupPhoto":${result.isGroupPhoto}}"""
    }

    /** 判断 embedding 是否为无效的零向量 */
    private fun isZeroVector(embedding: FloatArray): Boolean {
        return embedding.all { it == 0f }
    }

    /** 从 JSON 恢复人脸上下文 */
    private fun parseFaceRoi(json: String): FaceRoiPersist? {
        return try {
            val obj = JSONObject(json)
            FaceRoiPersist(
                hasFace = obj.optBoolean("hasFace", false),
                faceCount = obj.optInt("faceCount", 0),
                isSelfie = obj.optBoolean("isSelfie", false),
                isGroupPhoto = obj.optBoolean("isGroupPhoto", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse faceRoi JSON: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════
    //  Stage 1: 轻量人脸 ROI 检测（仅 bbox，无关键点）
    // ═══════════════════════════════════════════════════

    /**
     * [对齐版] 人脸检测 — 获取 ROI + RetinaFace 5 点 landmarks
     *
     * 使用 faceDetector.detectFacesWithLandmarks() 获取每个人脸的 ROI 和 5 点 landmarks，
     * 供 Stage 2 的 MobileFaceNet 进行 5 点仿射对齐，提升聚类准确度。
     */
    private fun stage1FaceDetection(bitmap: Bitmap): Stage1Result {
        val detections = faceDetector.detectFacesWithLandmarks(bitmap)

        if (detections.isEmpty()) {
            return Stage1Result(false)
        }

        val faces = detections.map { FaceRoi(it.roi, it.landmarks5) }
        return Stage1Result(
            hasFace = true,
            faceCount = faces.size,
            faces = faces
        )
    }

    // ═══════════════════════════════════════════════════
    //  Stage 2: MobileFaceNet 特征提取 → 人脸聚类
    // ═══════════════════════════════════════════════════

    private suspend fun stage2FaceCluster(
        bitmap: Bitmap,
        mediaId: Long,
        stage1Result: Stage1Result
    ): Stage2Result? {
        val embeddings = mutableListOf<FaceEmbeddingOutput>()

        for (face in stage1Result.faces) {
            val feature = faceClusterEngine.extractFeature(
                bitmap, face.roi, face.landmarks5
            )

            // 过滤零向量，避免误聚类
            if (isZeroVector(feature)) {
                Log.w(TAG, "[Stage 2] Zero vector embedding skipped for mediaId=$mediaId, roi=${face.roi}")
                continue
            }

            val matchedPersonId = faceClusterEngine.matchCluster(feature)

            val personId: Long = if (matchedPersonId != null) {
                faceClusterEngine.addToCluster(matchedPersonId, feature, mediaId)
                matchedPersonId
            } else {
                faceClusterEngine.createCluster(feature, mediaId)
            }

            embeddings.add(FaceEmbeddingOutput(mediaId, feature, personId))
        }

        return Stage2Result(
            faceEmbeddings = embeddings,
            personIds = embeddings.mapNotNull { it.personId }
        )
    }

    // ═══════════════════════════════════════════════════
    //  Stage 3: Qwen3.5-2B 多模态图像理解
    // ═══════════════════════════════════════════════════

    private suspend fun stage3QwenTagging(
        uri: String,
        stage1Result: Stage1Result,
        stage2Result: Stage2Result?
    ): QwenTagsNormalized {
        val bitmap = loadBitmap(uri, MAX_VISION_SIZE)
        if (bitmap == null) {
            Log.w(TAG, "Stage 3: failed to load bitmap, returning empty tags")
            return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
        }

        return try {
            if (!llmEngine.isLoaded) {
                Log.w(TAG, "Stage 3: LLM not loaded, skipping Qwen tagging")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
            }

            // 构造 user prompt（融入人脸上下文）
            val userPrompt = promptProvider.userPrompt(
                targetLanguage,
                if (stage1Result.hasFace) stage1Result.faceCount else 0,
                stage1Result.isGroupPhoto
            )

            val response = runVisionInference(bitmap, userPrompt)

            if (response.isBlank()) {
                Log.w(TAG, "Stage 3: empty response from LLM")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
            }

            Log.d(TAG, "Stage 3 raw response: $response")

            // 提取 JSON 部分（模型可能生成额外文本）
            val jsonPart = extractJson(response)
            if (jsonPart == null) {
                Log.w(TAG, "Stage 3: failed to extract JSON from response")
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "", listOf(response))
            }

            val qwenTags = parseQwenResponse(jsonPart)
            if (qwenTags == null) {
                return QwenTagsNormalized("", "", emptyList(), emptyList(), "", listOf(response))
            }

            // 后处理规范化
            normalizer.normalize(qwenTags)
        } finally {
            bitmap.recycle()
        }
    }

    private fun parseQwenResponse(jsonStr: String): QwenTags? {
        return try {
            val obj = JSONObject(jsonStr)
            QwenTags(
                scene = obj.optString("scene", ""),
                activity = obj.optString("activity", ""),
                objects = obj.optJSONArray("objects")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                tags = obj.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                summary = obj.optString("summary", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Stage 3: failed to parse JSON: ${e.message}")
            null
        }
    }

    private fun toJsonString(result: UnifiedTagResult): String {
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
        obj.put("qwenSummary", result.qwenSummary)
        return obj.toString()
    }

    /**
     * 从 LLM 返回中提取 JSON 对象
     */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start != -1 && end > start) {
            text.substring(start, end + 1)
        } else null
    }

    /**
     * 带 OpenCL 守护的多模态推理
     *
     * - 若 [openClGuardian] 存在，则使用其超时保护与自动降级逻辑
     * - 若 OpenCL 路径返回 Timeout，自动降级到 CPU 并立即重试一次
     * - 若不存在 Guardian，回退到原始 llmEngine.imageInference
     */
    private suspend fun runVisionInference(bitmap: Bitmap, userPrompt: String): String {
        return if (openClGuardian != null) {
            when (val result = openClGuardian.inference(
                bitmap = bitmap,
                systemPrompt = stage3SystemPrompt,
                userPrompt = userPrompt,
                maxTokens = QWEN_MAX_TOKENS
            )) {
                is OpenClInferenceResult.Success -> result.response
                is OpenClInferenceResult.Timeout -> {
                    Log.w(TAG, "OpenCL timeout, retrying with CPU fallback")
                    llmEngine.imageInference(bitmap, stage3SystemPrompt, userPrompt, maxTokens = QWEN_MAX_TOKENS)
                }
                is OpenClInferenceResult.Error -> {
                    Log.w(TAG, "OpenCL error: ${result.message}, falling back to CPU")
                    llmEngine.imageInference(bitmap, stage3SystemPrompt, userPrompt, maxTokens = QWEN_MAX_TOKENS)
                }
            }
        } else {
            llmEngine.imageInference(bitmap, stage3SystemPrompt, userPrompt, maxTokens = QWEN_MAX_TOKENS)
        }
    }

    /**
     * 从 Content URI 加载 Bitmap，缩放到指定最长边，并校正 EXIF 方向。
     *
     * inSampleSize 会被 BitmapFactory 向下取整到 2 的幂次，因此实际尺寸可能略大于 maxSize。
     * 注意：返回的 Bitmap 需要调用方负责回收。
     */
    private fun loadBitmap(uri: String, maxSize: Int): Bitmap? {
        return try {
            val contentUri = Uri.parse(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(contentUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val scale = if (maxOf(options.outWidth, options.outHeight) > maxSize) {
                maxOf(options.outWidth, options.outHeight) / maxSize
            } else 1

            // inSampleSize 必须是 2 的幂次
            val sampleSize = Integer.highestOneBit(scale).coerceAtLeast(1)

            val decoded = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }.let { decodeOptions ->
                context.contentResolver.openInputStream(contentUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            } ?: return null

            // 校正 EXIF 方向，避免竖拍/旋转照片编码错误
            applyExifRotation(contentUri, decoded)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bitmap from $uri: ${e.message}")
            null
        }
    }

    /**
     * 根据 EXIF 方向标签旋转 Bitmap。
     *
     * @return 旋转后的新 Bitmap；无需旋转时返回原 Bitmap
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val rotationDegrees = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).rotationDegrees
            } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF orientation: ${e.message}")
            0
        }

        if (rotationDegrees == 0) return bitmap

        return try {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rotate bitmap: ${e.message}")
            bitmap
        }
    }
}
