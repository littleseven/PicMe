# 系统栏适配统一治理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一 App 各页面系统栏处理：仅相机/全屏预览沉浸，其余页面系统栏可见且避让正确，顶栏避让收口到 AppTopBar。

**Architecture:** 单点修复 + 删除副作用：AppTopBar 内置 `statusBarsPadding()`（一处改、全局生效），删除 Gallery/Chat/Settings 三处 `DisposableEffect(Unit)` 无条件隐藏系统栏的逻辑。相机、MediaPager、聊天全屏预览等已正确处理的沉浸场景不动。

**Tech Stack:** Kotlin / Jetpack Compose / androidx.core WindowInsetsControllerCompat

**Spec:** `docs/superpowers/specs/2026-08-02-system-bars-insets-governance-design.md`

**说明：** 本任务为纯 Compose UI 修饰符/副作用调整，无可单测逻辑，验证方式为编译 + adb 真机截屏逐页核对（见 Task 5），不适用 TDD。Git 提交需用户明确要求后执行，本计划不含 commit 步骤。

---

### Task 1: AppTopBar 内置 statusBarsPadding

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/common/topbar/AppTopBar.kt`

- [ ] **Step 1: 添加 import**

在文件 import 区（`androidx.compose.foundation.layout.padding` 附近）添加：

```kotlin
import androidx.compose.foundation.layout.statusBarsPadding
```

- [ ] **Step 2: 主重载添加参数并应用 padding**

将主重载签名（`AppTopBar.kt:58-65`）改为：

```kotlin
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
    /** 是否内置状态栏避让（默认开启）；仅当顶栏外层已处理状态栏 insets 时关闭 */
    includeStatusBarPadding: Boolean = true
) {
```

将内部 Row 的 modifier（`AppTopBar.kt:75-81`）改为（statusBarsPadding 加在外层 Row 上，背景 Box 不加，使状态栏区域被 surface 背景铺满、视觉无缝）：

```kotlin
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (includeStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
                .displayCutoutPadding()
                .height(TopBarHeight)
                .padding(horizontal = TopBarHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
```

- [ ] **Step 3: 便捷重载透传参数**

将便捷重载（`AppTopBar.kt:102-127`）签名加同名参数并透传：

```kotlin
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
    includeStatusBarPadding: Boolean = true
) {
    AppTopBar(
        title = { /* 原样保留 */ },
        modifier = modifier,
        navigationIcon = { /* 原样保留 */ },
        actions = actions,
        centered = centered,
        includeStatusBarPadding = includeStatusBarPadding
    )
}
```

- [ ] **Step 4: 更新 KDoc**

将 `AppTopBar.kt:48-57` 的 KDoc 中「内置刘海避让」表述更新为「内置状态栏 + 刘海避让（`includeStatusBarPadding` 可关闭状态栏避让）」。

---

### Task 2: 删除 GalleryScreen 隐藏系统栏副作用

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt:559-568`

- [ ] **Step 1: 删除 DisposableEffect 块**

完整删除：

```kotlin
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
```

- [ ] **Step 2: 清理不再使用的 import**

编译后若报 unused import 警告，删除 `androidx.core.view.WindowCompat`、`androidx.core.view.WindowInsetsCompat`、`androidx.core.view.WindowInsetsControllerCompat` 中不再被引用的项（先 grep 文件内剩余引用再删）。`Activity`、`view`、`context` 若仍有其他用途则保留。

---

### Task 3: 删除 ChatScreen 隐藏系统栏副作用

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt:453-464`

- [ ] **Step 1: 删除 DisposableEffect 块**

完整删除（含上方 `// 沉浸式模式：隐藏系统栏` 注释）：

```kotlin
    // 沉浸式模式：隐藏系统栏
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
```

- [ ] **Step 2: 清理不再使用的 import**

同 Task 2 Step 2 策略，先 grep 确认无剩余引用再删。

---

### Task 4: 删除 SettingsScreen 隐藏系统栏副作用

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt:133-146`

- [ ] **Step 1: 删除 DisposableEffect 块**

完整删除：

```kotlin
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)

        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
```

同时检查该文件 `val view = LocalView.current`（`SettingsScreen.kt:133`）是否还有其他用途，若无则一并删除。

- [ ] **Step 2: 清理不再使用的 import**

同 Task 2 Step 2 策略。

---

### Task 5: 编译与真机验证

- [ ] **Step 1: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，无 unused import error（警告可接受但尽量清理）

- [ ] **Step 2: 安装**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 逐页截屏核对**

用 `adb shell am start` 启动 App，`adb exec-out screencap -p > tmp/shots/<page>.png` 逐页截图并用 ReadMediaFile 核对：

| 页面 | 验收点 |
|------|--------|
| 相册 | 状态栏可见；顶栏不被遮挡；FloatingBottomTab 不被导航栏遮挡 |
| 聊天 | 顶栏不被遮挡；输入框不被导航栏遮挡；键盘弹起时输入框随 imePadding 上移 |
| 人物 | 顶栏不被状态栏遮挡（本次根治点） |
| 设置 + 任意 2 个二级页 | 顶栏位置一致，不被状态栏遮挡 |
| 相机 | 系统栏仍隐藏（沉浸保持），预览与按钮正常 |
| 大图预览 | MediaPager / 聊天图片预览显示正常 |

- [ ] **Step 4: Pager 横滑回归**

横滑切换 相机→相册→聊天→人物，系统栏在相机页隐藏、其余页可见，无闪烁错乱。

---

### Task 6: 文档同步

**Files:**
- Modify: `app/AGENTS.md`（仅当其中有关于顶栏/系统栏避让的约定描述时）

- [ ] **Step 1: 检查并更新**

Grep `app/AGENTS.md` 中 `AppTopBar`、`statusBarsPadding`、`刘海`、`displayCutout` 相关描述，若有「AppTopBar 内置刘海避让」等表述，更新为「内置状态栏 + 刘海避让」。若无相关描述则跳过，不新增内容。

- [ ] **Step 2: 更新根 AGENTS.md 架构说明（可选）**

根 `AGENTS.md` 第 8 节架构说明如无系统栏相关条目则不改动。
