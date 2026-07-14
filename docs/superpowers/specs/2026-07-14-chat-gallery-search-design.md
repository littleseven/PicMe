# Chat 相册搜索 + 卡片 Carousel 设计

- 日期：2026-07-14
- 状态：已评审，待实现
- 范围：`app` 模块（独立 ChatScreen）、`runtime-core`（命令/动作扩展）

## 1. 背景与目标

在独立 ChatScreen 中，用户通过自然语言对话查找相册照片；搜索结果以**横滑卡片 carousel** 形式插入对话流；点击卡片在**预览页**打开浏览。支持**多轮细化筛选**（「这些里有没有海边的」）。

### 现有基建（复用，不重造）

- `MediaSearchEngine.search(query)`：端侧搜索引擎，已支持显式约束分段 + 规则解析（时间/地点/标签/OCR/人脸）+ MobileCLIP 语义召回 + 可选 LLM 辅助解析。返回 `SearchResult(media, originalQuery, resultCount)`。隐私 100% 端侧。
- `MediaDao`：`searchAll/searchByLabel/searchByOcrText/searchByLocation/searchByTimeRange/searchByHasFace/searchByMlKitLabel(Zh)/**searchLabelsInIds**(ids, keyword)`。
- `data.model.MediaAsset`（Room `media_assets`）：`{id, uri, type, captureDate, fileName, duration, hasFace, faceId}`。
- `ChatViewModel`：已接 `AgentOrchestrator.streamChat` → 返回 commands → `CapabilityRegistry.dispatch` → `handleAgentAction` 渲染 `AgentAction` → 落 Room（`ChatMessageEntity`，type 标签）。场景 `AgentScene.CHAT`。
- `MediaPager(assets, initialIndex, onClose, onDelete, onStartOcr, onDismissOcr, ocrState, onNavigateToEditor, onAiOptimize, voiceCoordinator, onReTag)`：现有全屏预览。
- `ThumbnailCacheFetcher`：缩略图加载。

### 缺口（本设计填补）

1. CHAT 场景无搜索执行器：`SearchMedia` 仅由 `GalleryCapability`（GALLERY 场景专属 + 需 Delegate）处理，在 CHAT 场景 dispatch 回 `CAPABILITY_UNAVAILABLE`。
2. Agent 在 CHAT 场景未把 `search_media` 暴露为可用工具，LLM 不会发出搜索命令。
3. 无「细化」命令与 in-set 过滤路径。
4. 聊天消息模型无媒体结果类型，无 carousel 渲染。

## 2. 关键决策

| 维度 | 决策 | 理由 |
|---|---|---|
| 入口 | 独立 ChatScreen，ChatViewModel 直接调 `MediaSearchEngine` | 链路最短，不依赖相册场景/委托 |
| 搜索方式 | Agent 为主 + 回退直连 + in-set 细化 | 多轮筛选需对话记忆与意图解析（直连无状态，无法理解「这些里」） |
| 预览 | 复用 `MediaPager`，非必要回调首版 stub | UX 与相册一致；首版聚焦浏览 |
| 范围 | 仅照片，上限 20，溢出「在相册查看全部」 | carousel 有界、内存可控 |
| 架构 | 能力 + Delegate（镜像 `GalleryCapability`） | dispatch 链路统一；细化状态天然落在 session VM |

## 3. 架构与数据流

### 首轮搜索

```
用户输入「找去年夏天的照片」
 → ChatViewModel.sendMessage
 → AgentOrchestrator.streamChat(scene=CHAT)        // LLM 解析意图
 → 返回 commands=[SearchMedia(query="去年夏天")]
 → CapabilityRegistry.dispatch(SearchMedia, CHAT)
 → ChatSearchCapability.execute → Delegate.onSearchMedia(query)
 → ChatViewModel 调 MediaSearchEngine.search(query)
 → 得 List<MediaAsset>，filter type=PHOTO，记 lastResultIds[session]=全量 ids；carousel 展示前 20
 → 包成 AgentAction.MediaResults(query, mediaIds, totalCount, isRefinement=false)
 → handleAgentAction(MediaResults) → 按 id 回灌 data.model.MediaAsset
 → 落 Room(type=media_results) + UI 追加 MediaResults 消息 → 渲染 carousel
```

### 多轮细化（in-set）

```
用户输入「这些里有没有海边的」
 → streamChat（Agent 记忆含上一轮搜索）→ 识别为细化
 → 返回 commands=[RefineMediaSearch(constraint="海边")]
 → dispatch → ChatSearchCapability → Delegate.onRefineMediaSearch("海边")
 → ChatViewModel 取 lastResultIds[session]
 → MediaDao.searchLabelsInIds(ids, "海边")        // 在上轮 id 集合内过滤
 → 更新 lastResultIds，包成 MediaResults(isRefinement=true)
 → 新增一条 MediaResults 消息（细化后结果）
```

### 设计原则（隔离与清晰）

- **Agent**：只管「理解 + 记忆 + 决定 fresh/refine」（LLM）。全程不接触 mediaId。
- **ChatSearchCapability**：只管「声明工具 + 转发」。CHAT 场景把 `search_media`/`refine_media_search` 暴露给 LLM；execute 回调 Delegate。
- **ChatViewModel（Delegate 实现）**：拥有「执行搜索 + 细化状态（lastResultIds）+ 结果回灌」。mediaId 全部留在此层。

## 4. 组件清单

### 新增

| 组件 | 路径 | 职责 |
|---|---|---|
| `ChatSearchCapability` | `app/features/chat/capability/ChatSearchCapability.kt` | CHAT 场景 capability；`supportedCommands=["search_media","refine_media_search"]`；`activeScenes=[CHAT]`；`getCommandDescription` 描述两命令；execute 回调 Delegate；Delegate 未绑定时回 `CAPABILITY_UNAVAILABLE`。镜像 `GalleryCapability` 的 WeakReference Delegate 套路 |
| `ChatSearchDelegate` | 同文件 | 接口：`onSearchMedia(query): SearchOutcome`、`onRefineMediaSearch(constraint): SearchOutcome`；`SearchOutcome(mediaIds, totalCount, isRefinement, query)` |
| `MediaResultsCarousel` | `app/features/chat/components/MediaResultsCarousel.kt` | 横滑 `LazyRow`；头部「找到 N 张『query』」；尾部「在相册查看全部」卡；空态 |
| `MediaCard` | 同文件 | 单卡：缩略图（Coil + `ThumbnailCacheFetcher`）+ 日期角标 |

### 改动

| 组件 | 路径 | 改动 |
|---|---|---|
| `AgentCommand` | `runtime-core/.../model/command/AgentCommand.kt` | 加 `RefineMediaSearch(constraint: String, commandId)` |
| `AgentAction` | `runtime-core/.../model/context/AgentAction.kt` | 加 `MediaResults(query, mediaIds: List<Long>, totalCount: Int, isRefinement: Boolean, commandId)`。**只带 id**，不带 MediaAsset 对象（避免 runtime-core 依赖 app Room 实体） |
| `ChatViewModel` | `app/features/chat/ChatViewModel.kt` | 实现 `ChatSearchDelegate`；维护 `lastResultIds: Map<sessionId, List<Long>>`；`handleAgentAction` 加 `MediaResults` 分支（按 id 从 `MediaDao` 回灌 `data.model.MediaAsset`）；注册/绑定 `ChatSearchCapability` Delegate；回退直连（见 §6） |
| `ChatMessageUi` / `ChatMessageType` | chat models | 加 `ChatMessageType.MEDIA_RESULTS`；`ChatMessageUi` 加 `mediaResults: MediaResultsUi?`（`query, cards: List<MediaCardUi>, totalCount, isRefinement`） |
| `ChatMessageEntity` | `app/data/local/ChatMessageEntity.kt` | `type="media_results"`；`content`=JSON `[{id,uri,captureDate,fileName},...]`；`metadata`=`{query,totalCount,isRefinement}`；`toUiModel()` 反序列化 |
| `ChatScreen` | `app/features/chat/ChatScreen.kt` | 消息列表按 type 分发渲染；MediaResults → `MediaResultsCarousel`；卡片点击 → `MediaPager` |
| `QuickActionBar` | `app/features/chat/components/QuickActionBar.kt` | 加「🔍 搜相册」chip（回退直连入口） |
| `GalleryScreen` / 导航 | `app/features/gallery/` + nav | 接收 `initialQuery` nav 参数，`SearchTopBar` 预填并执行搜索 |

### 注册点

`PicMeApplication.onCreate()` 注册 `ChatSearchCapability` 单例（参照 `GalleryCapability.getInstance()`）。`ChatScreen` 进入时 `bindDelegate(viewModel)`，离开 `onDispose` 时 `unbindDelegate()`。

## 5. 消息模型与持久化

- Room 持久化保活重启：`type="media_results"`，`content` 存 JSON 数组（每项 `id/uri/captureDate/fileName`），`metadata` 存 `query/totalCount/isRefinement`。
- 加载时 `toUiModel()` 反序列化 `content` → `List<MediaCardUi>`；若某 id 对应照片已删（回灌不到）→ 跳过该卡。
- MediaResults 消息**不进**流式占位（`_streamingMessage`）；搜索期用轻量 `_searching` 标志驱动「搜索中…」提示（或复用 `isProcessing`）。

## 6. 回退直连（Agent 不可用时）

- **显式入口**：QuickActionBar「🔍 搜相册」chip。点击 → 当前输入框文本**直接**喂 `MediaSearchEngine.search(text)`（不经 Agent/LLM）→ MediaResults 消息。始终可用，即使本地模型未加载/远程不可用。
- **自动回退**：`sendMessage` 走 Agent 路径时，若 `streamChat` 因 LLM 不可用失败 → 提示「AI 不可用，是否直接搜索相册？」并高亮该 chip；用户点 chip 即直搜。**不做启发式意图猜测**（避免误判）。
- 直连模式为单轮（无 Agent 记忆）；用户可手输复合 query（「去年夏天 海边」）由引擎自身 NL 解析处理。

## 7. UI：Carousel + 预览 + 查看全部

- **Carousel**：`LazyRow`，卡片宽 ~120dp、圆角、阴影；头部一行「找到 N 张『query』的照片」（细化时显示「细化：constraint」）；尾部溢出卡「在相册查看全部」（仅 `totalCount>20` 时出现，跳相册看完整结果集）。
- **卡片点击**：`onCardClick(index)` → 打开 `MediaPager(assets=resultList, initialIndex=index, onClose, …)`。结果列表（VM 已按 id 回灌的 `List<data.model.MediaAsset>`）作 pager 源，点哪张定位哪张。
- **MediaPager 非必要回调首版 stub**：`onDelete/onStartOcr/onNavigateToEditor/onAiOptimize/voiceCoordinator` 传 no-op 或最小实现（如 `onDelete`→删除后刷新列表）；`ocrState` 传空 `StateFlow`。仅 `onClose` 必须实现。后续再接全功能。
- **在相册查看全部**：导航到 `GalleryScreen` 并带 `initialQuery`；`SearchTopBar` 接收后预填并执行搜索，展示完整结果集。

## 8. 错误与边界

| 场景 | 处理 |
|---|---|
| 搜索结果为空 | MediaResults 消息显示空态卡：「未找到『query』的照片」+ 建议换词 |
| 搜索异常（engine 抛错） | `AgentAction.Error`，聊天显示「❌ 搜索失败：原因」 |
| 细化 in-set 结果为空 | 先回退全局重搜 constraint；仍空则空态卡 |
| 细化但无上一轮结果（lastResultIds 空） | 当作首轮 fresh search（用 constraint 全局搜） |
| 照片已被删除（回灌不到 id） | 渲染时跳过；全部失效 → 空态 |
| Delegate 未绑定（capability 不可用） | `AgentAction.Error(CAPABILITY_UNAVAILABLE)`，提示「相册搜索暂不可用」 |
| 结果 >20 | carousel 显 20 +「查看全部」跳相册；`lastResultIds` 仍存全量 id（供细化） |
| 视频类型 | v1 仅照片：搜索时 filter `type=PHOTO`；视频后续支持 |

## 9. 测试

- **单元**：
  - `ChatSearchCapability`：dispatch `SearchMedia`/`RefineMediaSearch` → 正确回调 Delegate；未绑定 → `Error(CAPABILITY_UNAVAILABLE)`；`activeScenes`/`supportedCommands` 正确。
  - `ChatViewModel` 细化逻辑：`lastResultIds` 在 fresh 后设置、refine 后收窄；in-set 空 → 全局回退；无上一轮 → 当 fresh。
  - 序列化往返：`AgentAction.MediaResults` ↔ `ChatMessageEntity`（content JSON ↔ `MediaCardUi`）。
  - 删除照片后回灌：缺失 id 被跳过。
- **Compose UI**：`MediaResultsCarousel` 渲染 N 张卡 + 头尾；空态；卡片点击回调 index。
- **集成（可选）**：mock Agent 返回 `SearchMedia`/`RefineMediaSearch`，验证端到端 fresh→refine 消息序列。

## 10. 范围与非目标（YAGNI）

- **不做**：视频搜索（v1 仅照片）；从 carousel 直接删除/分享/编辑（走预览页）；细化按时间/地点 in-set（v1 仅标签/关键词 in-set，时间地点走全局复合重搜）；自动意图回退（用显式 chip）。
- **后续可演进**：in-set 通用 `StructuredFilter` 过滤；`MediaPager` 全功能回调接入；视频支持；搜索结果排序/相关性分数展示。
