# 统一三个图像理解入口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Pass 3 批量、预览页「图像理解」、信息弹框「重新打标」三个入口共用设置项 `taggerModelKey` 驱动的同一模型（Florence-2 / qwen3_vl_2b），`qwen3_5_2b` 退出图像理解；并修复 retag 恒走 Qwen 导致的单张/批量不一致。

**Architecture:** `TagGenerationPipeline` 新增 Stage-3 统一分流 `runStage3Unified(uri, faceRoiJson)`（按 `taggerModelKey` 选 Florence-2 ORT 或 Qwen3-VL），entry 1（`executeQwenTagging`）与 entry 3（`processPhoto`）都改调它；`TagGenerationScheduler` 新增 `describeImage(uri)`（entry 2），按「模型 × UI 语言」矩阵出提示词或 caption+译中。新增纯函数 `ImageDescriptionStrategyResolver` 承载可 JVM 测试的语言/模型决策。

**Tech Stack:** Kotlin（Android `:app` 模块），JUnit4 纯 JVM 单测，Gradle（`./gradlew :app:testDebugUnitTest` / `:app:assembleDebug`）。

**Spec:** `docs/superpowers/specs/2026-07-27-unify-image-understanding-entries-design.md`

---

## 实现说明（相对 spec 的一处细化）

Spec 4.2 把 `runStage3Unified` 写在 `TagGenerationScheduler`。实现时放在 **`TagGenerationPipeline`**——因为 entry 3 的 Stage-3 发生在 `TagGenerationPipeline.processPhoto` 内（它必须保留 Stage 1/2 人脸重检测），而 pipeline 已拥有 `stage3QwenTagging`。scheduler 把自己的 `florence2Tagger`（lazy）与已解析的 `taggerModelKey` 作为 provider 注入 pipeline。行为与 spec 意图一致（entry 1 与 entry 3 共用同一个 Stage-3 分流），仅落点更自然。

## 测试策略

`LocalLlmEngine`、`Florence2Tagger` 都是需 Android/MNN 的具体类，无法在纯 JVM fake。因此：
- **可 JVM 测试的纯决策**（`ImageDescriptionStrategyResolver` 的模型×语言矩阵）→ 真实 TDD。
- **Android 绑定的接线**（`runStage3Unified`、`executeQwenTagging`、`processPhoto`、`describeImage`、`MediaPager` 改造）→ 以 **编译 + 既有 JVM 单测全绿 + 手动验证** 为门槛（与本仓库现状一致：真门槛 = 编译 + JVM 单测）。

---

## File Structure

- **Create**
  - `app/src/main/java/com/mamba/picme/domain/tag/ImageDescriptionStrategy.kt` — 纯函数：模型×语言 → 描述提示词/是否译中（entry 2 决策核心，JVM 可测）。
  - `app/src/test/java/com/mamba/picme/domain/tag/ImageDescriptionStrategyTest.kt` — 上述矩阵的 JVM 单测。
- **Modify**
  - `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt` — 构造器注入两个 provider；新增 `runStage3Unified`；`processPhoto` Stage-3 改调它。
  - `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt` — `pipeline` lazy 传 provider；`executeQwenTagging` 改调 `runStage3Unified`；新增 `describeImage`。
  - `app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt` — 新增 `onDescribeImage` 参数；`onStartVisionClick` 改调它，删写死的 `qwen3_5_2b`。
  - `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt` — 给 `MediaPager` 传 `onDescribeImage`。
  - `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml` — 新增 `vision_failed`。
  - `app/src/main/java/com/mamba/picme/data/indexing/ImageTagIndexingWorker.kt` — 删除陈旧 `reTagSingle` + `TAGGING_SYSTEM_PROMPT`/`TAGGING_USER_PROMPT`（确认无调用方后）。

---

## Task 1: 纯函数 `ImageDescriptionStrategyResolver` + JVM 单测（entry 2 语言/模型矩阵）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/tag/ImageDescriptionStrategy.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/tag/ImageDescriptionStrategyTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/tag/ImageDescriptionStrategyTest.kt`:

```kotlin
package com.mamba.picme.domain.tag

import com.mamba.picme.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 [ImageDescriptionStrategyResolver]：Qwen3-VL 按 UI 语言直出提示词；
 * Florence-2 不用提示词（走 caption），中文 UI 需 en→zh 翻译。
 */
class ImageDescriptionStrategyTest {

    @Test
    fun qwen_chinese_outputs_chinese_prompts_and_no_translate() {
        val s = ImageDescriptionStrategyResolver.resolve("qwen3_vl_2b", AppLanguage.CHINESE)
        assertTrue(s.systemPrompt.contains("图像理解助手"))
        assertEquals("请描述这张图片", s.userPrompt)
        assertFalse(s.needsZhTranslate)
    }

    @Test
    fun qwen_traditional_chinese_also_uses_chinese_prompts() {
        val s = ImageDescriptionStrategyResolver.resolve("qwen3_vl_2b", AppLanguage.TRADITIONAL_CHINESE)
        assertTrue(s.systemPrompt.contains("图像理解助手"))
        assertFalse(s.needsZhTranslate)
    }

    @Test
    fun qwen_english_outputs_english_prompts_and_no_translate() {
        val s = ImageDescriptionStrategyResolver.resolve("qwen3_vl_2b", AppLanguage.ENGLISH)
        assertTrue(s.systemPrompt.contains("image understanding assistant"))
        assertEquals("Describe this image", s.userPrompt)
        assertFalse(s.needsZhTranslate)
    }

    @Test
    fun florence2_chinese_needs_translate_and_ignores_prompts() {
        val s = ImageDescriptionStrategyResolver.resolve("florence2_base", AppLanguage.CHINESE)
        assertEquals("", s.systemPrompt)
        assertEquals("", s.userPrompt)
        assertTrue(s.needsZhTranslate)
    }

    @Test
    fun florence2_english_no_translate() {
        val s = ImageDescriptionStrategyResolver.resolve("florence2_base", AppLanguage.ENGLISH)
        assertEquals("", s.systemPrompt)
        assertFalse(s.needsZhTranslate)
    }
}
```

> `AppLanguage` 枚举为 `{ SYSTEM, ENGLISH, CHINESE, TRADITIONAL_CHINESE }`（见 `UserPreferences.kt:18`）。简中=`CHINESE`、繁中=`TRADITIONAL_CHINESE`。`SYSTEM` 按既有 `persistUnifiedTags` 约定视作非英文（→zh），与现状一致。

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.ImageDescriptionStrategyTest"`
Expected: 编译失败（`ImageDescriptionStrategyResolver` 未定义）。

- [ ] **Step 3: 实现纯函数**

Create `app/src/main/java/com/mamba/picme/domain/tag/ImageDescriptionStrategy.kt`:

```kotlin
package com.mamba.picme.domain.tag

import com.mamba.picme.domain.model.AppLanguage

/**
 * entry 2（预览页「图像理解」描述）的提示词/翻译策略。
 *
 * - Qwen3-VL-2B：中英文均强，按 UI 语言直接出提示词。
 * - Florence-2：中文弱，走 `Florence2Tagger.summary`（英文 caption），中文 UI 时需 en→zh 翻译。
 *
 * 与「打标」（结构化 JSON，[TagGenerationPipeline] Stage-3）刻意不同：这里是自由文本描述。
 * 模型来源统一为 [TaggerModelSelector]/`taggerModelKey`，保证三个入口同模型。
 */
data class ImageDescriptionStrategy(
    val systemPrompt: String,
    val userPrompt: String,
    val needsZhTranslate: Boolean
)

object ImageDescriptionStrategyResolver {

    private const val QWEN_SYSTEM_ZH =
        "你是一个图像理解助手。请用简洁的中文描述这张图片的内容，包括主要对象、场景、颜色和氛围。"
    private const val QWEN_USER_ZH = "请描述这张图片"

    private const val QWEN_SYSTEM_EN =
        "You are an image understanding assistant. Briefly describe this image " +
            "in concise English, covering the main objects, scene, colors and mood."
    private const val QWEN_USER_EN = "Describe this image"

    fun resolve(modelKey: String, lang: AppLanguage): ImageDescriptionStrategy {
        val isZh = lang != AppLanguage.ENGLISH
        return if (modelKey == TaggerModelSelector.defaultKey) {
            // Florence-2：走 caption，不用提示词；中文 UI 需翻译。
            ImageDescriptionStrategy(
                systemPrompt = "",
                userPrompt = "",
                needsZhTranslate = isZh
            )
        } else {
            // Qwen3-VL-2B：按 UI 语言直出提示词。
            if (isZh) {
                ImageDescriptionStrategy(QWEN_SYSTEM_ZH, QWEN_USER_ZH, needsZhTranslate = false)
            } else {
                ImageDescriptionStrategy(QWEN_SYSTEM_EN, QWEN_USER_EN, needsZhTranslate = false)
            }
        }
    }
}
```

> `TaggerModelSelector.defaultKey` == `"florence2_base"`（既有常量，DRY）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.ImageDescriptionStrategyTest"`
Expected: 5 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/ImageDescriptionStrategy.kt \
        app/src/test/java/com/mamba/picme/domain/tag/ImageDescriptionStrategyTest.kt
git commit -m "feat(tag): entry2 图像理解策略——模型×语言矩阵纯函数 + JVM 单测"
```

---

## Task 2: `TagGenerationPipeline` 注入 provider + 新增 `runStage3Unified`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt`（构造器 55-65；新增方法；`processPhoto` 在 Task 4 改）

- [ ] **Step 1: 构造器新增两个 provider 参数**

在 `TagGenerationPipeline` 构造器（`mobileClipTagClassifier` 之后）追加两个参数：

```kotlin
@Suppress("LongParameterList") // 待重构：依赖容器，考虑分组
class TagGenerationPipeline(
    private val context: Context,
    private val faceDetector: FaceDetector,
    private val llmEngine: LocalLlmEngine,
    private val faceClusterEngine: FaceClusterEngine,
    private val normalizer: TagNormalizer,
    private val openClGuardian: OpenClGuardian? = null,
    private val promptProvider: TagPromptProvider = DefaultTagPromptProvider(),
    private val mobileClipEngine: MobileClipEngine? = null,
    private val mobileClipTagClassifier: MobileClipTagClassifier? = null,
    private val florence2TaggerProvider: () -> Florence2Tagger? = { null },
    private val taggerModelKeyProvider: () -> String = { TaggerModelSelector.defaultKey }
) {
```

- [ ] **Step 2: 新增 `runStage3Unified` 方法**

在 `stage3QwenTagging(bitmap, faceRoiJson)`（约 268-280 行）之后新增：

```kotlin
    /**
     * Stage-3 统一分流：按 scheduler 解析的 taggerModelKey 决定用 Florence-2（ORT）
     * 还是 Qwen3-VL（MNN）打标。entry 1（批量 [TagGenerationScheduler.executeQwenTagging]）
     * 与 entry 3（retag [processPhoto]）共用，保证「单张/批量同模型同提示词同过程」。
     *
     * 内部按所选模型解码合适尺寸 bitmap（Florence-2 → 768，Qwen → 512）。
     *
     * @param uri 照片 Content URI
     * @param faceRoiJson Pass 1 持久化的人脸上下文（Qwen 提示词提示用；可为 null）
     * @return Stage-3 产物；face 字段留空，由调用方按各自人脸上下文填充。
     *         模型不可用 / 解码失败 / 推理空 → 返回空 [UnifiedTagResult]。
     */
    suspend fun runStage3Unified(uri: String, faceRoiJson: String?): UnifiedTagResult {
        val modelKey = taggerModelKeyProvider()
        return if (modelKey == TaggerModelSelector.defaultKey) {
            // Florence-2 ORT 路径
            val tagger = florence2TaggerProvider()
            if (tagger == null || !tagger.isInit) {
                Log.w(TAG, "[Stage3] Florence-2 unavailable, returning empty")
                return UnifiedTagResult()
            }
            val bitmap = loadBitmap(uri, Florence2Tagger.IMAGE_SIZE) ?: return UnifiedTagResult()
            try {
                tagger.tag(bitmap)
            } finally {
                bitmap.recycle()
            }
        } else {
            // Qwen3-VL-2B 路径：复用既有 Stage-3（含 faceRoi 上下文提示 + normalize）
            val qwen = stage3QwenTagging(uri, faceRoiJson)
            UnifiedTagResult(
                scene = qwen.scene,
                activity = qwen.activity,
                objects = qwen.objects,
                tags = qwen.tags,
                summary = qwen.summary
            )
        }
    }
```

> `loadBitmap` 是 pipeline 既有 private suspend fun（Stage-3 原本就用它）。`Florence2Tagger.IMAGE_SIZE`、`isInit`、`tag(bitmap)` 均既有。

- [ ] **Step 3: scheduler 的 `pipeline` lazy 传入 provider**

Modify `TagGenerationScheduler.kt` 的 `pipeline` lazy（约 202-228 行），在构造 `TagGenerationPipeline(...)` 时追加两行实参：

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
            mobileClipEngine = mobileClip,
            mobileClipTagClassifier = classifier,
            florence2TaggerProvider = { florence2Tagger },
            taggerModelKeyProvider = { taggerModelKey }
        )
    }
```

> `{ florence2Tagger }`、`{ taggerModelKey }` 既是 scheduler 的既有 lazy/val（180-189、90-105）。

- [ ] **Step 4: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（新方法尚未被调用，零行为变化）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt \
        app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
git commit -m "feat(tag): pipeline 新增 runStage3Unified 统一 Stage-3 分流 + 注入 provider"
```

---

## Task 3: entry 1（`executeQwenTagging` 批量）改调 `runStage3Unified`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt:1260-1300`

- [ ] **Step 1: 替换 Stage-3 分流段**

在 `executeQwenTagging`（约 1260-1300 行）把整段「按模型分流」的 `if (isFlorence2) { ... } else { ... }` 替换为：

```kotlin
        // 可用性守卫（保留批量 fail-fast → FAILED → 退避重试语义）
        if (taggerModelKey == "florence2_base") {
            check(florence2Tagger != null && florence2Tagger.isInit) {
                "[Pass 3] Florence-2 not available for mediaId=$mediaId"
            }
        } else {
            check(ensureModelLoaded()) {
                "[Pass 3] Model not loaded for mediaId=$mediaId"
            }
        }

        // Stage-3 统一分流（与 retag 同源）：runStage3Unified 内部按 taggerModelKey
        // 选 Florence-2 / Qwen3-VL，保证单张/批量同模型同提示词。
        val stage3 = pipeline.runStage3Unified(entity.uri, entity.faceRoiResult)
        currentCoroutineContext().ensureActive()
        val faceInfo = parseFaceRoiForUnifiedResult(entity.faceRoiResult, entity.faceId)
        val unified = stage3.copy(face = faceInfo)

        // 恒英文：labelsEn 存英文原语；labelsZh 由 LabelSinicizer 离线汉化派生。
        persistUnifiedTags(entity.id, unifiedTagToJson(unified))
```

> 删除原先的 `val isFlorence2 = ...`、Florence-2 分支（`tagger.tag(bitmap)` 等）、Qwen 分支（`stage3QwenTagging` + 手搓 UnifiedTagResult）。保留之后的 `[Benchmark]` 日志与 `delay(getPass3CooldownMs())`。`unifiedTagToJson` 既有。

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 既有 JVM 单测全绿**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 无回归（`TagScanOrchestratorTest` 等通过）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
git commit -m "refactor(tag): entry1 批量 Pass3 改调 runStage3Unified——去除重复分流"
```

---

## Task 4: entry 3（`processPhoto` retag）Stage-3 改调 `runStage3Unified`（修 Florence-2 bug）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt:130-160`（`processPhoto` 的 Stage-3 段与 `stage3Result` 类型）

- [ ] **Step 1: 改 `stage3Result` 类型与 Stage-3 调用**

在 `processPhoto` 中：

(a) 把局部变量声明（约 132 行）
```kotlin
        val stage3Result: QwenTagsNormalized
```
改为
```kotlin
        val stage3Result: UnifiedTagResult
```

(b) 把 Stage-3 段（约 132-138 行，含 `scaleBitmapToMaxSize` / `stage3QwenTagging(stage3Bitmap, faceRoiJson)` / `stage3Bitmap.recycle()`）替换为：
```kotlin
            // ── Stage 3: 统一分流（与批量同模型同提示词；按模型内部解码合适尺寸）───
            val faceRoiJson = faceRoiToJson(stage1Result)
            stage3Result = runStage3Unified(uri, faceRoiJson)
            Log.d(TAG, "Stage 3 done: scene=${stage3Result.scene}, tags=${stage3Result.tags}")
```

> 删除 `val stage3Bitmap = scaleBitmapToMaxSize(faceBitmap, MAX_VISION_SIZE)` 与 `stage3Bitmap.recycle()`（不再复用 faceBitmap 做 Stage-3；runStage3Unified 自解码）。`faceBitmap` 仍由 Stage 1/2 使用并在 finally 中 recycle。

- [ ] **Step 2: 核对结果组装无需改**

`processPhoto` 末尾组装 `UnifiedTagResult(face = FaceTagInfo(...), scene = stage3Result.scene, …)`（约 155-165 行）——`UnifiedTagResult` 同样有 `scene/activity/objects/tags/summary` 字段，无需改动。仅核对字段名一致即可。

- [ ] **Step 3: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 既有 JVM 单测全绿**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 无回归。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt
git commit -m "fix(tag): entry3 retag Stage-3 改调 runStage3Unified——修复默认 Florence-2 下与批量不一致"
```

---

## Task 5: `TagGenerationScheduler.describeImage`（entry 2 后端）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt`（新增 public suspend 方法）

- [ ] **Step 1: 新增 `describeImage`**

在 `processSingleSync`（约 279-289 行）之后新增：

```kotlin
    /**
     * 预览页「图像理解」入口（entry 2）：对单张图片生成自然语言描述。
     *
     * 模型与 entry 1/3 同源（复用已解析的 [taggerModelKey]）：
     * - Florence-2 → `Florence2Tagger.summary`（英文 caption）；中文 UI → en→zh 翻译。
     * - Qwen3-VL-2B → `imageInference`，按 UI 语言直出提示词。
     *
     * 输出语言跟随 [userSettingsRepository] 的 appLanguage（zh-TW 复用 zh 译文）。
     *
     * @return 描述文本；模型不可用 / 解码失败 / 推理空 → null。
     */
    suspend fun describeImage(uri: String): String? = withContext(Dispatchers.IO) {
        val lang = userSettingsRepository.getAppLanguageBlocking()
        val strategy = ImageDescriptionStrategyResolver.resolve(taggerModelKey, lang)
        val bitmap = pipeline.loadBitmapPublic(uri) ?: return@withContext null
        try {
            if (taggerModelKey == "florence2_base") {
                val tagger = florence2Tagger
                if (tagger == null || !tagger.isInit) return@withContext null
                val caption = tagger.tag(bitmap).summary
                if (caption.isBlank()) return@withContext null
                if (strategy.needsZhTranslate) enToZhTranslator.translate(caption) else caption
            } else {
                if (!ensureModelLoaded()) return@withContext null
                val engine = AgentOrchestrator.getInstance(context).getLlmEngine()
                val result = engine.imageInference(
                    bitmap = bitmap,
                    systemPrompt = strategy.systemPrompt,
                    userPrompt = strategy.userPrompt,
                    maxTokens = 256
                )
                result.ifEmpty { null }
            }
        } finally {
            bitmap.recycle()
        }
    }
```

> `ImageDescriptionStrategyResolver` 同包 `com.mamba.picme.domain.tag`，无需 import。`AgentOrchestrator` 已为 scheduler 既有引用（`ensureModelLoaded` 等处）。`enToZhTranslator`、`pipeline.loadBitmapPublic`、`ensureModelLoaded`、`taggerModelKey`、`florence2Tagger` 均为 scheduler 既有成员（注意 scheduler 无 `orchestrator` 字段，故直接 `AgentOrchestrator.getInstance(context)`）。

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
git commit -m "feat(tag): scheduler 新增 describeImage——entry2 图像理解（模型同源 + 语言适配）"
```

---

## Task 6: entry 2 接线（`MediaPager` + `GalleryScreen`）+ i18n

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt:157-158, 228-278`
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt:823-828`
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

- [ ] **Step 1: 新增 i18n 字符串 `vision_failed`**

在三个 `strings.xml` 中（与既有 `vision_loading` 同区）各加一行：

`values/strings.xml`:
```xml
    <string name="vision_failed">Image understanding failed</string>
```
`values-zh-rCN/strings.xml`:
```xml
    <string name="vision_failed">图像理解失败</string>
```
`values-zh-rTW/strings.xml`:
```xml
    <string name="vision_failed">圖像理解失敗</string>
```

- [ ] **Step 2: `MediaPager` 新增 `onDescribeImage` 参数**

在 `MediaPager(...)` 参数表（约 157-158 行，`onReTag` 之后）追加：

```kotlin
    onReTag: suspend (Uri) -> String? = { null },
    onDescribeImage: suspend (Uri) -> String? = { null },
    onTriggerSummary: (Long) -> Unit = {}
```

- [ ] **Step 3: 重写 `onStartVisionClick`，删除写死的 `qwen3_5_2b`**

把 `MediaPager` 内 `onStartVisionClick`（约 228-278 行整段）替换为：

```kotlin
            val onStartVisionClick: () -> Unit = {
                val asset = assets.getOrNull(pagerState.currentPage)
                if (asset?.type == MediaType.PHOTO) {
                    Log.d("Gallery", "Trigger image understanding for asset: ${asset.id}")
                    visionResult = null
                    isVisionLoading = true
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching { onDescribeImage(asset.uri.toUri()) }
                            .getOrNull()
                        visionResult = result
                            ?: context.getString(R.string.vision_failed)
                        isVisionLoading = false
                    }
                }
            }
```

> 删除原先 `withModelLoaded(modelId = "qwen3_5_2b")` + 内联 prompt + `imageInference` + try/catch 整段。`visionResult`/`isVisionLoading`/`scope`/`MediaType`/`R` 均为既有。`asset.uri.toUri()` 与 `onReTag` 同模式。失败（含异常）统一显示 `vision_failed`，不再硬编码中文错误串。

- [ ] **Step 4: `GalleryScreen` 给 `MediaPager` 传 `onDescribeImage`**

在 `GalleryScreen.kt` 调用 `MediaPager(...)` 处（`onReTag = { uri -> ... }` 紧邻，约 823-828 行），追加：

```kotlin
                        onReTag = { uri ->
                            val resultJson = app.container.tagGenerationScheduler.processSingleSync(uri.toString())
                            if (resultJson != null) viewModel.refreshLabels()
                            resultJson
                        },
                        onDescribeImage = { uri ->
                            app.container.tagGenerationScheduler.describeImage(uri.toString())
                        }
```

> `app.container.tagGenerationScheduler` 已是该处既有引用（`onReTag` 即用）。

- [ ] **Step 5: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt \
        app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(gallery): entry2 图像理解改调 scheduler.describeImage——移除写死 qwen3_5_2b"
```

---

## Task 7: 清理陈旧 `reTagSingle` + 独立 prompt

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/indexing/ImageTagIndexingWorker.kt:50-72, 118-165`

- [ ] **Step 1: 确认无调用方**

Run: `grep -rn "reTagSingle\|TAGGING_SYSTEM_PROMPT\|TAGGING_USER_PROMPT" app/src/main`
Expected: 仅 `ImageTagIndexingWorker.kt` 自身命中（定义 + 内部 `doBatchTagging` 可能复用 `TAGGING_SYSTEM_PROMPT`）。

> 若 `doBatchTagging`（约 167-214 行）仍用 `TAGGING_SYSTEM_PROMPT`/`TAGGING_USER_PROMPT`：`doBatchTagging` 是该 worker 的另一条批量旧路径——核对是否仍有调用方（`forceReTagAll`/外部触发）。若仍有用，**仅删 `reTagSingle`**，保留两个 prompt 常量（不要破坏 doBatchTagging）；若 `doBatchTagging` 也无人调，整块删除。**先 grep 再决定删除范围。**

- [ ] **Step 2: 删除 `reTagSingle`（及仅它用的 helper）**

删除 `reTagSingle`（约 118-165 行）。若 Step 1 确认 `TAGGING_SYSTEM_PROMPT`/`TAGGING_USER_PROMPT`/`parseLabels` 仅被 `reTagSingle` 使用，一并删除；否则保留。

- [ ] **Step 3: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（无未解析引用）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/data/indexing/ImageTagIndexingWorker.kt
git commit -m "chore(tag): 删除陈旧 reTagSingle 独立打标路径（相册 retag 已走 processSingleSync）"
```

---

## Task 8: 全量验证

- [ ] **Step 1: 全量 JVM 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿（含 Task 1 新增 5 个 + 既有 tag/scan/normalizer 等）。

- [ ] **Step 2: Debug APK 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 手动验证清单（设备）**

`adb install -r app/build/outputs/apk/debug/polang-debug.apk` 后：
1. 设置→相册功能→内容标签模型 = **Florence-2**（默认）。对同一张图：TAG 页 Pass 3 扫描产出 与 信息弹框「重新打标」产出**一致**（同 scene/tags/summary）。（验证 B）
2. 预览页「图像理解」按钮：产出为 Florence-2 caption（中文 UI 下译中）。（验证 A：entry2 跟设置）
3. 设置切到 **Qwen3-VL-2B**（需先在模型中心下载）：三个入口都用 qwen3_vl_2b；entry 2 产出为 Qwen 描述，语言随 UI。
4. `adb logcat -s "PoLang:TagPipeline" "PoLang:TagScheduler"` 确认无 `qwen3_5_2b` 出现在图像理解路径。

- [ ] **Step 4: 文档同步（如 spec/CLAUDE.md 涉及模型描述）**

若 `CLAUDE.md`/`PRODUCT.md`/`docs` 中有「entry 2 用 qwen3_5_2b」「retag 走独立管道」等过期描述，按本实现更新（三层文档同一原子提交原则）。

---

## Self-Review

**1. Spec coverage**
- A（三者同模型来自设置）：Task 5+6 让 entry 2 走 `taggerModelKey`；Task 2-4 让 entry 1/3 共用 `runStage3Unified` 读同一 `taggerModelKey`。✓
- B（单张/批量一致）：Task 3（entry1）+ Task 4（entry3）共调 `runStage3Unified`，修 Florence-2 bug。✓
- C（entry 2 语言适配）：Task 1 矩阵 + Task 5 用之。✓
- `qwen3_5_2b` 退出三入口：Task 6 删写死模型；Task 8 Step 3.4 校验。✓
- 清理陈旧实现：Task 7。✓
- i18n：Task 6 Step 1 三语同步。✓

**2. Placeholder scan**：无 TBD/TODO；每步含完整代码或确切命令。Task 7 的删除范围以 grep 结果 conditional（有确切判定规则，非占位）。

**3. Type consistency**：`runStage3Unified` 返回 `UnifiedTagResult`（Task 2 定义）；Task 3 `stage3.copy(face=...)`（data class，成立）；Task 4 `stage3Result: UnifiedTagResult`（与组装字段名一致）；`ImageDescriptionStrategy` 字段（systemPrompt/userPrompt/needsZhTranslate）Task 1 定义、Task 5 消费一致；`onDescribeImage: suspend (Uri) -> String?` Task 6 定义处与 GalleryScreen 实参一致。

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-27-unify-image-understanding-entries.md`.
