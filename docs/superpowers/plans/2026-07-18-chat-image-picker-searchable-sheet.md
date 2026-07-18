# Chat 图片选择器：可搜索半屏面板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把独立 Chat 页底部的选图弹窗从「原始 MediaStore 平铺网格」升级为「全高度、可搜索、复用既有搜索/网格」的半屏面板，让用户能在不离开对话的前提下按内容/人物搜到目标照片并单选发送。

**Architecture:** 新增一个轻量状态持有者 `ChatPhotoPickerViewModel`（注入 `search` 与 `coroutineScope`，便于单测）负责 query 防抖 → 调 `MediaSearchEngine.search()` → 结果；新增 `ChatPhotoPickerSheet` Composable，在 `ModalBottomSheet` 内复用 `SearchField` + `MediaGrid`（浏览态用 `MediaViewModel.groupedMedia`，搜索态把结果包成单条 `GroupedMedia`）。替换 `ChatScreen` 中现有 `InAppPhotoPicker`。

**Tech Stack:** Kotlin、Jetpack Compose（Material3 `ModalBottomSheet`）、kotlinx-coroutines（`debounce`/`StateFlow`）、kotlinx-coroutines-test + runTest、MockK、Coil、App 既有 `MediaSearchEngine`/`MediaGrid`/`ThumbnailCache`。

**Spec:** `docs/superpowers/specs/2026-07-18-chat-image-picker-searchable-sheet-design.md`

---

## File Structure

- **Create** `app/src/main/java/com/mamba/picme/features/chat/ChatPhotoPickerViewModel.kt` — query/防抖/搜索结果状态 + `PickerMode`。
- **Create** `app/src/test/java/com/mamba/picme/features/chat/ChatPhotoPickerViewModelTest.kt` — VM 行为单测（runTest）。
- **Create** `app/src/main/java/com/mamba/picme/features/chat/components/ChatPhotoPickerSheet.kt` — 面板 Composable（SearchField + 浏览/搜索 MediaGrid + 单选发送）。
- **Modify** `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` — `ChatInputArea` 增加 `mediaViewModel` 参数；用 `ChatPhotoPickerSheet` 替换 `InAppPhotoPicker` 调用；删除 `InAppPhotoPicker`；清理失效 import。
- **Modify** `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml` — 新增 4 条文案。

复用（不新造）：
- 搜索：`GalleryCapability.getInstance().searchEngine?.search(q)?.media`（与 `GalleryScreen.kt:141` 同一调用，返回 `List<MediaAsset>`）。
- 网格：`features/gallery/components/MediaGrid.kt:62`（`isSelectionMode`/`thumbnailCache`/`thumbnailPositions` 等）。
- 输入框：`features/common/SearchField.kt:41`。
- 缩略图缓存：`app.container.thumbnailCache`（`GalleryScreen.kt:179` 的取法）。
- 媒体数据：`MediaViewModel.groupedMedia: StateFlow<List<GroupedMedia>>`（`MediaViewModel.kt:98`）。

---

## Task 1: 新增 i18n 文案（中/英/繁）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: 在 `values/strings.xml`（EN/默认）追加 4 条**

在 `<resources>` 内合适位置插入：
```xml
<string name="chat_photo_picker_title">Select Photo</string>
<string name="chat_photo_picker_search_hint">Search photos (e.g. beach, person)</string>
<string name="chat_photo_picker_search_unavailable">Search unavailable — showing recent photos</string>
<string name="chat_photo_picker_no_results">No matching photos</string>
```

- [ ] **Step 2: 在 `values-zh-rCN/strings.xml` 追加 4 条**

```xml
<string name="chat_photo_picker_title">选择图片</string>
<string name="chat_photo_picker_search_hint">搜索照片（如：海滩、人物）</string>
<string name="chat_photo_picker_search_unavailable">搜索暂不可用，仅显示最近照片</string>
<string name="chat_photo_picker_no_results">未找到相关图片</string>
```

- [ ] **Step 3: 在 `values-zh-rTW/strings.xml` 追加 4 条（繁体）**

```xml
<string name="chat_photo_picker_title">選擇圖片</string>
<string name="chat_photo_picker_search_hint">搜尋照片（如：海灘、人物）</string>
<string name="chat_photo_picker_search_unavailable">搜尋暫不可用，僅顯示最近照片</string>
<string name="chat_photo_picker_no_results">未找到相關圖片</string>
```

- [ ] **Step 4: 校验三份文件 key 一致**

Run: `grep -hE 'chat_photo_picker_(title|search_hint|search_unavailable|no_results)' app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml`
Expected: 三个文件各出现 4 条，共 12 行。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(chat): 选图面板 i18n 文案（中/英/繁）"
```

---

## Task 2: `ChatPhotoPickerViewModel` + `PickerMode`（TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/ChatPhotoPickerViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatPhotoPickerViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/mamba/picme/features/chat/ChatPhotoPickerViewModelTest.kt`：
```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPhotoPickerViewModelTest {

    private fun asset(id: Long) = MediaAsset(
        id = id,
        uri = "content://media/external/images/media/$id",
        type = MediaType.PHOTO,
        captureDate = 0L,
        fileName = "img$id.jpg"
    )

    @Test
    fun `blank query never searches and keeps results empty`() = runTest {
        val calls = mutableListOf<String>()
        val vm = ChatPhotoPickerViewModel(
            search = { q -> calls.add(q); emptyList() },
            searchAvailable = true,
            coroutineScope = backgroundScope
        )
        vm.setQuery("")
        advanceTimeBy(300); advanceUntilIdle()
        assertTrue(calls.isEmpty())
        assertTrue(vm.results.value.isEmpty())
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun `non-blank query searches after debounce and populates results`() = runTest {
        val fake = listOf(asset(1), asset(2))
        val vm = ChatPhotoPickerViewModel(
            search = { q -> if (q == "cat") fake else emptyList() },
            searchAvailable = true,
            coroutineScope = backgroundScope
        )
        vm.setQuery("cat")
        advanceTimeBy(300); advanceUntilIdle()
        assertEquals(fake, vm.results.value)
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun `search unavailable never invokes search`() = runTest {
        val calls = mutableListOf<String>()
        val vm = ChatPhotoPickerViewModel(
            search = { q -> calls.add(q); emptyList() },
            searchAvailable = false,
            coroutineScope = backgroundScope
        )
        vm.setQuery("cat")
        advanceTimeBy(300); advanceUntilIdle()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `clearing query after a search empties results`() = runTest {
        val fake = listOf(asset(1))
        val vm = ChatPhotoPickerViewModel(
            search = { fake },
            searchAvailable = true,
            coroutineScope = backgroundScope
        )
        vm.setQuery("cat"); advanceTimeBy(300); advanceUntilIdle()
        assertEquals(fake, vm.results.value)
        vm.setQuery(""); advanceTimeBy(300); advanceUntilIdle()
        assertTrue(vm.results.value.isEmpty())
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（类未创建）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatPhotoPickerViewModelTest"`
Expected: 编译失败 / `ChatPhotoPickerViewModel` unresolved。

- [ ] **Step 3: 写最小实现**

`app/src/main/java/com/mamba/picme/features/chat/ChatPhotoPickerViewModel.kt`：
```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 选图面板模式：浏览最近照片 / 展示搜索结果。 */
enum class PickerMode { BROWSE, SEARCH }

/**
 * 选图面板状态持有者：管理搜索词、防抖后的搜索结果与搜索中状态。
 *
 * 为便于单测，将 [search] 与 [coroutineScope] 以构造参数注入；
 * 生产环境在 Composable 中用 rememberCoroutineScope() + 真实 searchEngine 传入。
 *
 * @param search 给定 query 返回匹配媒体；searchAvailable=false 时不会被调用。
 * @param searchAvailable 搜索引擎是否就绪（false 时一律走浏览态，不发起搜索）。
 */
class ChatPhotoPickerViewModel(
    private val search: suspend (String) -> List<MediaAsset>,
    private val searchAvailable: Boolean,
    coroutineScope: CoroutineScope,
    private val debounceMs: Long = 250L
) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<MediaAsset>>(emptyList())
    val results: StateFlow<List<MediaAsset>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        coroutineScope.launch {
            _query
                .debounce(debounceMs)
                .distinctUntilChanged()
                .collect { q ->
                    if (q.isBlank() || !searchAvailable) {
                        _results.value = emptyList()
                        _isSearching.value = false
                    } else {
                        _isSearching.value = true
                        _results.value = search(q)
                        _isSearching.value = false
                    }
                }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatPhotoPickerViewModelTest"`
Expected: 4 tests PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatPhotoPickerViewModel.kt \
        app/src/test/java/com/mamba/picme/features/chat/ChatPhotoPickerViewModelTest.kt
git commit -m "feat(chat): ChatPhotoPickerViewModel 防抖搜索状态（含单测）"
```

---

## Task 3: `ChatPhotoPickerSheet` 面板 Composable

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/components/ChatPhotoPickerSheet.kt`

> 说明：面板 UI 属 Compose 视图层，验证以「编译 + 端到端手动」为主（见 Task 5）；核心状态逻辑已由 Task 2 单测覆盖。

- [ ] **Step 1: 写面板 Composable**

`app/src/main/java/com/mamba/picme/features/chat/components/ChatPhotoPickerSheet.kt`：
```kotlin
package com.mamba.picme.features.chat.components

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.domain.model.GroupedMedia
import com.mamba.picme.domain.model.GroupTitleType
import com.mamba.picme.features.chat.ChatPhotoPickerViewModel
import com.mamba.picme.features.chat.PickerMode
import com.mamba.picme.features.common.SearchField
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.capability.GalleryCapability
import com.mamba.picme.features.gallery.components.MediaGrid

/**
 * Chat 选图半屏面板：
 * - 顶部 [SearchField] 接入既有 [GalleryCapability] 搜索引擎；
 * - 浏览态复用 [MediaGrid]（日期分组最近照片）；搜索态把结果包成单条 [GroupedMedia]；
 * - 单选：点击缩略图即回调 [onImageSelected] 并关闭面板（与旧 [InAppPhotoPicker] 行为一致）。
 *
 * `searchEngine` 为 null（语义索引未就绪）时隐藏搜索框、显示回退提示并只展示最近照片。
 */
@Composable
fun ChatPhotoPickerSheet(
    sheetState: SheetState,
    mediaViewModel: MediaViewModel,
    onImageSelected: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val thumbnailCache = remember { app.container.thumbnailCache }
    val thumbnailPositions = remember { mutableStateMapOf<Long, Rect>() }
    val scope = rememberCoroutineScope()

    val searchEngine = remember { GalleryCapability.getInstance().searchEngine }
    val searchAvailable = searchEngine != null

    val vm = remember(searchAvailable) {
        ChatPhotoPickerViewModel(
            search = { q -> searchEngine?.search(q)?.media ?: emptyList() },
            searchAvailable = searchAvailable,
            coroutineScope = scope
        )
    }

    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val isSearching by vm.isSearching.collectAsState()
    val groupedMedia by mediaViewModel.groupedMedia.collectAsState()

    val mode = if (query.isBlank() || !searchAvailable) PickerMode.BROWSE else PickerMode.SEARCH

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(560.dp)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_photo_picker_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (!searchAvailable) {
                Text(
                    text = stringResource(R.string.chat_photo_picker_search_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            } else {
                SearchField(
                    query = query,
                    onQueryChange = vm::setQuery,
                    placeholder = stringResource(R.string.chat_photo_picker_search_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(480.dp)) {
                when {
                    mode == PickerMode.SEARCH && isSearching -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    mode == PickerMode.SEARCH && results.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.chat_photo_picker_no_results),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    mode == PickerMode.SEARCH -> {
                        val searchGroup = remember(results) {
                            GroupedMedia(GroupTitleType.SEARCH, "", results)
                        }
                        MediaGrid(
                            context = context,
                            groupedMedia = listOf(searchGroup),
                            selectedIds = emptyList(),
                            isSelectionMode = false,
                            thumbnailPositions = thumbnailPositions,
                            mediaById = results.associateBy { it.id },
                            thumbnailCache = thumbnailCache,
                            onThumbnailPositioned = { id, rect -> thumbnailPositions[id] = rect },
                            onMediaClick = { asset ->
                                onImageSelected(asset.uri.toUri()); onDismiss()
                            },
                            onMediaLongClick = {},
                            onDragSelectionStart = {},
                            onDragSelectionItem = {},
                            onDragSelectionEnd = {}
                        )
                    }
                    else -> {
                        val mediaById = remember(groupedMedia) {
                            groupedMedia.flatMap { g -> g.items }.associateBy { it.id }
                        }
                        MediaGrid(
                            context = context,
                            groupedMedia = groupedMedia,
                            selectedIds = emptyList(),
                            isSelectionMode = false,
                            thumbnailPositions = thumbnailPositions,
                            mediaById = mediaById,
                            thumbnailCache = thumbnailCache,
                            onThumbnailPositioned = { id, rect -> thumbnailPositions[id] = rect },
                            onMediaClick = { asset ->
                                onImageSelected(asset.uri.toUri()); onDismiss()
                            },
                            onMediaLongClick = {},
                            onDragSelectionStart = {},
                            onDragSelectionItem = {},
                            onDragSelectionEnd = {}
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（如有未使用/缺失 import，按编译报错增删；ktlint 在 Task 5 统一校验）。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/components/ChatPhotoPickerSheet.kt
git commit -m "feat(chat): 可搜索选图半屏面板 ChatPhotoPickerSheet"
```

---

## Task 4: 接入 ChatScreen，移除旧 `InAppPhotoPicker`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: 给 `ChatInputArea` 增加 `mediaViewModel` 参数**

定位 `ChatInputArea`（`ChatScreen.kt:674-680`），在参数列表末尾增加 `mediaViewModel: MediaViewModel`：

旧：
```kotlin
private fun ChatInputArea(
    currentModel: ChatModelOption,
    isProcessing: Boolean,
    onModelSwitch: (ChatModelOption) -> Unit,
    onSendMessage: (String) -> Unit,
    onImagePicked: (Uri) -> Unit = {}
) {
```
新：
```kotlin
private fun ChatInputArea(
    currentModel: ChatModelOption,
    isProcessing: Boolean,
    onModelSwitch: (ChatModelOption) -> Unit,
    onSendMessage: (String) -> Unit,
    onImagePicked: (Uri) -> Unit = {},
    mediaViewModel: MediaViewModel
) {
```

- [ ] **Step 2: 在 ChatScreen 调用处透传 `mediaViewModel`**

定位 `ChatScreen` 内 `ChatInputArea(...)` 调用（`ChatScreen.kt:317-334`），在 `onImagePicked = { uri -> viewModel.sendImageMessage(uri) }` 之后增加一行 `mediaViewModel = mediaViewModel,`：

```kotlin
                    onImagePicked = { uri ->
                        viewModel.sendImageMessage(uri)
                    },
                    mediaViewModel = mediaViewModel
                )
```

- [ ] **Step 3: 用 `ChatPhotoPickerSheet` 替换 `InAppPhotoPicker` 调用块**

定位 `ChatScreen.kt:807-818`。

旧：
```kotlin
    // 内置相册选取底部弹窗
    if (showPhotoPicker) {
        InAppPhotoPicker(
            sheetState = sheetState,
            context = context,
            onImageSelected = { uri ->
                onImagePicked(uri)
                showPhotoPicker = false
            },
            onDismiss = { showPhotoPicker = false }
        )
    }
```
新：
```kotlin
    // 内置相册选取底部弹窗（可搜索 + 复用 MediaGrid）
    if (showPhotoPicker) {
        ChatPhotoPickerSheet(
            sheetState = sheetState,
            mediaViewModel = mediaViewModel,
            onImageSelected = { uri ->
                onImagePicked(uri)
                showPhotoPicker = false
            },
            onDismiss = { showPhotoPicker = false }
        )
    }
```

- [ ] **Step 4: 新增 import**

在 `ChatScreen.kt` import 区追加：
```kotlin
import com.mamba.picme.features.chat.components.ChatPhotoPickerSheet
```

- [ ] **Step 5: 删除旧 `InAppPhotoPicker`**

删除 `ChatScreen.kt:1354-1430` 整个 `private fun InAppPhotoPicker(...) { ... }` 函数（连同其上方 KDoc）。

- [ ] **Step 6: 清理失效 import**

删除 `InAppPhotoPicker` 后，移除不再被引用的 import（以编译器/ktlint 报错为准，典型如）：
```kotlin
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape   // 若其它处未用
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable                   // 若其它处未用
import coil.compose.AsyncImage                                // 若其它处未用
import coil.request.ImageRequest                              // 若其它处未用
import android.provider.MediaStore
import androidx.compose.material.icons.rounded.PhotoLibrary    // 若其它处未用
```
> 注意：逐条按 `:app:compileDebugKotlin` + ktlint 实际报错删；被其它代码引用的 import 保留。

- [ ] **Step 7: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): ChatScreen 接入可搜索选图面板，移除 InAppPhotoPicker"
```

---

## Task 5: 构建 + 端到端验证

**Files:** （无新增；验证用）

- [ ] **Step 1: ktlint + detekt + 单测**

Run: `./gradlew :app:ktlintCheck :app:detekt :app:testDebugUnitTest`
Expected: 全绿（如有风格问题按提示修）。

- [ ] **Step 2: 构建 debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，产物 `app/build/outputs/apk/debug/polang-debug.apk`。

- [ ] **Step 3: 安装并启动**

Run:
```bash
adb install -r app/build/outputs/apk/debug/polang-debug.apk
adb shell am start -n com.mamba.picme/.MainActivity
```
Expected: 应用启动无崩溃（`adb logcat -b crash -d` 为空）。

- [ ] **Step 4: 手动验证主路径（用 ui-driver 或手动）**

进入相册首页 → 「+」→ 独立 Chat → 输入区图库按钮 → 弹出全高度面板：
1. 浏览态：显示日期分组的最近照片网格（非旧的 MediaStore 平铺）。
2. 搜索框输入「海滩」/某人 → 出现 loading → 相关结果网格。
3. 点击任一结果 → 面板关闭 → 该图作为消息出现在对话流。
4. 清空搜索 → 回到浏览态。
5. （可选）若设备语义索引未就绪 → 显示「搜索暂不可用」提示 + 仅浏览态。

期望：每步无崩溃、无 OOM；图片成功发出。

- [ ] **Step 5: 崩溃检查**

Run: `adb logcat -b crash -d | grep -E "FATAL|OutOfMemory" | head`
Expected: 空。

- [ ] **Step 6: 最终提交（如 Step 4-5 中有修调）**

```bash
git add -A
git commit -m "test(chat): 选图面板端到端验证通过"
```

---

## Self-Review（已核对）

- **Spec 覆盖**：搜索（Task 2/3 复用 `searchEngine.search`）、浏览态复用 `MediaGrid`（Task 3）、单选发送（Task 3/4 `onImageSelected`→`sendImageMessage`）、`searchEngine==null` 回退（Task 3 banner + VM 不调用 search，Task 2 测试覆盖）、i18n（Task 1）、移除主线程全量 cursor（Task 4 删除旧 picker，改用 `MediaViewModel.groupedMedia`）、Phase 2 多选（非目标，Spec 已列为后续）。全覆盖。
- **占位符**：无 TBD/TODO；每步含可执行代码或命令。
- **类型一致**：`ChatPhotoPickerViewModel(search, searchAvailable, coroutineScope, debounceMs)` 在 Task 2 定义、Task 3 调用签名一致；`PickerMode`、`ChatPhotoPickerSheet(sheetState, mediaViewModel, onImageSelected, onDismiss)` 定义与 Task 4 调用一致；`MediaAsset.uri: String` → `.toUri()` 与 `onImageSelected: (Uri)` 一致。
```
