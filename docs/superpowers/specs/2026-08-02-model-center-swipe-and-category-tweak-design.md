# 模型中心横滑切换 + 分类调整 设计

- 日期：2026-08-02
- 范围：`app` 模块（模型中心 UI + 模型清单数据）
- 状态：已 brainstorm，待实现

## 1. 背景与现状

模型中心 = `ModelCenterScreen`（`app/src/main/java/com/mamba/picme/features/settings/LlmModelManagerScreen.kt:131`）。

当前结构：
- `Scaffold` + `AppTopBar`（标题"模型中心"+ 返回）。
- `Column` 内：可横向滚动的 Chip 风格分类栏 `ScrollableCategoryTabs`（:295）+ 单个 `LazyColumn`，按当前分类 `currentTab` 渲染该分类下的模型。
- 分类（Tab）顺序由 `serviceCategoryTags`（`LlmModelDownloadManager.kt:1450`）= `["must-have","recommended","chat","photo-tagging","beauty-camera"]` 决定；分组由 `groupByCategory()`（:1481）按此顺序遍历产生。
- 分类显示名由 `DEFAULT_TAG_TRANSLATIONS`（:144）提供（单语言硬编码 map）。
- 切换方式：仅支持点击 Chip（`viewModel.switchTab(category)`），**不支持左右横滑**。
- 特殊页："必须"(`must-have`) 页顶有 `MustHaveHeaderCard`（一键下载）；"推荐"(`recommended`) 页顶有 `RecommendedHeaderCard`（Wi-Fi 自动下载开关）。

数据源全部本地可控：
- 模型清单：`res/raw/llm_models.json`（`loadLocalModels()` 读取，`loadMarketData/refreshMarketData` 均只读本地；网络 `fetchMarketData` 未启用）。
- 分类顺序：`LlmModelDownloadManager.kt:1450` `serviceCategoryTags`。
- 分类名翻译：`LlmModelDownloadManager.kt:144` `DEFAULT_TAG_TRANSLATIONS`。
- 默认分类（兜底）：`UserPreferences.kt:210` `ModelCategory.DEFAULT_CATEGORIES`。

`HorizontalPager` 在项目已是成熟模式（`MainPagerHost`、`MediaPager`、`ChatScreen`、`LlmCallLogScreen`），Compose foundation 依赖齐全（BOM 2024.12.01），无需新增依赖。

## 2. 目标

1. 模型中心支持**左右横滑**切换分类页，与顶部 Chip 栏双向联动。
2. 分类调整：
   - "聊天"(chat) 分类更名为"语音"；
   - chat 下的 VLM 模型 `qwen3_vl_2b` 移至"相册打标"(photo-tagging) 分类；
   - "语音" Tab 调整到末尾。

## 3. 范围

- 全部分类页参与横滑（含"必须""推荐"特殊页），顺序与 Chip 栏一致。
- `qwen3_vl_2b` 仅移分类，不加 `must-have`/`recommended` 标记（它只在"相册打标"页出现）。

## 4. 设计

### 4.1 数据层（全部本地，3 文件 + 1 json）

| 文件 | 位置 | 现状 | 改为 |
|---|---|---|---|
| `LlmModelDownloadManager.kt` | `DEFAULT_TAG_TRANSLATIONS` :144 | `"chat" to "聊天"` | `"chat" to "语音"` |
| `LlmModelDownloadManager.kt` | `serviceCategoryTags` :1450 | `listOf("must-have","recommended","chat","photo-tagging","beauty-camera")` | `listOf("must-have","recommended","photo-tagging","beauty-camera","chat")` |
| `UserPreferences.kt` | `ModelCategory.DEFAULT_CATEGORIES` :210 | `[..., ModelCategory("chat"), ModelCategory("photo-tagging"), ModelCategory("beauty-camera")]` | 把 `ModelCategory("chat")` 移到列表末尾，与 `serviceCategoryTags` 顺序一致 |
| `res/raw/llm_models.json` | `qwen3_vl_2b.tags` :56 | `["chat","vision","tagging","multilingual"]` | `["photo-tagging","vision","tagging","multilingual"]`（去 `chat`、加 `photo-tagging`） |

约束：tag 字符串 `"chat"` 保持不变。语音入口 `SettingsVoice` 的 `Audio→chat` 映射、`CHAT_REQUIRED_MODEL_IDS` 批量下载逻辑均不受影响。

### 4.2 UI 层：横滑（`ModelCenterScreen`，`LlmModelManagerScreen.kt:131`）

引入 `HorizontalPager` 承载各分类页，与 `ScrollableCategoryTabs` 双向联动。顶层 `Scaffold`+`AppTopBar` 结构不变。

结构：

```
Scaffold(AppTopBar) {
  Column {
    ScrollableCategoryTabs(           // 保留；selectedIndex 改读 pagerState.currentPage
      selectedIndex = pagerState.currentPage,
      onCategorySelected = { idx, _ -> scope.launch { pagerState.animateScrollToPage(idx) } }
    )
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
      val category = categories[page]
      ModelCategoryPage(
        category = category,
        models = groupedModels[category] ?: emptyList(),
        isMustHave = category.tag.equals("must-have", ignoreCase = true),
        isRecommended = category.tag.equals("recommended", ignoreCase = true),
        ...下载状态/回调
      )
    }
  }
}
```

关键点：

- `categories = modelTypeLabels.entries.toList()`（有序）；`pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { categories.size })`。
- **initialPage**：复用现有 `LaunchedEffect(initialCategoryTag, modelTypeLabels)` 的映射逻辑（`Audio`/`Chat`→chat、`Vision`→beauty-camera），得到目标 `ModelCategory` 后取其在 `categories` 中的 index；`initialCategoryTag` 为空或未命中则 `0`（"必须"页）。原 `LaunchedEffect` 内的 `viewModel.switchTab(targetCategory)` 改为 `pagerState.scrollToPage(index)`（无动画的初始定位）。
- **滑页 → Chip**：`LaunchedEffect(pagerState) { snapshotFlow { pagerState.currentPage }.collect { idx -> viewModel.switchTab(categories[idx]); scrollChipIntoView(idx) } }`。`scrollChipIntoView`：当前选中 Chip 若在 `ScrollableCategoryTabs` 的 `scrollState` 可视区外，`animateScrollTo` 使其可见。
- **Chip → 滑页**：`onCategorySelected` 改为 `scope.launch { pagerState.animateScrollToPage(idx) }`。`switchTab` 统一由滑页 `collect` 触发，避免 Chip 点击与 pager 双写状态。
- **移除**：本地 `selectedTabIndex` 状态（:146）及其同步 `LaunchedEffect`（:167）——被 `pagerState.currentPage` 取代。

每页内容抽私有 Composable `ModelCategoryPage`，封装现有 `LazyColumn` + `MustHaveHeaderCard`/`RecommendedHeaderCard`/`EmptyModelList`/`ModelCardWithBadge`：

```
@Composable
private fun ModelCategoryPage(
    category: ModelCategory,
    models: List<ModelConfig>,
    isMustHave: Boolean,
    isRecommended: Boolean,
    downloadedIds: Set<String>,
    downloadStates: Map<String, DownloadState>,
    tagTranslations: Map<String, String>,
    autoDownloadRecommended: Boolean,
    onDownload: (ModelConfig) -> Unit,
    onCancel: (String) -> Unit,
    onPause: (String) -> Unit,
    onDelete: (ModelConfig) -> Unit,
    onShowProperties: (ModelConfig) -> Unit,
    onDownloadAllRequired: () -> Unit,
    onAutoDownloadRecommendedChange: (Boolean) -> Unit
)
```

删除确认对话框、模型属性对话框保持在 `ModelCenterScreen` 顶层（不随页），由各页通过回调触发（`modelToDelete`/`modelToShowProperties` 状态不变）。

### 4.3 边界与注意

- **嵌套滚动**：每页是独立 `LazyColumn`（纵向），外层 `HorizontalPager`（横向）；Compose 原生 nested-scroll 自动协调，无需手写。
- **横滑 vs 返回手势**：Android 边缘返回作用于屏幕左边缘，横滑从屏幕中部起，不冲突。
- **i18n**：`DEFAULT_TAG_TRANSLATIONS` 是 data 层单语言硬编码 map（现有"必须/推荐/相册打标/美颜相机"均如此，与 `values/strings.xml` 无关）。本次"语音"沿用此做法，**不扩大为 strings.xml 三语言**（属独立的 i18n 改造）。
- **空分类**：`HorizontalPager` 页数 = `groupByCategory` 实际产出分类数（空分类不生成页），与现状一致。
- **`currentTab` 一致性**：`viewModel.currentTab` 仍由 `switchTab` 维护，作为"当前分类"的 SSOT；`getCurrentTabModels()` 等依赖不变。pager 只是新增的横向切换 UI 层。

## 5. 验证

- **JVM 单测**（`LlmModelDownloadManager` 分组相关测试）：
  - `groupByCategory()` 顺序为 必须/推荐/相册打标/美颜相机/语音；
  - `qwen3_vl_2b` ∈ photo-tagging 分组、∉ chat 分组；
  - sherpa ASR、KWS 仍在 chat（语音）分组。
- **编译**：`./gradlew :app:assembleDebug`。
- **真机**：
  - 横滑切页跟手；
  - 滑页时对应 Chip 高亮并滚进可视区；点 Chip 平滑滑到对应页；
  - 从 设置→语音 入口进入模型中心，初始停在"语音"页；
  - "必须"页一键下载、"推荐"页 Wi-Fi 自动下载开关随页正确工作；
  - 模型下载/暂停/删除/属性对话框正常。

## 6. 不在范围

- 分类名多语言（strings.xml 三语言）改造。
- 新增分类或模型。
- Chip 栏视觉改版（保留现有 Chip 风格）。
