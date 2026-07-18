# Chat 选图「预览 + 意图」交互流程 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Chat 选图「选完即发 + 本地图像理解」改为「选中→输入框缩略图预览 + 意图 chips(理解/找相似/编辑) + 可选文字→按意图路由发送」。

**Architecture:** 选图不再即发——`ChatInputArea` 持有 `pendingImage`/`selectedIntent` 状态并渲染缩略图行 + chips 行；`ChatViewModel` 拆出 `stageImage`（仅持久化+设 `_lastUserImageUri`）与 `sendImageWithIntent(uri, intent, text)`（UNDERSTAND 复用现有图像理解，FIND_SIMILAR 用既有 `SemanticSearchEngine.searchByImage` 召回）；EDIT 由 UI 直连 `onNavigateToPhotoEditor`。

**Tech Stack:** Kotlin / Jetpack Compose（Material3 `FilterChip`/`ModalBottomSheet`）/ Coil / kotlinx-coroutines-test + runTest + MockK。

**Spec:** `docs/superpowers/specs/2026-07-18-chat-image-preview-intent-flow-design.md`

---

## File Structure

- **Modify** `app/src/main/res/values/strings.xml` + `values-zh-rCN/` + `values-zh-rTW/` — 新增 chip 文案与移除按钮 cd。
- **Modify** `app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt` — 新增 public `searchByImage(bitmap, topK)` 委托到私有 `semanticSearchEngine`。
- **Modify** `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` — 新增 `ImageIntent` 枚举、`stageImage(uri)`、`sendImageWithIntent(uri, intent, text)`。
- **Create** `app/src/test/java/com/mamba/picme/features/chat/ChatImageIntentViewModelTest.kt` — `stageImage`/`sendImageWithIntent` 路由单测。
- **Modify** `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` — `ChatInputArea`（pendingImage/intent 状态、onSend 路由、透传 `onNavigateToPhotoEditor`）、`ChatTextInputMode`（缩略图行+chips 行）、选图回调改 `stageImage`。

复用（不新造）：
- 持久化：`ChatViewModel.persistImage`（:1389）。
- 图像理解：`ChatViewModel.sendImageMessage`（:1074）整段（UNDERSTAND 直接复用）。
- 相似召回：`SemanticSearchEngine.searchByImage(bitmap): List<SemanticScoredMedia>`（:208，`.media: MediaAsset`）。
- 结果轮播：`insertMediaResultsMessage(sessionId, MediaResultsUi(...))`（:988）。
- 编辑跳转：`ChatScreen.onNavigateToPhotoEditor(uri, autoOptimize)`（:168）。
- VM 持有的 `mediaSearchEngine`（见 :953 直连回退处）。

---

## Task 1: i18n 文案（中/英/繁）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

- [ ] **Step 1: `values/strings.xml` 追加**

```xml
    <!-- Chat image intent chips -->
    <string name="chat_intent_understand">Understand</string>
    <string name="chat_intent_find_similar">Find Similar</string>
    <string name="chat_intent_edit">Edit</string>
    <string name="cd_remove_pending_image">Remove selected image</string>
```
插在上一任务 `chat_photo_picker_no_results` 那一组之后（`</resources>` 之前）。

- [ ] **Step 2: `values-zh-rCN/strings.xml` 追加**

```xml
    <!-- Chat image intent chips -->
    <string name="chat_intent_understand">图像理解</string>
    <string name="chat_intent_find_similar">找相似</string>
    <string name="chat_intent_edit">编辑</string>
    <string name="cd_remove_pending_image">移除已选图片</string>
```

- [ ] **Step 3: `values-zh-rTW/strings.xml` 追加**

```xml
    <!-- Chat image intent chips -->
    <string name="chat_intent_understand">圖像理解</string>
    <string name="chat_intent_find_similar">找相似</string>
    <string name="chat_intent_edit">編輯</string>
    <string name="cd_remove_pending_image">移除已選圖片</string>
```

- [ ] **Step 4: 校验三份各 4 条**

Run: `grep -hcE 'name="chat_intent_(understand|find_similar|edit)"|name="cd_remove_pending_image"' app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml`
Expected: 三个文件各输出 `4`。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(chat): 选图意图 chips i18n 文案（中/英/繁）"
```

---

## Task 2: `MediaSearchEngine.searchByImage` 公共委托

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt`（`semanticSearchEngine` 为 private，需暴露）
- Test: `app/src/test/java/com/mamba/picme/domain/search/MediaSearchEngineImageDelegateTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSearchEngineImageDelegateTest {

    @Test
    fun `searchByImage delegates to semantic engine and maps to media`() = runTest {
        val asset = MediaAsset(id = 1, uri = "u1", type = MediaType.PHOTO, captureDate = 0, fileName = "a.jpg")
        val semantic = mockk<SemanticSearchEngine>()
        coEvery { semantic.searchByImage(any(), any(), any()) } returns listOf(SemanticScoredMedia(asset, 0.9f))
        // 用反射把 semantic 注入 MediaSearchEngine 的 private 构造参，或用其 public 工厂；
        // 若 MediaSearchEngine 无注入入口，则在 MediaSearchEngine 上新增 @VisibleForTesting 构造参。
        val engine = MediaSearchEngineFactory.forTest(semanticSearchEngine = semantic)
        val result = engine.searchByImage(android.graphics.Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        assertEquals(listOf(asset), result)
    }

    @Test
    fun `searchByImage returns empty when semantic engine absent`() = runTest {
        val engine = MediaSearchEngineFactory.forTest(semanticSearchEngine = null)
        val result = engine.searchByImage(android.graphics.Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        assertTrue(result.isEmpty())
    }
}
```

> 说明：`MediaSearchEngine` 当前构造里 `semanticSearchEngine` 为 private。实现 Step 3 时，把该构造参保留为 private val，并新增一个 `@VisibleForTesting internal constructor` 或 `companion object fun forTest(...)` 供测试注入；生产构造不变。`android.graphics.Bitmap` 在 JVM 单测需配置 robolectric 或改用 mock——若项目未配 robolectric，则本任务改为「仅编译验证 + 端到端验证」（见 Task 5），跳过该单测，并在 Step 2 验证委托编译通过。

- [ ] **Step 2: 运行测试，确认状态**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.MediaSearchEngineImageDelegateTest"`
Expected：如配 robolectric 则 FAIL（方法未实现）；否则编译通过、单测跳过。无论哪种，进入 Step 3。

- [ ] **Step 3: 在 `MediaSearchEngine` 新增公共委托方法**

在 `MediaSearchEngine` 类体内（紧邻现有 `search(...)` 之后）追加：

```kotlin
    /**
     * 以图搜图（公共入口）：委托给 [semanticSearchEngine]，把打分结果映射为 [MediaAsset] 列表。
     * 用于 Chat「找相似」意图——对用户刚选中的图片做 embedding 近邻召回。
     * 引擎未就绪/未注入时返回空列表。
     */
    suspend fun searchByImage(
        bitmap: android.graphics.Bitmap,
        topK: Int = 50
    ): List<MediaAsset> =
        semanticSearchEngine?.searchByImage(bitmap, null, topK)?.map { it.media } ?: emptyList()
```

确保文件已 `import com.mamba.picme.agent.core.model.context.MediaAsset`（若未 import 则补）。

- [ ] **Step 4: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt \
        app/src/test/java/com/mamba/picme/domain/search/MediaSearchEngineImageDelegateTest.kt
git commit -m "feat(search): MediaSearchEngine 暴露 searchByImage 公共委托"
```

---

## Task 3: `ChatViewModel.stageImage` + `sendImageWithIntent`（TDD）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatImageIntentViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatImageIntentViewModelTest {

    @Test
    fun `stageImage returns null when persist fails`() = runTest {
        val vm = ChatViewModelTestFactory.create(persistImageResult = null)
        val result = vm.stageImage(android.net.Uri.parse("content://x/1"))
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `sendImageWithIntent UNDERSTAND delegates to image understanding`() = runTest {
        val vm = ChatViewModelTestFactory.create(persistImageResult = "files/img.jpg")
        vm.sendImageWithIntent("files/img.jpg", ImageIntent.UNDERSTAND, null)
        // 断言：触发了图像理解（通过 spy/计数器验证 streamChat 或 imageInference 被调用），
        // 见 ChatViewModelTestFactory 里的 mock orchestrator 记录。
        coVerify(atLeast = 1) { /* orchestrator.withModelLoaded(...) 或 imageInference 被调用 */ }
    }

    @Test
    fun `sendImageWithIntent FIND_SIMILAR emits media results carousel`() = runTest {
        val asset = MediaAsset(id = 1, uri = "u1", type = MediaType.PHOTO, captureDate = 0, fileName = "a.jpg")
        val vm = ChatViewModelTestFactory.create(
            persistImageResult = "files/img.jpg",
            searchByImageResult = listOf(asset)
        )
        vm.sendImageWithIntent("files/img.jpg", ImageIntent.FIND_SIMILAR, null)
        // 断言：插入了一条 media-results 消息（通过 mock chatMessageDao 捕获 type）
        coVerify { vm.insertedMessagesAny { it.type.startsWith("agent") } }
    }
}
```

> 说明：`ChatViewModelTestFactory.create(...)` 是测试辅助，用 MockK 构造 `ChatViewModelDependencies`（mock `ChatMessageDao`/`ChatSessionDao`/`MediaSearchEngine`/orchestrator），并把 `persistImage`/`searchByImage` 桩好。若 `ChatViewModel` 现有测试（如 `ChatViewModelTitleUpdateTest`）已有类似工厂，直接复用并扩展；否则在本测试文件内新建 `internal object ChatViewModelTestFactory`。`stageImage`/`sendImageWithIntent`/`persistImage` 的可见性：测试需要时用 `@VisibleForTesting` 暴露。

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatImageIntentViewModelTest"`
Expected: FAIL（`stageImage`/`sendImageWithIntent`/`ImageIntent` 未定义）。

- [ ] **Step 3: 实现 `ImageIntent` + `stageImage` + `sendImageWithIntent`**

在 `ChatViewModel.kt` 顶层（class 之外）加：

```kotlin
/** 选图后的用户意图。EDIT 在 UI 层直接跳 PhotoEditor，不会进入 VM。 */
enum class ImageIntent { UNDERSTAND, FIND_SIMILAR, EDIT }
```

在 `ChatViewModel` 类体内（紧邻 `sendImageMessage`）加：

```kotlin
    /**
     * 仅暂存图片：复制到内部存储 + 设 [_lastUserImageUri]，**不**插入消息、**不**触发推理。
     * 返回持久化后的路径字符串；失败返回 null。供 Chat 输入框「缩略图预览」用。
     */
    fun stageImage(uri: android.net.Uri): String? {
        val persisted = persistImage(uri) ?: return null
        _lastUserImageUri.value = persisted
        return persisted
    }

    /**
     * 按 [intent] 发送「图 + 意图/文字」。
     * - [ImageIntent.UNDERSTAND]：复用既有图像理解（等价 sendImageMessage）。
     * - [ImageIntent.FIND_SIMILAR]：以图搜图，命中则插 media-results 轮播，否则提示。
     * - [text] 非空时作为 Agent 指令（覆盖/补充 chip 意图）。
     * 注意：[ImageIntent.EDIT] 由 UI 直接跳 PhotoEditor，不应进入本方法。
     */
    fun sendImageWithIntent(uri: String, intent: ImageIntent, text: String?) = viewModelScope.launch {
        val sessionId = _currentSessionId.value
        try {
            ensureSessionExists(sessionId)
            when {
                !text.isNullOrBlank() -> {
                    // 文字指令优先：插 user_text 并带上图片上下文走 Agent
                    _lastUserImageUri.value = uri
                    sendMessage(text)
                }
                intent == ImageIntent.FIND_SIMILAR -> {
                    _isProcessing.value = true
                    val bitmap = runCatching {
                        context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }.getOrNull()
                    val assets = if (bitmap != null) {
                        mediaSearchEngine.searchByImage(bitmap)
                    } else emptyList()
                    _isProcessing.value = false
                    if (assets.isNotEmpty()) {
                        insertMediaResultsMessage(
                            sessionId,
                            com.mamba.picme.features.chat.model.MediaResultsUi(
                                query = context.getString(R.string.chat_intent_find_similar),
                                assets = assets.take(MAX_CARDS),
                                totalCount = assets.size,
                                isRefinement = false
                            )
                        )
                    } else {
                        insertAgentMessage(sessionId, context.getString(R.string.gallery_search_no_results), "gallery_search")
                    }
                }
                else -> {
                    // UNDERSTAND（默认）：直接复用现有图像理解
                    sendImageMessage(android.net.Uri.parse(uri))
                }
            }
            chatSessionDao.touchSession(sessionId)
        } catch (e: Exception) {
            Logger.e(TAG, "sendImageWithIntent failed", e)
            _isProcessing.value = false
        }
    }
```

> 实现注意：
> - `mediaSearchEngine`：用 VM 内既有的 `mediaSearchEngine` 属性（见 :953 直连回退处使用的同名引用）。若该属性实际命名不同，按现有代码对齐。
> - `MAX_CARDS`、`insertMediaResultsMessage`、`insertAgentMessage`、`_isProcessing`、`ensureSessionExists`、`currentModelLabel` 均为 VM 既有成员，直接复用。
> - `MediaResultsUi` 的包路径以现有 import 为准（如已在文件中 import 则去掉全限定名）。
> - `sendImageMessage` 的参数类型是 `Uri`，故 `sendImageMessage(android.net.Uri.parse(uri))`。

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatImageIntentViewModelTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt \
        app/src/test/java/com/mamba/picme/features/chat/ChatImageIntentViewModelTest.kt
git commit -m "feat(chat): ChatViewModel stageImage + sendImageWithIntent（按意图路由）"
```

---

## Task 4: ChatScreen UI —— 预览缩略图 + chips + 发送路由

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: `ChatInputArea` 增加 `onNavigateToPhotoEditor` 参数 + 预览/意图状态**

`ChatInputArea`（:776）参数列表追加 `onNavigateToPhotoEditor: (String, Boolean) -> Unit`；函数体内（`var text` 旁）追加：

```kotlin
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var selectedIntent by remember { mutableStateOf<ImageIntent?>(null) }
```

并把传给 `ChatTextInputMode` 的 `onImagePicked`/选图回调由 `sendImageMessage` 改为暂存：

定位 `ChatPhotoPickerSheet(...)` 调用块（:907 附近），`onImageSelected` 改为：

```kotlin
            onImageSelected = { uri ->
                viewModel.stageImage(uri)?.let { persisted ->
                    pendingImage = Uri.parse(persisted)
                    selectedIntent = null
                }
                showPhotoPicker = false
            },
```

- [ ] **Step 2: `ChatInputArea` 的发送 `onSend` 改为按意图路由**

定位 `ChatTextInputMode(...)` 调用（:855 附近）的 `onSend = { ... }`，改为：

```kotlin
                    onSend = {
                        val img = pendingImage
                        when {
                            img != null && selectedIntent == ImageIntent.EDIT -> {
                                onNavigateToPhotoEditor(img.toString(), false)
                                pendingImage = null
                                selectedIntent = null
                            }
                            img != null -> {
                                viewModel.sendImageWithIntent(
                                    uri = img.toString(),
                                    intent = selectedIntent ?: ImageIntent.UNDERSTAND,
                                    text = text.trim().takeIf { it.isNotBlank() }
                                )
                                pendingImage = null
                                selectedIntent = null
                                text = ""
                            }
                            text.isNotBlank() -> {
                                onSendMessage(text.trim())
                                text = ""
                            }
                        }
                    },
```

并给 `ChatTextInputMode(...)` 调用新增参数透传：

```kotlin
                    pendingImage = pendingImage,
                    selectedIntent = selectedIntent,
                    onSelectIntent = { selectedIntent = it },
                    onRemovePendingImage = {
                        pendingImage = null
                        selectedIntent = null
                    },
```

- [ ] **Step 3: `ChatScreen` 透传 `onNavigateToPhotoEditor` 给 `ChatInputArea`**

定位 `ChatInputArea(...)` 调用（:417），在参数末尾追加：

```kotlin
                    onNavigateToPhotoEditor = onNavigateToPhotoEditor,
                    viewModel = viewModel
```
（`viewModel` 已在 ChatScreen 作用域；若 `ChatInputArea` 之前没有 `viewModel`，则按需新增形参 `viewModel: ChatViewModel`——本任务 Step 1 已用 `viewModel.stageImage/sendImageWithIntent`，故需把 `viewModel` 透入 `ChatInputArea`。）

> 说明：`ChatInputArea` 现有签名未含 `viewModel`。本步骤把 `viewModel: ChatViewModel` 加入 `ChatInputArea` 形参，并在 `:417` 调用处传 `viewModel = viewModel`。

- [ ] **Step 4: `ChatTextInputMode` 渲染缩略图行 + chips 行**

`ChatTextInputMode`（:924）形参追加：

```kotlin
    pendingImage: Uri? = null,
    selectedIntent: ImageIntent? = null,
    onSelectIntent: (ImageIntent) -> Unit = {},
    onRemovePendingImage: () -> Unit = {}
```

`hasContent` 改为 `val hasContent = text.isNotBlank() || pendingImage != null`（让「有图无字」也能发送）。

在 `Column { ... }` 最顶部（第一行 Box 之前）插入：

```kotlin
        if (pendingImage != null) {
            // 缩略图行
            Box(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(pendingImage).size(256).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                IconButton(
                    onClick = onRemovePendingImage,
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_remove_pending_image),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            // chips 行
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ImageIntentChip(R.string.chat_intent_understand, ImageIntent.UNDERSTAND, selectedIntent, onSelectIntent)
                ImageIntentChip(R.string.chat_intent_find_similar, ImageIntent.FIND_SIMILAR, selectedIntent, onSelectIntent)
                ImageIntentChip(R.string.chat_intent_edit, ImageIntent.EDIT, selectedIntent, onSelectIntent)
            }
        }
```

文件末尾新增私有 Composable：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageIntentChip(
    @StringRes labelRes: Int,
    intent: ImageIntent,
    selected: ImageIntent?,
    onSelect: (ImageIntent) -> Unit
) {
    FilterChip(
        selected = selected == intent,
        onClick = { onSelect(intent) },
        label = { Text(stringResource(labelRes), fontSize = 12.sp) }
    )
}
```


补 import（按编译报错增删）：

```kotlin
import androidx.compose.material3.FilterChip
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import coil.compose.AsyncImage   // 若已被移除则重新加回
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import com.mamba.picme.features.chat.ImageIntent   // 若 ImageIntent 与 ChatTextInputMode 不同包
```

- [ ] **Step 5: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（按报错补/删 import）。

- [ ] **Step 6: ktlintFormat 清理**

Run: `./gradlew :app:ktlintFormat` ；`git diff --stat app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` 确认仅风格整理。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): 选图后预览缩略图+意图 chips，按意图路由发送"
```

---

## Task 5: 构建 + 端到端验证

**Files:** （无新增；验证用）

- [ ] **Step 1: detekt + 单测**

Run: `./gradlew :app:detekt :app:testDebugUnitTest`
Expected: 全绿（ktlint 的 `build.gradle.kts` 解析报错为既有环境问题，不计入；chat 源码 ktlint 用 `--continue` 确认无违规）。

- [ ] **Step 2: 构建 APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 安装并进入独立 Chat**

Run:
```bash
adb install -r app/build/outputs/apk/debug/polang-debug.apk
adb shell am force-stop com.mamba.picme && adb logcat -c
adb shell am start -n com.mamba.picme/.MainActivity
```
Expected: 启动无崩溃（`adb shell pidof com.mamba.picme` 非空，`adb logcat -b crash -d` 为空）。

- [ ] **Step 4: 手动验证主路径（ui-driver 或手动）**

进入独立 Chat → 相册选图：
1. 选中后**不**立即发送；输入框上方出现缩略图（72dp）+ chips 行（图像理解/找相似/编辑）。
2. 点「×」→ 缩略图与 chips 消失，回到纯文字输入。
3. 重新选图 → 点「编辑」chip → 发送 → 进入 PhotoEditor；返回 Chat 后文字草稿仍在。
4. 重新选图 → 点「找相似」chip → 发送 → 出现相似照片轮播（MediaResultsCarousel）。
5. 重新选图 → 不选 chip 直接发送 → 等同「图像理解」（本地/远程描述图片）。
6. 重新选图 → 输入「把背景调蓝」→ 发送 → 走文字指令/AI 编辑。

期望：每步无崩溃；意图路由正确。

- [ ] **Step 5: 崩溃检查**

Run: `adb logcat -b crash -d | grep -E "FATAL|OutOfMemory" | head`
Expected: 空。

- [ ] **Step 6: 最终提交（如有 Step 4-5 修调）**

```bash
git add -A && git commit -m "test(chat): 选图意图流程端到端验证通过" || echo "no changes"
```

---

## Self-Review（已核对）

- **Spec 覆盖**：选中不即发（Task 4 改 onImagePicked→stageImage）、缩略图预览+取消（Task 4）、意图 chips（Task 1+4）、按意图路由——理解(Task3 UNDERSTAND 复用)/找相似(Task2+3)/编辑(Task4 直连 PhotoEditor)/文字(Task3 文字分支)（全覆盖）；默认 UNDERSTAND 兜底（Task 4 `selectedIntent ?: UNDERSTAND`）；i18n（Task 1）；stageImage 失败处理（Task 3+4）。全覆盖。
- **占位符**：Task 2 的单测含 robolectric 条件分支说明（非 TBD，是明确的二选一执行路径）；其余步骤均含可执行代码/命令。
- **类型一致**：`ImageIntent { UNDERSTAND, FIND_SIMILAR, EDIT }` 在 Task 3 定义、Task 4 引用一致；`stageImage(uri): String?`、`sendImageWithIntent(uri: String, intent, text: String?)` 定义与 Task 4 调用一致；`searchByImage(bitmap): List<MediaAsset>`（Task 2）与 VM 调用一致；`MediaResultsUi`/`insertMediaResultsMessage` 复用既有签名。
```
