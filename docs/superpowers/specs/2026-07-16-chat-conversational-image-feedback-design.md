# Chat 多轮图片发现：对话式反馈

> **状态**: 设计待确认  
> **最后更新**: 2026-07-16  
> **关联需求**: 通过多轮对话查找用户感兴趣的图片，支持自然语言反馈与跨轮指代  
> **前置实现**: `docs/superpowers/specs/2026-07-16-chat-image-feedback-design.md`（卡片点击反馈）

---

## 1. 设计目标

在已有的卡片点击反馈基础上，让用户可以通过**自然语言对话**对搜索结果进行反馈，实现真正的多轮图片发现：

- 👍 **正向反馈**：“第三张不错”“我喜欢海边的这张”
- 👎 **负向反馈**：“第三张不是我想要的”“不要有人物的”
- 🔁 **继续探索**：“再来点这种”“前面那张类似的再多来点”
- 🚫 **排除约束**：“排除夜景”“不要室内的”
- 🎯 **跨轮指代**：“前面那张海边的”“上一轮第二张小孩子的”

响应策略：
- `feedback`（👍/👎）：系统插入确认文本，并**重排当前结果卡片**
- `more`（🔁）：系统**插入新的 `MEDIA_RESULTS` 消息**
- `exclude`（🚫）：系统插入确认文本，并在**后续搜索中过滤**

---

## 2. 设计决策

| 决策项 | 选择 | 原因 |
|--------|------|------|
| 解析层 | **Agent Command 扩展** | 复用现有 Agent Orchestrator + Capability 体系，LLM 处理复杂自然语言和跨轮指代 |
| 命令名 | 短方法名 `feedback` / `more` / `exclude` | 节省本地小模型 token |
| 上下文 | `AgentContext.recentSearchResults` | 让 LLM 看得见最近几轮结果，才能解析“第三张”“海边的” |
| Target 解析 | `ChatViewModel` 负责 | Capability 只传递命令，具体 mediaId 映射在 ViewModel 做（它持有 `lastResultAssets`） |
| 确认文案 | ViewModel 固定文案 | 避免小模型输出不稳定，保证体验一致 |
| 快速路径 | 保留现有卡片按钮 | 点击反馈不走 LLM，延迟更低 |

---

## 3. 架构与组件

```
用户输入
    │
    ▼
ChatViewModel.sendMessage()
    │
    ▼
AgentContext(recentSearchResults) ──► AgentOrchestrator.streamChat()
    │
    ▼
LLM 输出命令
    │
    ├── feedback(target, action)
    ├── more(target)
    ├── exclude(constraint)
    ├── refine_media_search(constraint)  [已有]
    └── text_reply                       [已有]
    │
    ▼
ChatSearchCapability.execute()
    │
    ▼
ChatViewModel Delegate
    │
    ├── feedback ──► MediaFeedbackUseCase.record() + 重排
    ├── more ──────► refine / 相似搜索 + 新结果消息
    └── exclude ───► 记录排除约束 + 后续过滤
```

**修改/新增文件**：

| 文件 | 改动 |
|------|------|
| `runtime-core/.../model/context/AgentModels.kt` | `AgentContext` 新增 `recentSearchResults`；新增 `SearchResultSnapshot` |
| `runtime-core/.../model/command/AgentCommands.kt` | 新增 `RecordMediaFeedback`、`MoreLikeThis`、`ExcludeConstraint`；`FeedbackTarget` sealed class |
| `runtime-core/.../inference/local/prompt/LocalPromptBuilder.kt` | 新增短命令 schema、示例、搜索结果状态片段 |
| `runtime-core/.../inference/remote/prompt/RemotePromptBuilder.kt` | 新增工具说明和状态片段 |
| `runtime-core/.../inference/remote/tool/PicMeToolService.kt` | 如需要，补充 `@Tool` 方法 |
| `runtime-core/.../inference/local/parser/LocalCommandParser.kt` | 解析 `feedback` / `more` / `exclude` |
| `runtime-core/.../inference/remote/parser/ToolCallCommandParser.kt` | 解析对应 tool_calls |
| `app/.../features/chat/capability/ChatSearchCapability.kt` | 支持新命令，扩展 `Delegate` |
| `app/.../features/chat/ChatViewModel.kt` | 实现 delegate、target 解析、命令处理、消息更新 |
| `app/.../features/chat/components/MediaResultsCarousel.kt` | 增加高亮能力（可选） |
| `app/.../domain/search/MediaFeedbackUseCase.kt` | 新增 exclude 约束相关接口 |

---

## 4. 数据模型

### 4.1 AgentContext 扩展

```kotlin
data class AgentContext(
    val scene: AgentScene,
    val beautySettings: BeautySettings = BeautySettings(),
    val filterType: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter = StyleFilter.NONE,
    val zoomRatio: Float = 1f,
    val exposureCompensation: Int = 0,
    val captureMode: MediaType = MediaType.PHOTO,
    val isRecording: Boolean = false,
    val memorySessionId: String = scene.name.lowercase(),
    val recentSearchResults: List<SearchResultSnapshot> = emptyList()   // 新增
)

data class SearchResultSnapshot(
    val query: String,
    val results: List<ResultItem>,        // 最多前 10 个，控制 token
    val totalCount: Int,
    val isRefinement: Boolean,
    val timestamp: Long
)

data class ResultItem(
    val mediaId: String,
    val tags: List<String>                // 前 3 个 tag，用于自然语言指代
)
```

### 4.2 FeedbackTarget

```kotlin
sealed interface FeedbackTarget {
    data class Ordinal(val index: Int) : FeedbackTarget        // "第三张" → index=3
    data class Description(val text: String) : FeedbackTarget  // "海边的"
    data class MediaId(val id: String) : FeedbackTarget        // 精确 id
    data object LastShown : FeedbackTarget                     // "这张"
}
```

### 4.3 新增 AgentCommand

```kotlin
sealed class AgentCommand {
    // ... 原有命令

    data class RecordMediaFeedback(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val target: FeedbackTarget,
        val action: FeedbackAction,   // LIKE / DISLIKE
        val queryHint: String? = null
    ) : AgentCommand()

> **注意**：`FeedbackAction` 当前位于 `:app` 模块，但 `AgentCommand` 在 `:runtime-core`。
> 为避免循环依赖，实现时需把 `FeedbackAction` 上移到 `:runtime-core`（与 `AgentCommand` 同包），
> `:app` 中的 `MediaFeedbackUseCase` / `MediaFeedbackEntity` 通过 import 复用该枚举。

    data class MoreLikeThis(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val target: FeedbackTarget,
        val queryHint: String? = null
    ) : AgentCommand()

    data class ExcludeConstraint(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val constraint: String
    ) : AgentCommand()
}
```

### 4.4 命令 ↔ method/tool 名映射

| AgentCommand | 本地 method | 远程 tool name |
|--------------|-------------|----------------|
| `RecordMediaFeedback` | `feedback` | `feedback` |
| `MoreLikeThis` | `more` | `more` |
| `ExcludeConstraint` | `exclude` | `exclude` |

---

## 5. Prompt 设计

### 5.1 本地模型 schema

在 `LocalPromptBuilder.basePrompt` 中追加：

```text
- feedback: {"method":"feedback","params":{"target":"ordinal:3|desc:海边|last","action":"like|dislike"}}
- more: {"method":"more","params":{"target":"ordinal:3|desc:海边|last"}}
- exclude: {"method":"exclude","params":{"constraint":"夜景"}}
```

`params` 允许键增加：`target`、`action`、`constraint`。

### 5.2 本地模型示例

```text
「第三张不错」→ [{"method":"feedback","params":{"target":"ordinal:3","action":"like"}}]
「不喜欢有人物的」→ [{"method":"exclude","params":{"constraint":"人物"}}]
「再来点这种」→ [{"method":"more","params":{"target":"last"}}]
「前面海边的再多来点」→ [{"method":"more","params":{"target":"desc:海边"}}]
「第三张不错，再来点类似的」→ [{"method":"feedback","params":{"target":"ordinal:3","action":"like"}},{"method":"more","params":{"target":"ordinal:3"}}]
```

### 5.3 状态片段

```text
【最近搜索结果】
- 第 1 轮 (query="海边日落", 共 8 张):
  [1] id=img_001 tags=[海, 日落, 沙滩]
  [2] id=img_002 tags=[海, 人, 背影]
  ...
- 第 2 轮 (query="有猫的", 共 5 张, 细化):
  [1] id=img_010 tags=[猫, 室内, 沙发]
  ...
```

> `tagSummaries` 已改为 `results: List<ResultItem>`，每个 item 包含 `mediaId` 和 `tags`。

### 5.4 远程模型

`RemotePromptBuilder.buildBatchPrompt()` 的状态片段同步增加最近搜索结果；`ChatSearchCapability` 为三个新命令提供 `getCommandDescription()` / `getCommandJsonSchema()`。

---

## 6. 数据流

### 6.1 发送消息

```kotlin
fun sendMessage(text: String) {
    // 1. 保存用户消息
    // 2. 构建 AgentContext
    val agentContext = AgentContext(
        scene = AgentScene.CHAT,
        memorySessionId = sessionId,
        recentSearchResults = buildSearchSnapshots(sessionId)
    )
    // 3. 流式推理
    orchestrator.streamChat(input = text, agentContext = agentContext, ...)
}
```

`buildSearchSnapshots()` 从 `lastResultAssets` 取最近 3 轮，每轮最多 10 张；从 `MediaAsset.labels` JSON 解析 `tags` 数组，取前 3 个填入 `ResultItem.tags`。

### 6.2 命令执行

`ChatSearchCapability.execute()` 新增分支：

```kotlin
when (command) {
    is AgentCommand.RecordMediaFeedback -> delegate.onRecordMediaFeedback(command)
    is AgentCommand.MoreLikeThis -> delegate.onMoreLikeThis(command)
    is AgentCommand.ExcludeConstraint -> delegate.onExcludeConstraint(command)
    // ... 已有 search_media / refine_media_search
}
```

### 6.3 ChatViewModel 处理

```kotlin
override suspend fun onRecordMediaFeedback(command: AgentCommand.RecordMediaFeedback) {
    val mediaId = resolveTarget(command.target) ?: return reportResolveFailure()
    mediaFeedbackUseCase.record(mediaId, currentQuery, command.action)
    updateCurrentResultsFeedback(mediaId, command.action)
    reorderCurrentResults()
    insertAgentMessage(confirmationText(command.action))
}

override suspend fun onMoreLikeThis(command: AgentCommand.MoreLikeThis) {
    val mediaId = resolveTarget(command.target)
    val constraint = buildConstraint(mediaId, command.target)
    val outcome = onRefineMediaSearch(constraint)
    insertNewMediaResultsMessage(outcome)
}

override suspend fun onExcludeConstraint(command: AgentCommand.ExcludeConstraint) {
    activeExcludes.add(command.constraint)
    mediaFeedbackUseCase.recordExclude(command.constraint)
    reapplyFiltersToCurrentResults()
    insertAgentMessage("已排除'${command.constraint}'，后续结果不再包含。")
}
```

### 6.4 Target 解析

```kotlin
private fun resolveTarget(target: FeedbackTarget): String? {
    val prior = lastResultAssets[currentSessionId].orEmpty()
    return when (target) {
        is FeedbackTarget.Ordinal -> prior.getOrNull(target.index - 1)?.id
        is FeedbackTarget.Description -> prior.firstOrNull { matchesTags(it, target.text) }?.id
        is FeedbackTarget.MediaId -> target.id
        is FeedbackTarget.LastShown -> prior.firstOrNull()?.id
    }
}
```

---

## 7. UI 行为

### 7.1 确认消息

| 命令 | 系统文本 |
|------|----------|
| `feedback like` | “已记录，会优先推荐类似照片。” |
| `feedback dislike` | “已记录，会减少类似照片。” |
| `exclude` | “已排除‘{constraint}’，后续结果不再包含。” |

### 7.2 高亮被指代的卡片

- `MediaResultsUi` 增加 `highlightedMediaId: String?`
- `MediaCard` 根据该值显示 2dp 描边，持续 300ms 后清除
- 让用户直观感知系统理解了哪张图

### 7.3 结果消息策略

- `feedback` / `exclude`：修改当前 `MEDIA_RESULTS` 消息，卡片重排或过滤
- `more`：新增一条 `MEDIA_RESULTS` 消息，保留历史上下文

---

## 8. 错误处理

| 场景 | 行为 |
|------|------|
| Target 解析失败 | 插入文本：“我没找到你说的那张图，你能再描述一下吗？” |
| 序数越界 | 同上 |
| `description` 匹配不到 | 提示：“没理解你说的是哪一张，可以说‘第几张’或描述一下内容。” |
| 无最近结果时指代 | 提示：“请先搜索照片。” |
| LLM 只输出 `text_reply` | 按正常聊天处理 |
| 反馈写入失败 | 记录日志，UI 仍显示确认，下次启动丢失 |
| LLM 输出未注册命令 | `CapabilityRegistry` 分发时报 `METHOD_NOT_FOUND`，记录日志 |

---

## 9. 测试计划

| 测试类型 | 覆盖点 |
|----------|--------|
| 命令解析 | `LocalCommandParser` / `ToolCallCommandParser` 正确解析 `feedback` / `more` / `exclude` |
| Target 解析 | `ChatViewModel.resolveTarget()`：ordinal、description、last、mediaId 各种命中与未命中 |
| UseCase | `MediaFeedbackUseCase` 支持 exclude 约束记录与查询 |
| Prompt | `LocalPromptBuilderChatSearchTest` 验证新 schema、示例、状态片段包含搜索结果 |
| 集成 | “第三张不错” → `RecordMediaFeedback` → 落库 → 当前消息重排 → 确认文本 |
| 边界 | 无结果指代、越界、description 未匹配、多命令组合执行 |

---

## 10. 红线检查

- **[PRIVACY]**：反馈数据仍只本地持久化，LLM 仅用于解析命令，不上传原始图片
- **[PERF]**：搜索结果快照每轮最多 10 张 × 3 轮，控制 prompt token；target 解析纯本地，< 5ms
- **[I18N]**：确认文案需四语同步（zh / zh-rCN / zh-rTW / en）
- **[AGENT-FIRST]**：新命令通过 `AgentCommand` 显式定义；状态通过 `AgentContext` 显式传递

---

## 11. 后续演进

1. **Embedding 偏好画像**：聚合点赞图片的 MobileCLIP embedding，用于跨查询语义推荐
2. **Agent 主动追问**：当指代歧义时，LLM 主动问“你指的是海边日落那张吗？”
3. **排除持久化**：当前 exclude 可先放内存；后续可持久化到 `media_feedback` 或新表
