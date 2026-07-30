# 全 App TopBar 图标风格统一 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `:app` 内所有 topbar 的图标族、返回键、按钮/字形尺寸、间距、容器统一到一套紧凑规格（Rounded 族 + AutoMirrored 返回键 + 36dp 按钮 / 22dp 字形 / 8dp 间距 + Material3 TopAppBar），并用可复用组件固化。

**Architecture:** 先落地可复用组件 `AppTopBar` / `AppTopBarAction` / `AppTopBarNavBack`（锁死 36/22/8 与 Rounded 返回键），再迁移 P0 四个主入口（chat / 相册 / editor / image-edit）到组件，然后批量统一 P1 设置子页与 P2 测试页的图标族/返回键/尺寸，最后全量替换 13 处过时返回键并验证。

**Tech Stack:** Kotlin + Jetpack Compose（Material3 `TopAppBar` / `CenterAlignedTopAppBar`），Robolectric 4.14 + `androidx.compose.ui.test`（JVM 单测验证组件），`/ui-driver`（设备端 a11y 验证）。

**对应 spec:** `docs/superpowers/specs/2026-07-30-app-topbar-icon-standard-design.md`

**全局规则（CLAUDE.md，每个 task 都要遵守）:** 不得使用 `com.mamba.picme.*` 全限定名（用 import）；禁止 `*` 通配 import；lambda 参数必须显式命名（禁 `it`）；XML/MD 缩进 2 空格、Kotlin 4 空格；i18n 字符串禁止硬编码。

---

## File Structure

- **Create:** `app/src/main/java/com/mamba/picme/features/common/topbar/AppTopBar.kt` — 可复用 topbar 组件（`AppTopBar` × 2 重载 + `AppTopBarAction` + `AppTopBarNavBack`）。唯一职责：固化 topbar 图标规格。
- **Create:** `app/src/test/java/com/mamba/picme/features/common/topbar/AppTopBarTest.kt` — Robolectric Compose 单测，验证组件交互契约。
- **Modify (P0):** `features/gallery/components/GalleryTopBar.kt`、`features/chat/ChatScreen.kt`（`ChatTopBar`）、`features/editor/components/EditorTopBar.kt`、`features/editor/ImageEditScreen.kt`
- **Modify (P1):** `features/settings/{LlmModelManagerScreen,SettingsScreen,DataPrivacyScreen,CommunicationChannelScreen,MemoryFactsScreen}.kt`、`features/idphoto/IDPhotoScreen.kt`、`features/debug/DebugScreen.kt`、`features/backuprestore/BackupRestoreActivity.kt`、`features/gallery/components/{TagGenerationControlScreen,SearchTopBar}.kt`
- **Modify (P2):** `features/debug/{JsBridgeDemo,LlmCallLogScreen}.kt`、`features/search/SearchTestScreen.kt`、`features/tagviewer/TagViewerTestScreen.kt`、`features/translation/SentencePieceTestScreen.kt`
- **不动（豁免）:** `features/gallery/components/MediaPager.kt`（全屏浮层关闭键 32dp）、`service/chat/FloatingChatBubbleService.kt`（悬浮泡 32dp）；所有列表行/卡片内图标、bottomBar 图标。

---

## Task 1: 可复用组件 AppTopBar（TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/common/topbar/AppTopBar.kt`
- Test: `app/src/test/java/com/mamba/picme/features/common/topbar/AppTopBarTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/features/common/topbar/AppTopBarTest.kt`:

```kotlin
package com.mamba.picme.features.common.topbar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppTopBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun action_click_invokes_callback() {
        var clicked = false
        composeRule.setContent {
            AppTopBarAction(
                icon = Icons.Rounded.Settings,
                contentDescription = "settings",
                onClick = { clicked = true }
            )
        }
        composeRule.onNodeWithContentDescription("settings").performClick()
        assertTrue(clicked)
    }

    @Test
    fun action_disabled_does_not_invoke_callback() {
        var clicked = false
        composeRule.setContent {
            AppTopBarAction(
                icon = Icons.Rounded.Settings,
                contentDescription = "settings",
                onClick = { clicked = true },
                enabled = false
            )
        }
        composeRule.onNodeWithContentDescription("settings").performClick()
        assertFalse(clicked)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.common.topbar.AppTopBarTest"`
Expected: 编译失败（`AppTopBarAction` 未定义）。

> **Robolectric 失败处置（重要）：** 本项目 `:app` 无 Robolectric+Compose 先例。若报 `SDK` 不支持（compileSdk 36 超 Robolectric 上限），在测试类加注解 `@Config(sdk = [34])`（import `org.robolectric.annotation.Config`）；若报需要 android resources，确认 `app/build.gradle.kts` 的 `testOptions { unitTests { isIncludeAndroidResources = true } }` 已存在。若 Robolectric 始终无法跑 Compose，**降级**：把本测试文件移到 `app/src/androidTest/...` 同包下，用 `./gradlew :app:connectedAndroidTest`（需设备）运行，测试代码不变。

- [ ] **Step 3: 实现组件**

Create `app/src/main/java/com/mamba/picme/features/common/topbar/AppTopBar.kt`:

```kotlin
package com.mamba.picme.features.common.topbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

/** topbar 图标规格（chat 紧凑基准）：36dp 按钮 / 22dp 字形 / 8dp 同组间距。 */
private val TopBarButtonSize = 36.dp
private val TopBarIconSize = 22.dp
private val TopBarSpacing = 8.dp

/** 槽位式主力 topbar。centered=true 用 CenterAlignedTopAppBar，否则 TopAppBar。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()
) {
    val wrappedActions: @Composable RowScope.() -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(TopBarSpacing), content = actions)
    }
    if (centered) {
        CenterAlignedTopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = { navigationIcon() },
            actions = wrappedActions,
            colors = colors
        )
    } else {
        TopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = { navigationIcon() },
            actions = wrappedActions,
            colors = colors
        )
    }
}

/** 便捷重载：文字标题 + 可选返回键 + 操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()
) {
    AppTopBar(
        title = { Text(title) },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                AppTopBarNavBack(onClick = onBack)
            }
        },
        actions = actions,
        centered = centered,
        colors = colors
    )
}

/** 标准操作图标 —— 一致性执行点。锁死 36dp 按钮 + 22dp 字形。 */
@Composable
fun AppTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(TopBarButtonSize)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: LocalContentColor.current,
            modifier = Modifier.size(TopBarIconSize)
        )
    }
}

/** 标准返回键 —— 锁死 AutoMirrored.Rounded.ArrowBack + 36/22。 */
@Composable
fun AppTopBarNavBack(
    onClick: () -> Unit,
    contentDescription: String = stringResource(R.string.back)
) {
    IconButton(onClick = onClick, modifier = Modifier.size(TopBarButtonSize)) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
            modifier = Modifier.size(TopBarIconSize)
        )
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.common.topbar.AppTopBarTest"`
Expected: 2 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/common/topbar/AppTopBar.kt \
        app/src/test/java/com/mamba/picme/features/common/topbar/AppTopBarTest.kt
git commit -m "feat(ui): 新增可复用 AppTopBar 组件（36/22/8 紧凑规格）"
```

---

## Task 2: P0 — GalleryTopBar 接组件

**Files:** Modify `app/src/main/java/com/mamba/picme/features/gallery/components/GalleryTopBar.kt`

- [ ] **Step 1: 替换 `GalleryTopBar` 主体（第 46-138 行）**

把现有 `TopAppBar(...)` 整体改为 `AppTopBar(...)`。关键变化：标题用 String 重载、navigationIcon 用 `AppTopBarNavBack`、所有 actions 改用 `AppTopBarAction`（自动 36/22）、删除 idle 态 `alpha(0.7f)`（idle 走默认 LocalContentColor，仅扫描态 primary）。保留 `displayCutoutPadding()` 经 modifier 透传、选择态标题计数、`GroupingMenu`。

新 `GalleryTopBar` 函数体（替换原 46-138 行的 `@OptIn... fun GalleryTopBar(...)`）:

```kotlin
@Composable
fun GalleryTopBar(
    isSelectionMode: Boolean,
    selectedCount: Int,
    groupingMode: GroupingMode,
    onNavigateBack: (() -> Unit)? = null,
    onNavigateToSettings: () -> Unit = {},
    onToggleSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onGroupingModeSelected: (GroupingMode) -> Unit,
    onSearchClick: () -> Unit = {},
    onTagScanClick: () -> Unit = {},
    onNavigateToTagControl: () -> Unit = {},
    onToggleScan: () -> Unit = {}
) {
    val isScanning by TagGenerationService.isScanning.collectAsState(false)
    AppTopBar(
        title = if (isSelectionMode) {
            stringResource(R.string.selected_items, selectedCount)
        } else {
            stringResource(R.string.gallery)
        },
        modifier = Modifier.displayCutoutPadding(),
        navigationIcon = {
            if (isSelectionMode || onNavigateBack != null) {
                AppTopBarNavBack(onClick = {
                    if (isSelectionMode) {
                        onToggleSelectionMode()
                    } else {
                        onNavigateBack?.invoke()
                    }
                })
            }
        },
        actions = {
            if (isSelectionMode) {
                AppTopBarAction(Icons.Rounded.SelectAll, stringResource(R.string.select_all), onSelectAll)
                AppTopBarAction(Icons.Rounded.Share, stringResource(R.string.ocr_share), onShareSelected)
                AppTopBarAction(Icons.Rounded.Delete, stringResource(R.string.delete), onDeleteSelected)
            } else {
                val scanTint = if (isScanning) MaterialTheme.colorScheme.primary else null
                AppTopBarAction(
                    icon = Icons.Rounded.Sell,
                    contentDescription = stringResource(R.string.tag_scan_control),
                    onClick = onNavigateToTagControl,
                    tint = scanTint
                )
                AppTopBarAction(
                    icon = if (isScanning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isScanning) {
                        stringResource(R.string.pause)
                    } else {
                        stringResource(R.string.start_scan)
                    },
                    onClick = onToggleScan,
                    tint = scanTint
                )
                AppTopBarAction(Icons.Rounded.Search, stringResource(R.string.search_photos), onSearchClick)
                GroupingMenu(currentMode = groupingMode, onModeSelected = onGroupingModeSelected)
                AppTopBarAction(Icons.Rounded.Settings, stringResource(R.string.settings), onNavigateToSettings)
            }
        }
    )
}
```

`GroupingMenu`（第 161-200 行）内部那个 `IconButton { Icon(Icons.AutoMirrored.Rounded.Sort, ...) }` 也改为 `AppTopBarAction(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.group_by), { showMenu = true })`，下拉菜单逻辑不变。

- [ ] **Step 2: 修正 imports**

删除不再需要的：`TopAppBar`、`IconButton`、`Icon`（若 GroupingMenu 改后不再直接用）、`displayCutoutPadding` 保留。新增：`import com.mamba.picme.features.common.topbar.AppTopBar`、`AppTopBarAction`、`AppTopBarNavBack`。保留 `MaterialTheme`（scanTint 用）。注意：禁止 `*` import，逐条核对。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/GalleryTopBar.kt
git commit -m "refactor(gallery): GalleryTopBar 接入 AppTopBar（36/22/8）"
```

---

## Task 3: P0 — ChatTopBar 接组件 + 加背景

**Files:** Modify `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（`ChatTopBar`，第 708-768 行）

- [ ] **Step 1: 重写 `ChatTopBar`（第 708-768 行）**

从自定义透明 `Row` 改为 `AppTopBar`（获得 surface 背景 + 标准 insets）。左侧导航两键（返回 + 侧栏）放 navigationIcon 槽的 `Row(spacedBy(8.dp))`，全部压到 36/22。标题留空。

替换第 708-768 行整个 `ChatTopBar` 函数:

```kotlin
@Composable
private fun ChatTopBar(
    onNavigateBack: () -> Unit,
    onOpenSidebar: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onClearChat: () -> Unit
) {
    AppTopBar(
        title = {},
        navigationIcon = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTopBarNavBack(onClick = onNavigateBack)
                AppTopBarAction(
                    icon = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.cd_open_sidebar),
                    onClick = onOpenSidebar
                )
            }
        },
        actions = {
            AppTopBarAction(Icons.Rounded.DeleteSweep, stringResource(R.string.clear_chat), onClearChat)
            AppTopBarAction(Icons.Rounded.AddComment, stringResource(R.string.new_chat), onNewChat)
            AppTopBarAction(Icons.Rounded.Settings, stringResource(R.string.settings), onNavigateToSettings)
        }
    )
}
```

- [ ] **Step 2: 清理 ChatScreen 不再使用的 imports**

删除：`statusBarsPadding`、`horizontalArrangement`（若仅此处用——核对其他用法）、`SpaceBetween`（若仅此处用）、`Modifier.size`/`22.dp`/`24.dp` 中仅服务于此处的（小心：ChatScreen 其他地方大量用 size/Modifier，**只删确实不再引用的**）。新增：`import com.mamba.picme.features.common.topbar.AppTopBar` 及其两个伴生。保留 `Arrangement`（navigationIcon Row 用 spacedBy）。**逐条核对，勿误删。**

- [ ] **Step 3: 确认 Scaffold topBar 条件渲染仍正常**

第 410-419 行的 `if (previewAssets.isEmpty() && imagePreview == null && previewChartSvg == null) { ChatTopBar(...) }` 不动。chat 加背景后，预览态隐藏 topBar 行为不变。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "refactor(chat): ChatTopBar 接入 AppTopBar + 加 surface 背景（36/22/8）"
```

---

## Task 4: P0 — EditorTopBar 接组件 + 图标族全 Rounded

**Files:** Modify `app/src/main/java/com/mamba/picme/features/editor/components/EditorTopBar.kt`

- [ ] **Step 1: 重写整个 `EditorTopBar`（第 30-115 行）**

从手写 `Surface + Row(64dp)` 改为 `AppTopBar`。6 个图标全部 Rounded 化：ArrowBack→`AppTopBarNavBack`、LayersClear→`Icons.Rounded.LayersClear`、AutoFixHigh→`Icons.Rounded.AutoFixHigh`、Undo→`Icons.AutoMirrored.Rounded.Undo`、Redo→`Icons.Rounded.Redo`、Check→`Icons.Rounded.Check`。删除手写 tint/alpha，disabled 用 `enabled` 参数。

替换第 30-115 行:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // 待重构：抽 EditorTopBarState
@Composable
fun EditorTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompare: (pressed: Boolean) -> Unit,
    onRemoveBackground: () -> Unit,
    onAiOptimize: () -> Unit,
    onDone: () -> Unit
) {
    AppTopBar(
        title = title,
        navigationIcon = {
            AppTopBarNavBack(onClick = onCancel)
        },
        actions = {
            AppTopBarAction(
                icon = Icons.Rounded.LayersClear,
                contentDescription = stringResource(R.string.remove_background),
                onClick = onRemoveBackground,
                enabled = !isSaving
            )
            AppTopBarAction(
                icon = Icons.Rounded.AutoFixHigh,
                contentDescription = stringResource(R.string.ai_optimize),
                onClick = onAiOptimize,
                enabled = !isSaving
            )
            AppTopBarAction(
                icon = Icons.AutoMirrored.Rounded.Undo,
                contentDescription = stringResource(R.string.undo),
                onClick = onUndo,
                enabled = canUndo
            )
            AppTopBarAction(
                icon = Icons.Rounded.Redo,
                contentDescription = stringResource(R.string.redo),
                onClick = onRedo,
                enabled = canRedo
            )
            AppTopBarAction(
                icon = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.done),
                onClick = onDone,
                enabled = !isSaving
            )
        }
    )
}
```

> **注意：** 原 `EditorTopBar` 有 `onCompare` 参数（长按对比），新代码未渲染它——若 editor 依赖对比手势，需保留一个 `AppTopBarAction` 或在别处承载。**执行时先 grep `onCompare` 用法**（`grep -rn "onCompare" app/src/main`）确认：若仍被调用，在 actions 末尾加一个 `AppTopBarAction(Icons.Rounded.Compare, stringResource(R.string.compare), { onCompare(true) })`（或按现有交互保留）。若已废弃，连同参数一并删除并在 commit message 注明。

- [ ] **Step 2: 替换 imports**

删除全部 `androidx.compose.material.icons.filled.*`、`automirrored.filled.*`、`outlined.LayersClear`、`Surface`、`TopAppBarColors` 相关、`statusBarsPadding`、`height`、`alpha`、`padding`、`Row`、`Alignment` 等仅服务旧实现的 import。新增 Rounded 版本：`import androidx.compose.material.icons.automirrored.rounded.Undo`、`import androidx.compose.material.icons.rounded.LayersClear`、`AutoFixHigh`、`Redo`、`Check`，以及 `import com.mamba.picme.features.common.topbar.AppTopBar`、`AppTopBarNavBack`、`AppTopBarAction`。逐条核对，禁 `*`。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/components/EditorTopBar.kt
git commit -m "refactor(editor): EditorTopBar 接入 AppTopBar + 图标族全 Rounded"
```

---

## Task 5: P0 — ImageEditScreen topbar 接组件

**Files:** Modify `app/src/main/java/com/mamba/picme/features/editor/ImageEditScreen.kt`（第 180-212 行 topBar）

- [ ] **Step 1: 替换 topBar（第 181-211 行的 `CenterAlignedTopAppBar(...)`）**

标题居中（`centered = true`）。Close 用 `AppTopBarAction(Icons.Rounded.Close, ...)`，Undo→`Icons.AutoMirrored.Rounded.Undo`、Check→`Icons.Rounded.Check`，全部经 `AppTopBarAction`。

替换第 181-211 行:

```kotlin
            AppTopBar(
                title = stringResource(R.string.edit),
                centered = true,
                navigationIcon = {
                    AppTopBarAction(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cancel),
                        onClick = onDismiss
                    )
                },
                actions = {
                    AppTopBarAction(
                        icon = Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = stringResource(R.string.undo),
                        onClick = {
                            onUndo()
                            drawIteration++
                        },
                        enabled = actions.isNotEmpty()
                    )
                    AppTopBarAction(
                        icon = Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.save),
                        onClick = onSave,
                        enabled = originalBitmap != null
                    )
                }
            )
```

> **不动 bottomBar（第 213-240 行）：** `Icons.Default.Brush` / `Icons.Default.BlurOn` 是 bottomBar FilterChip 的 leadingIcon，不在本次范围。

- [ ] **Step 2: 修正 imports**

删除：`CenterAlignedTopAppBar`、`IconButton`（若仅 topBar 用——核对）、`Icons.Default.Close/Check`、`Icons.AutoMirrored.Filled.Undo`。新增：`import androidx.compose.material.icons.rounded.Close`、`Check`、`import androidx.compose.material.icons.automirrored.rounded.Undo`，以及 `import com.mamba.picme.features.common.topbar.AppTopBar`、`AppTopBarAction`。保留 `Icons.Default.Brush/BlurOn` 的 import（bottomBar 仍用）。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/ImageEditScreen.kt
git commit -m "refactor(editor): ImageEditScreen topbar 接入 AppTopBar（居中标题）"
```

---

## Task 6: P0 设备验证检查点

- [ ] **Step 1: 安装并截图对比三页**

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/polang-debug.apk
```
用 `/ui-driver`（a11y 结构化驱动）进入 **chat / 相册 / 编辑器** 三页，抓取 topbar 图标节点，确认：
- 所有可点图标节点尺寸一致（36dp 容器）；
- contentDescription 存在且为中文字符串；
- 三页 topbar 都有 surface 背景横栏。

- [ ] **Step 2: 若有视觉问题，回到对应 Task 修正后再继续 P1**

---

## Task 7: P1 — 设置/子页迁移（图标族 + 返回键 + 尺寸）

**通用规则（适用于本 task 每个文件）:**
1. topbar 内的 `Icons.Default.*` / `Icons.Filled.*` / `Icons.Outlined.*` → 对应 `Icons.Rounded.*`（方向感知的用 `Icons.AutoMirrored.Rounded.*`）。
2. 返回键 `Icons.Default.ArrowBack` / `Icons.AutoMirrored.Filled.ArrowBack` / `Icons.Filled.ArrowBack` → 用 `AppTopBarNavBack` 或 `Icons.AutoMirrored.Rounded.ArrowBack`。
3. topbar 的 `IconButton { Icon(...) }` 若结构标准，整体替换为 `AppTopBar` + `AppTopBarAction`；若 topbar 结构特殊（带特殊 layout），则就地给 `IconButton` 加 `Modifier.size(36.dp)`、`Icon` 加 `Modifier.size(22.dp)`、actions 区 `spacedBy(8.dp)`。
4. **只改 topbar 内图标；列表行/卡片/bottomBar 图标一律不动。**
5. 每个文件改完编译通过后单独 commit。

- [ ] **Step 1: `IDPhotoScreen.kt`**

定位：`grep -n "TopAppBar\|IconButton\|Icons\." app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoScreen.kt`。已知第 75 行 `Icons.Default.Check`（topbar done 键）→ `Icons.Rounded.Check`，按钮/字形压 36/22（经 `AppTopBarAction` 或就地 size）。编译通过后 commit：`refactor(idphoto): topbar 图标统一 Rounded + 36/22`。

- [ ] **Step 2: `DebugScreen.kt`**

定位：`grep -n "TopAppBar\|IconButton\|Icons\." app/src/main/java/com/mamba/picme/features/debug/DebugScreen.kt`。已知非 Rounded 行：224(Save)、268(PlayArrow/Pause)、287(Stop,16dp)、347(DeleteSweep)、366(Search,18dp)、308-331（人物/风景等列表图标——**列表图标不动**）。**只改 topbar 区**（通常在文件顶部 Scaffold topBar 内）的图标→Rounded + 36/22；修正 16dp/18dp 字形为 22dp。编译后 commit：`refactor(debug): topbar 图标统一 Rounded + 36/22`。

- [ ] **Step 3: `LlmModelManagerScreen.kt`**

定位 topbar：读文件顶部 Scaffold topBar 区。已知非 Rounded 行多在列表/卡片（425/735/750/761/776/785/797/811/886）——**逐一判断是否在 topbar，仅改 topbar 内的**；capability 分类图标（113-127 Outlined.*）是列表映射，**不动**。topbar 内非 Rounded→Rounded + 36/22。编译后 commit：`refactor(settings): LlmModelManager topbar 图标统一`。

- [ ] **Step 4: `MemoryFactsScreen.kt`**

已知 331/338/378/385 `Icons.Outlined.Edit/Delete` 是**行内**操作图标，**不动**。检查 topbar（文件顶部）是否已 Rounded：`grep -n "TopAppBar" app/src/main/java/com/mamba/picme/features/settings/MemoryFactsScreen.kt`。若 topbar 已全 Rounded，仅确认返回键为 AutoMirrored；否则统一。编译后 commit（如有改动）：`refactor(settings): MemoryFacts topbar 返回键统一 AutoMirrored`。

- [ ] **Step 5: 已全 Rounded 的设置页（就地接组件 + 返回键核对）**

对 `SettingsScreen.kt`、`DataPrivacyScreen.kt`、`CommunicationChannelScreen.kt`、`BackupRestoreActivity.kt`、`TagGenerationControlScreen.kt`、`SearchTopBar.kt`：这些文件不在非 Rounded grep 结果中（已全 Rounded）。逐个 `grep -n "ArrowBack\|TopAppBar" <file>` 确认：返回键是 `AutoMirrored.Rounded.ArrowBack`（不是 `Filled`）；按钮/字形已是默认（若要严格 36/22，就地接 `AppTopBar` 或加 size）。每文件编译后 commit（可合并为一次 `refactor: 设置子页 topbar 返回键/尺寸统一`，若改动相似）。

- [ ] **Step 6: P1 整体编译 + 单测**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，单测全绿。

---

## Task 8: P2 — 测试页迁移

**通用规则同 Task 7。** 这些是内部测试页，优先级最低。

- [ ] **Step 1: `JsBridgeDemo.kt`** — 第 150 行 `Icons.Default.Code` → `Icons.Rounded.Code`（若在 topbar）。
- [ ] **Step 2: `SearchTestScreen.kt`** — 已知 177/181/201/234/546 `Icons.Default.*`（多为搜索框 leadingIcon/clear）。仅改 topbar 内的；搜索框图标属内容区可不动，但若要彻底统一也改 Rounded。
- [ ] **Step 3: `TagViewerTestScreen.kt`** — 第 200 行 `Icons.Filled/Outlined.ThumbDown` 是**行内**，不动；核对 topbar。
- [ ] **Step 4: `SentencePieceTestScreen.kt` / `LlmCallLogScreen.kt`** — `grep -n "Icons\.\|TopAppBar"` 核对 topbar，统一返回键与图标族。
- [ ] **Step 5: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。Commit：`refactor: 测试页 topbar 图标统一`。

---

## Task 9: 全量返回键替换 + 残留扫描

- [ ] **Step 1: 全量替换过时返回键**

Run（先看还剩哪些）:
```bash
grep -rn "Icons\.Default\.ArrowBack\|Icons\.Filled\.ArrowBack\|automirrored\.filled\.ArrowBack" app/src/main --include="*.kt"
```
对每一处：替换为 `Icons.AutoMirrored.Rounded.ArrowBack`（import 同步：`import androidx.compose.material.icons.automirrored.rounded.ArrowBack`），或改用 `AppTopBarNavBack`。逐文件编译。

- [ ] **Step 2: 残留扫描（应为空或仅豁免/列表图标）**

```bash
# topbar 文件内不应再有非 Rounded 图标族（豁免的 bottomBar/列表除外）
grep -rn "Icons\.Default\.\|Icons\.Filled\." app/src/main --include="*.kt" | grep -iE "TopBar|Screen|Activity" 
# 过时返回键应为 0
grep -rn "Filled\.ArrowBack\|Default\.ArrowBack" app/src/main --include="*.kt"
```
Expected: 返回键 0 命中；非 Rounded 仅剩 bottomBar/chip/列表行图标（已在豁免范围）。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: 全量替换过时 ArrowBack 为 AutoMirrored.Rounded"
```

---

## Task 10: 全量验证 + 收尾

- [ ] **Step 1: 全量编译 + JVM 单测**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，单测全绿。

- [ ] **Step 2: ktlint/detekt（注意项目现状）**

Run: `./gradlew ktlintCheck`
Expected: 无新增违规（项目 ktlint 插件状态见团队记忆，重点保证：无 FQN、无 `*` import、lambda 显式命名）。

- [ ] **Step 3: 设备端三页 + 设置页 ui-driver 复验**

用 `/ui-driver` 进入 chat / 相册 / 编辑器 / 设置，确认所有 topbar 图标节点尺寸一致（36dp）、图标族视觉一致（Rounded）、背景横栏统一。

- [ ] **Step 4: 更新文档（若 CLAUDE.md/FEATURES 有 topbar 相关描述需同步）**

`grep -rn "TopBar\|topbar\|TopAppBar" CLAUDE.md PRODUCT.md docs/01-PRODUCT/FEATURES.md`。若有描述 topbar 图标风格的内容，按新规范同步（三层文档一致性）。

- [ ] **Step 5: 最终 commit（如有文档/收尾改动）**

```bash
git add -A
git commit -m "docs: 同步 topbar 图标统一规范到文档"
```

---

## Self-Review

**Spec 覆盖:** spec 第 3 节规范 → Task 1（组件锁死）+ Task 2-5/7-8（迁移）；第 4 节组件 → Task 1；第 5 节 P0/P1/P2 → Task 2-5/7/8；豁免（MediaPager/悬浮泡）→ File Structure 注明不动；非目标（列表图标）→ Task 7 规则 4 + 各 Step 反复强调。✅

**类型一致性:** `AppTopBar`/`AppTopBarAction`/`AppTopBarNavBack` 签名在 Task 1 定义，Task 2-5 调用参数名（icon/contentDescription/onClick/enabled/tint、title/onBack/centered/navigationIcon/actions）全文一致。✅

**已知风险点（执行时留意）:**
1. Robolectric+Compose 无先例 → Task 1 Step 2 已给 SDK/降级处置。
2. EditorTopBar 的 `onCompare` 可能仍被调用 → Task 4 Step 1 已给核实指令。
3. ChatScreen imports 庞杂 → Task 3 Step 2 强调逐条核对勿误删。
4. P1/P2 多文件 topbar 边界需执行时 grep 核实 → 各 Step 给了 grep 命令。
