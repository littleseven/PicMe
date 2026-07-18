# Chat 图片选择器：可搜索半屏面板（设计）

- **日期**: 2026-07-18
- **状态**: 待评审
- **范围**: 独立 Chat 页底部「选图入口」→ 由原始 MediaStore 平铺网格升级为可搜索、全高度、复用既有搜索/网格的半屏面板

## Context（为什么改）

独立 Chat 页（`ChatScreen.kt`，从相册首页「+」菜单进入）底部有选图入口，点击弹出半屏相册 `InAppPhotoPicker`（`ChatScreen.kt:1354`）。当前实现存在多个问题，导致「找不到想要的图、选图困难」：

- 直接查询 `MediaStore.Images`，仅按 `DATE_ADDED DESC` 排序——对所有照片的**平铺墙**，无日期分组、无相册、无跳转。
- 固定 `height(320.dp)` + 3 列——可视区只有约 2 行，缩略图极小。
- **完全没有搜索**——且绕过了 App 自有索引（`media_assets` 已有 MobileCLIP 语义向量、ML Kit 标签 中/英、人物聚类、OCR、位置）。
- 在主线程一次性读取全部 URI 列表（大图库下卡顿/ANR 风险）。
- 仅支持单选；标题 `"选择图片"` 为硬编码字符串（违反 i18n 规则）。

用户首要痛点：**定位一张特定的较老照片**（按内容/人物/时间）。当前平铺「最近」网格对这种诉求无能为力——而 App 已经有成熟的语义/标签/人物搜索，只是没在这个选图入口暴露。

## 目标 / 非目标

**目标（Phase 1，本次）**
- 在不离开 Chat 上下文的前提下，让用户能按内容/人物/标签**搜索**找到目标照片并选中发送。
- 用 App 自有索引与既有组件替代原始 MediaStore 平铺（顺带修复卡顿、 cramped 视图、i18n）。
- 保持单选发送（与现状一致），零行为回退。

**非目标（Phase 2，后续）**
- 多选 / 单条消息多图发送（需改造 Chat 消息模型，见「分阶段」）。

## 方案选择

选定 **方案 A：全高度可搜索半屏面板**（备选 B「跳转完整相册页」、C「仅加搜索框」已评估，见下）。

- **A（选定）**：把 `InAppPhotoPicker` 升级为全高度 `ModalBottomSheet`，顶部搜索栏接入既有搜索，下方复用既有网格；停留在 Chat 内，符合主流聊天 App「附件用面板」的交互惯例。
- B（跳转完整相册页）：复用度最高，但要离开 Chat 上下文、来回导航、丢失对话现场，体验更重。
- C（仅加搜索框）：改动最小，但保留 320dp cramped 网格与 MediaStore 绕过，搜索结果在小视区里依旧难用。

选 A 的核心理由：直接命中「不离开对话就能找到一张特定的老照片」，且复用了既有搜索与网格的绝大部分能力。

## 复用映射（不新造轮子）

| 能力 | 复用既有 | 位置 |
|------|----------|------|
| 搜索执行 | `GalleryCapability.getInstance().searchEngine.search(query).media` → `List<MediaAsset>` | 与 `GalleryScreen.kt:129,141` 同一行调用 |
| 搜索输入框 | `SearchField` | `features/common/`（`GalleryScreen` 已用） |
| 网格（浏览态） | `MediaGrid`（支持 `isSelectionMode`/`selectedIds`/`ThumbnailCache` 预取/日期分组/拖选） | `features/gallery/components/MediaGrid.kt:62` |
| 媒体数据源 | `MediaViewModel` / `media_assets`（替代原始 MediaStore cursor） | `features/gallery/MediaViewModel.kt` |
| 选图回调 | 复用 `ChatViewModel.sendImageMessage(uri)`（单选） | `features/chat/ChatViewModel.kt` |

不新增任何搜索/索引逻辑。

## 设计

### 架构 / 落点
- 新增 Composable `ChatPhotoPickerSheet`，置于 `features/chat/components/`；删除/替换 `InAppPhotoPicker`（`ChatScreen.kt:1354`）。
- 新增轻量 `ChatPhotoPickerViewModel`，持有：`query`、防抖后的 `searchResultMedia`、`browseMedia`、`isSearching`、`searchAvailable`。面板保持声明式。
- 触发点不变：`ChatInputArea` 的图库按钮 → `showPhotoPicker = true` → 渲染 `ChatPhotoPickerSheet`。

### UX / 组件
- 全高度 `ModalBottomSheet`（可拖至顶部，约 90% 屏高）。头部：标题 +（Phase 2 选数）。头部下：`SearchField`。
- 两种模式，由 query 驱动：
  - **浏览态（query 空）**：`MediaGrid` 日期分组「最近照片」（Phase 1 单选）。替代原始平铺网格。
  - **搜索态（query 非空）**：`searchEngine.search(query).media` 的扁平相关性排序网格，query 防抖，带 loading/empty 态。
- **单选行为（Phase 1）**：点击任一缩略图即选中并立即发送（等同现状 `sendImageMessage`），关闭面板。保证「快速发一张」零回退。
- 关闭：先清 query 回浏览态，再下滑关面板（与现有 BackHandler 风格对齐）。

### 数据流
`query`（防抖 ~250ms）→ `searchEngine.search(query).media` → 排序网格。
点击缩略图 → `MediaAsset.uri` → `ChatViewModel.sendImageMessage(uri)` → 关闭面板。
浏览态：`MediaViewModel` 既有媒体 → `GroupedMedia`（日期分组）→ `MediaGrid`。

### 边界与错误处理
- `searchEngine == null` 或语义索引未就绪：顶部条「语义搜索暂不可用，仅显示最近照片」，自动回退浏览态。
- query 空 → 浏览态；结果空 → 「未找到相关图片」。
- 大图库：`MediaGrid` 已分页 + `ThumbnailCache` 预取；移除一次性全量 cursor 读取。
- i18n：所有新增文案同步 `values/`、`values-zh-rCN/`、`values-zh-rTW/`（项目硬规则）。

### 测试
- JVM（纯 VM 逻辑）：query→防抖→search 映射；query 空/非空模式切换；`searchEngine == null` 回退；结果空态。
- Compose/UI：浏览态 vs 搜索态渲染；loading / empty 态；点击单选触发回调并关闭面板。
- 手动：进入独立 Chat → 选图 → 搜「海滩」/某人 → 点选 → 图片出现在对话流；无 `searchEngine` 时回退浏览态可用。

## 分阶段

- **Phase 1（本次实现）**：全高度可搜索面板 + 浏览态复用 `MediaGrid` + 单选发送 + 修复（主线程查询、cramped 视图、i18n）。
- **Phase 2（后续）**：多选 + 单条消息多图发送。需先扩展 Chat 消息模型与发送链路以支持 `List<Uri>`，再在面板启用多选与「发送 N 张」确认条。届时 `MediaGrid` 的 `selectedIds`/`isSelectionMode` 已就绪可直接复用。

## 明确不在本次范围
- 不改 Chat 消息模型 / 多图发送（Phase 2）。
- 不新增搜索能力或改索引（复用既有）。
- 不动 Chat 注册引导弹层、侧边栏、对话流渲染等无关组件。
