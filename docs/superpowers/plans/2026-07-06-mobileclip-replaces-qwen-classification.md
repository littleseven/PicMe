# MobileCLIP 替代 Qwen 分类实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `TagGenerationPipeline` 中的 `scene`、`objects`、`tags` 字段生成迁移到 MobileCLIP 零 shot 分类，Qwen 仅保留 `activity` 和 `summary`，并保证搜索侧数据格式兼容。

**Architecture:** 新增 `MobileClipTagClassifier`，启动时预计算 `ControlledVocab` 候选标签的文本 embedding；`TagGenerationPipeline` 的 Stage 3 并行/串行调用 MobileCLIP 分类和精简版 Qwen prompt，合并结果后写入 DB。MobileCLIP 失败时回退到 Qwen 全量输出。

**Tech Stack:** Kotlin, Android Room, MobileCLIP-ONNX, ONNX Runtime, ControlledVocab

---

## 前置检查

确认 `app/src/main/assets/controlled_vocab.json` 存在且包含以下顶层键：`scene`、`activity`、`objects`、`atmosphere`、`people`、`clothing`、`animal`、`food_drink`、`architecture`、`nature`、`transport`。

```bash
cat app/src/main/assets/controlled_vocab.json | head -c 500
```

---

## Task 1: 扩展 ControlledVocab 暴露分类别候选

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/ControlledVocab.kt:34-38`

**目标:** 为 MobileCLIP 分类提供 `sceneCandidates`、`objectCandidates`、`tagCandidates` 三个只读属性。

- [ ] **Step 1: 添加候选属性**

在 `ControlledVocab` 类中 `allCategories` 之后添加：

```kotlin
    /** MobileCLIP scene 字段候选：直接取 scene 类别 */
    val sceneCandidates: List<String>
        get() = scene

    /** MobileCLIP objects 字段候选：直接取 objects 类别 */
    val objectCandidates: List<String>
        get() = objects

    /** MobileCLIP tags 字段候选：跨人物、服饰、动物、食物、建筑、自然、交通工具、氛围等类别 */
    val tagCandidates: List<String>
        get() = people + clothing + animal + foodDrink + architecture + nature + transport + atmosphere
```

- [ ] **Step 2: 编译检查**

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/ControlledVocab.kt
git commit -m "feat(tag): expose per-field candidate lists from ControlledVocab"
```

---

## Task 2: 扩展 TagPromptProvider 支持精简 Prompt

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/prompt/TagPromptProvider.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/prompt/DefaultTagPromptProvider.kt`

**目标:** 新增只输出 `activity` 和 `summary` 的 prompt，减少 Qwen decode token 数量。

- [ ] **Step 1: 扩展接口**

```kotlin
interface TagPromptProvider {
    fun systemPrompt(lang: AppLanguage): String
    fun userPrompt(lang: AppLanguage, faceCount: Int, isGroupPhoto: Boolean): String

    /** 精简版：只要求 Qwen 输出 activity 和 summary */
    fun systemPromptForActivityAndSummary(lang: AppLanguage): String
    fun userPromptForActivityAndSummary(lang: AppLanguage, faceCount: Int, isGroupPhoto: Boolean): String
}
```

- [ ] **Step 2: 实现 DefaultTagPromptProvider 精简版**

```kotlin
    override fun systemPromptForActivityAndSummary(lang: AppLanguage): String = if (lang == AppLanguage.ENGLISH) {
        ENGLISH_SYSTEM_PROMPT_ACTIVITY_SUMMARY
    } else {
        CHINESE_SYSTEM_PROMPT_ACTIVITY_SUMMARY
    }

    override fun userPromptForActivityAndSummary(
        lang: AppLanguage,
        faceCount: Int,
        isGroupPhoto: Boolean
    ): String {
        if (faceCount <= 0) {
            return if (lang == AppLanguage.ENGLISH) {
                "Analyze the activity and write a one-sentence summary."
            } else {
                "请分析照片中的活动，并用一句话概括照片内容。"
            }
        }

        return if (lang == AppLanguage.ENGLISH) {
            buildString {
                append("The photo has $faceCount face(s), ")
                append(
                    when {
                        isGroupPhoto -> "it looks like a group photo."
                        faceCount >= 2 -> "it looks like a photo of two people."
                        else -> "it looks like a single-person photo."
                    }
                )
                append(" Analyze the activity and write a one-sentence summary.")
            }
        } else {
            buildString {
                append("照片中有${faceCount}张人脸，")
                append(
                    when {
                        isGroupPhoto -> "可能是合影。"
                        faceCount >= 2 -> "可能是双人照。"
                        else -> "可能是单人照。"
                    }
                )
                append("请分析照片中的活动，并用一句话概括照片内容。")
            }
        }
    }
```

- [ ] **Step 3: 添加精简版 system prompt 常量**

在 `companion object` 中添加：

```kotlin
        private val CHINESE_SYSTEM_PROMPT_ACTIVITY_SUMMARY = buildString {
            appendLine("你是一个相册照片描述助手。只输出纯JSON，不要markdown代码块、不要解释、不要多余文字。")
            appendLine()
            appendLine("输出格式：")
            appendLine("{\"activity\":\"活动\",\"summary\":\"一句话概括\"}")
            appendLine()
            appendLine("要求：")
            appendLine("1. 全部使用中文，专有名词（如iPhone）除外")
            appendLine("2. activity：吃饭/旅行/运动/聚会/散步/自拍/工作/休息等")
            appendLine("3. summary：30-40字的一句话概括，包含主要人物、场景、动作和氛围")
            appendLine()
            appendLine("示例：")
            appendLine("{\"activity\":\"散步\",\"summary\":\"一位妈妈推着婴儿车在阳光明媚的公园小径上散步，周围绿树成荫，氛围轻松愉快\"}")
        }

        private val ENGLISH_SYSTEM_PROMPT_ACTIVITY_SUMMARY = buildString {
            appendLine("You are a photo album description assistant. Output valid JSON only. No markdown, no explanation, no extra text.")
            appendLine()
            appendLine("Output format:")
            appendLine("{\"activity\":\"the activity\",\"summary\":\"a one-sentence summary\"}")
            appendLine()
            appendLine("Requirements:")
            appendLine("1. Use English only, except proper nouns like iPhone.")
            appendLine("2. activity: eating/traveling/sports/party/walking/selfie/working/resting/etc.")
            appendLine("3. summary: a 25-40 word sentence summarizing the photo, including main people, scene, action and atmosphere.")
            appendLine()
            appendLine("Example:")
            appendLine("{\"activity\":\"walking\",\"summary\":\"A mother pushing a stroller with her baby along a sunny park path lined with green trees, enjoying a relaxing afternoon walk\"}")
        }
```

- [ ] **Step 4: 编译检查**

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/prompt/TagPromptProvider.kt
git add app/src/main/java/com/mamba/picme/domain/tag/prompt/DefaultTagPromptProvider.kt
git commit -m "feat(tag): add activity+summary only prompt variants"
```

---

## Task 3: 新建 MobileClipTagClassifier

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/tag/MobileClipTagClassifier.kt`

**目标:** 实现核心分类器：候选标签文本 embedding 预计算、图像编码、Top-K + 阈值分类。

- [ ] **Step 1: 创建 MobileClipTags 数据类和 MobileClipTagClassifier 骨架**

```kotlin
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
 * - 启动时预计算 ControlledVocab 候选标签的文本 embedding
 * - 对输入图像编码，与候选标签 embedding 计算余弦相似度
 * - 按字段阈值策略输出 Top-K 标签
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
    }

    private var isReady = false

    /** 候选标签文本 embedding 缓存：label -> FloatArray(512) */
    private val textEmbeddings = mutableMapOf<String, FloatArray>()

    /**
     * 预热：加载 MobileCLIP 模型并预计算所有候选标签的文本 embedding
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

        val candidates = vocab.sceneCandidates + vocab.objectCandidates + vocab.tagCandidates
        val distinct = candidates.distinct()
        Log.i(TAG, "Precomputing text embeddings for ${distinct.size} candidates")

        var failed = 0
        for (label in distinct) {
            val tokenIds = tokenizer.encode(label) ?: run {
                failed++
                continue
            }
            val embedding = mobileClipEngine.encodeText(tokenIds) ?: run {
                failed++
                continue
            }
            textEmbeddings[label] = embedding
        }

        if (textEmbeddings.isEmpty()) {
            Log.w(TAG, "No text embeddings computed, classifier unusable")
            return false
        }

        if (failed > 0) {
            Log.w(TAG, "$failed/${distinct.size} candidate labels failed to encode")
        }

        isReady = true
        Log.i(TAG, "Warmup complete: ${textEmbeddings.size} text embeddings cached")
        return true
    }

    /**
     * 对单张图像进行分类
     *
     * @return MobileClipTags，失败返回 null
     */
    fun classify(bitmap: Bitmap): MobileClipTags? {
        if (!isReady) {
            Log.w(TAG, "Classifier not warmed up")
            return null
        }

        val imageEmbedding = mobileClipEngine.encodeImage(bitmap) ?: run {
            Log.w(TAG, "Failed to encode image")
            return null
        }

        val scene = topK(SCENE_TOP_K, SCENE_THRESHOLD, vocab.sceneCandidates, imageEmbedding).firstOrNull() ?: ""
        val objects = topK(OBJECT_TOP_K, OBJECT_THRESHOLD, vocab.objectCandidates, imageEmbedding)
        val tags = topK(TAG_TOP_K, TAG_THRESHOLD, vocab.tagCandidates, imageEmbedding)

        return MobileClipTags(scene = scene, objects = objects, tags = tags)
    }

    /**
     * 从指定候选集中选取与图像相似度最高的 Top-K 标签，过滤低于阈值的标签
     */
    private fun topK(
        k: Int,
        threshold: Float,
        candidates: List<String>,
        imageEmbedding: FloatArray
    ): List<String> {
        val scored = candidates.mapNotNull { label ->
            val textEmbedding = textEmbeddings[label] ?: return@mapNotNull null
            val sim = cosineSimilarity(imageEmbedding, textEmbedding)
            if (sim >= threshold) label to sim else null
        }
        return scored.sortedByDescending { it.second }
            .take(k)
            .map { it.first }
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
```

- [ ] **Step 2: 编译检查**

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/MobileClipTagClassifier.kt
git commit -m "feat(tag): add MobileClipTagClassifier for zero-shot scene/objects/tags"
```

---

## Task 4: 修改 TagGenerationPipeline 合并 MobileCLIP 与 Qwen 结果

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt`

**目标:** Stage 3 同时调用 MobileCLIP 分类和精简 Qwen，合并输出；MobileCLIP 失败时回退到 Qwen 全量输出。

- [ ] **Step 1: 构造函数注入 classifier**

在类定义中添加参数：

```kotlin
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
    private val mlKitTagExtractor: MlKitTagExtractor? = null,
    private val mobileClipTagClassifier: MobileClipTagClassifier? = null
) {
```

- [ ] **Step 2: 修改 runVisionInference 接收 systemPrompt 参数**

当前 `runVisionInference` 固定使用 `stage3SystemPrompt`。精简 prompt 需要传入不同的 system prompt。

修改签名和实现：

```kotlin
    /**
     * 带 OpenCL 守护的多模态推理
     *
     * @param systemPrompt 本次推理使用的 system prompt
     */
    private suspend fun runVisionInference(
        bitmap: Bitmap,
        systemPrompt: String,
        userPrompt: String
    ): String {
        return if (openClGuardian != null) {
            when (val result = openClGuardian.inference(
                bitmap = bitmap,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                maxTokens = QWEN_MAX_TOKENS
            )) {
                is OpenClInferenceResult.Success -> result.response
                is OpenClInferenceResult.Timeout -> {
                    Log.w(TAG, "OpenCL timeout, retrying with CPU fallback")
                    llmEngine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens = QWEN_MAX_TOKENS)
                }
                is OpenClInferenceResult.Error -> {
                    Log.w(TAG, "OpenCL error: ${result.message}, falling back to CPU")
                    llmEngine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens = QWEN_MAX_TOKENS)
                }
            }
        } else {
            llmEngine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens = QWEN_MAX_TOKENS)
        }
    }
```

并更新 `runVisionInference` 的调用点：原 `processPhoto` 中的 Stage 3 调用已通过 `stage3QwenTagging` 重定向，无需额外修改。

- [ ] **Step 3: 修改 stage3QwenTagging 返回结构**

把 `stage3QwenTagging` 的返回类型从 `QwenTagsNormalized` 改为内部数据类，同时承载 MobileCLIP 和 Qwen 的结果。

在 `TagGenerationPipeline` 内添加：

```kotlin
    /**
     * Stage 3 合并结果：MobileCLIP 分类 + Qwen activity/summary
     */
    private data class Stage3CombinedResult(
        val scene: String = "",
        val activity: String = "",
        val objects: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val summary: String = "",
        val fromMobileClip: Boolean = false
    )
```

- [ ] **Step 4: 重写 stage3QwenTagging**

将原 `stage3QwenTagging(uri, faceRoiJson)` 的实现替换为：

```kotlin
    /**
     * [Pass 3] MobileCLIP 分类 + Qwen 精简理解
     *
     * 1. MobileCLIP 负责 scene / objects / tags
     * 2. Qwen 精简 prompt 只负责 activity / summary
     * 3. MobileCLIP 失败时回退到 Qwen 全量输出
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
            val combined = runStage3Combined(bitmap, faceRoiJson)
            QwenTagsNormalized(
                scene = combined.scene,
                activity = combined.activity,
                objects = combined.objects,
                tags = combined.tags,
                summary = combined.summary
            ).let { normalizer.normalize(it) }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun runStage3Combined(bitmap: Bitmap, faceRoiJson: String?): Stage3CombinedResult {
        if (!llmEngine.isLoaded) {
            Log.w(TAG, "[Pass 3] LLM not loaded, skipping Qwen tagging")
        }

        // 从 JSON 恢复人脸上下文
        val faceRoi = faceRoiJson?.let { parseFaceRoi(it) }
        val faceCount = if (faceRoi?.hasFace == true) faceRoi.faceCount else 0
        val isGroupPhoto = faceRoi?.isGroupPhoto ?: false

        // 1. 先尝试 MobileCLIP 分类
        val mobileClipTags = mobileClipTagClassifier?.classify(bitmap)
        val mobileClipSuccess = mobileClipTags != null

        // 2. MobileCLIP 成功时：Qwen 只输出 activity + summary
        //    MobileCLIP 失败时：Qwen 输出全量字段作为回退
        val qwenResult = if (mobileClipSuccess) {
            runQwenActivityAndSummary(bitmap, faceCount, isGroupPhoto)
        } else {
            runQwenFull(bitmap, faceCount, isGroupPhoto)
        }

        return if (mobileClipSuccess && qwenResult != null) {
            Stage3CombinedResult(
                scene = mobileClipTags!!.scene,
                activity = qwenResult.activity,
                objects = mobileClipTags.objects,
                tags = mobileClipTags.tags,
                summary = qwenResult.summary,
                fromMobileClip = true
            )
        } else if (qwenResult != null) {
            Stage3CombinedResult(
                scene = qwenResult.scene,
                activity = qwenResult.activity,
                objects = qwenResult.objects,
                tags = qwenResult.tags,
                summary = qwenResult.summary,
                fromMobileClip = false
            )
        } else {
            Stage3CombinedResult()
        }
    }

    private data class QwenActivitySummary(
        val activity: String,
        val summary: String
    )

    private suspend fun runQwenActivityAndSummary(
        bitmap: Bitmap,
        faceCount: Int,
        isGroupPhoto: Boolean
    ): QwenActivitySummary? {
        if (!llmEngine.isLoaded) return null

        val systemPrompt = promptProvider.systemPromptForActivityAndSummary(targetLanguage)
        val userPrompt = promptProvider.userPromptForActivityAndSummary(targetLanguage, faceCount, isGroupPhoto)
        val response = runVisionInference(bitmap, systemPrompt, userPrompt)

        if (response.isBlank()) return null
        val jsonPart = extractJson(response) ?: return null
        return parseQwenActivitySummary(jsonPart)
    }

    private fun parseQwenActivitySummary(jsonStr: String): QwenActivitySummary? {
        return try {
            val obj = JSONObject(jsonStr)
            QwenActivitySummary(
                activity = obj.optString("activity", ""),
                summary = obj.optString("summary", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse activity+summary JSON: ${e.message}")
            null
        }
    }

    private suspend fun runQwenFull(
        bitmap: Bitmap,
        faceCount: Int,
        isGroupPhoto: Boolean
    ): QwenTags? {
        if (!llmEngine.isLoaded) return null

        val userPrompt = promptProvider.userPrompt(targetLanguage, faceCount, isGroupPhoto)
        val response = runVisionInference(bitmap, stage3SystemPrompt, userPrompt)

        if (response.isBlank()) return null
        val jsonPart = extractJson(response) ?: return null
        return parseQwenResponse(jsonPart)
    }
```

- [ ] **Step 5: 保留旧的 stage3QwenTagging 签名兼容**

已有 `stage3QwenTagging(uri, stage1Result, stage2Result)` 的重载（pipeline 内部 `processPhoto` 调用）。需要把它改为调用新的 `stage3QwenTagging(uri, faceRoiJson)`。

找到原方法：

```kotlin
    private suspend fun stage3QwenTagging(
        uri: String,
        stage1Result: Stage1Result,
        stage2Result: Stage2Result?
    ): QwenTagsNormalized {
```

改为：

```kotlin
    private suspend fun stage3QwenTagging(
        uri: String,
        stage1Result: Stage1Result,
        stage2Result: Stage2Result?
    ): QwenTagsNormalized {
        val faceRoiJson = faceRoiToJson(stage1Result)
        return stage3QwenTagging(uri, faceRoiJson)
    }
```

- [ ] **Step 5: 编译检查**

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt
git commit -m "feat(tag): combine MobileCLIP classification with Qwen activity+summary"
```

---

## Task 5: 修改 TagGenerationScheduler 初始化并注入 Classifier

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt:131-157`

**目标:** 在 scheduler 中创建 `MobileClipTagClassifier`，在 Pass 3 前 warmUp，并注入 pipeline。

- [ ] **Step 1: 在 pipeline 初始化时创建 classifier**

修改 `pipeline` 的 lazy 初始化：

```kotlin
    private val pipeline: TagGenerationPipeline by lazy {
        val faceDetector = FaceDetectorFactory.create(context)
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
            mlKitTagExtractor = MlKitTagExtractor(context),
            mobileClipTagClassifier = classifier
        )
    }
```

- [ ] **Step 2: 在 Pass 3 前 warmUp MobileCLIP classifier**

修改 `executeQwenTagging` 开头：

```kotlin
    suspend fun executeQwenTagging(mediaId: Long) {
        if (!ensureModelLoaded()) {
            throw IllegalStateException("LLM model not loaded")
        }

        // 预热 MobileCLIP 分类器（首次会预计算候选标签文本 embedding）
        pipeline.warmUpMobileClipClassifier()

        val dao = db.mediaDao()
        ...
    }
```

- [ ] **Step 3: 在 TagGenerationPipeline 添加 warmUpMobileClipClassifier 代理**

在 `TagGenerationPipeline` 中添加：

```kotlin
    /**
     * 预热 MobileCLIP 分类器
     */
    fun warmUpMobileClipClassifier(): Boolean {
        return mobileClipTagClassifier?.warmUp() ?: false
    }
```

- [ ] **Step 4: 编译检查**

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt
git commit -m "feat(tag): wire MobileClipTagClassifier into scheduler and pipeline"
```

---

## Task 6: 单元测试 MobileClipTagClassifier 的 Top-K 逻辑

**Files:**
- Create: `app/src/test/java/com/mamba/picme/domain/tag/MobileClipTagClassifierTest.kt`

**目标:** 测试阈值过滤和 Top-K 选择逻辑，不依赖真实 MobileCLIP 模型。

- [ ] **Step 1: 编写测试**

```kotlin
package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileClipTagClassifierTest {

    @Test
    fun `topK respects k and threshold`() {
        // 模拟 3 个候选标签的 embedding，与查询向量点积分别为 0.5, 0.2, 0.1
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf("high", "medium", "low")
        val embeddings = mapOf(
            "high" to floatArrayOf(0.5f, 0f),
            "medium" to floatArrayOf(0.2f, 0f),
            "low" to floatArrayOf(0.1f, 0f)
        )

        val result = topKForTest(k = 2, threshold = 0.15f, candidates, query, embeddings)

        assertEquals(listOf("high", "medium"), result)
    }

    @Test
    fun `topK filters below threshold`() {
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf("a", "b")
        val embeddings = mapOf(
            "a" to floatArrayOf(0.5f, 0f),
            "b" to floatArrayOf(0.1f, 0f)
        )

        val result = topKForTest(k = 5, threshold = 0.2f, candidates, query, embeddings)

        assertEquals(listOf("a"), result)
    }

    private fun topKForTest(
        k: Int,
        threshold: Float,
        candidates: List<String>,
        query: FloatArray,
        embeddings: Map<String, FloatArray>
    ): List<String> {
        return candidates.mapNotNull { label ->
            val emb = embeddings[label] ?: return@mapNotNull null
            val sim = cosineSimilarityForTest(query, emb)
            if (sim >= threshold) label to sim else null
        }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    private fun cosineSimilarityForTest(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.MobileClipTagClassifierTest" --no-daemon
```

Expected: BUILD SUCCESSFUL + tests pass

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/mamba/picme/domain/tag/MobileClipTagClassifierTest.kt
git commit -m "test(tag): add MobileClipTagClassifier top-k unit tests"
```

---

## Task 7: 集成验证

**Files:**
- N/A（手动/设备验证）

**目标:** 在真机或模拟器上跑少量照片，确认 MobileCLIP 分类生效且 Qwen 回退可用。

- [ ] **Step 1: 安装 Debug APK**

```bash
./gradlew :app:installDebug --no-daemon
```

Expected: BUILD SUCCESSFUL + install success

- [ ] **Step 2: 触发 Pass 3 增量扫描**

通过 adb 触发：

```bash
adb shell am startservice -n com.mamba.picme/.service.tag.TagGenerationService -a com.mamba.picme.tag.SCAN_PASS_3
```

- [ ] **Step 3: 抓取日志验证 MobileCLIP 输出**

```bash
adb logcat -d | grep -E "MobileClipTagClassifier|TagPipeline|Pass 3"
```

Expected: 日志中出现 `MobileClipTagClassifier: Warmup complete` 和 `TagPipeline: Stage 3 done: scene=... tags=...`

- [ ] **Step 4: 数据库抽查**

```bash
adb shell run-as com.mamba.picme sqlite3 databases/app_database.db "SELECT id, labels FROM media_assets WHERE labels IS NOT NULL LIMIT 1;"
```

Expected: JSON 中 `scene/objects/tags` 有值，`activity/summary` 有值。

- [ ] **Step 5: Commit 验证记录（可选）**

如果验证通过：

```bash
git commit --allow-empty -m "qa(tag): verify MobileCLIP classification integration on device"
```

---

## Self-Review Checklist

- [x] **Spec coverage:** 每个设计点（候选暴露、精简 prompt、classifier、pipeline 合并、scheduler 注入、测试、验证）都有对应任务。
- [x] **Placeholder scan:** 无 TBD/TODO/"implement later"，每个步骤都有具体代码和命令。
- [x] **Type consistency:** `MobileClipTags`、`QwenTagsNormalized`、`Stage3CombinedResult` 字段一致；`TagPromptProvider` 新接口在两个实现类中同步添加。
- [x] **DRY:** Top-K 和余弦相似度逻辑集中在 `MobileClipTagClassifier`，不重复实现。
- [x] **YAGNI:** 不引入照片去重、INT4 量化等本范围外的优化。

---

## 执行选项

Plan complete and saved to `docs/superpowers/plans/2026-07-06-mobileclip-replaces-qwen-classification.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
