# 批量去 LLM（ML Kit + summary 按需）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Pass3 批量打标改用 ML Kit（不加载 SmolVLM → 不发热）；文字 summary 改按需（照片详情点开时 SmolVLM 单张生成 + 缓存）。

**Architecture:** `executeQwenTagging` 从 SmolVLM 全量改为 ML Kit 标签（`extractMlKitLabels` + `translateToZh` → `labels.tags`，scene/objects/activity/summary 空，不加载 LLM）；新建 `GenerateSummaryOnDemandUseCase`（按需 SmolVLM 单张 summary）；`MediaPager` 打开详情时触发按需 summary。

**Tech Stack:** Kotlin, ML Kit Image Labeling（已接入 `MlKitTagExtractor`）, MNN-LLM SmolVLM（按需）, `MlKitLabelTranslator.translateToZh`（英→中，已有）。

**Spec:** `docs/superpowers/specs/2026-07-20-batch-mlkit-on-demand-summary-design.md`

---

## File map

| File | Responsibility |
|---|---|
| `app/.../domain/tag/TagGenerationScheduler.kt` | `executeQwenTagging` 改 ML Kit 标签（去 SmolVLM）|
| `app/.../domain/usecase/GenerateSummaryOnDemandUseCase.kt`（新）| 按需 summary：加载 SmolVLM + 单张 imageInference + 写回 labels.summary |
| `app/.../features/gallery/components/MediaPager.kt`（+ 其 VM）| 详情打开时，summary 空 → 触发按需 UseCase |
| `app/.../di/AppContainer.kt` | 构造 `GenerateSummaryOnDemandUseCase` |

**Code style (CLAUDE.md):** 无全限定 `com.mamba.picme.*`（用 import）；无 wildcard import；lambda 参数显式命名（不用 `it`）；log tag `PoLang:xxx`。

---

### Task 1: `executeQwenTagging` 改 ML Kit（去 SmolVLM）

**Files:** Modify `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt:1014`

- [ ] **Step 1: 读 executeQwenTagging 现状（确认 face 处理，改时不破坏）**

Run: `sed -n '1014,1046p' app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt`
确认当前：`ensureModelLoaded` + `stage3QwenTagging`（SmolVLM）+ `unifiedTagToJson`。face 由 Pass1 单独写（faceRoiResult 字段），Pass3 的 UnifiedTagResult.face 用默认空即可（或保留现有 face 来源）。

- [ ] **Step 2: 改 executeQwenTagging 为 ML Kit**

替换 `executeQwenTagging` 方法体（去掉 `ensureModelLoaded` + `stage3QwenTagging`，改 ML Kit）：

```kotlin
suspend fun executeQwenTagging(mediaId: Long) {
    val dao = db.mediaDao()
    val entity = dao.getMediaById(mediaId) ?: return

    val startMs = System.currentTimeMillis()
    // 批量 Pass3 改用 ML Kit（不加载 SmolVLM → 不发热）。
    // ML Kit 英文标签 → translateToZh 中文 → 全放 labels.tags。
    // scene/objects/activity/summary 留空（summary 由照片详情按需 SmolVLM 生成）。
    val labelsEn = pipeline.extractMlKitLabels(entity.uri)
    val labelsZh = mlKitLabelTranslator.translateToZh(labelsEn)

    currentCoroutineContext().ensureActive()

    val unified = UnifiedTagResult(
        scene = "",
        activity = "",
        objects = emptyList(),
        tags = labelsZh,
        summary = ""
    )
    dao.updateLabels(entity.id, unifiedTagToJson(unified))

    Log.d(TAG, "[Benchmark] Pass 3 (ML Kit) done: mediaId=$mediaId, " +
        "durationMs=${System.currentTimeMillis() - startMs}, tags=$labelsZh")
}
```

> 关键：去掉 `if (!ensureModelLoaded()) ...`（不加载 SmolVLM）。`pipeline.extractMlKitLabels` + `mlKitLabelTranslator.translateToZh` 已有。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
git commit -m "refactor(tag): Pass3 executeQwenTagging 改用 ML Kit（去 SmolVLM，不发热）"
```

---

### Task 2: `GenerateSummaryOnDemandUseCase`（按需 summary）

**Files:** Create `app/src/main/java/com/mamba/picme/domain/usecase/GenerateSummaryOnDemandUseCase.kt`

- [ ] **Step 1: 写 UseCase**

```kotlin
package com.mamba.picme.domain.usecase

import android.content.Context
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.data.local.AppDatabase

/**
 * 按需生成照片 summary：用户点开照片详情时，若 labels.summary 为空，
 * 触发 SmolVLM 单张推理生成中文描述并写回 labels.summary（缓存，后续秒开）。
 *
 * 批量扫描不调用此 UseCase（批量用 ML Kit 标签，不加载 SmolVLM）。
 */
class GenerateSummaryOnDemandUseCase(private val context: Context) {

    suspend fun generateIfMissing(mediaId: Long): String? {
        val dao = AppDatabase.getDatabase(context).mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return null

        // 已有 summary（解析 labels JSON 的 qwenSummary）→ 直接返回（缓存命中）
        val existing = parseSummary(entity.labels)
        if (existing.isNotBlank()) return existing

        // 加载 SmolVLM + 单张推理生成 summary
        val orchestrator = AgentOrchestrator.getInstance(context)
        val engine = orchestrator.getLlmEngine()
        orchestrator.ensureModelLoaded(
            modelId = "smolvlm_500m",
            useOpencl = false,
            caller = "GenerateSummaryOnDemand"
        ).let { result ->
            if (result.isFailure) return null
        }

        val uri = entity.uri
        // 用专门 summary prompt（或复用 promptProvider 的 summary 模板）
        val summary = engine.imageInference(
            bitmap = loadBitmap(uri),
            systemPrompt = "你是一个照片描述助手，用一句流畅的中文描述照片内容。",
            userPrompt = "描述这张照片。",
            maxTokens = 128
        )
        // 写回 labels.summary（合并到现有 labels JSON）
        val merged = mergeSummaryIntoLabels(entity.labels, summary)
        dao.updateLabels(mediaId, merged)
        return summary
    }

    private fun parseSummary(labelsJson: String?): String {
        if (labelsJson.isNullOrBlank()) return ""
        return try {
            org.json.JSONObject(labelsJson).optString("qwenSummary", "")
        } catch (e: Exception) { "" }
    }

    private fun mergeSummaryIntoLabels(labelsJson: String?, summary: String): String {
        val obj = if (labelsJson.isNullOrBlank()) org.json.JSONObject()
                  else org.json.JSONObject(labelsJson)
        obj.put("qwenSummary", summary)
        return obj.toString()
    }

    private fun loadBitmap(uri: String): android.graphics.Bitmap? = try {
        val contentUri = android.net.Uri.parse(uri)
        context.contentResolver.openInputStream(contentUri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        }
    } catch (e: Exception) { null }
}
```

> Note: `engine.imageInference` 签名以 `MnnLlmClient` 实际为准（可能要 bitmap 非空处理）。`loadBitmap` 简化版，可复用 pipeline 的 loadBitmap（若可访问）。

- [ ] **Step 2: AppContainer 注入**

In `AppContainer`，加 lazy 构造（参考其他 UseCase 模式）：

```kotlin
private val generateSummaryOnDemandUseCase: GenerateSummaryOnDemandUseCase by lazy {
    GenerateSummaryOnDemandUseCase(context = context)
}
```

并在需要的地方暴露（MediaPager VM 的 dependencies）。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/usecase/GenerateSummaryOnDemandUseCase.kt \
        app/src/main/java/com/mamba/picme/di/AppContainer.kt
git commit -m "feat(tag): 按需 summary UseCase（详情触发 SmolVLM 单张生成）"
```

---

### Task 3: MediaPager 详情触发 summary 按需

**Files:** Modify `app/.../features/gallery/components/MediaPager.kt` + 其 ViewModel

- [ ] **Step 1: 读 MediaPager summary 展示现状**

Run: `rg -n "qwenSummary|summary" app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt`
确认 summary 当前怎么展示（从 labels 解析）。找到按需触发点（详情打开/选中照片变化）。

- [ ] **Step 2: ViewModel 加按需触发**

在 MediaPager 的 ViewModel（或照片详情 VM）里，当当前照片变化时：
```kotlin
// 当前照片 summary 空 → 触发按需生成
viewModelScope.launch {
    val summary = generateSummaryOnDemandUseCase.generateIfMissing(currentMediaId)
    // 更新 UI state 展示 summary
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt \
        app/src/main/java/com/mamba/picme/features/gallery/MediaViewModel.kt
git commit -m "feat(gallery): 照片详情按需触发 summary 生成"
```

---

### Task 4: 全量验证

- [ ] **Step 1: 批量扫描不发热**

设备重连后：`./gradlew :app:installDebug` → 触发 `scan_pass3` → logcat 确认 **无 `Loading LLM model: smolvlm_500m`**（Pass3 不加载 SmolVLM）+ Pass 3 (ML Kit) done 日志 + 设备不发烫。

- [ ] **Step 2: summary 按需**

打开照片详情 → 首次 summary 空 → 等待 ~13s（SmolVLM 单张）→ summary 出现；再次打开同一张 → 秒开（缓存）。

- [ ] **Step 3: 搜索验证**

搜「连衣裙」「项链」等 ML Kit 中文标签 → 命中（labels.tags）；语义搜索（MobileCLIP）正常。

---

## Self-review

- [ ] **Spec coverage:** 批量 Pass3 ML Kit（Task 1）+ summary 按需（Task 2-3）+ 搜索（Task 4 step 3）+ 不加载 SmolVLM（Task 1/4）✓
- [ ] **Placeholder:** Task 2 的 imageInference 签名/loadBitmap 复用待实现时按实际 API 调整（已注明）。
- [ ] **Type consistency:** `UnifiedTagResult(tags=labelsZh)`、`GenerateSummaryOnDemandUseCase.generateIfMissing(mediaId): String?`、`translateToZh(List): List` 一致。
- [ ] **风险:** executeQwenTagging 改 ML Kit 后 face 处理（Task 1 Step 1 确认 Pass3 不破坏 Pass1 的 face）；MobileCLIP 语义向量 Pass1 保留不受影响。
