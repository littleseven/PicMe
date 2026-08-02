# 模型中心横滑切换 + 分类调整 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让模型中心支持左右横滑切换分类页（与顶部 Chip 栏双向联动），并把「聊天」分类更名为「语音」、VLM 模型 `qwen3_vl_2b` 归入「相册打标」、「语音」Tab 调到末尾。

**Architecture:** 数据层改动集中在 3 个常量/列表 + 1 个 JSON 模型 tags（全部本地，`groupByCategory()` 在纯 data class `ModelMarketData` 中，可 JVM 单测）。UI 层把 `ModelCenterScreen` 内容区从"单 `LazyColumn` 按 `currentTab` 切换"重构为 `HorizontalPager` 多页，抽出 `ModelCategoryPage` 承载单页内容，顶部 `ScrollableCategoryTabs` 与 `pagerState` 双向联动。

**Tech Stack:** Kotlin、Jetpack Compose（`androidx.compose.foundation.pager.HorizontalPager`，项目已在 `MainPagerHost`/`MediaPager` 等处使用）、JUnit4（纯 JVM 单测）。

**Spec:** `docs/superpowers/specs/2026-08-02-model-center-swipe-and-category-tweak-design.md`

**测试策略说明（务实）：**
- 数据层分类顺序：真 TDD，纯 JVM 单测（`ModelMarketData` 不依赖 Android Context）。
- VLM 归类 / 分类名翻译：回归测试 + 编译 + 真机验证（JSON 数据改动与显示名不直接被 JVM 单测覆盖）。
- UI 横滑：完整代码 + 编译 + 真机验证（Compose UI 测试需设备，按项目惯例放 `androidTest`，本计划用真机/ui-driver 验证）。

---

## File Structure

| 文件 | 角色 | 动作 |
|---|---|---|
| `app/src/test/java/com/mamba/picme/data/download/ModelMarketDataTest.kt` | 数据层分组/顺序/归类 JVM 单测 | Create |
| `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt` | `serviceCategoryTags`(:1450) 顺序 + `DEFAULT_TAG_TRANSLATIONS`(:147) chat→语音 | Modify |
| `app/src/main/java/com/mamba/picme/domain/model/UserPreferences.kt` | `ModelCategory.DEFAULT_CATEGORIES`(:210) chat 移末尾 | Modify |
| `app/src/main/res/raw/llm_models.json` | `qwen3_vl_2b.tags`(:56) 去 chat 加 photo-tagging | Modify |
| `app/src/main/java/com/mamba/picme/features/settings/LlmModelManagerScreen.kt` | `ModelCenterScreen`(:131) 重构为 HorizontalPager；抽 `ModelCategoryPage`；`ScrollableCategoryTabs`(:295) 接收外部 `scrollState` | Modify |

---

## Task 1: 数据层分类顺序调整（TDD）

把 `chat`（语音）分类在 `serviceCategoryTags` 与 `DEFAULT_CATEGORIES` 中移到末尾，使模型中心 Tab 顺序变为 必须/推荐/相册打标/美颜相机/语音。

**Files:**
- Create: `app/src/test/java/com/mamba/picme/data/download/ModelMarketDataTest.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt:1450`
- Modify: `app/src/main/java/com/mamba/picme/domain/model/UserPreferences.kt:210`

- [ ] **Step 1: 写失败测试（分类顺序 + VLM/sherpa 归属回归）**

Create `app/src/test/java/com/mamba/picme/data/download/ModelMarketDataTest.kt`:

```kotlin
package com.mamba.picme.data.download

import com.mamba.picme.domain.model.ModelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMarketDataTest {

    private fun model(id: String, tags: List<String>) = ModelConfig(
        id = id,
        name = id,
        description = "",
        type = "",
        size = 1L,
        sources = emptyMap(),
        files = emptyList(),
        tags = tags
    )

    /**
     * 覆盖 5 个分类的代表模型。
     * - florence2_base / face-det-retina500m-mnn：id 命中真实 REQUIRED_MODEL_IDS 白名单 → isRequired
     * - modnet-onnx：id 命中真实 RECOMMENDED_MODEL_IDS 白名单 → isRecommended
     * - qwen3_vl_2b / sherpa-voice：虚构 id，不在任何白名单，纯按 tags 归类
     */
    private val sampleModels = listOf(
        model("florence2_base", listOf("must-have", "photo-tagging", "vision-llm")),
        model("modnet-onnx", listOf("matting", "recommended")),
        model("qwen3_vl_2b", listOf("photo-tagging", "vision", "tagging")),
        model("face-det-retina500m-mnn", listOf("beauty-camera", "face")),
        model("sherpa-voice", listOf("chat", "ASR"))
    )

    @Test
    fun groupByCategory_returnsExpectedOrder_withVoiceLast() {
        val grouped = ModelMarketData(sampleModels, emptyMap()).groupByCategory()

        assertEquals(
            listOf("must-have", "recommended", "photo-tagging", "beauty-camera", "chat"),
            grouped.keys.map { it.tag }
        )
    }

    @Test
    fun groupByCategory_assignsVlmToPhotoTagging_notToChat() {
        val grouped = ModelMarketData(sampleModels, emptyMap()).groupByCategory()

        val photoTagging = grouped.getValue(ModelCategory("photo-tagging"))
        assertTrue(photoTagging.any { it.id == "qwen3_vl_2b" })

        val chat = grouped.getValue(ModelCategory("chat"))
        assertFalse(chat.any { it.id == "qwen3_vl_2b" })
        assertTrue(chat.any { it.id == "sherpa-voice" })
    }
}
```

- [ ] **Step 2: 跑测试验证失败（顺序断言应红）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.download.ModelMarketDataTest"`
Expected: `groupByCategory_returnsExpectedOrder_withVoiceLast` FAIL（当前顺序为 `must-have,recommended,chat,photo-tagging,beauty-camera`，与期望不符）。`groupByCategory_assignsVlmToPhotoTagging_notToChat` 应已 PASS（回归保护）。

- [ ] **Step 3: 调整 `serviceCategoryTags` 顺序**

Modify `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt:1450`，把 `"chat"` 移到列表末尾：

```kotlin
    /**
     * 服务功能分类标签集合（用于顶部 Tab 分类）
     * 语音(chat)分类调整到末尾。
     */
    private val serviceCategoryTags = listOf("must-have", "recommended", "photo-tagging", "beauty-camera", "chat")
```

- [ ] **Step 4: 同步 `ModelCategory.DEFAULT_CATEGORIES`**

Modify `app/src/main/java/com/mamba/picme/domain/model/UserPreferences.kt:210-215`，把 `ModelCategory("chat")` 移到末尾：

```kotlin
        /** 预置的服务功能分类，用于本地缓存缺失时的默认展示 */
        val DEFAULT_CATEGORIES = listOf(
            ModelCategory("must-have"),
            ModelCategory("photo-tagging"),
            ModelCategory("beauty-camera"),
            ModelCategory("chat")
        )
```

- [ ] **Step 5: 跑测试验证通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.download.ModelMarketDataTest"`
Expected: PASS（2 个测试全绿）。

- [ ] **Step 6: Commit**

```bash
git add app/src/test/java/com/mamba/picme/data/download/ModelMarketDataTest.kt \
        app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt \
        app/src/main/java/com/mamba/picme/domain/model/UserPreferences.kt
git commit -m "feat(model): 模型中心分类顺序调整，语音(chat)移至末尾" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: VLM 归入相册打标 + chat 分类更名语音

把 `qwen3_vl_2b` 的 tags 从 chat 改到 photo-tagging；把分类名翻译 `chat` 由「聊天」改为「语音」。

**Files:**
- Modify: `app/src/main/res/raw/llm_models.json`（`qwen3_vl_2b.tags`，约第 56 行）
- Modify: `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt:147`

- [ ] **Step 1: 改 `qwen3_vl_2b.tags`**

Modify `app/src/main/res/raw/llm_models.json`，找到 `id: "qwen3_vl_2b"` 的对象，把其 `tags` 由

```json
    "tags": ["chat", "vision", "tagging", "multilingual"]
```

改为（去 `chat`、加 `photo-tagging`）：

```json
    "tags": ["photo-tagging", "vision", "tagging", "multilingual"]
```

- [ ] **Step 2: 改分类名翻译 `chat` → 「语音」**

Modify `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt:147`，把

```kotlin
            "chat" to "聊天",
```

改为：

```kotlin
            "chat" to "语音",
```

- [ ] **Step 3: 跑数据层单测（回归保护，确认逻辑未被破坏）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.download.ModelMarketDataTest"`
Expected: PASS（Task 1 的测试仍绿）。

- [ ] **Step 4: 编译确认（JSON 格式 + Kotlin 编译）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/raw/llm_models.json \
        app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt
git commit -m "feat(model): VLM(qwen3_vl_2b)归入相册打标 + chat分类更名语音" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: UI 重构 —— HorizontalPager 横滑 + Chip 联动

把 `ModelCenterScreen` 内容区重构为 `HorizontalPager`（每分类一页），抽出 `ModelCategoryPage`；`ScrollableCategoryTabs` 与 `pagerState` 双向联动；移除旧的 `selectedTabIndex` 状态。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/settings/LlmModelManagerScreen.kt`

- [ ] **Step 1: 补充 imports**

在 `LlmModelManagerScreen.kt` 顶部 import 区（`androidx.compose.foundation.horizontalScroll` 附近）新增：

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
```

- [ ] **Step 2: 抽出 `ModelCategoryPage` composable**

在 `ModelCenterScreen` 函数之后、`ScrollableCategoryTabs` 之前，新增私有 Composable（封装原有 `LazyColumn` + `MustHaveHeaderCard`/`RecommendedHeaderCard`/`EmptyModelList`/`ModelCardWithBadge`）：

```kotlin
/**
 * 单个分类页内容（HorizontalPager 的一页）
 */
@Composable
private fun ModelCategoryPage(
    category: ModelCategory,
    models: List<ModelConfig>,
    downloadedIds: Set<String>,
    downloadStates: Map<String, DownloadState>,
    tagTranslations: Map<String, String>,
    autoDownloadRecommended: Boolean,
    onDownload: (ModelConfig) -> Unit,
    onCancel: (ModelConfig) -> Unit,
    onPause: (ModelConfig) -> Unit,
    onDelete: (ModelConfig) -> Unit,
    onShowProperties: (ModelConfig) -> Unit,
    onDownloadAllRequired: () -> Unit,
    onAutoDownloadRecommendedChange: (Boolean) -> Unit
) {
    val isMustHaveTab = category.tag.equals("must-have", ignoreCase = true)
    val isRecommendedTab = category.tag.equals("recommended", ignoreCase = true)

    if (models.isEmpty()) {
        EmptyModelList()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (isMustHaveTab) {
            item(key = "must-have-header") {
                val missingCount = models.count { it.id !in downloadedIds }
                MustHaveHeaderCard(
                    requiredCount = models.size,
                    missingCount = missingCount,
                    onDownloadAll = onDownloadAllRequired
                )
            }
        }

        if (isRecommendedTab) {
            item(key = "recommended-header") {
                RecommendedHeaderCard(
                    checked = autoDownloadRecommended,
                    onCheckedChange = onAutoDownloadRecommendedChange
                )
            }
        }

        items(models) { model ->
            val downloadState = downloadStates[model.id]
            val isDownloaded = model.id in downloadedIds
            ModelCardWithBadge(
                model = model,
                downloadState = downloadState,
                isDownloaded = isDownloaded,
                tagTranslations = tagTranslations,
                onDownload = { onDownload(model) },
                onCancel = { onCancel(model) },
                onPause = { onPause(model) },
                onDelete = { onDelete(model) },
                onShowProperties = { onShowProperties(model) }
            )
        }
    }
}
```

- [ ] **Step 3: 改 `ScrollableCategoryTabs` 接收外部 `scrollState`**

Modify `ScrollableCategoryTabs`（约 :295），签名增加 `scrollState: ScrollState` 参数，删除函数体内的 `val scrollState = rememberScrollState()`：

```kotlin
/**
 * 可滚动的分类 Tab 栏 - 使用 Chip 风格替代 TabRow，避免文字截断
 */
@Composable
private fun ScrollableCategoryTabs(
    categories: Map<ModelCategory, String>,
    scrollState: ScrollState,
    selectedIndex: Int,
    onCategorySelected: (Int, ModelCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.entries.forEachIndexed { index, entry ->
            // ……（forEachIndexed 内部渲染逻辑保持不变：Surface/Icon/Text）
        }
    }
}
```

注：`forEachIndexed` 内部（`Surface`/`Icon`/`Text` 渲染）保持原样不变。

- [ ] **Step 4: 重写 `ModelCenterScreen` 主体为 HorizontalPager + 联动**

Replace `ModelCenterScreen` 函数体（约 :131–252，从 `@OptIn` 注解到 `Scaffold` 闭合 `}`）。`rememberScrollState` 的 import 若已存在则保留。新主体：

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModelCenterScreen(
    viewModel: SettingsViewModel,
    initialCategoryTag: String = "",
    onNavigateBack: () -> Unit
) {
    val groupedModels by viewModel.groupedModels.collectAsState()
    val modelTypeLabels = viewModel.getModelTypeLabels()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val tagTranslations by viewModel.tagTranslations.collectAsState()
    val autoDownloadRecommended by viewModel.autoDownloadRecommendedOnWifi.collectAsState()
    var modelToDelete by remember { mutableStateOf<ModelConfig?>(null) }
    var modelToShowProperties by remember { mutableStateOf<ModelConfig?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val categories = modelTypeLabels.entries.toList()
    val pagerState = rememberPagerState(
        initialPage = remember(initialCategoryTag, categories) {
            initialPageIndex(initialCategoryTag, categories)
        },
        pageCount = { categories.size }
    )
    val chipScrollState = rememberScrollState()

    // 横滑切页 → 同步当前分类 + 把选中 Chip 滚进可视区
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page !in categories.indices) return@collect
            viewModel.switchTab(categories[page].key)
            val target = if (categories.size > 1) {
                (chipScrollState.maxValue.toFloat() * page / (categories.size - 1)).toInt()
            } else {
                0
            }
            chipScrollState.animateScrollTo(target)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.model_center)) },
                navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            ScrollableCategoryTabs(
                categories = modelTypeLabels,
                scrollState = chipScrollState,
                selectedIndex = pagerState.currentPage,
                onCategorySelected = { index, _ ->
                    coroutineScope.launch { pagerState.animateScrollToPage(index) }
                }
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val category = categories[page].key
                ModelCategoryPage(
                    category = category,
                    models = groupedModels[category] ?: emptyList(),
                    downloadedIds = downloadedModels.map { it.id }.toSet(),
                    downloadStates = downloadStates,
                    tagTranslations = tagTranslations,
                    autoDownloadRecommended = autoDownloadRecommended,
                    onDownload = { model ->
                        if (downloadStates[model.id]?.status == DownloadStatus.PAUSED) {
                            viewModel.resumeModelDownload(model.id, model)
                        } else {
                            viewModel.downloadModel(model.id, model)
                        }
                    },
                    onCancel = { model -> viewModel.cancelModelDownload(model.id) },
                    onPause = { model -> viewModel.pauseModelDownload(model.id) },
                    onDelete = { model -> modelToDelete = model },
                    onShowProperties = { model -> modelToShowProperties = model },
                    onDownloadAllRequired = { viewModel.downloadAllRequiredModels() },
                    onAutoDownloadRecommendedChange = { enabled ->
                        viewModel.setAutoDownloadRecommendedOnWifi(enabled)
                    }
                )
            }
        }
    }

    // 删除确认对话框（保持原样，不变）
    if (modelToDelete != null) {
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text(stringResource(R.string.delete_model_title)) },
            text = { Text(stringResource(R.string.delete_model_confirm, modelToDelete?.name ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deletingModel = modelToDelete ?: return@TextButton
                        modelToDelete = null
                        coroutineScope.launch {
                            viewModel.cancelModelDownload(deletingModel.id)
                            viewModel.deleteDownloadedModel(deletingModel.id)
                            viewModel.refreshModels()
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 模型属性对话框（保持原样，不变）
    if (modelToShowProperties != null) {
        ModelPropertiesDialog(
            model = modelToShowProperties!!,
            onDismiss = { modelToShowProperties = null }
        )
    }
}

/**
 * 根据入口传入的初始标签计算 Pager 初始页索引。
 * Chat/Audio → chat（语音）；Vision → beauty-camera；其它按标签字面匹配。
 */
private fun initialPageIndex(
    initialCategoryTag: String,
    categories: List<Map.Entry<ModelCategory, String>>
): Int {
    if (initialCategoryTag.isBlank() || categories.isEmpty()) return 0
    val target = when (initialCategoryTag) {
        "Chat", "Audio" -> ModelCategory("chat")
        "Vision" -> ModelCategory("beauty-camera")
        else -> ModelCategory(initialCategoryTag)
    }
    return categories.indexOfFirst { it.key == target }.coerceAtLeast(0)
}
```

要点（务必删除的旧代码）：
- 删除旧本地状态 `var selectedTabIndex by remember { mutableIntStateOf(0) }`（原 :146）。
- 删除两个旧 `LaunchedEffect`：根据 `initialCategoryTag` 设 tab 的（原 :152-164）、同步 `selectedTabIndex` 的（原 :167-170）——分别由 `initialPageIndex` 与 pager 联动 `LaunchedEffect` 取代。
- 删除旧 `Column` 内直接渲染的 `LazyColumn`/`EmptyModelList`/`MustHaveHeaderCard`/`RecommendedHeaderCard`/`ModelCardWithBadge` 块（原 :191-250）——已迁入 `ModelCategoryPage`。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。若报 ` ExperimentalFoundationApi` OptIn 警告已用 `@OptIn` 处理；若 `mutableIntStateOf` import 变成未使用，按 IDE 提示移除。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/settings/LlmModelManagerScreen.kt
git commit -m "feat(model-center): 分类页支持左右横滑切换并与Chip联动" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: 真机验证

**Files:** 无（仅验证）

- [ ] **Step 1: 安装到设备**

Run: `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/polang-debug.apk`
Expected: Success。

- [ ] **Step 2: 进入模型中心，验证 Tab 顺序与分类名**

进入 设置 → 相册功能 → 模型中心（或「相册调试功能→图片下载页」入口，以设备实际为准）。确认顶部 Chip 顺序为：必须 / 推荐 / 相册打标 / 美颜相机 / **语音**（最后，且名为「语音」而非「聊天」）。

- [ ] **Step 3: 验证横滑与联动**

- 左右横滑内容区，页面跟随切换；滑动停止后，对应 Chip 高亮。
- 横滑到靠后的分类时，顶部 Chip 栏自动滚动让选中 Chip 可见。
- 点击某个 Chip，内容区平滑滑到对应页。

- [ ] **Step 4: 验证 VLM 归类与特殊页**

- 横滑到「相册打标」页：确认 `Qwen3-VL-2B` 出现在该页。
- 横滑到「语音」页：确认只有语音相关模型（Sherpa ASR / KWS），无 VLM。
- 横滑到「必须」页：顶部「一键下载」卡正常；「推荐」页：Wi-Fi 自动下载开关可切换。

- [ ] **Step 5: 验证语音入口跳转**

进入 设置 → 语音控制 → 「语音模型」入口（`SettingsVoice` 传 `Audio`）。确认模型中心初始停在「语音」页（最后一页）。

- [ ] **Step 6: 收尾**

如有回归问题回到对应 Task 修复；全部通过则本分支实施完成，按 worktree 合并流程（`git push origin HEAD:main`，主仓库 `git fetch && git merge --ff-only`）合入 main。

---

## Self-Review

**1. Spec 覆盖：**
- 横滑（全部分类参与 + Chip 联动）→ Task 3 ✓
- chat→语音 → Task 2 Step 2 ✓
- qwen3_vl_2b → photo-tagging → Task 2 Step 1 ✓
- 语音 tab 末尾 → Task 1（serviceCategoryTags + DEFAULT_CATEGORIES）✓
- tag 串 chat 不变（未触碰白名单/映射）→ 无相关改动 ✓
- ModelCategoryPage 抽取 + 移除 selectedTabIndex → Task 3 ✓
- 验证（顺序/归类单测 + 编译 + 真机）→ Task 1/2/3/4 ✓

**2. 占位符扫描：** 无 TBD/TODO；所有代码步骤含完整代码。

**3. 类型一致性：**
- `ModelCategoryPage.downloadStates: Map<String, DownloadState>` 与 `viewModel.downloadStates: StateFlow<Map<String, DownloadState>>` 的 collectAsState 值一致 ✓
- `ModelCardWithBadge` 回调为无参 `()->Unit`，`ModelCategoryPage` 用 `{ onDownload(model) }` 等包装 ✓
- `ScrollableCategoryTabs` 新增 `scrollState: ScrollState`，调用处传 `chipScrollState` ✓
- `initialPageIndex` 用 `List<Map.Entry<ModelCategory, String>>` 与 `categories = modelTypeLabels.entries.toList()` 一致 ✓
