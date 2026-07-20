# 标签查看测试页（Tag Viewer）设计

> **状态**：设计稿，待写实现计划
> **日期**：2026-07-19
> **范围**：在 Debug 页新增一个**只读**的标签查看测试页，以双视图 Tab（照片列表 / 标签聚合）展示 `media_assets.labels` 中已生成的标签，用于人工抽检打标结果质量（当前重点是验证 Qwen3.5-0.8B 切换后的标签质量）。

---

## 1. 目标

提供一个 DEBUG-only 的可视化页面，让开发者能够：

1. **照片视角浏览**：以列表形式逐张查看每张照片生成的标签（scene / activity / objects / tags / summary / face）。
2. **标签视角聚合**：按字段聚合统计所有标签的出现频次，观察标签体系覆盖度。
3. **质量抽检**：点开任意照片查看完整标签 + 原始 labels JSON，人工核对标签是否合理。
4. **零副作用**：纯只读，不写数据库、不触发任何 LLM / 人脸检测 / MobileCLIP 推理。

---

## 2. 设计原则

- **只读**：本页只消费 `MediaDao.getAllMedia()` 的已有数据，绝不触发任何推理管线。需要"重新打标"能力时另开页面，不在本页混入。
- **DEBUG-only**：路由挂在 `MainActivity` 的 `if (BuildConfig.DEBUG)` 块内，release 包不含本页。
- **沿用现有模式**：照抄 `SentencePieceTestScreen` + `SentencePieceTestViewModel` 的结构（`Screen(onNavigateBack)` + Scaffold + sealed UiState）。
- **纯函数可测**：labels JSON 解析与标签聚合都做成无 Android 依赖的纯函数，单元测试覆盖容错路径。
- **容错优先**：labels 字段可能为空、无效 JSON、或旧格式（如 Pass1-only 的 `{"face":{"count":0}}`），解析失败时该行降级为"未打标"，绝不崩溃。

---

## 3. 数据模型

### 3.1 解析后的标签（对应 `UnifiedTagResult`）

```kotlin
/** 从 labels JSON 解析出的单张照片标签（字段对齐 UnifiedTagResult） */
data class ParsedTags(
    val scene: String = "",
    val activity: String = "",
    val objects: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val face: ParsedFaceInfo? = null
)

data class ParsedFaceInfo(
    val count: Int = 0,
    val selfie: Boolean = false,
    val groupPhoto: Boolean = false,
    val personIds: List<Long> = emptyList()
)
```

### 3.2 列表项

```kotlin
/** 照片列表 Tab 的单行数据 */
data class PhotoTagsItem(
    val mediaId: Long,
    val uri: String,          // 取自 MediaEntity.uri，供 Coil 加载缩略图
    val fileName: String,     // 取自 MediaEntity.fileName
    val parsed: ParsedTags,   // 解析自 MediaEntity.labels
    val rawJson: String,      // 原始 labels 字符串，调试展示
    val hasLabels: Boolean    // labels 非空且解析成功
)
```

### 3.3 标签聚合

```kotlin
/** 单个标签的聚合计数 */
data class TagCount(val label: String, val count: Int)

/** 按字段分组的聚合结果（用于标签聚合 Tab） */
data class TagAggregates(
    val scenes: List<TagCount>,    // scene 字段聚合（按出现次数降序）
    val objects: List<TagCount>,   // objects 字段聚合
    val tags: List<TagCount>       // tags 字段聚合
)
```

### 3.4 UI 状态

```kotlin
sealed interface TagViewerUiState {
    data object Loading : TagViewerUiState
    data class Ready(
        val photos: List<PhotoTagsItem>,
        val aggregates: TagAggregates,
        val filteredPhotos: List<PhotoTagsItem>  // 应用搜索过滤后的列表
    ) : TagViewerUiState
    data class Error(val message: String) : TagViewerUiState
}
```

---

## 4. 路由与入口

### 4.1 路由

在 `navigation/Screen.kt` 增加：

```kotlin
data object TagViewer : Screen("tag_viewer")
```

### 4.2 导航挂载（DEBUG-only）

在 `MainActivity.kt` 现有 `if (BuildConfig.DEBUG)` 块内（与 `SearchTest` / `SentencePieceTest` 并列，约 `MainActivity.kt:467`）增加：

```kotlin
composable(Screen.TagViewer.route) {
    TagViewerTestScreen(onNavigateBack = { navController.popBackStack() })
}
```

### 4.3 入口按钮

在 `DebugScreen` 中增加一个入口按钮"查看生成标签"，点击后 `navController.navigate(Screen.TagViewer.route)`。实现时对齐现有 `SearchTest` / `SentencePieceTest` 的入口暴露方式（若 Debug 页已有测试子页入口区，挂到同一区；否则新增一个按钮卡片）。

---

## 5. 页面结构（双视图 Tab）

`TagViewerTestScreen` = `Scaffold` + `TopAppBar`（标题"标签查看" + 返回）+ `TabRow`（两个 Tab）。

### 5.1 Tab A · 照片列表（默认选中）

- **顶部搜索框**：按 `fileName` / `scene` / `activity` / `tags` / `objects` 文本模糊过滤（大小写不敏感）。
- **LazyColumn 列表**，每行布局：
  - 左：缩略图 56dp（Coil `AsyncImage`，`model = MediaEntity.uri`）。
  - 右：
    - 第 1 行：`fileName`（加粗）+ 右侧场景 chip（取 `scene`，无则显示"未打标"灰字）。
    - 第 2 行：`activity`（有则显示，前置「活动·」标签）。
    - 第 3 行：`tags` 前 3 个（chip 形式，超出显示 `+N`）。
- **点击行 → 折叠展开详情区**（同一页内，`remember` 保存展开的 mediaId）：
  - 完整字段：scene / activity / objects（全部）/ tags（全部）/ summary / face（count / selfie / groupPhoto / personIds）。
  - 原始 `labels` JSON：等宽字体（`FontFamily.Monospace`）展示 `rawJson`，便于调试核对。

### 5.2 Tab B · 标签聚合

- **三个分组 section**（`场景` / `物体` / `标签`），每组下按 `count` 降序排列。
- 每行：`标签名 (N 张)` + 右侧 `>`。
- **点击行 → 折叠展开**该标签下的照片缩略图网格（横向滚动的缩略图列表，或折叠子列表，实现时取简单者）。
- 组内为空时显示"暂无"占位。

---

## 6. 数据流与 ViewModel

```
MediaDao.getAllMedia(): Flow<List<MediaEntity>>
    → TagViewerViewModel 订阅
    → 对每条 MediaEntity：TagJsonParser.parse(entity.labels) → ParsedTags
    → 组装 PhotoTagsItem(mediaId, uri, fileName, parsed, rawJson, hasLabels)
    → TagAggregator.aggregate(allParsed) → TagAggregates
    → TagViewerUiState.Ready(photos, aggregates, filteredPhotos)
```

- `TagViewerViewModel`：
  - 持有 `MutableStateFlow<TagViewerUiState>`，初始 `Loading`。
  - 在 `init` 中 `viewModelScope.launch` 订阅 `MediaDao.getAllMedia()`，每次发射重新解析 + 聚合，发出 `Ready`。
  - 持有搜索关键字状态，关键字变化时仅重算 `filteredPhotos`（不重新解析）。
  - 异常时发出 `Error`。
- **无 DB 写入、无推理调用**。

---

## 7. JSON 解析与容错（`TagJsonParser`）

纯函数，无 Android 依赖，输入 `labels: String?`，输出 `ParsedTags?`（含解析是否成功的语义）。

容错矩阵：

| labels 输入 | 行为 |
|---|---|
| `null` 或空白 | 返回 null → `hasLabels=false`，行显示"未打标" |
| 非 JSON 字符串 | 捕获异常，返回 null → "未打标" |
| 合法 JSON 但缺字段 | 缺省字段用默认值（`scene=""`, `objects=[]` 等） |
| 旧格式（如 `{"face":{"count":0}}`，无 scene/tags） | 正常解析已有字段，其余缺省 → 行显示但标签区空 |
| 标准格式（含全部字段） | 完整解析 |

实现要点：
- 用 `org.json.JSONObject`（项目已普遍使用，见 `TagGenerationScheduler.unifiedTagToJson`）。
- 字段名严格对齐 `unifiedTagToJson` 输出：`face` / `scene` / `activity` / `objects` / `tags` / `qwenSummary`。
- `face` 为对象时解析 `count` / `selfie` / `groupPhoto` / `personIds`；缺失或类型不符时用默认值。

---

## 8. 错误处理

| 场景 | 表现 |
|---|---|
| 数据库为空（无照片） | 全屏占位"暂无照片" |
| 全部照片未打标 | 照片列表正常显示，每行"未打标"；聚合 Tab 显示三组"暂无" |
| 单张 labels 解析异常 | 仅该行降级为"未打标"，不影响其他行与聚合统计 |
| 订阅 Flow 抛异常 | `UiState.Error`，显示错误信息 + 重试按钮 |

---

## 9. 文件清单

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/mamba/picme/navigation/Screen.kt` | 增加 `TagViewer` 路由 |
| `app/src/main/java/com/mamba/picme/MainActivity.kt` | DEBUG 块内挂 `composable(Screen.TagViewer.route)` |
| `app/src/main/java/com/mamba/picme/features/debug/DebugScreen.kt` | 增加入口按钮 |
| `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerTestScreen.kt` | UI：双视图 Tab + 列表 + 折叠详情 |
| `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerViewModel.kt` | 状态管理 + 数据订阅 |
| `app/src/main/java/com/mamba/picme/features/tagviewer/TagJsonParser.kt` | 纯函数：labels JSON → `ParsedTags?` |
| `app/src/main/java/com/mamba/picme/features/tagviewer/TagAggregator.kt` | 纯函数：`List<ParsedTags>` → `TagAggregates` |
| `app/src/test/java/com/mamba/picme/features/tagviewer/TagJsonParserTest.kt` | 解析纯函数单测（覆盖容错矩阵） |
| `app/src/test/java/com/mamba/picme/features/tagviewer/TagAggregatorTest.kt` | 聚合纯函数单测 |
| `app/src/main/res/values/strings.xml` | EN 文案 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 简中文案 |
| `app/src/main/res/values-zh-rTW/strings.xml` | 繁中文案 |

---

## 10. 测试策略

- **`TagJsonParserTest`**（纯函数，JVM 单测）覆盖第 7 节容错矩阵全部场景 + 字段缺省 + face 子结构解析。
- **`TagAggregatorTest`**（纯函数，JVM 单测）覆盖：
  - 空输入 → 三组皆空。
  - 单张多标签 → 计数正确。
  - 多张相同标签 → 累加正确。
  - 降序排列正确。
- **ViewModel 测试**（可选）：用 fake / in-room `MediaDao` 验证 `Loading → Ready` 状态流转与搜索过滤。
- **手动 smoke**：debug 包进入页面，切换两个 Tab，点开若干行核对 JSON 与展示一致。

---

## 11. 不做（YAGNI）

- 不触发任何推理 / 重新打标（单张或批量）。
- 不写入数据库。
- 不做照片大图编辑器（点击只在当前页折叠展开详情 + JSON）。
- 不做标签编辑 / 删除 / 修正。
- 不进入 release 包（DEBUG-only）。

---

## 12. 实现 checklist

- [ ] 在 `navigation/Screen.kt` 增加 `TagViewer` 路由
- [ ] 在 `MainActivity` DEBUG 块挂 `composable(Screen.TagViewer.route)`
- [ ] 在 `DebugScreen` 增加入口按钮
- [ ] 实现 `TagJsonParser` 纯函数 + 单测
- [ ] 实现 `TagAggregator` 纯函数 + 单测
- [ ] 实现 `TagViewerViewModel`（订阅 + 解析 + 聚合 + 搜索过滤）
- [ ] 实现 `TagViewerTestScreen`（双视图 Tab + 照片列表 + 折叠详情 + 聚合视图）
- [ ] 三语文案同步（values / values-zh-rCN / values-zh-rTW）
- [ ] 编译验证 `./gradlew :app:assembleDebug`
- [ ] 单测 `./gradlew :app:testDebugUnitTest`
- [ ] debug 包手动 smoke：两 Tab 切换、行折叠、JSON 核对
