# 系统栏（Status Bar / Navigation Bar）适配统一治理设计

> **日期**：2026-08-02
> **状态**：已获用户批准
> **范围**：`:app` 模块 UI 层
> **关联红线**：[PERF]（不引入额外测量开销）、[DOC-SYNC]（同步模块 AGENTS.md）

## 1. 背景与问题

`MainActivity` 已启用 `enableEdgeToEdge()`（`MainActivity.kt:107`），外层 Scaffold 将 insets 责任下放到各页面（`contentWindowInsets = WindowInsets(0,0,0,0)`，`MainActivity.kt:200`）。现状盘点发现：

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | PersonScreen 完全无 insets 处理，顶栏不被遮挡仅依赖 Gallery/Chat 的副作用隐藏系统栏（脆弱隐式依赖） | `PersonScreen.kt:103` | 🔴 |
| 2 | GalleryScreen / ChatScreen 用 `DisposableEffect(Unit)` 无条件隐藏系统栏，由于 Pager 四页常驻组合（`beyondViewportPageCount = 3`），系统栏可见性由组合顺序决定，行为不可预测 | `GalleryScreen.kt:559`、`ChatScreen.kt:454` | 🔴 |
| 3 | AppTopBar（全 App 17+ 处使用）仅内置 `displayCutoutPadding()`，无 `statusBarsPadding()`，系统栏可见时顶栏必被状态栏遮挡 | `AppTopBar.kt:78` | 🟡 |
| 4 | SettingsScreen 隐藏系统栏而其他二级页不隐藏，同为二级页顶栏位置不一致 | `SettingsScreen.kt:136-146` | 🟡 |
| 5 | 用户从屏幕边缘滑出 transient 系统栏时（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE），系统栏直接覆盖顶栏内容 | 所有隐藏系统栏的页面 | 🟡 |

## 2. 设计决策（已与用户确认）

- **系统栏可见性策略：仅相机/预览沉浸。** 相机页（`CameraScreen`）与全屏预览（`MediaPager`、聊天图片/图表预览）保持隐藏系统栏；其余所有页面（相册、聊天、人物、设置及全部二级页）系统栏始终可见并正确避让。
- **顶部避让统一收口到 `AppTopBar`**，页面不再各自处理状态栏避让。

## 3. 方案

### 3.1 AppTopBar 内置 statusBarsPadding

`app/src/main/java/com/mamba/picme/features/common/topbar/AppTopBar.kt`：

- 在现有 `displayCutoutPadding()` 前叠加 `statusBarsPadding()`（先避让状态栏再避让刘海，二者正交不冲突）。
- 新增参数 `includeStatusBarPadding: Boolean = true`，默认开启；特殊嵌套场景可显式关闭。
- 背景 `Box` 同步扩展：padding 应用在背景 Box 外层的 Row 上，保证状态栏区域被顶栏背景色铺满（surface 色），视觉无缝。

### 3.2 删除三处无条件隐藏系统栏的副作用

| 文件 | 行号 | 改动 |
|------|------|------|
| `GalleryScreen.kt` | 559-568 | 删除整个 `DisposableEffect(Unit)` 隐藏逻辑及不再使用的 import |
| `ChatScreen.kt` | 453-464 | 同上 |
| `SettingsScreen.kt` | 136-146 | 同上 |

删除后这三页系统栏恢复可见，顶栏由 3.1 的 AppTopBar 自动避让。

### 3.3 底部避让维持现状（已正确，不动）

- Gallery 的 `FloatingBottomTab`（`navigationBarsPadding`）、Chat 输入区（`navigationBarsPadding`）与 `imePadding()` 均保留。
- 两页 Scaffold 的 `contentWindowInsets = WindowInsets(0,0,0,0)` 保留：网格/消息列表可滚动至半透明导航栏下方，功能性底栏不被遮挡。
- 全局 `windowSoftInputMode="adjustNothing"` 下 `imePadding()` 仍可正确读取 IME inset，键盘避让不受影响。

### 3.4 沉浸场景不动

- `CameraScreen.kt:332-353`：已按 `isActivePage` 正确门控，保留。
- `MediaPager`、聊天图片/图表全屏预览、聊天侧边栏：已各自正确处理，保留。
- `BackupRestoreActivity`：独立 Activity 且已 `enableEdgeToEdge`，保留。

### 3.5 PersonScreen 修复路径

PersonScreen 不做任何改动——系统栏可见 + AppTopBar 自带避让后，其顶栏问题自动根治，同时消除对 Gallery/Chat 副作用的隐式依赖。

## 4. 影响面

改动文件（共 4 个）：
1. `app/src/main/java/com/mamba/picme/features/common/topbar/AppTopBar.kt`
2. `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`
3. `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`
4. `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`

间接受益页面（无需改动）：PersonScreen、DataPrivacyScreen、MemoryFactsScreen、CommunicationChannelScreen、LlmModelManagerScreen、DebugScreen、LlmCallLogScreen、JsBridgeDemo、PersonInfoScreen、DuplicateManager、IDPhotoScreen、ImageEditScreen、TagGenerationControlScreen 等所有使用 AppTopBar 的页面。

视觉变化（预期内）：相册、聊天、设置页顶部状态栏与底部导航栏由隐藏变为可见，顶栏整体下移状态栏高度。

## 5. 验证方案

1. `./gradlew :app:compileDebugKotlin` 编译通过。
2. adb 安装后逐页截屏核对：
   - 相册页：状态栏可见、顶栏不被遮挡、FloatingBottomTab 不被导航栏遮挡；
   - 聊天页：顶栏不遮挡；键盘弹起时输入框避让正确（imePadding）；输入框不被导航栏遮挡；
   - 人物页：顶栏不被状态栏遮挡（本次根治点）；
   - 设置页及 2-3 个二级页：顶栏位置一致；
   - 相机页：系统栏仍隐藏（沉浸保持）；
   - 大图预览（MediaPager / 聊天图片预览）：显示正常。
3. 横滑 Pager 切换四页，系统栏状态在相机页隐藏、其余页可见，无闪烁/错乱。

## 6. 文档同步

- 实施后更新 `app/AGENTS.md` 中 UI/顶栏相关约定（如已有描述）。
