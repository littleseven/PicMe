# 聊天图片预览横滑 + LLM 日志 traceId 关联 — Design

- Date: 2026-07-27
- Status: Approved (direction), pending spec review
- Owner: guoshuai
- App version: v1.0.26
- Related modules: `:app` (features/chat, features/debug, data/local/llmlog), `:runtime-core`

## 背景

两个独立的「基础体验」问题:

1. **聊天图片预览不能翻页**:点聊天气泡里的图片打开 `ImagePreviewOverlay`,它是单图全屏,无法左右滑浏览本会话其它图片,编辑/生成图(`AGENT_IMAGE`/`AGENT_EDIT_RESULT`)尤其显得「孤立」。相册搜索结果卡片已复用 `MediaPager`(支持横滑),但单图预览没有。
2. **LLM 调用日志页三层无关联**:页内三 Tab(LLM 调用 / Tool 执行 / JS 运行)对应三张表 `llm_call_log` / `tool_call_log` / `js_run_log`,实体注释均写明「无外键、无与其它表的关联」,三个 Recorder 写入时**无任何 traceId/turnId**。一次用户对话(=一次 LLM 调用 → 可能触发 tool → 可能跑 JS)在数据里没有显式链接,详情页只显示单条记录,看不到同一轮的另外两层。

## 目标

- **功能①**:聊天图片预览支持左右横滑,翻页集合 = 当前会话所有带图消息(含编辑/生成图);支持双指缩放。
- **功能②**:给三张日志表加 `traceId`(一条用户消息 = 一个 traceId),沿 dispatch 链路贯穿 LLM/tool/JS 三层;详情页变 `HorizontalPager`,同 traceId 的三层记录按时间横滑浏览,顶部指示器标注层级与计数。

## 非目标 (YAGNI)

- 不统一 chat 与 gallery 的图片浏览器(类型模型不同:`Uri` vs `MediaAsset`,硬套代价大于收益)。
- 不给 tool_call_log 增加任何业务内容(隐私红线);traceId 只是不透明随机 ID。
- 不做时间窗近似关联(已否决,并发场景不精确)。
- 不保留旧日志数据(DB 升级走 `fallbackToDestructiveMigration`,诊断数据可丢,用户已确认)。
- 功能②不做跨设备/上报关联,仅本地诊断页消费。

## 功能①:聊天图片预览横滑

### 现状

`ChatScreen.kt`:
- `var previewImage by remember { mutableStateOf<PreviewImageState?>(null) }` — 单图预览状态。
- `ChatMessageItem(message, onImageClick = { msg -> ... previewImage = PreviewImageState(uri, messageId, isEditableResult, isSaved) })` — 点任一带图气泡只传当前一条。
- `ImagePreviewOverlay(state, onSave, onDismiss)` — 单图全屏,`AsyncImage` + 关闭按钮 + (仅编辑/生成图)保存按钮。无翻页、无缩放。
- 覆盖消息类型:`USER_IMAGE`、`USER_IMAGE_TEXT`、`AGENT_IMAGE`、`AGENT_EDIT_RESULT`(即 `ChatMessageUi.imageUri != null`)。
- 编辑/生成图在打开预览时调 `viewModel.touchEditImage(msg.imageUri)` 续期(LRU 回收)。

`PreviewImageState(uri, messageId, isEditableResult, isSaved)`(`ChatScreen.kt:1673`)。

### 目标行为

- 点任一带图气泡 → 打开横滑预览,初始定位到被点的图。
- 左右滑 → 在「本会话全部带图消息」(按 `displayMessages` 顺序)之间翻页。
- 双指缩放 1x~5x + 单指拖动平移(与 `MediaPager`/`ChartPreviewOverlay` 一致),缩放回 ~1x 自动回正。
- 编辑/生成图页:显示底部「保存到相册」按钮,状态随**当前页**的 `imageSaved` 走;滑到已保存的图显示「已保存」并禁用。翻到编辑/生成页时调 `touchEditImage(当前页 uri)` 续期。
- 关闭:点空白 / 返回键 / 右上关闭键(与现有一致)。

### 设计

#### 数据流

1. `ChatScreen` 在打开预览时,从 `displayMessages`(已 `collectAsState`)快照出所有 `imageUri != null` 的消息,得到 `List<ChatMessageUi>` + 被点 index。
   - **快照时机**:打开瞬间取一次,预览期间不再随新消息变化(预览短生命周期;避免 pager index 抖动)。重新打开重新快照。
2. 用一个新状态持有翻页集合:

```kotlin
// 替换原 previewImage 单值
private data class ChatImagePreviewState(
    val pages: List<ChatMessageUi>,   // 带图消息快照
    val initialIndex: Int
)

var imagePreview by remember { mutableStateOf<ChatImagePreviewState?>(null) }
```

3. `onImageClick` 改为:快照带图消息列表 → 计算 index → `imagePreview = ChatImagePreviewState(pages, index)`。

#### 组件改造

`ImagePreviewOverlay` 升级为 pager 版本(建议重命名 `ChatImagePreviewOverlay`,保留旧名作别名或直接替换调用点):

- 入参:`state: ChatImagePreviewState?`、`onSave: (messageId, onDone) -> Unit`、`onDismiss: () -> Unit`、`onPageChanged: (ChatMessageUi) -> Unit`(用于编辑图续期)。
- 内部:`rememberPagerState(pageCount = { state.pages.size })`,`initialPage = state.initialIndex`。
- 每页:`AsyncImage(model = page.uri, contentScale = Fit)` + `detectTransformGestures` 缩放/平移(抄 `ChartPreviewOverlay:1755-1780` 的写法,per-page scale/offset `remember(pageId)`)。
- 顶部指示器:`1 / N`(可选加页码),右上关闭键常驻。
- 底部保存按钮:`LaunchedEffect(pagerState.currentPage)` 取当前页,若 `page.isEditableResult`(type ∈ {AGENT_IMAGE, AGENT_EDIT_RESULT})则显示,文案/启用随 `page.imageSaved`。
- 续期:`LaunchedEffect(pagerState.currentPage) { val p = pages[currentPage]; if (p 是编辑/生成) on_touchEditImage(p.imageUri) }` —— 通过 `onPageChanged` 回调到 `ChatScreen`,由 `ChatScreen` 调 `viewModel.touchEditImage`。
- 关闭:`onDismiss` 置 `imagePreview = null`。

#### 边界情况

- **过期编辑图占位**:已被 LRU 回收的编辑图(现有过期占位气泡逻辑)在 pager 里该页仍可翻到,`AsyncImage` 加载失败/空时显示过期占位文案(复用现有 `chat_edit_save_expired_failed` 类文案与占位 UI);不影响翻页。
- **单图会话**:`pages.size == 1` 时 pager 仍渲染,只是无可滑方向(行为等价单图)。
- **空 uri 兜底**:快照时过滤 `imageUri != null`(已保证);uri scheme 解析沿用现有 `Uri.parse` + `file://` 兜底逻辑(`ChatScreen.kt:478-480`)。
- **Scheme 解析需在快照阶段一次性完成**:把每页的 `imageUri` 解析成最终 `Uri` 存进 page 模型,避免 pager 内每页重复解析。可在快照时映射成 `List<{ uri: Uri, messageId, isEditableResult, isSaved }>`。

### 文件级改动(功能①)

- `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`
  - 新增 `ChatImagePreviewState`;`previewImage` → `imagePreview`。
  - `onImageClick` 改为构建翻页集合 + index。
  - `ImagePreviewOverlay` → pager 版本(缩放 + per-page 保存/续期)。
  - `BackHandler` 条件同步更新(`imagePreview != null`)。
- 字符串:`values/strings.xml` + `values-zh-rCN` + `values-zh-rTW`(若新增页码指示器文案如 `chat_image_preview_position` = "%1$d / %2$d")。

---

## 功能②:LLM 日志 traceId 关联 + 详情页横滑

### 现状(已探查)

- 三实体 `LlmCallLogEntity` / `ToolCallLogEntity` / `JsRunLogEntity` 均无关联字段;DB `LlmLogDatabase` version=3,`fallbackToDestructiveMigration(true)`,`exportSchema=false`。
- 三个 Recorder 接口无 traceId:
  - `LlmCallRecord(createdAt, source, model, ...)`(`runtime-core`)— 由 `CapturingChatModelListener.kt:54/79` 产出 → `RoomLlmCallRecorder.record`。
  - `CommandExecutionRecorder.record(capability, commandType, latencyMs, success, errorCode, errorMessage)`(`runtime-core`)— 由 `CommandExecutor.kt:91` 调用 → `RoomToolCallRecorder`。
  - `JsRunEvent(...)`(`runtime-core`)— 由 `JsRuntime.kt:112` 调用 → `RoomJsRunRecorder`。
- `AgentContext(scene, ...)`(`runtime-core/.../model/context/AgentModels.kt:24`)是 dispatch 上下文对象,`ChatViewModel` 经 `orchestrator.dispatch(command, AgentContext(scene = CHAT), null)` 传入 —— **traceId 的天然主注入点**。
- 详情页 `LlmCallLogDetail` / `JsRunLogDetail` 单条展示;Tab 切换 `LogTab { LLM, TOOL, JS }`。

### 目标行为

- 一条用户消息(`ChatViewModel.sendMessage`)= 一个 traceId(UUID),该轮内所有 LLM 调用 / tool 执行 / JS 运动都带同一 traceId(含多步 ReAct 循环)。
- 日志详情页:打开任一记录 → 顶部指示器 `LLM·TOOL·JS` + 各层计数,`HorizontalPager` 横滑浏览同 traceId 的全部记录(按 `createdAt` 升序);当前页高亮所属层级。
- traceId 为 null 的历史/非会话来源记录(老数据、来源非 chat 的工具/JS 调用):详情页只显示自己,指示器不出现关联(或显示「无关联」)。

### 设计

#### 表结构(三表统一加列)

三实体加 `val traceId: String? = null`(老数据/非 chat 来源为 null)。DB version 3 → 4,靠 `fallbackToDestructiveMigration` 清旧数据(已确认可丢)。

```kotlin
@Entity(tableName = "llm_call_log")
data class LlmCallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // ...existing fields...
    val traceId: String? = null   // 新增:一条用户消息一个;非会话来源为 null
)
```
`tool_call_log` / `js_run_log` 同理。

#### traceId 生成与贯穿

**生成点**:`ChatViewModel.sendMessage(text, imageUri)` 入口生成 `traceId = UUID.randomUUID().toString()`,传入当次 dispatch 链路。

> 说明:仅 chat 会话来源走 traceId 关联。非 chat 入口(Debug 页直跑 JS、系统 Capability 直触等)traceId 为 null,详情页按「无关联」处理。这与「一条用户消息 = 一个 traceId」目标一致。

**载体**:`AgentContext` 增加 `val traceId: String? = null`。

**三层贯穿(关键实现细节)**:

| 层 | 注入方式 | 落点 |
|---|---|---|
| Tool | `AgentContext.traceId` 经 `CapabilityRegistry.dispatch` → `CommandExecutor`,record 时带上 | `CommandExecutor.kt:91` record 调用;`CommandExecutionRecorder.record(...)` 加 `traceId` 参数 |
| JS | JS capability(如 `ChatRunScriptCapability`)从 dispatch 的 AgentContext 取 traceId,写入 `JsRunEvent` | `JsRuntime.kt:112`;`JsRunEvent` 加 `traceId`;JS 执行入口需能拿到当前 AgentContext(由调用方透传或经 `CoroutineContext`) |
| LLM(最难) | `CapturingChatModelListener` 位于 langchain4j model listener 层,拿不到 AgentContext → 用 `CoroutineContext` 侧信道:发起模型调用前把 traceId 装进自定义 `TraceIdElement` 注入协程上下文,listener 在 `onResponse/onError` 读同上下文 | `CapturingChatModelListener.kt:54/79` 构造 `LlmCallRecord` 时从 `coroutineContext[TraceIdKey]` 取;模型调用发起处(`RemoteInferencePipeline`/orchestrator 远程分支)注入 |

> LLM 层协程上下文方案是本功能最大不确定点。实现计划阶段需先验证:`CapturingChatModelListener` 的回调是否运行在与发起调用相同的协程上下文(若 listener 在 langchain4j 内部线程/独立调度,则协程上下文不可达,需退化为 per-call setter 或 ThreadLocal)。spec 据此留两条退路。

**Recorder 接口与实体同步**:
- `LlmCallRecord` 加 `traceId: String?`。
- `CommandExecutionRecorder.record(...)` 加 `traceId: String?` 参数(`fun interface`,改签名)。
- `JsRunEvent` 加 `traceId: String?`。
- `:app` 三个 `Room*Recorder` 把 traceId 写进实体。

#### 查询

各 DAO 加:

```kotlin
@Query("SELECT * FROM llm_call_log WHERE traceId = :tid ORDER BY createdAt ASC")
suspend fun getByTraceId(tid: String): List<LlmCallLogEntity>
// tool_call_log / js_run_log 同理
```

`LlmCallLogViewModel` 加 `loadTurn(traceId): TurnRecords`(合并三层、按 `createdAt` 排序、带类型标签)。

#### 详情页 UI

`LlmCallLogScreen` 详情分支改为 pager:

```kotlin
when {
    selectedItem != null -> LlmTurnDetailPager(
        anchor = selectedItem,         // 当前记录(任一类型)
        vm = vm,
        modifier = ...
    )
    ...
}
```

- `LlmTurnDetailPager`:
  1. 取 `selectedItem.traceId`;为 null → 退化为现有单条详情(`LlmCallLogDetail` / `JsRunLogDetail` / tool 详情)。
  2. 否则 `LaunchedEffect(traceId)` 调 `vm.loadTurn(traceId)` 得到合并列表 `List<TurnRecordItem>`(每项带 `kind: LLM|TOOL|JS` + 原始实体),按 `createdAt` 升序。
  3. `rememberPagerState(pageCount = { items.size })`,`initialPage` = anchor 在列表中的位置。
  4. 顶部指示器 Row:`LLM {count} · TOOL {count} · JS {count}`,当前页所属 kind 高亮。
  5. 每页按 `kind` 复用现有详情组件(`LlmCallLogDetail` / tool 详情 / `JsRunLogDetail`)。
- 顶栏返回行为不变(详情态返回列表)。

#### 边界

- traceId 为 null:单条详情,不出指示器、不查关联。
- 同 traceId 仅 1 条:pager 单页,无翻页方向。
- tool 详情当前无独立 Detail 组件?需在实现时确认(列表 `ToolCallLogRow` 不可点进详情 —— 见现状:Tool Tab 的 row 无 onClick)。**缺口**:Tool 记录目前不能进详情。需补 `ToolCallLogDetail` 组件,或让 Tool row 可点进 → 进入 turn pager。spec 据此要求:新增 tool 详情视图(纯指标卡片,不含业务内容)。

### 文件级改动(功能②)

`:runtime-core`:
- `model/context/AgentModels.kt` — `AgentContext` 加 `traceId`。
- `inference/remote/log/LlmCallRecord.kt` — 加 `traceId`。
- `inference/remote/log/CapturingChatModelListener.kt` — 构造 `LlmCallRecord` 带上 traceId(协程上下文读取)。
- `runtime/capability/CommandExecutionRecorder.kt` — `record(...)` 加 `traceId` 参数。
- `runtime/capability/CommandExecutor.kt` — record 时传 traceId(从 AgentContext 取)。
- `js/JsRunEvent` / `JsRuntime` 相关 — `JsRunEvent` 加 `traceId`;JS 执行入口透传。
- 模型调用发起处(远程分支)— 注入 `TraceIdElement` 到协程上下文(实现时定位确切文件)。

`:app`:
- `data/local/llmlog/` 三实体加 `traceId`;三 DAO 加 `getByTraceId`;`LlmLogDatabase` version 3→4。
- `data/local/llmlog/Room*Recorder`(三个)— 写 traceId;`CommandExecutionRecorder.record` 实现签名同步。
- `features/debug/LlmCallLogViewModel.kt` — 加 `loadTurn`;`TurnRecords` 模型。
- `features/debug/LlmCallLogScreen.kt` — `LlmTurnDetailPager`、指示器、tool 详情组件。
- `features/chat/ChatViewModel.kt` — `sendMessage` 生成 traceId 传入 dispatch。

字符串:`values/strings.xml` + `values-zh-rCN` + `values-zh-rTW`(如 `llm_log_turn_indicator` = "LLM %1$d · TOOL %2$d · JS %3$d"、tool 详情标签等)。

---

## 跨切关注点

- **i18n**:所有新增用户可见字符串同步三语言(EN / zh-rCN / zh-rTW)。功能①页码指示器、功能②turn 指示器与 tool 详情标签。
- **代码规范**:无全限定名(`com.mamba.picme.*` 用 import)、无通配 import、lambda 显式命名、日志 tag `PoLang:Chat` / `PoLang:LlmCallLog` / `PoLang:ToolCallLog` / `PoLang:JsRunLog`。
- **隐私红线**:`traceId` 为不透明 UUID,不含业务内容;`tool_call_log` 仍只存纯指标。
- **性能**:traceId 透传为字符串引用,无额外 IO;详情 pager 查询走索引(如量大可给 `traceId` 加 `@Index`,当前每表仅留 200 条,可暂不加)。

## 测试

- **JVM 单测**(无需设备):
  - 功能②:`LlmCallLogViewModel.loadTurn` 合并排序逻辑(给三表 mock 数据,断言按 createdAt 升序、kind 标签正确、traceId=null 退化为单条)。
  - 功能②:traceId 透传单测 —— 验证 `CommandExecutionRecorder.record` 收到的 traceId 与传入 AgentContext 一致(mock recorder)。
  - 功能①:翻页集合构建 —— 给 `displayMessages`,断言过滤 `imageUri != null`、index 正确。
- **手动/仪器测试**:聊天预览横滑 + 缩放 + 保存;日志页发一条 chat 触发 LLM+tool(+JS),详情横滑看到三层同 traceId。

## 风险与未决

1. **LLM listener 协程上下文可达性**(功能②最大风险):`CapturingChatModelListener` 回调能否读到发起调用的协程上下文,需实现时先验证。不可达则退化为 per-call setter 或 ThreadLocal。已写进实现计划前置探查项。
2. **Tool 详情组件缺失**:现状 Tool Tab row 不可点进详情,需补 `ToolCallLogDetail`。
3. **JS 执行入口 traceId 透传**:需确认 `ChatRunScriptCapability` 等是否能拿到 AgentContext(若走 JS sandbox 独立调度,可能也需协程上下文侧信道)。

## 实现顺序建议

1. 功能② 表结构 + Recorder 接口 + traceId 透传(tool 层先通,最易;LLM 层最后,需探查)。
2. 功能② 详情 pager + 指示器 + tool 详情组件。
3. 功能① 聊天预览 pager(独立,可并行/先后皆可)。
