# Chat 选图后「预览 + 意图」交互流程（设计）

- **日期**: 2026-07-18
- **状态**: 待评审
- **前置**: 已合并的「可搜索选图面板」(Phase 1，单选)
- **范围**: 独立 Chat 页底部选图后，由「选完即发 + 本地图像理解」改为「选中→输入框缩略图预览→(意图 chips + 文字)→发送并按意图路由」

## Context（为什么改）

当前选图体验：在 `ChatPhotoPickerSheet` 选中图片即调用 `ChatViewModel.sendImageMessage(uri)`——立刻插入 `user_image` 气泡、设置 `_lastUserImageUri` 并触发本地模型图像理解。

问题：用户选图的真实诉求往往不是「图像理解」，而是**找相似**、**编辑图片**等。当前流程把「图像理解」当默认且唯一动作，用户无法在发送前表达意图。

目标：选中图片后**先不发送**，进入输入框（缩略图预览），用户可点选意图 chip（图像理解 / 找相似 / 编辑图片）或输入文字描述意图，再发送；按意图路由到对应能力。

## 现状复用（已确认）

- `ChatViewModel.sendImageMessage(uri)`：`persistImage` 拷贝到内部存储 → 插入 `user_image` → 设 `_lastUserImageUri` → 触发图像理解。其中 `persistImage` 与 `_lastUserImageUri` 可直接复用。
- `ChatViewModel.sendMessage(text)`：插入 `user_text` → 流式推理，构建 `AgentContext` 时已带入 `lastUserImageUri = _lastUserImageUri.value`。即「图 + 文字意图」的上下文链路已存在。
- 输入框为 `ChatTextInputMode`（`BasicTextField` + 相册按钮 + 发送按钮），`ChatInputArea` 持有 `showPhotoPicker` 等状态。
- 图像理解、找相似等本就由 Agent 按文字指令路由（orchestrator/能力注册表）。
- 编辑：`ChatScreen` 已有 `onNavigateToPhotoEditor(uri, autoOptimize)`，可直连 PhotoEditor。

## 目标 / 非目标

**目标**
- 选中图片不即发；输入框内以缩略图预览，支持取消。
- 提供意图 chips（图像理解 / 找相似 / 编辑图片）+ 可选文字。
- 发送按意图路由：理解（默认）/ 找相似 / 编辑（跳 PhotoEditor）/ 文字指令。
- 保留「只选图、直接发 = 图像理解」为默认兜底，不回退现状体验。

**非目标**
- 多图（仍 Phase 2，受消息模型限制）。
- 新建图像相似度后端（复用既有「more-like-this」/embedding NN；若需接线列为独立小任务）。
- 改 PhotoEditor、GallerySearch 内部实现。

## 设计

### 交互流程
```
选图(ChatPhotoPickerSheet) ──选中──→ 输入框缩略图预览（不发送）
                                        │  + 意图 chips 行（理解/找相似/编辑）
                                        │  + 文字输入框（可选）
                                        ↓
                                   [× 取消] 或 [发送]
                                        │
                ┌───────────────────────┼───────────────────────────┐
                ↓                       ↓                           ↓
          编辑 chip              理解 chip / 默认 / 文字        找相似 chip
   onNavigateToPhotoEditor        sendImageWithIntent         sendImageWithIntent
     (不产生消息)                  → 图像理解/Agent 路由       → 相似图召回 → 轮播
```

### 状态变更
- `ChatInputArea`（或上提到 `ChatViewModel`）新增：
  - `pendingImage: Uri?` —— 待发送图片（已持久化）。
  - `selectedIntent: ImageIntent?` —— `enum class ImageIntent { UNDERSTAND, FIND_SIMILAR, EDIT }`；单选，初始 null。
- `ChatViewModel` 新增：
  - `stageImage(uri: Uri): Uri?` —— 仅 `persistImage` + 设 `_lastUserImageUri`，**不**插入消息、**不**触发推理；返回持久化 uri（失败返回 null）。
  - `sendImageWithIntent(uri: Uri, intent: ImageIntent, text: String?)` —— 插入**一条**用户消息（图 + 意图/文字），设 `_lastUserImageUri`，按 `intent` 路由（理解→现有图像分析；找相似→相似召回）；`text` 非空则作为 Agent 指令覆盖/补充 chip 意图。
- `sendImageMessage(uri)` 保留为兼容入口（等价于 `stageImage` + `sendImageWithIntent(UNDERSTAND, null)`，或逐步迁移调用点）。

### UI（`ChatTextInputMode`）
当 `pendingImage != null`，在文字输入行**上方**插入两行：
1. **缩略图行**：约 72dp 图片 + 圆角 + 右上「×」移除按钮（清 `pendingImage` / `selectedIntent`）。
2. **chips 行**：`图像理解` / `找相似` / `编辑图片`，`FilterChip` 单选高亮，初始都不选。
- 文字框 / 相册按钮 / 发送按钮沿用现有；发送按钮在有 `pendingImage` 时始终可点（无 chip 无文字 → 默认 `UNDERSTAND`）。

### 发送路由（`ChatInputArea.onSend`）
- `pendingImage == null` → 现有 `sendMessage(text)`（纯文字）。
- `pendingImage != null`：
  - `selectedIntent == EDIT` → `onNavigateToPhotoEditor(pendingImage, autoOptimize=false)`；清 pending，**不发消息**。
  - 否则 → `sendImageWithIntent(pendingImage, selectedIntent ?: UNDERSTAND, text.takeIf { it.isNotBlank() })`；清 pending/text。
- 发送后 `text` 与 `pendingImage` 一并清空；Voice 模式暂不引入图片预览（保持现状）。

### 意图路由细节
- **图像理解**（`UNDERSTAND`，默认）：等同现 `sendImageMessage` 的图像分析链路。
- **找相似**（`FIND_SIMILAR`）：复用既有相似召回（embedding NN / "more-like-this"），结果以 `MediaResultsCarousel` 展示。**待确认**：若 chat 场景尚未接图像相似召回，需补一条 use-case 接线（列为实现期小任务，不阻塞设计）。
- **编辑**（`EDIT`）：仅交给 PhotoEditor；返回 chat 时保留已输入文字草稿。
- **文字指令**：`text` 非空时，由 Agent 按文字路由（覆盖 chip 默认），保留「输入文字描述意图」的灵活性。

### 边界与错误
- `stageImage` 持久化失败 → toast「无法加载图片」，不进入预览态。
- 取消预览（×）→ 回到纯文字输入，不发送、不清 `_lastUserImageUri` 的历史语义（保持现状）。
- 编辑返回：pending 已清，文字草稿保留；用户可重新选图或直接发文字。
- 发送期间 `isProcessing` 复用现有禁用逻辑。
- i18n：chip 文案 + 移除按钮 `contentDescription` 同步 `values/`、`values-zh-rCN/`、`values-zh-rTW/`。

### 测试
- JVM（VM）：`stageImage` 成功/失败（mock `persistImage`）；`sendImageWithIntent` 在 UNDERSTAND/FIND_SIMILAR/EDIT + 有/无 text 下的消息插入与路由调用；EDIT 不插消息。
- Compose/UI：`pendingImage` 存在时渲染缩略图行 + chips 行；chip 单选高亮；× 清除；发送按钮可用性。
- 手动：选图→预览→点「编辑」进 PhotoEditor 返回；选图→「找相似」出轮播；选图→不选 chip 直接发送=图像理解；选图→输入「把背景调蓝」→发送走文字指令/AI 编辑。

## 明确不在本次范围
- 多图（Phase 2）。
- 新建图像相似度后端（复用既有；接线为独立小任务）。
- Voice 输入模式的图片预览。
- PhotoEditor / GallerySearch 内部改造。
