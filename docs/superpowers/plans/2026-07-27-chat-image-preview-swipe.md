# 聊天图片预览横滑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把聊天气泡单图全屏预览升级为 `HorizontalPager`,可在本会话全部带图消息间左右横滑(含编辑/生成图),并支持双指缩放。

**Architecture:** 点任一带图气泡时,从 `displayMessages` 快照出所有 `imageUri != null` 的消息 + 被点 index,交给一个新的 pager 版 overlay。overlay 用 `HorizontalPager` + per-page `detectTransformGestures` 缩放;保存按钮与续期按当前页驱动。原 `ImagePreviewOverlay`(单图)被替换。

**Tech Stack:** Jetpack Compose(`HorizontalPager`/`rememberPagerState`/`detectTransformGestures`)、Coil `AsyncImage`、现有 `ChatViewModel`。

**Spec:** `docs/superpowers/specs/2026-07-27-chat-preview-swipe-and-llmlog-traceid-design.md` 功能①。

---

## File Structure

- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`
  - 新增纯函数 `buildImagePreviewPages(messages, clickedId)`(可测)。
  - `PreviewImageState` → `ChatImagePreviewState`;`previewImage` 状态 → `imagePreview`。
  - `onImageClick` 改为构建翻页集合。
  - `ImagePreviewOverlay` → pager 版(`ChatImagePreviewOverlay`)。
- Create: `app/src/test/java/com/mamba/picme/features/chat/ImagePreviewPagesBuilderTest.kt` — 纯函数 JVM 单测。
- Modify(若新增页码文案):`app/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml` + `values-zh-rTW/strings.xml`。

**测试策略**:翻页集合构建是纯逻辑 → JVM 单测(TDD)。Compose UI(pager/缩放/保存按钮)→ 编译 + 手动验证(项目无 Compose UI JVM 测试基建;真门槛=编译+JVM 单测)。

---

### Task 1: 翻页集合构建纯函数(TDD)

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`(新增顶层私有函数 + page 模型)
- Test: `app/src/test/java/com/mamba/picme/features/chat/ImagePreviewPagesBuilderTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mamba/picme/features/chat/ImagePreviewPagesBuilderTest.kt`:

```kotlin
package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePreviewPagesBuilderTest {

    private fun msg(id: String, imageUri: String? = null, type: ChatMessageType = ChatMessageType.AGENT_IMAGE) =
        ChatMessageUi(id = id, type = type, content = "", imageUri = imageUri)

    @Test
    fun `filters to image-bearing messages and preserves order`() {
        val messages = listOf(
            msg("t1", type = ChatMessageType.USER_TEXT),        // 无图,丢弃
            msg("u1", imageUri = "content://a", type = ChatMessageType.USER_IMAGE),
            msg("a1", imageUri = "file://edited1", type = ChatMessageType.AGENT_EDIT_RESULT),
            msg("t2", type = ChatMessageType.AGENT_TEXT)         // 无图,丢弃
        )
        val pages = buildImagePreviewPages(messages)
        assertEquals(2, pages.size)
        assertEquals("u1", pages[0].messageId)
        assertEquals("a1", pages[1].messageId)
    }

    @Test
    fun `clicked index resolved within filtered list`() {
        val messages = listOf(
            msg("u1", imageUri = "content://a", type = ChatMessageType.USER_IMAGE),
            msg("a1", imageUri = "file://edited1", type = ChatMessageType.AGENT_EDIT_RESULT),
            msg("a2", imageUri = "file://edited2", type = ChatMessageType.AGENT_IMAGE)
        )
        val pages = buildImagePreviewPages(messages)
        val idx = indexOfPage(pages, "a1")
        assertEquals(1, idx)
    }

    @Test
    fun `clicked id not found yields index zero`() {
        val pages = buildImagePreviewPages(listOf(msg("u1", imageUri = "content://a")))
        assertEquals(0, indexOfPage(pages, "missing"))
    }

    @Test
    fun `empty when no image messages`() {
        val pages = buildImagePreviewPages(listOf(msg("t1", type = ChatMessageType.USER_TEXT)))
        assertTrue(pages.isEmpty())
    }
}
```

- [ ] **Step 2: 运行测试,确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ImagePreviewPagesBuilderTest"`
Expected: FAIL —— `buildImagePreviewPages` / `indexOfPage` / page 模型未定义(编译错)。

- [ ] **Step 3: 写最小实现**

在 `ChatScreen.kt` 末尾(其它 private data class 附近)新增:

```kotlin
/**
 * 图片预览翻页集合中的一个页面。uri 已在构建阶段解析完成(scheme 兜底)。
 */
data class ImagePreviewPage(
    val messageId: String,
    val uri: android.net.Uri,
    val isEditableResult: Boolean, // AGENT_IMAGE / AGENT_EDIT_RESULT
    val isSaved: Boolean
)

/**
 * 从会话消息快照构建图片预览翻页集合:保留所有 [ChatMessageUi.imageUri] 非空的消息,
 * 解析最终 Uri(file:// 兜底),保持原顺序。纯函数,便于单测。
 */
fun buildImagePreviewPages(messages: List<ChatMessageUi>): List<ImagePreviewPage> =
    messages.mapNotNull { msg ->
        val raw = msg.imageUri ?: return@mapNotNull null
        val parsed = android.net.Uri.parse(raw)
        val resolved = if (parsed.scheme != null) parsed
            else java.io.File(raw).toUri()
        ImagePreviewPage(
            messageId = msg.id,
            uri = resolved,
            isEditableResult = msg.type == ChatMessageType.AGENT_IMAGE ||
                msg.type == ChatMessageType.AGENT_EDIT_RESULT,
            isSaved = msg.imageSaved
        )
    }

/** 返回 [messageId] 在 pages 中的下标;找不到返回 0(兜底定位到首页)。 */
fun indexOfPage(pages: List<ImagePreviewPage>, messageId: String): Int {
    val i = pages.indexOfFirst { it.messageId == messageId }
    return if (i >= 0) i else 0
}
```

> 注:`android.net.Uri` / `java.io.File` 在 JVM 单测可用(Android SDK stub 之外,`Uri.parse` 在 unit test 走的是 Robolectric 或 Android 的 stub)。**若单测因 `Uri` 报 RuntimeError**:把 `buildImagePreviewPages` 拆成「先 `filterImageMessages(messages): List<ChatMessageUi>`(纯 Kotlin,可测顺序/过滤)」+「`resolvePage(msg): ImagePreviewPage`(Uri 解析,手动/仪器测)」。Step 3 先实现 `filterImageMessages` 版本让测试通过,Uri 解析作为单独未测函数。若遇此,改测试只断言顺序/过滤/下标(用 messageId),Uri 断言移除。

- [ ] **Step 4: 运行测试,确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ImagePreviewPagesBuilderTest"`
Expected: PASS(4/4)。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt \
        app/src/test/java/com/mamba/picme/features/chat/ImagePreviewPagesBuilderTest.kt
git commit -m "feat(chat): 图片预览翻页集合构建纯函数 + 单测"
```

---

### Task 2: 替换状态 + onImageClick 构建翻页集合

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt:199`(状态)、`:474-488`(onImageClick)、`:365/:412`(BackHandler/顶栏条件)

- [ ] **Step 1: 替换状态定义**

把 `ChatScreen.kt:199` 的:

```kotlin
var previewImage by remember { mutableStateOf<PreviewImageState?>(null) }
```

替换为:

```kotlin
var imagePreview by remember { mutableStateOf<ChatImagePreviewState?>(null) }
```

并在 `PreviewImageState` 定义处(`:1673`)替换为:

```kotlin
/**
 * 聊天图片横滑预览状态:打开瞬间快照的翻页集合 + 初始页下标。
 */
data class ChatImagePreviewState(
    val pages: List<ImagePreviewPage>,
    val initialIndex: Int
)
```

- [ ] **Step 2: 改 onImageClick 构建翻页集合**

把 `ChatScreen.kt:474-488` 的 `onImageClick = { msg -> ... }` 替换为:

```kotlin
onImageClick = { msg ->
    val pages = buildImagePreviewPages(messages)
    if (pages.isNotEmpty()) {
        val isEdit = msg.type == ChatMessageType.AGENT_IMAGE ||
            msg.type == ChatMessageType.AGENT_EDIT_RESULT
        if (isEdit) viewModel.touchEditImage(msg.imageUri)
        imagePreview = ChatImagePreviewState(
            pages = pages,
            initialIndex = indexOfPage(pages, msg.id)
        )
    }
}
```

> `messages` 是 `:186` 的 `val messages by viewModel.displayMessages.collectAsState()`,作用域可见。

- [ ] **Step 3: 同步引用 previewImage 的其余处**

`ChatScreen.kt:365`(BackHandler 条件)与 `:412`(顶栏隐藏条件)里的 `previewImage != null` 全部改为 `imagePreview != null`;BackHandler body 内 `previewImage = null` 改为 `imagePreview = null`。

- [ ] **Step 4: 暂时让旧 overlay 占位以保持可编译**

下一 Task 重写 overlay。本步先把 `:531-540` 的 `ImagePreviewOverlay(...)` 调用与 `:539 onDismiss`、`:535 previewImage?.copy` 里所有 `previewImage` 引用改名为 `imagePreview`。若 `ImagePreviewOverlay` 签名此时不匹配,先注释整个 `ImagePreviewOverlay(...)` 块,留 TODO 标记下个 Task 处理。**确保 `./gradlew :app:compileDebugKotlin` 通过。**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "refactor(chat): 图片预览状态切到翻页集合 ChatImagePreviewState"
```

---

### Task 3: 重写 overlay 为 HorizontalPager + 双指缩放

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt:1820`(替换 `ImagePreviewOverlay`)

- [ ] **Step 1: 新增必要 import**

在 `ChatScreen.kt` import 区添加(若无):

```kotlin
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
```

- [ ] **Step 2: 替换 ImagePreviewOverlay 为 pager 版**

把 `:1820` 起的整个 `private fun ImagePreviewOverlay(...)` 替换为:

```kotlin
@Composable
private fun ChatImagePreviewOverlay(
    state: ChatImagePreviewState?,
    onSave: (messageId: String, onDone: (Boolean) -> Unit) -> Unit,
    onPageChanged: (ImagePreviewPage) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val expiredToast = stringResource(R.string.chat_edit_save_expired_failed)
    if (state == null) return
    val pages = state.pages
    val pagerState = rememberPagerState(
        initialPage = state.initialIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size }
    )

    // 切页时:若是编辑/生成图则续期(LRU 回收)
    LaunchedEffect(pagerState.currentPage, pages.size) {
        pages.getOrNull(pagerState.currentPage)?.let(onPageChanged)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            val page = pages[pageIndex]
            // per-page 缩放状态
            var scale by remember(page.messageId) { mutableStateOf(1f) }
            var offset by remember(page.messageId) { mutableStateOf(Offset.Zero) }
            AsyncImage(
                model = page.uri,
                contentDescription = stringResource(R.string.cd_image_preview),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .pointerInput(page.messageId) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = nextScale
                            offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
        }

        // 关闭按钮(右上)
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // 页码指示器(右上,关闭键左侧)
        Text(
            text = "${pagerState.currentPage + 1} / ${pages.size}",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 72.dp, top = 20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // 保存按钮(底部居中,仅当前页为编辑/生成图)
        val currentPage = pages.getOrNull(pagerState.currentPage)
        if (currentPage?.isEditableResult == true) {
            Button(
                onClick = {
                    if (!currentPage.isSaved) {
                        onSave(currentPage.messageId) { ok ->
                            if (!ok) Toast.makeText(context, expiredToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !currentPage.isSaved,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(
                        if (currentPage.isSaved) R.string.chat_edit_saved_to_gallery
                        else R.string.chat_edit_save_to_gallery
                    )
                )
            }
        }
    }
}
```

> 注:翻页时 `isSaved` 状态不会自动随保存结果更新到 pages(它是打开快照)。**保存成功后**:在 `onSave` 回调里重建 `imagePreview`(把当前页 isSaved 置 true)即可。见 Step 4 调用方接线。

- [ ] **Step 3: 接线调用方**

把 `ChatScreen.kt:531-540` 的 overlay 调用替换为:

```kotlin
ChatImagePreviewOverlay(
    state = imagePreview,
    onSave = { messageId, onDone ->
        viewModel.saveEditResult(messageId) { ok ->
            if (ok) {
                // 重建快照,把已保存页置为 isSaved=true
                imagePreview?.let { s ->
                    imagePreview = s.copy(
                        pages = s.pages.map { p ->
                            if (p.messageId == messageId) p.copy(isSaved = true) else p
                        }
                    )
                }
            }
            onDone(ok)
        }
    },
    onPageChanged = { page ->
        if (page.isEditableResult) viewModel.touchEditImage(page.uri.toString())
    },
    onDismiss = { imagePreview = null }
)
```

> `touchEditImage` 当前签名接受 uri 字符串(原 `onImageClick` 传 `msg.imageUri`)。`page.uri.toString()` 与原 `file://` 路径一致。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。修复 import / 未解析符号。

- [ ] **Step 5: 手动验证**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/polang-debug.apk
```
在 chat 里:发图 / 触发一次图片编辑 → 点开图片预览 → 验证:
- 左右滑可遍历本会话所有图片(含编辑/生成图);
- 双指可缩放(1x~5x),松手回 ~1x 自动回正;
- 滑到编辑/生成图显示「保存到相册」,保存后变「已保存」;
- 页码 `N/M` 随翻页更新;关闭键/返回键正常。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): 图片预览升级为 HorizontalPager 横滑 + 双指缩放"
```

---

## Self-Review(写完核对)

- **Spec 覆盖**:① 本会话全部带图消息(Task 1 过滤)② 含编辑/生成(Task 1 isEditableResult)③ 双指缩放(Task 3)④ 保存按当前页(Task 3)⑤ 续期(Task 3 onPageChanged)—— 全覆盖。
- **占位符**:无 TBD;Uri 单测潜在 Robolectric 问题已写退路(Task 1 Step 3 注)。
- **类型一致**:`ImagePreviewPage` / `ChatImagePreviewState` / `buildImagePreviewPages` / `indexOfPage` 在 Task 1-3 名称一致;`onPageChanged` / `onSave` 签名一致。
