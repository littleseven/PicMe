# 标签查看测试页（Tag Viewer）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Debug 页新增一个 DEBUG-only、纯只读的标签查看测试页，以双视图 Tab（照片列表 / 标签聚合）展示 `media_assets.labels` 中已生成的标签，用于人工抽检打标质量。

**Architecture:** 沿用现有 `SentencePieceTestScreen` + `SentencePieceTestViewModel`（`AndroidViewModel`）模式。labels JSON 解析与标签聚合做成无 Android 依赖的纯函数（`TagJsonParser` / `TagAggregator`），TDD 覆盖容错路径；`TagViewerViewModel` 订阅 `MediaDao.getAllMedia()` 解析聚合；UI 为 Compose 双视图 Tab。路由挂在 `MainActivity` 的 `if (BuildConfig.DEBUG)` 块，release 包不含。

**Tech Stack:** Kotlin, Jetpack Compose（Material3），Room（`MediaDao` 只读），Coil（`AsyncImage` 缩略图），`org.json`（单测已有真实实现依赖），kotlinx.coroutines Flow。

**Spec:** `docs/superpowers/specs/2026-07-19-tag-viewer-test-page-design.md`

---

## File map

| File | Responsibility |
|------|----------------|
| `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerModels.kt` | 纯数据类：`ParsedFaceInfo`/`ParsedTags`/`PhotoTagsItem`/`TagCount`/`TagAggregates`/`TagViewerUiState` |
| `app/src/main/java/com/mamba/picme/features/tagviewer/TagJsonParser.kt` | 纯函数 `object`：`labels: String? → ParsedTags?`，含容错 |
| `app/src/main/java/com/mamba/picme/features/tagviewer/TagAggregator.kt` | 纯函数 `object`：`List<PhotoTagsItem> → TagAggregates`，按字段分组计数 |
| `app/src/test/java/com/mamba/picme/features/tagviewer/TagJsonParserTest.kt` | 解析纯函数单测（容错矩阵） |
| `app/src/test/java/com/mamba/picme/features/tagviewer/TagAggregatorTest.kt` | 聚合纯函数单测 |
| `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerViewModel.kt` | `AndroidViewModel`：订阅 DAO、解析、聚合、搜索过滤 |
| `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerTestScreen.kt` | UI：双视图 Tab + 照片列表（折叠详情 + 原始 JSON）+ 标签聚合 |
| `app/src/main/java/com/mamba/picme/navigation/Screen.kt` | 增加 `TagViewer` 路由 |
| `app/src/main/java/com/mamba/picme/MainActivity.kt` | DEBUG 块内挂 `composable(Screen.TagViewer.route)`；DebugScreen 传导航回调 |
| `app/src/main/java/com/mamba/picme/features/debug/DebugScreen.kt` | 增加入口按钮（新 section） |
| `app/src/main/res/values/strings.xml` | EN 文案 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 简中文案 |
| `app/src/main/res/values-zh-rTW/strings.xml` | 繁中文案 |

**Code style reminders (CLAUDE.md hard rules):** no fully-qualified `com.mamba.picme.*` (use imports); no wildcard imports; **no implicit `it` in lambdas — name every lambda parameter explicitly**; log tags follow `PoLang:TagViewer`; XML indent 2 spaces.

---

### Task 1: 数据模型 + `TagJsonParser`（TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerModels.kt`
- Create: `app/src/main/java/com/mamba/picme/features/tagviewer/TagJsonParser.kt`
- Test: `app/src/test/java/com/mamba/picme/features/tagviewer/TagJsonParserTest.kt`

- [ ] **Step 1: 创建数据模型文件**

Create `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerModels.kt`:

```kotlin
package com.mamba.picme.features.tagviewer

/** 从 labels JSON 解析出的人脸信息（对齐 FaceTagInfo） */
data class ParsedFaceInfo(
    val count: Int = 0,
    val selfie: Boolean = false,
    val groupPhoto: Boolean = false,
    val personIds: List<Long> = emptyList()
)

/** 从 labels JSON 解析出的单张照片标签（字段对齐 UnifiedTagResult） */
data class ParsedTags(
    val scene: String = "",
    val activity: String = "",
    val objects: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val face: ParsedFaceInfo? = null
)

/** 照片列表 Tab 的单行数据 */
data class PhotoTagsItem(
    val mediaId: Long,
    val uri: String,
    val fileName: String,
    val parsed: ParsedTags?,
    val rawJson: String
) {
    val hasLabels: Boolean get() = parsed != null
}

/** 单个标签的聚合计数 */
data class TagCount(val label: String, val count: Int)

/** 按字段分组的聚合结果（标签聚合 Tab） */
data class TagAggregates(
    val scenes: List<TagCount>,
    val objects: List<TagCount>,
    val tags: List<TagCount>
)

/** TagViewer 页面状态 */
sealed interface TagViewerUiState {
    data object Loading : TagViewerUiState
    data class Ready(
        val photos: List<PhotoTagsItem>,
        val filteredPhotos: List<PhotoTagsItem>,
        val aggregates: TagAggregates
    ) : TagViewerUiState
    data class Error(val message: String) : TagViewerUiState
}
```

- [ ] **Step 2: 写失败测试**

Create `app/src/test/java/com/mamba/picme/features/tagviewer/TagJsonParserTest.kt`:

```kotlin
package com.mamba.picme.features.tagviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagJsonParserTest {

    @Test
    fun `null or blank labels return null`() {
        assertNull(TagJsonParser.parse(null))
        assertNull(TagJsonParser.parse(""))
        assertNull(TagJsonParser.parse("   "))
    }

    @Test
    fun `invalid json returns null`() {
        assertNull(TagJsonParser.parse("not a json"))
        assertNull(TagJsonParser.parse("{broken"))
    }

    @Test
    fun `full well-formed json parses all fields`() {
        val json = """
            {"face":{"count":2,"selfie":false,"groupPhoto":true,"personIds":[10,20]},
             "scene":"海滩","activity":"游泳",
             "objects":["人","伞"],"tags":["夏天","度假"],
             "qwenSummary":"海边游泳的人"}
        """.trimIndent()

        val parsed = TagJsonParser.parse(json)
        assertNotNull(parsed)
        val tags = parsed!!
        assertEquals("海滩", tags.scene)
        assertEquals("游泳", tags.activity)
        assertEquals(listOf("人", "伞"), tags.objects)
        assertEquals(listOf("夏天", "度假"), tags.tags)
        assertEquals("海边游泳的人", tags.summary)
        assertEquals(2, tags.face?.count)
        assertEquals(false, tags.face?.selfie)
        assertEquals(true, tags.face?.groupPhoto)
        assertEquals(listOf(10L, 20L), tags.face?.personIds)
    }

    @Test
    fun `missing fields default to empty`() {
        val json = """{"scene":"餐厅"}"""

        val parsed = TagJsonParser.parse(json)
        assertNotNull(parsed)
        val tags = parsed!!
        assertEquals("餐厅", tags.scene)
        assertEquals("", tags.activity)
        assertTrue(tags.objects.isEmpty())
        assertTrue(tags.tags.isEmpty())
        assertEquals("", tags.summary)
        assertNull(tags.face)
    }

    @Test
    fun `old pass1-only format with face only parses without crash`() {
        val json = """{"face":{"count":0}}"""

        val parsed = TagJsonParser.parse(json)
        assertNotNull(parsed)
        val tags = parsed!!
        assertEquals("", tags.scene)
        assertEquals(0, tags.face?.count)
    }

    @Test
    fun `blank string values are treated as absent`() {
        val json = """{"scene":"  ","activity":""}"""

        val parsed = TagJsonParser.parse(json)
        assertNotNull(parsed)
        val tags = parsed!!
        assertEquals("", tags.scene)
        assertEquals("", tags.activity)
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.tagviewer.TagJsonParserTest"`
Expected: FAIL（`TagJsonParser` 未定义，编译错误 / unresolved reference）

- [ ] **Step 4: 实现 `TagJsonParser`**

Create `app/src/main/java/com/mamba/picme/features/tagviewer/TagJsonParser.kt`:

```kotlin
package com.mamba.picme.features.tagviewer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 将 [com.mamba.picme.data.model.MediaEntity.labels] 中的 JSON 字符串解析为 [ParsedTags]。
 *
 * 字段名对齐 TagGenerationScheduler.unifiedTagToJson 的输出：
 * face / scene / activity / objects / tags / qwenSummary。
 *
 * 容错：null / 空 / 非 JSON / 缺字段均不抛异常，返回 null（表示未打标或解析失败）。
 */
object TagJsonParser {

    fun parse(labels: String?): ParsedTags? {
        if (labels.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(labels)
            ParsedTags(
                scene = obj.optString("scene").trim(),
                activity = obj.optString("activity").trim(),
                objects = obj.optJSONArray("objects").toStringList(),
                tags = obj.optJSONArray("tags").toStringList(),
                summary = obj.optString("qwenSummary").trim(),
                face = obj.optJSONObject("face")?.let { faceObj ->
                    ParsedFaceInfo(
                        count = faceObj.optInt("count", 0),
                        selfie = faceObj.optBoolean("selfie", false),
                        groupPhoto = faceObj.optBoolean("groupPhoto", false),
                        personIds = faceObj.optJSONArray("personIds").toLongList()
                    )
                }
            )
        } catch (e: org.json.JSONException) {
            null
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val result = ArrayList<String>(length())
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) result.add(value)
        }
        return result
    }

    private fun JSONArray?.toLongList(): List<Long> {
        if (this == null) return emptyList()
        val result = ArrayList<Long>(length())
        for (index in 0 until length()) {
            result.add(optLong(index))
        }
        return result
    }
}
```

> Note: 这里 `let { faceObj -> ... }` 用了显式参数名 `faceObj`，符合"lambda 参数必须显式命名"规则。

- [ ] **Step 5: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.tagviewer.TagJsonParserTest"`
Expected: PASS（6 tests）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerModels.kt \
        app/src/main/java/com/mamba/picme/features/tagviewer/TagJsonParser.kt \
        app/src/test/java/com/mamba/picme/features/tagviewer/TagJsonParserTest.kt
git commit -m "feat(tagviewer): labels JSON 解析纯函数 + 容错单测"
```

---

### Task 2: `TagAggregator`（TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/tagviewer/TagAggregator.kt`
- Test: `app/src/test/java/com/mamba/picme/features/tagviewer/TagAggregatorTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/features/tagviewer/TagAggregatorTest.kt`:

```kotlin
package com.mamba.picme.features.tagviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class TagAggregatorTest {

    private fun item(parsed: ParsedTags?): PhotoTagsItem =
        PhotoTagsItem(mediaId = 0, uri = "", fileName = "x", parsed = parsed, rawJson = "")

    @Test
    fun `empty input returns empty groups`() {
        val result = TagAggregator.aggregate(emptyList())
        assertEquals(0, result.scenes.size)
        assertEquals(0, result.objects.size)
        assertEquals(0, result.tags.size)
    }

    @Test
    fun `unparsed items are skipped`() {
        val result = TagAggregator.aggregate(listOf(item(null), item(null)))
        assertEquals(0, result.scenes.size)
    }

    @Test
    fun `counts are accumulated across photos`() {
        val p1 = ParsedTags(scene = "海滩", objects = listOf("伞"), tags = listOf("夏天"))
        val p2 = ParsedTags(scene = "海滩", objects = listOf("伞", "人"), tags = listOf("夏天", "度假"))
        val result = TagAggregator.aggregate(listOf(item(p1), item(p2)))

        assertEquals(listOf(TagCount("海滩", 2)), result.scenes)
        assertEquals(TagCount("伞", 2), result.objects[0])
        assertEquals(TagCount("人", 1), result.objects[1])
        assertEquals(TagCount("夏天", 2), result.tags[0])
        assertEquals(TagCount("度假", 1), result.tags[1])
    }

    @Test
    fun `results are sorted by count descending`() {
        val p = ParsedTags(tags = listOf("稀有", "常见", "常见", "常见", "中等", "中等"))
        val result = TagAggregator.aggregate(listOf(item(p)))

        assertEquals("常见", result.tags[0].label)
        assertEquals(3, result.tags[0].count)
        assertEquals("中等", result.tags[1].label)
        assertEquals(2, result.tags[1].count)
        assertEquals("稀有", result.tags[2].label)
        assertEquals(1, result.tags[2].count)
    }

    @Test
    fun `blank labels are ignored`() {
        val p = ParsedTags(scene = "  ", tags = listOf("有效", ""))
        val result = TagAggregator.aggregate(listOf(item(p)))

        assertEquals(0, result.scenes.size)
        assertEquals(1, result.tags.size)
        assertEquals("有效", result.tags[0].label)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.tagviewer.TagAggregatorTest"`
Expected: FAIL（`TagAggregator` 未定义）

- [ ] **Step 3: 实现 `TagAggregator`**

Create `app/src/main/java/com/mamba/picme/features/tagviewer/TagAggregator.kt`:

```kotlin
package com.mamba.picme.features.tagviewer

/**
 * 将一批 [PhotoTagsItem] 的标签按字段聚合计数，结果按次数降序排列。
 *
 * 跳过 parsed==null 的项；忽略空白标签。
 */
object TagAggregator {

    fun aggregate(items: List<PhotoTagsItem>): TagAggregates {
        val scenes = mutableMapOf<String, Int>()
        val objects = mutableMapOf<String, Int>()
        val tags = mutableMapOf<String, Int>()

        for (item in items) {
            val parsed = item.parsed ?: continue
            accumulate(scenes, parsed.scene)
            for (label in parsed.objects) accumulate(objects, label)
            for (label in parsed.tags) accumulate(tags, label)
        }

        return TagAggregates(
            scenes = scenes.toSortedCounts(),
            objects = objects.toSortedCounts(),
            tags = tags.toSortedCounts()
        )
    }

    private fun accumulate(target: MutableMap<String, Int>, label: String) {
        if (label.isBlank()) return
        target.merge(label, 1, Int::plus)
    }

    private fun Map<String, Int>.toSortedCounts(): List<TagCount> =
        entries.map { entry -> TagCount(entry.key, entry.value) }
            .sortedByDescending { tag -> tag.count }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.tagviewer.TagAggregatorTest"`
Expected: PASS（5 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/tagviewer/TagAggregator.kt \
        app/src/test/java/com/mamba/picme/features/tagviewer/TagAggregatorTest.kt
git commit -m "feat(tagviewer): 标签聚合计数纯函数 + 单测"
```

---

### Task 3: `TagViewerViewModel`

> 测试策略说明：核心解析/聚合逻辑已由 Task 1/2 的纯函数单测覆盖；ViewModel 是薄集成层（订阅 Flow + 调用纯函数），用编译验证 + Task 7 手动 smoke 覆盖，不再加 Robolectric 重测，避免冗余。

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerViewModel.kt`

- [ ] **Step 1: 实现 ViewModel**

Create `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerViewModel.kt`:

```kotlin
package com.mamba.picme.features.tagviewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 标签查看测试页 ViewModel。
 *
 * 订阅 [AppDatabase] 的 [com.mamba.picme.data.local.MediaDao.getAllMedia]，对每条
 * [MediaEntity.labels] 解析为 [ParsedTags]，组装列表与聚合，纯只读、不触发任何推理。
 */
class TagViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "PoLang:TagViewer"
    private val dao = AppDatabase.getDatabase(application).mediaDao()

    private val _state = MutableStateFlow<TagViewerUiState>(TagViewerUiState.Loading)
    val state: StateFlow<TagViewerUiState> = _state.asStateFlow()

    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            dao.getAllMedia()
                .combine(query) { media, queryText -> media to queryText }
                .catch { error ->
                    Logger.e(tag, "Load media failed", error)
                    _state.value = TagViewerUiState.Error(error.message ?: "加载失败")
                }
                .collect { (media, queryText) ->
                    val items = media.map { entity -> entity.toItem() }
                    val aggregates = TagAggregator.aggregate(items)
                    val filtered = filterItems(items, queryText)
                    _state.value = TagViewerUiState.Ready(
                        photos = items,
                        filteredPhotos = filtered,
                        aggregates = aggregates
                    )
                }
        }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    private fun filterItems(items: List<PhotoTagsItem>, queryText: String): List<PhotoTagsItem> {
        if (queryText.isBlank()) return items
        val keyword = queryText.trim().lowercase()
        return items.filter { item -> item.matches(keyword) }
    }

    private fun PhotoTagsItem.matches(keyword: String): Boolean {
        val parsed = this.parsed
        if (fileName.lowercase().contains(keyword)) return true
        if (parsed == null) return false
        if (parsed.scene.lowercase().contains(keyword)) return true
        if (parsed.activity.lowercase().contains(keyword)) return true
        if (parsed.tags.any { label -> label.lowercase().contains(keyword) }) return true
        if (parsed.objects.any { label -> label.lowercase().contains(keyword) }) return true
        return false
    }

    private fun MediaEntity.toItem(): PhotoTagsItem = PhotoTagsItem(
        mediaId = id,
        uri = uri,
        fileName = fileName,
        parsed = TagJsonParser.parse(labels),
        rawJson = labels.orEmpty()
    )
}
```

> Notes: `Logger` 包路径以仓库实际为准（`com.mamba.picme.agent.core.platform.logging.Logger`，见 `OpenClGuardian` 已用）。`combine` 的 lambda 用 `(media, queryText)` 显式命名，`collect { (media, queryText) -> ... }` 解构命名；`any { label -> ... }` 显式命名，无 `it`。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerViewModel.kt
git commit -m "feat(tagviewer): ViewModel 订阅 DAO + 解析聚合 + 搜索过滤"
```

---

### Task 4: 路由 + MainActivity 挂载

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/navigation/Screen.kt`
- Modify: `app/src/main/java/com/mamba/picme/MainActivity.kt`

- [ ] **Step 1: 加 `TagViewer` 路由**

In `app/src/main/java/com/mamba/picme/navigation/Screen.kt`, after the `SentencePieceTest` entry (around line 21), add:

```kotlin
    data object TagViewer : Screen("tag_viewer")
```

- [ ] **Step 2: MainActivity DEBUG 块挂 composable**

In `app/src/main/java/com/mamba/picme/MainActivity.kt`, inside the `if (BuildConfig.DEBUG) { ... }` block (around line 467-478), add a new `composable` after `SentencePieceTest`:

```kotlin
                                composable(Screen.TagViewer.route) {
                                    TagViewerTestScreen(onNavigateBack = { navController.popBackStack() })
                                }
```

Add the import at the top of `MainActivity.kt`:

```kotlin
import com.mamba.picme.features.tagviewer.TagViewerTestScreen
```

> Note: `TagViewerTestScreen` 在 Task 5 创建；本步会因符号未定义编译失败，Task 5 完成后通过。**因此本 Task 不单独编译验证，Step 3 的提交可与 Task 5 合并**，或在此步先注释掉 composable、Task 5 完成后取消注释。下方 Step 3 假定采用"先注释、Task 5 后启用"的最小风险做法。

修订 Step 2 实施：写入 composable 时先整体保留，但若希望本 Task 可独立编译，可暂时改为：

```kotlin
                                // Task 5 完成后启用：
                                // composable(Screen.TagViewer.route) {
                                //     TagViewerTestScreen(onNavigateBack = { navController.popBackStack() })
                                // }
```

并在 Task 5 末尾取消注释。本 plan 采用此保守路径。

- [ ] **Step 3: 编译验证（仅 Screen.kt 改动）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（MainActivity 的 composable 仍注释，不引入未定义符号）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/navigation/Screen.kt \
        app/src/main/java/com/mamba/picme/MainActivity.kt
git commit -m "feat(tagviewer): 增加 TagViewer 路由（composable 暂注释，待 UI 就绪启用）"
```

---

### Task 5: `TagViewerTestScreen` UI + 三语文案

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerTestScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`
- Modify: `app/src/main/java/com/mamba/picme/MainActivity.kt`（取消 Task 4 注释的 composable）

- [ ] **Step 1: 新增 string keys（三语同步）**

在三个 `strings.xml` 中追加（EN 放 `values/`，简中 `values-zh-rCN/`，繁中 `values-zh-rTW/`）：

`values/strings.xml`:
```xml
    <string name="tag_viewer_title">Tag Viewer</string>
    <string name="tag_viewer_tab_photos">Photos</string>
    <string name="tag_viewer_tab_tags">Tags</string>
    <string name="tag_viewer_search_hint">Search file name or tag</string>
    <string name="tag_viewer_no_labels">Not tagged</string>
    <string name="tag_viewer_no_photos">No photos</string>
    <string name="tag_viewer_no_tags">None</string>
    <string name="tag_viewer_section_scenes">Scenes</string>
    <string name="tag_viewer_section_objects">Objects</string>
    <string name="tag_viewer_section_tags_field">Tags</string>
    <string name="tag_viewer_raw_json">Raw JSON</string>
```

`values-zh-rCN/strings.xml`:
```xml
    <string name="tag_viewer_title">标签查看</string>
    <string name="tag_viewer_tab_photos">照片</string>
    <string name="tag_viewer_tab_tags">标签聚合</string>
    <string name="tag_viewer_search_hint">搜索文件名或标签</string>
    <string name="tag_viewer_no_labels">未打标</string>
    <string name="tag_viewer_no_photos">暂无照片</string>
    <string name="tag_viewer_no_tags">暂无</string>
    <string name="tag_viewer_section_scenes">场景</string>
    <string name="tag_viewer_section_objects">物体</string>
    <string name="tag_viewer_section_tags_field">标签</string>
    <string name="tag_viewer_raw_json">原始 JSON</string>
```

`values-zh-rTW/strings.xml`:
```xml
    <string name="tag_viewer_title">標籤查看</string>
    <string name="tag_viewer_tab_photos">照片</string>
    <string name="tag_viewer_tab_tags">標籤聚合</string>
    <string name="tag_viewer_search_hint">搜尋檔名或標籤</string>
    <string name="tag_viewer_no_labels">未打標</string>
    <string name="tag_viewer_no_photos">暫無照片</string>
    <string name="tag_viewer_no_tags">暫無</string>
    <string name="tag_viewer_section_scenes">場景</string>
    <string name="tag_viewer_section_objects">物體</string>
    <string name="tag_viewer_section_tags_field">標籤</string>
    <string name="tag_viewer_raw_json">原始 JSON</string>
```

> 三语 key 必须一一对应，缺一会导致 release 构建回退到 EN。

- [ ] **Step 2: 实现 Screen**

Create `app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerTestScreen.kt`:

```kotlin
package com.mamba.picme.features.tagviewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mamba.picme.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagViewerTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: TagViewerViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tag_viewer_title)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.back)) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tag_viewer_tab_photos)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tag_viewer_tab_tags)) }
                )
            }
            when (val current = state) {
                is TagViewerUiState.Loading -> LoadingView()
                is TagViewerUiState.Error -> ErrorView(current.message)
                is TagViewerUiState.Ready -> {
                    if (selectedTab == 0) PhotosTab(current, viewModel) else TagsTab(current.aggregates)
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PhotosTab(
    state: TagViewerUiState.Ready,
    viewModel: TagViewerViewModel
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { text ->
                query = text
                viewModel.setQuery(text)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.tag_viewer_search_hint)) },
            singleLine = true
        )
        if (state.photos.isEmpty()) {
            EmptyText(stringResource(R.string.tag_viewer_no_photos))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.filteredPhotos, key = { item -> item.mediaId }) { item ->
                    PhotoRow(item)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun PhotoRow(item: PhotoTagsItem) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val notTagged = stringResource(R.string.tag_viewer_no_labels)
    Column(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(item.fileName, style = MaterialTheme.typography.bodyLarge)
                val sceneText = if (item.hasLabels) item.parsed?.scene.orEmpty() else notTagged
                Text(
                    text = sceneText.ifBlank { notTagged },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (expanded) {
            ExpandedDetails(item)
        }
    }
}

@Composable
private fun ExpandedDetails(item: PhotoTagsItem) {
    val parsed = item.parsed
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (parsed == null) {
            Text(stringResource(R.string.tag_viewer_no_labels))
        } else {
            DetailLine(stringResource(R.string.tag_viewer_section_scenes), parsed.scene)
            DetailLine("activity", parsed.activity)
            DetailLine(
                stringResource(R.string.tag_viewer_section_objects),
                parsed.objects.joinToString(" · ")
            )
            DetailLine(
                stringResource(R.string.tag_viewer_section_tags_field),
                parsed.tags.joinToString(" · ")
            )
            DetailLine("summary", parsed.summary)
            val face = parsed.face
            if (face != null) {
                DetailLine("face", "count=${face.count} selfie=${face.selfie} group=${face.groupPhoto}")
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.tag_viewer_raw_json), style = MaterialTheme.typography.labelSmall)
        Text(
            text = item.rawJson.ifBlank { "{}" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TagsTab(aggregates: TagAggregates) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            SectionHeader(stringResource(R.string.tag_viewer_section_scenes), aggregates.scenes.size)
        }
        items(aggregates.scenes, key = { tag -> "scene-${tag.label}" }) { tagCount ->
            TagCountRow(tagCount)
        }
        item {
            SectionHeader(stringResource(R.string.tag_viewer_section_objects), aggregates.objects.size)
        }
        items(aggregates.objects, key = { tag -> "object-${tag.label}" }) { tagCount ->
            TagCountRow(tagCount)
        }
        item {
            SectionHeader(stringResource(R.string.tag_viewer_section_tags_field), aggregates.tags.size)
        }
        items(aggregates.tags, key = { tag -> "tag-${tag.label}" }) { tagCount ->
            TagCountRow(tagCount)
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        text = "$title ($count)",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
}

@Composable
private fun TagCountRow(tagCount: TagCount) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(tagCount.label, style = MaterialTheme.typography.bodyMedium)
        Text("${tagCount.count}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun EmptyText(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.outline)
    }
}
```

> Notes: 所有 `items(...) { item -> ... }` 与 `{ tagCount -> ... }` 显式命名 lambda 参数，无 `it`。`stringResource` 只能在 `@Composable` 作用域调用，因此 `TagsTab` 内的 section 标题用 `item { SectionHeader(stringResource(...), ...) }` 包裹（`stringResource` 在 `@Composable` lambda 内合法）。

- [ ] **Step 3: 启用 MainActivity 的 composable**

回到 `MainActivity.kt`，取消 Task 4 Step 2 注释的 `composable(Screen.TagViewer.route) { ... }`。确认顶部 import 已有 `com.mamba.picme.features.tagviewer.TagViewerTestScreen`。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/tagviewer/TagViewerTestScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml \
        app/src/main/java/com/mamba/picme/MainActivity.kt
git commit -m "feat(tagviewer): 双视图 Tab UI + 三语文案 + 启用路由"
```

---

### Task 6: DebugScreen 入口按钮 + 接线

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/debug/DebugScreen.kt`
- Modify: `app/src/main/java/com/mamba/picme/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

- [ ] **Step 1: 新增入口 string（三语）**

三语各加：

`values/strings.xml`:
```xml
    <string name="tag_viewer_debug_section">Dev Test Pages</string>
    <string name="tag_viewer_open_entry">View generated tags</string>
```
`values-zh-rCN/strings.xml`:
```xml
    <string name="tag_viewer_debug_section">开发测试页</string>
    <string name="tag_viewer_open_entry">查看生成标签</string>
```
`values-zh-rTW/strings.xml`:
```xml
    <string name="tag_viewer_debug_section">開發測試頁</string>
    <string name="tag_viewer_open_entry">查看生成標籤</string>
```

- [ ] **Step 2: DebugScreen 增加导航回调参数**

在 `DebugScreen.kt` 的 `DebugScreen(...)` 主入口签名（约 line 70）增加回调参数：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onNavigateBack: () -> Unit,
    mediaViewModel: MediaViewModel,
    onNavigateToTagViewer: () -> Unit
) {
```

将该参数向下传递：在 `DebugContent(...)` 调用处（约 line 85）加 `onNavigateToTagViewer = onNavigateToTagViewer`，并在 `DebugContent(...)` 私有函数签名（约 line 137）增加 `onNavigateToTagViewer: () -> Unit` 参数。

- [ ] **Step 3: DebugScreen 增加入口 section**

在 `DebugContent` 的 `Column` 内，`HorizontalDivider`（截图 section 之后、`LogWindow` 之前）追加一个新 section：

```kotlin
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Text(
                stringResource(R.string.tag_viewer_debug_section),
                style = MaterialTheme.typography.titleSmall
            )

            Button(
                onClick = onNavigateToTagViewer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tag_viewer_open_entry))
            }
```

> `Icons.Default.Search` 已在 DebugScreen.kt import（见 line 29）。如该 import 缺失则补 `import androidx.compose.material.icons.filled.Search`。

- [ ] **Step 4: MainActivity 传入回调**

在 `MainActivity.kt` 的 `composable(Screen.Debug.route) { ... }`（约 line 462）调用处补回调：

```kotlin
                                DebugScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    mediaViewModel = mediaViewModel,
                                    onNavigateToTagViewer = { navController.navigate(Screen.TagViewer.route) }
                                )
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/DebugScreen.kt \
        app/src/main/java/com/mamba/picme/MainActivity.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(tagviewer): Debug 页加入口按钮 + 导航接线"
```

---

### Task 7: 全量验证

- [ ] **Step 1: 跑全部单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（含 TagJsonParserTest 6 个 + TagAggregatorTest 5 个）

- [ ] **Step 2: 构建 debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 安装并手动 smoke**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

手动验证清单：
1. 进入 Debug 页，看到「开发测试页」section +「查看生成标签」按钮。
2. 点击进入「标签查看」页，默认「照片」Tab。
3. 照片列表显示：缩略图、文件名、场景（未打标的显示"未打标"）。
4. 点击某行 → 展开完整字段 + 原始 JSON（等宽字体）；再点折叠。
5. 搜索框输入标签关键字 → 列表过滤。
6. 切到「标签聚合」Tab → 场景/物体/标签三组按计数降序。
7. 抽检若干张，核对 JSON 与展示字段一致（重点核对 0.8B 标签质量）。
8. 切系统语言（中/英/繁）→ 文案正确。

- [ ] **Step 4:（可选）记录质量抽检结论**

若用于评估 0.8B 标签质量，记录典型误标样例，作为后续是否调整 MobileCLIP 门控 / 启用按需 LLM 的输入。

---

## Self-review checklist

- [ ] **Spec coverage:**
  - 双视图 Tab（照片列表 / 标签聚合）→ Task 5
  - 只读、不触发推理 → Task 3 ViewModel 无推理调用；YAGNI 明示
  - DEBUG-only → Task 4 挂在 `if (BuildConfig.DEBUG)`
  - 入口在 Debug 页 → Task 6
  - labels JSON 解析容错矩阵 → Task 1 单测覆盖 null/空/非 JSON/缺字段/旧格式/空白
  - 标签聚合按字段分组 + 降序 → Task 2 单测覆盖空/跳过未打标/累加/排序/忽略空白
  - 三语文案同步 → Task 5 + Task 6（每批引入时三语同加）
  - 单测覆盖纯函数 → Task 1/2
  - 文件清单逐项 → 各 Task 对应

- [ ] **Placeholder scan:** 无 TBD/TODO；每步含完整代码或精确命令。

- [ ] **Type consistency:**
  - `ParsedTags` / `ParsedFaceInfo` / `PhotoTagsItem` / `TagCount` / `TagAggregates` / `TagViewerUiState` 在 Task 1 定义，Task 2/3/5 引用一致。
  - `TagJsonParser.parse(labels: String?): ParsedTags?` 在 Task 1 定义，Task 3 `toItem()` 调用一致。
  - `TagAggregator.aggregate(items: List<PhotoTagsItem>): TagAggregates` 在 Task 2 定义，Task 3 调用一致。
  - `TagViewerViewModel.state: StateFlow<TagViewerUiState>` 在 Task 3 定义，Task 5 `collectAsState()` + `when` 分支（Loading/Error/Ready）一致；`Ready` 字段 `photos`/`filteredPhotos`/`aggregates` 在 Task 5 使用一致。
  - `Screen.TagViewer` 在 Task 4 定义，Task 5/6 `navigate`/`composable` 引用一致。

- [ ] **Known deviation:** Task 4 为保证每个 Task 可独立编译，采用「composable 先注释、Task 5 启用」的保守路径；若执行者偏好一次性写入，可在 Task 4 直接写入并在 Task 5 跳过 Step 3，代价是 Task 4 单独编译会失败（需与 Task 5 合并提交）。
