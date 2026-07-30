# 全 App TopBar 图标风格统一设计

- **日期**：2026-07-30
- **状态**：已与用户对齐，待实施
- **范围**：`:app` 模块内所有顶部栏（topbar）的图标风格、尺寸、间距、容器统一为一套规范，并抽出可复用组件固化

---

## 1. 背景与现状

用户反馈：chat 页与相册页头部 topbar 的图标风格不一致，希望统一。盘点全 `:app` 后发现不一致是多维度的：

| 维度 | 现状分布 | 问题 |
|---|---|---|
| 图标族 | `Icons.Rounded` 212 处（主流）/ `Default`(=Filled) 43 / `Outlined` 25 / `Filled` 5 | 编辑器主入口 `EditorTopBar`/`ImageEditScreen` 用 `Default`（实心），与两个首页的 Rounded 直接冲突 |
| 返回键 | `AutoMirrored.Rounded.ArrowBack` 7 处 vs 过时 `Filled.ArrowBack` 13 处 | 13 处用已 Deprecated、不随 RTL 镜像的旧返回键 |
| 容器 | 标准 `TopAppBar` 18 / `CenterAlignedTopAppBar` 1 / 自定义 `Row`(chat) / `Surface+Row`(editor) | chat 是透明无背景 `Row`、editor 是手写 `Surface+Row(64dp)`，与标准 TopAppBar 不一致 |
| 按钮尺寸 | 绝大多数标准 48dp；chat 操作键 `size(36.dp)` | chat 操作键 36dp 与他处 48dp 密度不同 |
| 字形尺寸 | 多数默认 24dp；chat 操作 22dp、DebugScreen 偶有 16/18dp | 字形大小不统一 |
| tint | 多数走默认；editor 手写 `alpha(0.38f)` 模拟 disabled | 应交给 M3 `enabled` 机制 |

**用户审美偏好（关键反转）**：不喜欢相册页那种宽松规格（48dp 按钮 / 24dp 字形），希望**统一到 chat 页的紧凑规格**（36dp 按钮 / 22dp 字形 / 8dp 间距）。因此相册页从"被模仿的基准"变为"要被改瘦"的对象。

## 2. 已锁定决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 统一范围 | 全 App topbar 标准化 | 用户要求系统性梳理 |
| 统一深度 | 规范 + 可复用组件 | 一次标准化 + 从结构上防未来漂移 |
| 图标数值基准 | **36dp 按钮 / 22dp 字形 / 8dp 同组间距**，全 topbar 一套（含导航键） | 用户偏好 chat 紧凑规格；chat 自己左右两区也顺手统一。**已知权衡**：36dp 触摸热区 < Material 推荐的 48dp，用户已明确接受 |
| 容器 | 统一 Material3 `TopAppBar`（chat 加 surface 背景横栏） | 换取全 app 容器一致；chat 牺牲透明沉浸感 |
| 图标族 | 一律 `Icons.Rounded.*`（方向感知用 `Icons.AutoMirrored.Rounded.*`） | 已是绝对主流 |
| 返回键 | `Icons.AutoMirrored.Rounded.ArrowBack` | RTL 正确、替换 Deprecated 旧键 |

## 3. 统一规范

所有 `:app` 内 topbar 必须遵守：

| 维度 | 规范 |
|---|---|
| 图标族 | `Icons.Rounded.*`；方向感知图标（ArrowBack / Sort / Undo 等）用 `Icons.AutoMirrored.Rounded.*` |
| 返回键 | `Icons.AutoMirrored.Rounded.ArrowBack` |
| 容器 | Material3 `TopAppBar`；居中标题场景用 `CenterAlignedTopAppBar` |
| 图标按钮 | `IconButton` + `Modifier.size(36.dp)`（**所有** topbar 图标，含导航键） |
| 字形 | `Icon` + `Modifier.size(22.dp)`（不依赖 M3 默认 24dp） |
| 图标间距 | 同组图标 `Arrangement.spacedBy(8.dp)`（操作区、导航区均 8dp） |
| tint | 走 M3 `TopAppBarColors` 默认（onSurface）；语义状态高亮允许（如扫描中→`primary`）；删除 `enabled` 时手写 `alpha`，改用 `enabled = false` |
| insets/高度 | 交 TopAppBar 默认处理；删除各页手写 `statusBarsPadding()` / `height(64.dp)` / `padding(...)` |
| contentDescription | 可点图标必须有（i18n `stringResource`）；仅装饰性传 null |
| 标题 | title 槽有则填（相册「相册」、editor 编辑标题等），无标题页（chat）传空 composable |

**豁免（不属于 app topbar，保持现状）**：
- `MediaPager` 全屏查看的关闭键（`size(32.dp)`）—— 浮层控件
- `FloatingChatBubbleService` 悬浮聊天气泡（`size(32.dp)`）—— 浮层控件

**非目标（本次不做）**：
- 列表行内的分类图标（如 `LlmModelManagerScreen` capability 映射的 `Icons.Outlined.*`）—— 那是列表图标语义，与 topbar 无关。
- 浮层控件尺寸标准化（见豁免）。

## 4. 可复用组件

新增 `app/src/main/java/com/mamba/picme/features/common/topbar/AppTopBar.kt`。

### 4.1 公开 API

```kotlin
/** 槽位式主力：覆盖所有场景。内部按 centered 选 TopAppBar / CenterAlignedTopAppBar。 */
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
)

/** 最常见场景便捷重载：返回键 + 文字标题 + 操作。 */
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
)

/** 标准操作图标 —— 一致性的执行点。锁死 36dp 按钮 + 22dp 字形 + Rounded 字形由调用方传。 */
@Composable
fun AppTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
    modifier: Modifier = Modifier,
)

/** 标准返回键 —— 锁死 AutoMirrored.Rounded.ArrowBack + 36/22。 */
@Composable
fun AppTopBarNavBack(onClick: () -> Unit, contentDescription: String = stringResource(R.string.back))
```

### 4.2 关键实现约定

- `AppTopBarAction` 内部：`IconButton(onClick, enabled, modifier.size(36.dp)) { Icon(icon, contentDescription, Modifier.size(22.dp), tint ?: LocalContentColor.current) }`。**这是杜绝 36/22/Default 再出现的结构保障**——所有操作图标必须经它。
- `AppTopBar`（String 重载）`onBack != null` 时自动在 navigationIcon 槽渲染 `AppTopBarNavBack`。
- `actions` 槽内部用 `Row(spacedBy(8.dp))` 包裹，保证操作区间距统一。
- 多导航键（chat 的返回 + 侧栏）放进 navigationIcon 槽的 `Row(spacedBy(8.dp))`。
- `centered = true` → 内部用 `CenterAlignedTopAppBar`，否则 `TopAppBar`。
- 各页特殊 modifier（如相册的 `displayCutoutPadding()`）经 `modifier` 参数透传。

### 4.3 用法示例（chat 改造后）

```kotlin
AppTopBar(
    title = {},                          // chat 无标题
    navigationIcon = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTopBarNavBack(onNavigateBack)
            AppTopBarAction(Icons.Rounded.Menu, stringResource(R.string.cd_open_sidebar), onOpenSidebar)
        }
    },
    actions = {
        AppTopBarAction(Icons.Rounded.DeleteSweep, stringResource(R.string.clear_chat), onClearChat)
        AppTopBarAction(Icons.Rounded.AddComment, stringResource(R.string.new_chat), onNewChat)
        AppTopBarAction(Icons.Rounded.Settings, stringResource(R.string.settings), onNavigateToSettings)
    }
)
```

## 5. 迁移范围

### P0 —— 主入口（图标族/容器直接冲突，必改）

| 文件 | 现状 | 目标 |
|---|---|---|
| `features/gallery/components/GalleryTopBar.kt` | 已用 Rounded + TopAppBar，但按钮 48dp / 字形 24dp / 默认间距；idle 扫描键 `alpha(0.7f)` | 接入 `AppTopBar`+`AppTopBarAction`，全压 36/22/8；删 `alpha(0.7f)`（idle 走默认）；保留选择态标题计数与 `displayCutoutPadding` |
| `features/chat/ChatScreen.kt`（`ChatTopBar`） | 透明自定义 `Row`；左导航 48/24、右操作 36/22/8 | 改用 `AppTopBar`（加 surface 背景）；左导航 48/24 → 36/22（chat 自己统一）；保留预览态隐藏 topBar 的条件渲染 |
| `features/editor/components/EditorTopBar.kt` | 手写 `Surface+Row(64dp)`；图标族最乱（`AutoMirrored.Filled.ArrowBack` / `Outlined.LayersClear` / `Default.AutoFixHigh` / `AutoMirrored.Filled.Undo` / `Default.Redo` / `Default.Check`）；手写 tint/alpha | 改用 `AppTopBar`；6 图标全改 Rounded（Undo→`AutoMirrored.Rounded.Undo`，Redo→`Rounded.Redo`）；删手写 tint/alpha，用 `enabled` |
| `features/editor/ImageEditScreen.kt` | `CenterAlignedTopAppBar`；`Default.Close/Check/Brush/BlurOn` | `AppTopBar(centered = true)`；图标全改 Rounded |

### P1 —— 设置/子页（图标族不一致 + 接组件）

`LlmModelManagerScreen`、`IDPhotoScreen`、`DebugScreen`、`MemoryFactsScreen`、`SettingsScreen`、`DataPrivacyScreen`、`CommunicationChannelScreen`、`BackupRestoreActivity`、`TagGenerationControlScreen`、`SearchTopBar`：
- 接入 `AppTopBar` / `AppTopBarAction`；
- **仅改 topbar 内图标**；列表/行内图标（如 `MemoryFactsScreen` 的行内 Edit/Delete、`LlmModelManagerScreen` 的 capability 分类图标）不动；
- 非 Rounded 的 topbar 图标 → Rounded；
- 返回键 → `AutoMirrored.Rounded.ArrowBack`（经 `AppTopBarNavBack`）；
- 字形/按钮压到 22/36，间距 8dp。
- 注意：`LlmModelManagerScreen` 的 capability 分类图标属列表图标，**不在本次范围**，仅改其 topbar 动作键。

### P2 —— 调试/测试页（一致性收尾）

`JsBridgeDemo`、`SearchTestScreen`、`TagViewerTestScreen`、`SentencePieceTestScreen`、`LlmCallLogScreen`：同 P1 规则。这些是内部测试页，优先级最低但纳入以求彻底。

### 返回键全量替换

全 `:app` 内 13 处 `Filled.ArrowBack` / `AutoMirrored.Filled.ArrowBack` → 经 `AppTopBarNavBack`（`AutoMirrored.Rounded.ArrowBack`）。

## 6. 验证

1. **编译 + JVM 单测**：纯 UI 重构，无逻辑变更，现有 `./gradlew :app:testDebugUnitTest` 必须全绿。
2. **UI 一致性**：用 `/ui-driver`（a11y 结构化驱动）对 chat / 相册 / 编辑器三页 topbar 抓取图标节点的尺寸（36dp/22dp）与 contentDescription，确认一致。
3. **质量门**：`./gradlew ktlintCheck detekt`（注意无 FQN、无 `*` 导入、无隐式 `it`）；项目真门槛为编译 + JVM 单测（见团队记忆，detekt 有预存失败非门）。
4. **i18n**：新增/复用的 contentDescription 必须在 `values/`、`values-zh-rCN/`、`values-zh-rTW/` 三套 strings.xml 同步（本次以复用现有字符串为主）。

## 7. 实施顺序建议

1. 先落地 `AppTopBar.kt` 组件（含 `AppTopBarAction` / `AppTopBarNavBack`）—— 这是后续所有迁移的前提。
2. P0 三页（chat / 相册 / editor + image-edit）迁移并 `/ui-driver` 验证——这是用户最直接感知的页面。
3. P1 设置/子页批量迁移。
4. P2 测试页收尾 + 13 处返回键全量替换。
5. 全量编译 + 单测 + 质量门。
