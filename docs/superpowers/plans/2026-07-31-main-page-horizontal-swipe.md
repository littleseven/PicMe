# 主页面横滑切换实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在竖屏下为 Camera / Gallery / Chat / People 四个主页面增加左右边缘横滑切换手势，作为底部悬浮 Tab 的补充。

**Architecture:** 新增一个纯 Compose 层手势包装器 `MainPageSwipeWrapper`，将其套在四个主页面的根布局上；`MainActivity` 维护页面顺序与切换 lambda，页面切换仍走现有 `NavHost` 路由，不改变 `SceneManager/DisposableEffect` 生命周期。

**Tech Stack:** Jetpack Compose (`pointerInput`、`WindowInsets.systemGestures`)、Navigation Compose。

---

## 前置依赖

无需新增第三方依赖，`HorizontalPager` 已存在但本次不使用。

---

## Task 1: 创建 `MainPageSwipeWrapper.kt`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/common/components/MainPageSwipeWrapper.kt`

- [ ] **Step 1: 写入包装器完整实现**

```kotlin
package com.mamba.picme.features.common.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 主页面边缘横滑切换包装器。
 *
 * 仅在屏幕左右边缘的窄带内响应水平拖动，触发后通过 [onPageChanged] 通知外层切换页面。
 * 实际触发带会向屏幕内侧偏移系统手势区宽度，避免与系统返回手势冲突。
 *
 * @param enabled 是否启用横滑检测
 * @param currentIndex 当前页面索引
 * @param pageCount 总页面数
 * @param onPageChanged 切换请求回调，参数为目标页面索引
 * @param edgeWidth 边缘检测带宽度
 * @param swipeThreshold 触发切换的最小拖动距离
 * @param content 子内容
 */
@Composable
fun MainPageSwipeWrapper(
    enabled: Boolean,
    currentIndex: Int,
    pageCount: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    edgeWidth: Dp = 24.dp,
    swipeThreshold: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { edgeWidth.toPx() }
    val swipeThresholdPx = with(density) { swipeThreshold.toPx() }

    Box(
        modifier = modifier
            .pointerInput(enabled, currentIndex, pageCount, edgeWidthPx, swipeThresholdPx) {
                awaitEachGesture {
                    if (!enabled) return@awaitEachGesture

                    val down = awaitFirstDown()
                    val x = down.position.x
                    val width = size.width.toFloat()

                    // 使用 Ltr 获取物理左右边缘的系统手势 insets
                    val systemInsets = WindowInsets.systemGestures
                    val leftInsetPx = systemInsets.getLeft(density, LayoutDirection.Ltr).toFloat()
                    val rightInsetPx = systemInsets.getRight(density, LayoutDirection.Ltr).toFloat()

                    val inLeftEdge = x in leftInsetPx..(leftInsetPx + edgeWidthPx)
                    val inRightEdge = x in (width - rightInsetPx - edgeWidthPx)..(width - rightInsetPx)
                    if (!inLeftEdge && !inRightEdge) return@awaitEachGesture

                    val change = awaitTouchSlopOrCancellation(down.id) { pointerChange, _ ->
                        val dx = pointerChange.positionChange().x
                        if (abs(dx) > 0) {
                            pointerChange.consume()
                        }
                    } ?: return@awaitEachGesture

                    var totalDrag = 0f
                    val dragConsumed = horizontalDrag(change.id) { pointerChange ->
                        val dx = pointerChange.positionChange().x
                        totalDrag += dx
                        pointerChange.consume()
                    }

                    if (!dragConsumed) return@awaitEachGesture

                    when {
                        totalDrag >= swipeThresholdPx -> {
                            val target = (currentIndex - 1 + pageCount) % pageCount
                            onPageChanged(target)
                        }
                        totalDrag <= -swipeThresholdPx -> {
                            val target = (currentIndex + 1) % pageCount
                            onPageChanged(target)
                        }
                    }
                }
            }
    ) {
        content()
    }
}
```

- [ ] **Step 2: 验证无导入错误**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`

Expected: 本文件单独编译通过（其它任务引用后才会完整通过）。

---

## Task 2: 在 `MainActivity.kt` 增加主页面切换辅助

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/MainActivity.kt`

- [ ] **Step 1: 在 `MainActivity` 内定义页面顺序与切换 lambda**

在 `MainActivity` 的 `companion object` 下方（或 `setContent` 之前）添加：

```kotlin
private val mainPages = listOf(
    Screen.Camera,
    Screen.Gallery,
    Screen.Chat,
    Screen.People
)

private fun routeToMainPageIndex(route: String?): Int = when {
    route == Screen.Camera.route -> 0
    route == Screen.Chat.route -> 2
    route == Screen.People.route -> 3
    route == Screen.Gallery.route || route?.startsWith("${Screen.Gallery.route}?") == true -> 1
    else -> 1
}
```

- [ ] **Step 2: 在 `setContent` 内获取当前索引并提供切换回调**

在 `val navController = rememberNavController()` 之后添加：

```kotlin
val currentBackStackEntry by navController.currentBackStackEntryAsState()
val currentMainPageIndex = routeToMainPageIndex(currentBackStackEntry?.destination?.route)

val switchMainPage: (Int) -> Unit = { index ->
    val targetRoute = mainPages[index].route
    navController.navigate(targetRoute) {
        launchSingleTop = true
        popUpTo(Screen.Gallery.route) { saveState = true }
    }
}
```

**注意：** 需要在 `MainActivity.kt` 顶部添加导入：

```kotlin
import androidx.navigation.compose.currentBackStackEntryAsState
```

- [ ] **Step 3: 检查编译**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`

Expected: 编译通过（此时 lambda 尚未被使用，不会触发未使用警告之外的错误）。

---

## Task 3: 改造 `GalleryScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`

- [ ] **Step 1: 在函数签名中新增切换参数**

```kotlin
fun GalleryScreen(
    navController: NavController,
    viewModel: MediaViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateToChat: () -> Unit,
    onNavigateToCamera: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToModelCenter: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToTagControl: () -> Unit = {},
    onNavigateToPeople: () -> Unit = {},
    initialSearchQuery: String = "",
    initialPersonId: Long = 0L,
    currentMainPageIndex: Int = 1,
    onNavigateToMainPage: (Int) -> Unit = {}
)
```

- [ ] **Step 2: 导入 `MainPageSwipeWrapper`**

在文件导入区添加：

```kotlin
import com.mamba.picme.features.common.components.MainPageSwipeWrapper
```

- [ ] **Step 3: 用 `MainPageSwipeWrapper` 包裹 `Scaffold` 根布局**

找到 `Scaffold(` 调用处（约第 546 行），将其替换为：

```kotlin
MainPageSwipeWrapper(
    enabled = selectedMediaIndex == null && !isSelectionMode,
    currentIndex = currentMainPageIndex,
    pageCount = 4,
    onPageChanged = onNavigateToMainPage
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // 其余参数保持不变
        topBar = {
            // ... 原 topBar 内容
        }
    ) { padding ->
        // ... 原 content 内容
    }
}
```

**要点：**
- 保留 `Scaffold` 原有的 `modifier`、`contentWindowInsets`、`topBar` 和 `content`。
- `enabled` 仅在未打开媒体预览且未进入选择模式时为 `true`。

- [ ] **Step 4: 在 `MainActivity.kt` 的 Gallery 路由处传入新参数**

在 `MainActivity.kt` 中 `GalleryScreen` 调用处（约第 261 行）添加：

```kotlin
GalleryScreen(
    navController = navController,
    viewModel = mediaViewModel,
    settingsViewModel = settingsViewModel,
    initialSearchQuery = initialSearchQuery,
    initialPersonId = initialPersonId,
    onNavigateToChat = { ... },
    onNavigateToCamera = { ... },
    onNavigateToSettings = { ... },
    onNavigateToModelCenter = { ... },
    onNavigateToDebug = { ... },
    onNavigateToTagControl = { ... },
    onNavigateToPeople = { ... },
    currentMainPageIndex = currentMainPageIndex,
    onNavigateToMainPage = switchMainPage
)
```

- [ ] **Step 5: 编译检查**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`

Expected: 编译通过。

---

## Task 4: 改造 `CameraScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/camera/CameraScreen.kt`

- [ ] **Step 1: 在函数签名中新增切换参数**

```kotlin
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateBack: () -> Unit = {},
    viewModel: MediaViewModel,
    settingsViewModel: SettingsViewModel? = null,
    currentMainPageIndex: Int = 0,
    onNavigateToMainPage: (Int) -> Unit = {}
)
```

- [ ] **Step 2: 导入 `MainPageSwipeWrapper`**

在文件导入区添加：

```kotlin
import com.mamba.picme.features.common.components.MainPageSwipeWrapper
```

- [ ] **Step 3: 用 `MainPageSwipeWrapper` 包裹 `CameraContent` 调用**

将权限通过后的 `CameraContent(...)` 调用替换为：

```kotlin
MainPageSwipeWrapper(
    enabled = true,
    currentIndex = currentMainPageIndex,
    pageCount = 4,
    onPageChanged = onNavigateToMainPage
) {
    CameraContent(
        viewModel = viewModel,
        onNavigateToGallery = onNavigateToGallery,
        onNavigateBack = onNavigateBack,
        settingsViewModel = settingsViewModel
    )
}
```

**要点：** 权限未授予时显示权限请求 UI，不需要包装；相机预览内部手势由边缘区域隔离，不会产生误触。

- [ ] **Step 4: 在 `MainActivity.kt` 的 Camera 路由处传入新参数**

在 `MainActivity.kt` 中 `CameraScreen` 调用处（约第 230 行）添加：

```kotlin
CameraScreen(
    onNavigateToGallery = { navController.navigate(Screen.Gallery.route, navOptions { launchSingleTop = true }) },
    onNavigateBack = { navController.popBackStack() },
    viewModel = mediaViewModel,
    settingsViewModel = settingsViewModel,
    currentMainPageIndex = currentMainPageIndex,
    onNavigateToMainPage = switchMainPage
)
```

- [ ] **Step 5: 编译检查**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`

Expected: 编译通过。

---

## Task 5: 改造 `ChatScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: 在函数签名中新增切换参数**

在 `ChatScreen` 的现有参数末尾添加：

```kotlin
mediaViewModel: MediaViewModel,
onNavigateToPhotoEditor: (Uri, Boolean) -> Unit,
onNavigateToIDPhoto: (Uri) -> Unit,
currentMainPageIndex: Int = 2,
onNavigateToMainPage: (Int) -> Unit = {}
```

（实际位置根据当前签名调整，只需确保新增两个参数放在最后。）

- [ ] **Step 2: 导入 `MainPageSwipeWrapper`**

在文件导入区添加：

```kotlin
import com.mamba.picme.features.common.components.MainPageSwipeWrapper
```

- [ ] **Step 3: 用 `MainPageSwipeWrapper` 包裹 `Scaffold` 内容区**

当前结构为：

```kotlin
Scaffold(...) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding).background(...)) {
        // ...
    }
}
```

将其改为：

```kotlin
Scaffold(...) { padding ->
    MainPageSwipeWrapper(
        enabled = previewAssets.isEmpty() && imagePreview == null && previewChartSvg == null,
        currentIndex = currentMainPageIndex,
        pageCount = 4,
        onPageChanged = onNavigateToMainPage,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ... 原 Box 内部内容保持不变
        }
    }
}
```

**要点：**
- `enabled` 在全屏照片轮播、图片预览、图表 SVG 预览打开时禁用，避免与这些覆盖层的横向手势冲突。
- 注意 `MainPageSwipeWrapper` 本身消费 `padding`，内部 `Box` 不再重复 `padding`。

- [ ] **Step 4: 在 `MainActivity.kt` 的 Chat 路由处传入新参数**

在 `MainActivity.kt` 中 `ChatScreen` 调用处（约第 201 行）添加：

```kotlin
ChatScreen(
    viewModel = chatViewModel,
    settingsViewModel = settingsViewModel,
    onNavigateBack = { navController.popBackStack() },
    onNavigateToSettings = { navController.navigate(Screen.Settings.route, navOptions { launchSingleTop = true }) },
    onNavigateToGallery = { query -> navController.navigate(Screen.Gallery.createRoute(query), navOptions { launchSingleTop = true }) },
    mediaViewModel = mediaViewModel,
    onNavigateToPhotoEditor = { uri, autoOptimize -> ... },
    onNavigateToIDPhoto = { uri -> ... },
    currentMainPageIndex = currentMainPageIndex,
    onNavigateToMainPage = switchMainPage
)
```

- [ ] **Step 5: 编译检查**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`

Expected: 编译通过。

---

## Task 6: 改造 `PersonScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/person/PersonScreen.kt`

- [ ] **Step 1: 在函数签名中新增切换参数**

```kotlin
fun PersonScreen(
    viewModel: PersonViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGallery: (Long) -> Unit,
    currentMainPageIndex: Int = 3,
    onNavigateToMainPage: (Int) -> Unit = {}
)
```

- [ ] **Step 2: 导入 `MainPageSwipeWrapper`**

在文件导入区添加：

```kotlin
import com.mamba.picme.features.common.components.MainPageSwipeWrapper
```

- [ ] **Step 3: 用 `MainPageSwipeWrapper` 包裹 `Scaffold` 内容区**

当前 `Scaffold { innerPadding -> Box(...) { LazyVerticalGrid(...) } }`，改为：

```kotlin
Scaffold(
    // ... 原 topBar / snackbarHost
) { innerPadding ->
    MainPageSwipeWrapper(
        enabled = true,
        currentIndex = currentMainPageIndex,
        pageCount = 4,
        onPageChanged = onNavigateToMainPage,
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            LazyVerticalGrid(
                // ... 原参数
            )
        }
    }
}
```

- [ ] **Step 4: 在 `MainActivity.kt` 的 People 路由处传入新参数**

在 `MainActivity.kt` 中 `PersonScreen` 调用处（约第 530 行）添加：

```kotlin
PersonScreen(
    viewModel = personViewModel,
    onNavigateBack = { navController.popBackStack() },
    onNavigateToGallery = { personId ->
        navController.navigate(
            Screen.Gallery.createRoute(personId = personId),
            navOptions { launchSingleTop = true }
        )
    },
    currentMainPageIndex = currentMainPageIndex,
    onNavigateToMainPage = switchMainPage
)
```

- [ ] **Step 5: 编译检查**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`

Expected: 编译通过。

---

## Task 7: 最终验证

- [ ] **Step 1: 完整编译**

Run: `./gradlew :app:assembleDebug --no-daemon`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 静态检查（如项目启用 detekt/ktlint）**

Run: `./gradlew :app:detekt --no-daemon`（如果存在）

Expected: 无新增严重告警。

- [ ] **Step 3: 安装并手动验证（可选，需要设备）**

Run: `./gradlew :app:installDebug --no-daemon`

Expected: 安装成功。手动验证：
1. 在相册页左右边缘横滑可切到相机/Chat。
2. 在相机页左滑到相册、继续左滑到人物（循环）。
3. 在 Chat 打开图片预览时横滑被禁用。
4. 在相册打开大图预览时横滑被禁用。
5. 系统返回手势仍可正常返回上一页。

---

## 回滚提示

如验证不通过，优先回滚各屏幕对 `MainPageSwipeWrapper` 的引用与 `MainActivity` 新增参数，保留新增文件，直到手势行为稳定。
