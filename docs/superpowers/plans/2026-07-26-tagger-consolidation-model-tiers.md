# 打标模型收敛 + 模型中心必须/推荐分层 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **本环境注记:** Agent/Workflow 子代理在本环境不可用（模型错误 [1211]），按 superpowers:executing-plans **内联执行**。

**Goal:** 收敛端侧打标模型为 Florence-2（默认）+ Qwen3-VL-2B（备选），移除 SmolVLM-500M；模型中心「必须」收敛为单一事实来源并新增「推荐」Tab + WiFi 静默预下载。

**Architecture:** 纯 Kotlin 优先（`TaggerModelSelector`、`RecommendedModelAutoDownloader` 纯逻辑可单测）；模型分层用 `REQUIRED_MODEL_IDS` / `RECOMMENDED_MODEL_IDS` 两个集合驱动 `isRequired`/`isRecommended` 与 Tab 分组；WiFi 预下载复用既有 `NetworkUtils.isWifi` + `ConnectivityManager.NetworkCallback` + `LlmModelDownloadManager.downloadModel`，不引入 WorkManager。

**Tech Stack:** Kotlin + Compose、Android DataStore、OkHttp；JVM 单测（`./gradlew :app:testDebugUnitTest`）。

**项目硬规则（贯穿所有任务）:** 禁止 `com.mamba.picme.*` 全限定名（用 import）；禁止通配符 import；lambda 参数显式命名；日志 tag `PoLang:[Module]`；Kotlin/Java 4 空格缩进；用户可见字符串必须三语同步（`values` / `values-zh-rCN` / `values-zh-rTW`）。**真实质量门 = 编译 + JVM 单测**（detekt 141 预存失败非门、ktlint 插件坏、FQN 任务注释掉）。

**执行约定:** 每个任务先 Read 目标文件确认当前内容再 Edit；每个任务结束提交一次（Conventional Commits）。验证命令统一：编译 `./gradlew :app:assembleDebug`；相关单测 `./gradlew :app:testDebugUnitTest --tests "<FQN>"`。

---

## Task 1: 翻转 TaggerModelSelector 默认（TDD）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TaggerModelSelector.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/tag/TaggerModelSelectionTest.kt`

- [ ] **Step 1: 用新预期重写失败测试**（整文件替换）

```kotlin
package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验 [TaggerModelSelector]：Florence-2 为默认首选，未下载回退 Qwen3-VL-2B；
 * 手动指定仍可覆盖。SmolVLM/LFM2 等已下线模型视为无偏好。
 */
class TaggerModelSelectionTest {

    @Test
    fun default_key_is_florence2_base() {
        assertEquals("florence2_base", TaggerModelSelector.defaultKey)
    }

    @Test
    fun auto_prefers_florence2_when_available() {
        val allAvailable: (String) -> Boolean = { true }
        assertEquals("florence2_base", TaggerModelSelector.resolve(null, allAvailable))
        assertEquals("florence2_base", TaggerModelSelector.resolve("", allAvailable))
        assertEquals("florence2_base", TaggerModelSelector.resolve(TaggerModelSelector.AUTO, allAvailable))
        assertEquals("florence2_base", TaggerModelSelector.resolve("nonsense", allAvailable))
    }

    @Test
    fun auto_falls_back_to_qwen_when_florence2_unavailable() {
        val onlyQwen: (String) -> Boolean = { it == "qwen3_vl_2b" }
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve(TaggerModelSelector.AUTO, onlyQwen))
    }

    @Test
    fun auto_returns_default_when_nothing_available() {
        val noneAvailable: (String) -> Boolean = { false }
        assertEquals(
            TaggerModelSelector.defaultKey,
            TaggerModelSelector.resolve(TaggerModelSelector.AUTO, noneAvailable)
        )
    }

    @Test
    fun explicit_manual_override_wins() {
        val allAvailable: (String) -> Boolean = { true }
        assertEquals("florence2_base", TaggerModelSelector.resolve("florence2_base", allAvailable))
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("qwen3_vl_2b", allAvailable))
    }

    @Test
    fun explicit_override_falls_back_when_unavailable() {
        val onlyQwen: (String) -> Boolean = { it == "qwen3_vl_2b" }
        // 手动指定 florence2 但没下载 -> 回退 qwen
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("florence2_base", onlyQwen))
    }

    @Test
    fun removed_or_unknown_models_treated_as_auto() {
        // 已下线模型（smolvlm_500m / smolvlm_256m / lfm2_*）视为无偏好 -> Florence-2 优先
        assertEquals("florence2_base", TaggerModelSelector.resolve("smolvlm_500m"))
        assertEquals("florence2_base", TaggerModelSelector.resolve("smolvlm_256m"))
        assertEquals("florence2_base", TaggerModelSelector.resolve("lfm2_vl_450m"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.TaggerModelSelectionTest"`
Expected: FAIL（旧实现 `defaultKey="qwen3_vl_2b"`、首选 smolvlm）。

- [ ] **Step 3: 重写 TaggerModelSelector**（整文件替换）

```kotlin
package com.mamba.picme.domain.tag

/**
 * 打标模型选择器：Florence-2 为默认首选（ONNX INT8，~260MB，轻量稳定），
 * 未下载时回退 Qwen3-VL-2B；手动指定（florence2_base / qwen3_vl_2b）覆盖首选。
 *
 * 语言路由方案已废弃——打标恒英文（见 TagGenerationPipeline.targetLanguage），
 * 模型不再按 UI 语言选；中文由 LabelSinicizer 离线派生到 labelsZh。
 *
 * SmolVLM-500M、LFM2-VL（450M/1.6B）经评估打标效果不佳/被替代，已下线。
 */
object TaggerModelSelector {
    /** 默认打标模型：Florence-2-base。 */
    const val defaultKey = "florence2_base"

    /** 首选打标模型（同默认）：Florence-2。保留常量以兼容既有调用方。 */
    const val preferredKey = "florence2_base"

    /** 自动（无手动偏好）——DataStore 默认值，解析为首选 Florence-2。 */
    const val AUTO = "auto"

    private val knownKeys = setOf(defaultKey, "qwen3_vl_2b")

    /** 兼容入口：无可用性信息时，假定全部可用。 */
    fun resolve(raw: String?): String = resolve(raw) { true }

    /**
     * 解析最终使用的 tagger 模型 key（首选 + 下载感知兜底）。
     *
     * - [raw] 为白名单内显式模型 → 用它（手动覆盖）
     * - [raw] 为 [AUTO] / 空白 / 未识别 → 首选 [preferredKey]（Florence-2）
     * - 选中的模型 [isAvailable]=false → 回退另一个已知可用模型；全不可用 → [defaultKey]
     */
    fun resolve(raw: String?, isAvailable: (String) -> Boolean = { true }): String {
        val explicit = raw?.trim().orEmpty()
        val desired = if (explicit in knownKeys) explicit else preferredKey
        if (isAvailable(desired)) return desired
        val fallback = knownKeys.firstOrNull { candidate -> candidate != desired && isAvailable(candidate) }
        return fallback ?: defaultKey
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.TaggerModelSelectionTest"`
Expected: PASS（7 个用例）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TaggerModelSelector.kt \
        app/src/test/java/com/mamba/picme/domain/tag/TaggerModelSelectionTest.kt
git commit -m "refactor(tag): TaggerModelSelector 默认翻转为 Florence-2，移除 SmolVLM 常量"
```

---

## Task 2: 移除 smolvlm_500m 文件清单 + 更新 ModelFilesMappingTest

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt`（删 `SMOLVLM_MODEL_FILES` 常量 + 2 处 `modelId == "smolvlm_500m"` 分支：约 60、188、530 行）
- Test: `app/src/test/java/com/mamba/picme/data/download/ModelFilesMappingTest.kt`

- [ ] **Step 1: Read `LlmModelDownloadManager.kt` 定位 `SMOLVLM_MODEL_FILES`（~60）与两处 `modelFilesForId` 分支（~188、~530）**

- [ ] **Step 2: 删除 `SMOLVLM_MODEL_FILES` 常量定义（整块删除，含其上注释）**

- [ ] **Step 3: 删除两处分支行**

```kotlin
// 删除（约 188 行，companion 的 modelFilesForId 内）：
modelId == "smolvlm_500m" -> SMOLVLM_MODEL_FILES
// 删除（约 530 行，实例方法 getModelFiles 内）：
modelId == "smolvlm_500m" -> SMOLVLM_MODEL_FILES
```
删除后 `smolvlm_500m` 会落入兜底分支（`else -> LLM_MODEL_FILES`），符合“已下线”语义。

- [ ] **Step 4: Read `ModelFilesMappingTest.kt`，更新**：
  - 文件头注释（~10 行）改为：`当前打标模型仅保留 Florence-2-base（默认）与 Qwen3-VL-2B（备选）；SmolVLM-500M、LFM2-VL、SmolVLM-256M 已下线。`
  - 若存在断言 `smolvlm_500m` 返回某文件的用例 → 删除或改为：`assertEquals(LLM_MODEL_FILES, LlmModelDownloadManager.modelFilesForId("smolvlm_500m"))`（落兜底）。
  - 保留既有 `lfm2_vl_450m` 下线断言；确保仍通过。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.download.ModelFilesMappingTest"`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt \
        app/src/test/java/com/mamba/picme/data/download/ModelFilesMappingTest.kt
git commit -m "refactor(download): 移除 smolvlm_500m 文件清单与映射测试"
```

---

## Task 3: 更新模型目录 llm_models.json

**Files:**
- Modify: `app/src/main/res/raw/llm_models.json`

- [ ] **Step 1: 删除 `smolvlm_500m` 整段对象**（`"id": "smolvlm_500m"` 起、至其闭合 `}` 含逗号，约 59–78 行）

- [ ] **Step 2: `florence2_base.tags` 首项 `recommended` → `must-have`**

```json
"tags": ["must-have", "photo-tagging", "vision-llm", "onnx", "structured-output"]
```

- [ ] **Step 3: `opus-mt-en-zh.tags` 首项 `recommended` → `must-have`**

```json
"tags": ["must-have", "photo-tagging", "translation", "chinese", "english", "onnx", "nlp"]
```

- [ ] **Step 4: `mediapipe-face-landmarker.tags` 首项 `must-have` → `recommended`**

```json
"tags": ["recommended", "photo-tagging", "face", "landmark", "mediapipe"]
```

- [ ] **Step 5: 给推荐 6 项的 `tags` 数组补 `"recommended"`**（若尚无）：`qwen3_5_2b`、`sherpa-onnx-zipformer-zh-en`、`sherpa-onnx-kws-zipformer-wenetspeech`、`modnet-onnx`、`u2netp-onnx`、`mediapipe-face-landmarker`（mediapipe 上一步已为推荐首项，跳过）。

- [ ] **Step 6: 编译确认 JSON 合法**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（JSON 由 app 打包，解析在运行时；编译期确保无语法错误）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/res/raw/llm_models.json
git commit -m "feat(download): 目录移除 smolvlm_500m；florence2/opus-en-zh 入必须、mediapipe 入推荐"
```

---

## Task 4: 模型分层集合（必须调整 + 新增推荐 + isRecommended）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt`（`REQUIRED_MODEL_IDS` ~1367；新增 `RECOMMENDED_MODEL_IDS`；新增 `isRecommended` ~1397 旁）

- [ ] **Step 1: 调整 `REQUIRED_MODEL_IDS`**（`-qwen3_vl_2b`、`+florence2_base`）

```kotlin
val REQUIRED_MODEL_IDS = setOf(
    "face-det-retina500m-mnn",          // MNN ROI (Det500M)
    "face-landmark-2d106-mnn",          // MNN 2D106
    "face-embedding-glint360k-r100-mnn", // Glint360K R100 人脸 embedding
    "florence2_base",                   // 图片打标（默认 tagger，Pass 3）
    "mobileclip-onnx",                  // 语义搜索
    "opus-mt-zh-en",                    // 中文查询翻译
    "opus-mt-en-zh"                     // 英文 summary 汉化（labelsZh）
)
```

- [ ] **Step 2: 在 `CHAT_MODEL_IDS` 之后新增 `RECOMMENDED_MODEL_IDS`**

```kotlin
/**
 * 推荐模型 ID 集合（Tier 2：非核心，WiFi 下可静默预下载）。
 *
 * 含本地 LLM/语音（CHAT_MODEL_IDS）、证件照抠图、相册人脸标记预览。
 */
val RECOMMENDED_MODEL_IDS: Set<String> = CHAT_MODEL_IDS + setOf(
    "modnet-onnx",                  // 证件照/抠图
    "u2netp-onnx",                  // 证件照/抠图（轻量）
    "mediapipe-face-landmarker"     // 相册人脸标记预览
)
```

- [ ] **Step 3: 在 `isRequired` 旁新增 `isRecommended`**

```kotlin
/** 该模型是否为推荐（非必须）模型。 */
val isRecommended: Boolean get() = id in RECOMMENDED_MODEL_IDS
```

- [ ] **Step 4: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt
git commit -m "feat(download): REQUIRED 调整 + 新增 RECOMMENDED_MODEL_IDS/isRecommended"
```

---

## Task 5: 「推荐」Tab 分组 + 图标/颜色 + 标签翻译

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt`（`DEFAULT_TAG_TRANSLATIONS` ~150、`serviceCategoryTags` ~1429、`getCategories`/`groupByCategory` ~1442–1475）
- Modify: `app/src/main/java/com/mamba/picme/features/settings/LlmModelManagerScreen.kt`（`getCategoryIcon` ~109、`getTagColor` ~427）

- [ ] **Step 1: `DEFAULT_TAG_TRANSLATIONS` 增加推荐翻译**

```kotlin
"must-have" to "必须",
"recommended" to "推荐",
"chat" to "聊天",
```

- [ ] **Step 2: `serviceCategoryTags` 插入 `recommended`（位于 must-have 之后）**

```kotlin
private val serviceCategoryTags = listOf("must-have", "recommended", "chat", "photo-tagging", "beauty-camera")
```

- [ ] **Step 3: `getCategories()` 与 `groupByCategory()` 的 must-have 分支扩展为推荐**

把两处 `if (category.tag == "must-have") { models.filter { it.isRequired } ... }` 扩展为：

```kotlin
val categoryModels = when (categoryTag) {
    "must-have" -> models.filter { it.isRequired }
    "recommended" -> models.filter { it.isRecommended }
    else -> models.filter { categoryTag in it.tags }
}
```
（`getCategories()` 中 `assignedIds` 计算同改；保持「未分类入 All」逻辑不变。）

- [ ] **Step 4: `getCategoryIcon` 增加 recommended 分支**

```kotlin
"must-have" -> Icons.Outlined.Star
"recommended" -> Icons.Outlined.Download
```

- [ ] **Step 5: `getTagColor` 增加 recommended 分支**

```kotlin
"must-have" -> Color(0xFFE53935)
"recommended" -> MaterialTheme.colorScheme.tertiary
```

- [ ] **Step 6: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt \
        app/src/main/java/com/mamba/picme/features/settings/LlmModelManagerScreen.kt
git commit -m "feat(ui): 模型中心新增「推荐」Tab（分组/图标/颜色/翻译）"
```

---

## Task 6: GALLERY_REQUIRED_MODEL_IDS 派生自 REQUIRED_MODEL_IDS

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt`（`GALLERY_REQUIRED_MODEL_IDS` ~57–65）

- [ ] **Step 1: Read 确认 `GALLERY_REQUIRED_MODEL_IDS` 当前定义与所有引用点**（`grep GALLERY_REQUIRED_MODEL_IDS`）

- [ ] **Step 2: 改为派生**（保留 List 类型以兼容调用方）

```kotlin
/**
 * Tier 1：相册扫描/创建 TAG 必须的模型（最高优先）。
 * 单一事实来源：派生自 [LlmModelDownloadManager.REQUIRED_MODEL_IDS]。
 * 进入相册且自动扫描任务启动前必须全部已下载，否则弹出下载提醒。
 */
val GALLERY_REQUIRED_MODEL_IDS: List<String> =
    LlmModelDownloadManager.REQUIRED_MODEL_IDS.toList()
```
确保文件已 `import com.mamba.picme.data.download.LlmModelDownloadManager`（若无则补；遵守禁止 FQN 规则）。

- [ ] **Step 3: 删除原硬编码 6 项 `listOf(...)` 块**

- [ ] **Step 4: 编译 + 相关单测**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.settings.*"`
Expected: BUILD SUCCESSFUL + PASS（若有 SettingsViewModel 相关测试）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt
git commit -m "refactor(settings): GALLERY_REQUIRED_MODEL_IDS 派生自 REQUIRED_MODEL_IDS"
```

---

## Task 7: autoDownloadRecommendedOnWifi 偏好（默认 true）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/model/UserPreferences.kt`（若有相关 UI state 枚举/常量；否则跳过）

- [ ] **Step 1: 接口 `UserSettingsRepository` 增加成员**（置于「调试开关」或「TAG 生成」相近区块）

```kotlin
// ── 模型预下载 ──────────────────────────────────────────
val autoDownloadRecommendedOnWifiFlow: Flow<Boolean>
suspend fun updateAutoDownloadRecommendedOnWifi(enabled: Boolean)
```

- [ ] **Step 2: `PreferencesKeys` 增加 key**

```kotlin
val AUTO_DOWNLOAD_RECOMMENDED_ON_WIFI = booleanPreferencesKey("auto_download_recommended_on_wifi")
```

- [ ] **Step 3: `UserPreferencesRepository` 实现**（仿 `debugUiEnabledFlow` 模式，默认 `true`）

```kotlin
override val autoDownloadRecommendedOnWifiFlow: Flow<Boolean> = context.dataStore.data
    .catch { exception ->
        if (exception is IOException) emit(emptyPreferences()) else throw exception
    }
    .map { preferences ->
        preferences[PreferencesKeys.AUTO_DOWNLOAD_RECOMMENDED_ON_WIFI] ?: true
    }

override suspend fun updateAutoDownloadRecommendedOnWifi(enabled: Boolean) {
    context.dataStore.edit { preferences ->
        preferences[PreferencesKeys.AUTO_DOWNLOAD_RECOMMENDED_ON_WIFI] = enabled
    }
}
```
确保已 import `kotlinx.coroutines.flow.map` / `catch` / `emptyPreferences` / `IOException`（沿用文件既有 import）。

- [ ] **Step 4: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt \
        app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt
git commit -m "feat(prefs): 新增 autoDownloadRecommendedOnWifi（默认 true）"
```

---

## Task 8: RecommendedModelAutoDownloader（纯逻辑 + 触发器，TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/download/RecommendedModelAutoDownloader.kt`
- Test: `app/src/test/java/com/mamba/picme/data/download/RecommendedModelAutoDownloaderTest.kt`

- [ ] **Step 1: 写失败测试（纯逻辑 computeMissing）**

```kotlin
package com.mamba.picme.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendedModelAutoDownloaderTest {

    @Test
    fun missing_is_all_recommended_minus_downloaded_and_inprogress() {
        val downloaded = setOf("qwen3_5_2b", "modnet-onnx")
        val inProgress = setOf("u2netp-onnx")
        val expected = LlmModelDownloadManager.RECOMMENDED_MODEL_IDS
            .toList()
            .filter { it !in downloaded && it !in inProgress }
        assertEquals(
            expected,
            RecommendedModelAutoDownloader.computeMissing(downloaded, inProgress)
        )
    }

    @Test
    fun nothing_missing_returns_empty() {
        val all = LlmModelDownloadManager.RECOMMENDED_MODEL_IDS
        assertEquals(
            emptyList<String>(),
            RecommendedModelAutoDownloader.computeMissing(all, emptySet())
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**（类不存在）

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.download.RecommendedModelAutoDownloaderTest"`
Expected: FAIL（unresolved reference）。

- [ ] **Step 3: 创建 `RecommendedModelAutoDownloader.kt`**

```kotlin
package com.mamba.picme.data.download

import android.content.Context
import com.mamba.picme.core.common.NetworkUtils
import com.mamba.picme.data.preferences.UserSettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 推荐模型 WiFi 静默预下载（最小实现）。
 *
 * 非 WorkManager——项目未引入 androidx.work；复用既有 [NetworkUtils.isWifi]
 * 与 [LlmModelDownloadManager.downloadModel]。
 *
 * @param settings 用于读取 [UserSettingsRepository.autoDownloadRecommendedOnWifiFlow]。
 * @param downloader 用于查询已下载与发起下载。
 */
class RecommendedModelAutoDownloader(
    private val context: Context,
    private val settings: UserSettingsRepository,
    private val downloader: LlmModelDownloadManager
) {
    private val running = AtomicBoolean(false)

    /**
     * 纯逻辑：需要下载的推荐模型（[LlmModelDownloadManager.RECOMMENDED_MODEL_IDS]
     * 去除已下载 [downloadedIds] 与进行中 [inProgressIds]，保持集合稳定顺序）。
     */
    companion object {
        fun computeMissing(
            downloadedIds: Set<String>,
            inProgressIds: Set<String>
        ): List<String> = LlmModelDownloadManager.RECOMMENDED_MODEL_IDS
            .filter { id -> id !in downloadedIds && id !in inProgressIds }
    }

    /**
     * 满足条件时静默下载缺失推荐模型：设置开启 + WiFi + 有缺失项。
     * 不可重入；单模型失败不中断其余；不自动重试。
     */
    suspend fun triggerIfEligible(inProgressIds: Set<String> = emptySet()) {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!settings.autoDownloadRecommendedOnWifiFlow.first()) return
            if (!NetworkUtils.isWifi(context)) return
            val downloadedIds = LlmModelDownloadManager.RECOMMENDED_MODEL_IDS
                .filter { id -> downloader.isModelDownloaded(id) }
                .toSet()
            val missing = computeMissing(downloadedIds, inProgressIds)
            for (id in missing) {
                runCatching { downloader.downloadModel(id).collect { /* 驱动至完成 */ } }
            }
        } finally {
            running.set(false)
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.download.RecommendedModelAutoDownloaderTest"`
Expected: PASS（2 用例）。

- [ ] **Step 5: 编译确认整体**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/data/download/RecommendedModelAutoDownloader.kt \
        app/src/test/java/com/mamba/picme/data/download/RecommendedModelAutoDownloaderTest.kt
git commit -m "feat(download): 推荐模型 WiFi 静默预下载器（纯逻辑 + 触发器）"
```

---

## Task 9: PoLangApplication 接入触发器 + SmolVLM 孤儿清理

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/PoLangApplication.kt`（onCreate ~107；NetworkCallback ~264–315；需既有 app CoroutineScope）
- Modify: `app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt`（新增迁移标志 key + 读取/写入，或复用 dataStore）

- [ ] **Step 1: Read `PoLangApplication.kt` 确认**：app 级 `CoroutineScope`、`AppContainer`/DI 如何拿到 `LlmModelDownloadManager` 与 `UserSettingsRepository`、NetworkCallback 的 capabilities 回调体（~292–315）。

- [ ] **Step 2: UserPreferencesRepository 增加迁移标志**

```kotlin
// PreferencesKeys：
val MIGRATION_SMOLVLM_PURGED = booleanPreferencesKey("migration_smolvlm_purged")
```
```kotlin
// 读取/写入（suspend，仿 updateDebugUiEnabled）：
suspend fun isSmolvlmPurged(): Boolean =
    context.dataStore.data.first()[PreferencesKeys.MIGRATION_SMOLVLM_PURGED] ?: false
suspend fun markSmolvlmPurged() {
    context.dataStore.edit { it[PreferencesKeys.MIGRATION_SMOLVLM_PURGED] = true }
}
```
（确认已 import `kotlinx.coroutines.flow.first`。）

- [ ] **Step 3: PoLangApplication 增加 SmolVLM 清理函数**

```kotlin
/** 一次性清理已下线的 smolvlm_500m 目录（~598MB）。失败不阻塞启动。 */
private fun purgeSmolVlmIfFirstRun() {
    appScope.launch {
        val repo = appContainer.userSettingsRepository // 取实际持有的实例
        if (repo.isSmolvlmPurged()) return@launch
        runCatching {
            val dir = com.mamba.picme.data.download.ModelPathConfig.getModelDir(this@PoLangApplication, "smolvlm_500m")
            if (dir.exists()) dir.deleteRecursively()
        }.onFailure { err -> Logger.w(TAG, "smolvlm 清理失败: ${err.message}") }
        repo.markSmolvlmPurged()
    }
}
```
（用 import 消除 FQN：`import com.mamba.picme.data.download.ModelPathConfig`、`import java.io.File`（deleteRecursively 是 File 扩展，kotlin.io 已可用）。`appScope`/`appContainer` 用 Read 确认的真实字段名替换。）

- [ ] **Step 4: 实例化 AutoDownloader 并接入 NetworkCallback + 初始检查**

在 onCreate（或既有初始化处）构造一次：
```kotlin
private lateinit var recommendedAutoDownloader: RecommendedModelAutoDownloader
// onCreate 中（拿到 downloader/settings 后）：
recommendedAutoDownloader = RecommendedModelAutoDownloader(
    context = this,
    settings = appContainer.userSettingsRepository,
    downloader = appContainer.llmModelDownloadManager
)
```
在既有 NetworkCallback 的 WiFi 可用回调（`onCapabilitiesChanged`/`onAvailable`，capabilities 含 `NET_CAPABILITY_INTERNET` 且 `TRANSPORT_WIFI`）内：
```kotlin
appScope.launch { recommendedAutoDownloader.triggerIfEligible() }
```
onCreate 末尾加一次初始检查：
```kotlin
appScope.launch { recommendedAutoDownloader.triggerIfEligible() }
purgeSmolVlmIfFirstRun()
```

- [ ] **Step 5: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/PoLangApplication.kt \
        app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt
git commit -m "feat(app): 接入推荐模型 WiFi 预下载 + SmolVLM 孤儿一次性清理"
```

---

## Task 10: 设置页「推荐模型 WiFi 自动下载」开关 + i18n

**Files:**
- Modify: `app/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml` + `values-zh-rTW/strings.xml`
- Modify: 设置 UI（定位含开关组件的设置子屏，见 Step 1）

- [ ] **Step 1: Read 定位开关 UI**：`SettingsBaseComponents.kt` 中的开关组件签名；以及模型/Agent 设置子屏（如 `SettingsAiAgent.kt` 或含 `tagger_model_key` 设置项的页面）以放置新开关。

- [ ] **Step 2: 三语新增字符串**

`values/strings.xml`：
```xml
<string name="auto_download_recommended_title">Auto-download recommended models</string>
<string name="auto_download_recommended_summary">Download recommended models automatically on Wi-Fi</string>
```
`values-zh-rCN/strings.xml`：
```xml
<string name="auto_download_recommended_title">自动下载推荐模型</string>
<string name="auto_download_recommended_summary">在 Wi-Fi 下自动下载推荐模型</string>
```
`values-zh-rTW/strings.xml`：
```xml
<string name="auto_download_recommended_title">自動下載推薦模型</string>
<string name="auto_download_recommended_summary">在 Wi-Fi 下自動下載推薦模型</string>
```

- [ ] **Step 3: 在设置子屏加入开关**（复用既有 switch 组件；`checked` 绑定 `autoDownloadRecommendedOnWifiFlow`，`onCheckedChange` 调 `updateAutoDownloadRecommendedOnWifi`）

```kotlin
// 形如（具体组件名按 SettingsBaseComponents 实际签名）：
val autoDl by viewModel.autoDownloadRecommendedOnWifiFlow.collectAsState(initial = true)
SettingsSwitch(
    title = stringResource(R.string.auto_download_recommended_title),
    summary = stringResource(R.string.auto_download_recommended_summary),
    checked = autoDl,
    onCheckedChange = { enabled -> viewModel.setAutoDownloadRecommendedOnWifi(enabled) }
)
```
`SettingsViewModel` 暴露对应 state + set 方法（委托 repository）。

- [ ] **Step 4: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml \
        app/src/main/java/com/mamba/picme/features/settings/
git commit -m "feat(settings): 推荐模型 WiFi 自动下载开关（三语）"
```

---

## Task 11: 同步三层文档（与代码原子提交）

**Files:**
- Modify: `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md`
- Modify: `docs/03-TECHNICAL-SPECS/ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md`
- Modify: `docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`
- Modify: `docs/01-PRODUCT/FEATURES.md`（若无模型中心小节，加一段）

- [ ] **Step 1: `TAG_GENERATION.md`** — 打标模型章节改为：默认 `florence2_base`（ONNX INT8，~260MB），备选 `qwen3_vl_2b`；删除 SmolVLM-500M / LFM2-VL 作为可选项的描述；注明选择器见 `TaggerModelSelector`。

- [ ] **Step 2: `ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md`** — 顶部新增「决策 (2026-07-26)」段：经实测，打标终选 Florence-2（默认）+ Qwen3-VL-2B（备选）；SmolVLM/LFM2/MiniCPM 等出局。保留调研正文作参考。

- [ ] **Step 3: `ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`** — 模型清单表同步：必须 7 / 推荐 6 分层；删除 `smolvlm_500m` 行；`florence2_base`、`opus-mt-en-zh` 标必须；`mediapipe-face-landmarker`、`qwen3_5_2b`、sherpa×2、modnet、u2netp 标推荐；`qwen3_vl_2b` 标普通可选（备选 tagger）。

- [ ] **Step 4: `FEATURES.md`** — 新增「模型中心：必须 / 推荐 分层 + WiFi 静默预下载」行为说明（必须模型扫描前提醒；推荐模型 WiFi 下默认静默预下载，可在设置关闭）。

- [ ] **Step 5: 提交**

```bash
git add docs/03-TECHNICAL-SPECS/TAG_GENERATION.md \
        docs/03-TECHNICAL-SPECS/ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md \
        docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md \
        docs/01-PRODUCT/FEATURES.md
git commit -m "docs: 同步打标收敛与必须/推荐分层（三层文档）"
```

---

## Task 12: 全量验证

- [ ] **Step 1: 全量 JVM 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿（含 Task 1/2/8 新增 + 既有用例）。

- [ ] **Step 2: 编译 debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 设备端冒烟（人工，非阻塞）** — 模型中心「必须」7 项 /「推荐」6 项分组正确；推荐 Tab 进度可见；设置开关在；WiFi 下触发预下载；启动后旧 smolvlm 目录被清。

- [ ] **Step 4: 总结提交（如需合并整理）** — 按 `git log` 自检；本分支可进入评审/合并流程（见 finishing-a-development-branch）。

---

## Self-Review（计划完成后）

**Spec coverage（对照 spec §3–§10）**：
- §3.1 必须 7 项 → Task 4 Step 1（−qwen3_vl_2b / +florence2）。✓
- §3.2 推荐 6 项 → Task 4 Step 2（CHAT_MODEL_IDS + modnet/u2netp/mediapipe）。✓
- §3.3 qwen3_vl_2b 普通可选 → Task 1（仅 fallback）+ Task 11 文档。✓
- §4 打标收敛（选择器/目录/孤儿）→ Task 1 / 2 / 3 / 9。✓
- §5 单一事实来源 → Task 4 + Task 6（GALLERY 派生）。✓
- §6 推荐 Tab → Task 5。✓
- §7 WiFi 自动下载（settings/触发器/入口）→ Task 7 / 8 / 9。✓
- §8 strings/strings → Task 10。✓
- 文档 → Task 11。✓

**Placeholder scan**：无 TBD/TODO；Task 9/10 的 `appScope`/`appContainer`/`SettingsSwitch` 以「Read 确认真实名」标注（执行时内联读取替换，非占位）。**Type consistency**：`RECOMMENDED_MODEL_IDS`、`isRecommended`、`computeMissing`、`autoDownloadRecommendedOnWifiFlow`/`updateAutoDownloadRecommendedOnWifi` 跨任务命名一致。✓
