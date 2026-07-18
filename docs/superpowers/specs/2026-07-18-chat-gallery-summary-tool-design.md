# Chat 相册摘要工具设计

> **状态**：设计稿待实现  
> **日期**：2026-07-18  
> **范围**：让 Chat 中的 LLM 实时感知本地相册统计，并在冷启动/未扫描时引导用户启动 TAG 扫描。

---

## 1. 目标

在 Chat 页面给 LLM 提供一本地的“相册摘要”工具，使其能够：

1. 回答状态类问题，如“我有多少张照片”“多少人脸”“多少已打标/未打标”。
2. 根据未打标比例、Pass 1/Pass 3 剩余量判断是否需要扫描，并给出建议。
3. **冷启动场景**（totalMedia == 0 或 `gallerySummary` 不可用时），在对话中引导用户启动 TAG 扫描，而不是返回无法理解的错误。
4. 同一能力同时被本地 L2 命令解析和飞书远程控制（ReAct）链路复用。

---

## 2. 设计原则

- **读取-only**：该工具只做统计查询，不写数据库、不触发扫描（触发扫描由其他命令/入口负责）。
- **低成本**：全部使用 `COUNT(*)` 查询和 `StateFlow` 读取，目标耗时 <50ms。
- **跨模块共享**：摘要数据类放在 `:runtime-core`，`:app` 负责填充；避免 `:runtime-core` 反向依赖 `:app`。
- **向后兼容**：`gallerySummary` 字段默认为 `null`，不破坏现有 `AgentContext` 构造。

---

## 3. 数据模型

### 3.1 `GallerySummary`

位置：`runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/GallerySummary.kt`

```kotlin
data class GallerySummary(
    val totalPhotos: Int,
    val totalVideos: Int,
    val totalMedia: Int,
    val hasFaceCount: Int,
    val personClusterCount: Int,
    val namedPersonCount: Int,
    val labeledCount: Int,
    val unlabeledCount: Int,
    val mlKitLabeledCount: Int,
    val semanticEncodedCount: Int,
    val remainingPass1: Int,
    val remainingPass3: Int,
    val remainingMlKit: Int,
    val isScanning: Boolean,
    val currentPass: String? = null,
    val scanProgressText: String? = null,
    val recommendation: ScanRecommendation
) {
    enum class ScanRecommendation {
        NONE,
        INCREMENTAL,
        PASS3_FULL,
        PASS1_FIRST
    }
}
```

### 3.2 推荐规则

由 `GetGallerySummaryUseCase` 计算：

| 条件 | 推荐 |
|---|---|
| `remainingPass1 > totalMedia * 0.1` | `PASS1_FIRST` |
| `unlabeledCount > totalMedia * 0.3` | `PASS3_FULL` |
| `unlabeledCount > 0` | `INCREMENTAL` |
| 其他 | `NONE` |

阈值后续可根据实际体验微调。

### 3.3 冷启动/无数据表示

- `totalMedia == 0`：表示相册尚未被系统媒体库同步或 Room 中没有任何记录。
- `GallerySummary` 为 `null`：表示读取失败（如 Room 异常）。

---

## 4. UseCase：`GetGallerySummaryUseCase`

位置：`app/src/main/java/com/mamba/picme/domain/usecase/GetGallerySummaryUseCase.kt`

```kotlin
class GetGallerySummaryUseCase(
    private val context: Context,
    private val db: AppDatabase
) {
    suspend operator fun invoke(): GallerySummary? = withContext(Dispatchers.IO) {
        runCatching {
            val stats = TagScanOrchestrator.getDbStats(db)
            val progress = TagGenerationService.sessionProgress.value
            // 映射为 GallerySummary
        }.getOrNull()
    }
}
```

- 依赖现有 `TagScanOrchestrator.getDbStats(db)`，不新增复杂查询。
- `isScanning` 通过读取 `TagGenerationService.isScanning` / `sessionProgress` 获得。
- 返回 `null` 时上层按“不可用”处理。

---

## 5. AgentContext 注入

### 5.1 扩展 `AgentContext`

位置：`runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt`

```kotlin
data class AgentContext(
    // ... 现有字段
    val gallerySummary: GallerySummary? = null
)
```

### 5.2 Prompt 格式化

位置：`runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/prompt/LocalPromptBuilder.kt`

在 `buildStateSection()` 中追加：

```kotlin
append(", gallery_summary=")
append(formatGallerySummary(context.gallerySummary))
```

格式示例：

```text
gallery_summary={totalMedia:1200,photos:1150,videos:50,faces:380,persons:12,named:3,labeled:600,unlabeled:600,mlKit:1200,scanning:false,recommendation:INCREMENTAL}
```

当 `gallerySummary` 为 `null` 或 `totalMedia == 0` 时：

```text
gallery_summary={status:no_data}
```

### 5.3 Prompt 语义规则

在 `buildL2SystemPrompt()` / `buildSystemPrompt()` 的说明中增加：

> 当前相册摘要见【当前状态】中的 `gallery_summary`。用户问照片数量、人脸数量、是否需要扫描时，直接根据该摘要回答。
>
> 如果 `gallery_summary` 为 `{status:no_data}`，说明相册尚未完成首次扫描，请友好地告诉用户“还没有照片数据，可能需要先同步相册或启动 TAG 扫描”，并询问是否需要前往 TAG 生成控制页开始扫描。

---

## 6. Chat 中的主动命令

### 6.1 新增 `AgentCommand`

位置：`runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt`

```kotlin
data class GetGallerySummary(
    override val commandId: Int = AgentIdGenerator.nextId(),
    val includeDetails: Boolean = false
) : AgentCommand()
```

method 名注册为 `get_gallery_summary`。

### 6.2 解析器支持

- `LocalCommandParser` 增加 `get_gallery_summary` 分支。
- `ToolCallCommandParser` 增加同名 tool 解析。

### 6.3 Chat Capability

新增文件：`app/src/main/java/com/mamba/picme/features/chat/capability/ChatGallerySummaryCapability.kt`

与 `ChatSearchCapability` 保持相同模式：

- `BaseCapability` 子类。
- 通过 `WeakReference<Delegate>` 绑定 `ChatViewModel`。
- 仅活跃于 `SceneManager.Scene.CHAT`。
- 支持命令 `get_gallery_summary`。

```kotlin
interface Delegate {
    suspend fun onGetGallerySummary(includeDetails: Boolean): GallerySummary?
}
```

### 6.4 ChatViewModel 绑定

- 在 `ChatViewModel` 中实现 `ChatGallerySummaryCapability.Delegate`。
- 每次 `sendMessage()` 前调用 `GetGallerySummaryUseCase()`，把结果写入 `AgentContext.gallerySummary`。
- 在 `handleAgentAction` 中处理 `AgentCommand.GetGallerySummary`，返回 `AgentAction.TextReply`（把摘要转成自然语言）。

### 6.5 ChatScreen 注册

在 `ChatScreen.kt` 中：

```kotlin
RegisterCapability(ChatSearchCapability.getInstance())
RegisterCapability(ChatGallerySummaryCapability.getInstance())
```

并在 `DisposableEffect` 中绑定/解绑 delegate。

---

## 7. 飞书/ReAct 工具复用

位置：`runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/PoLangToolService.kt`

新增：

```kotlin
@Tool(name = "get_gallery_summary", value = ["获取本地相册摘要，包括照片数、人脸数、人物数、已/未打标数量以及扫描建议"])
fun getGallerySummary(): String {
    return runBlocking(Dispatchers.IO) {
        dispatchCommand(AgentCommand.GetGallerySummary())
    }
}
```

同时在 `callTool()` 中增加对应分支。

---

## 8. 冷启动引导话术

当 `gallerySummary` 为 `null` 或 `totalMedia == 0` 时，LLM 应返回类似：

> “我还没拿到你的相册数据，可能是首次使用或尚未完成同步。我可以帮你启动 TAG 扫描，让人脸、场景和物体标签都生成出来。要开始吗？”

后续实现可再补充一个 `start_tag_scan` 命令，让用户回复“开始”后直接触发 `TagGenerationService.intentScanIncremental(context)`。本期仅做引导，不实现自动触发。

---

## 9. 错误处理

| 场景 | 行为 |
|---|---|
| `GetGallerySummaryUseCase` 读取 Room 失败 | 返回 `null`，prompt 显示 `gallery_summary={status:unavailable}` |
| `TagGenerationService` 未启动 | `isScanning=false`，`currentPass=null` |
| `totalMedia == 0` | prompt 显示 `gallery_summary={status:no_data}`，LLM 引导扫描 |

---

## 10. 文件变更清单

| 文件 | 变更 |
|---|---|
| `runtime-core/.../model/context/GallerySummary.kt` | 新增 |
| `runtime-core/.../model/context/AgentModels.kt` | `AgentContext` 增加 `gallerySummary` |
| `runtime-core/.../model/command/AgentCommands.kt` | 新增 `GetGallerySummary` |
| `runtime-core/.../inference/local/parser/LocalCommandParser.kt` | 解析 `get_gallery_summary` |
| `runtime-core/.../inference/local/prompt/LocalPromptBuilder.kt` | 格式化并注入摘要 |
| `runtime-core/.../inference/remote/parser/ToolCallCommandParser.kt` | tool_call 解析 |
| `runtime-core/.../inference/remote/tool/PoLangToolService.kt` | 新增 `@Tool` |
| `app/.../domain/usecase/GetGallerySummaryUseCase.kt` | 新增 |
| `app/.../features/chat/capability/ChatGallerySummaryCapability.kt` | 新增 |
| `app/.../features/chat/ChatViewModel.kt` | 绑定 delegate、注入摘要、处理命令 |
| `app/.../features/chat/ChatScreen.kt` | 注册 capability |

---

## 11. 测试计划

1. **单元测试**：`GetGallerySummaryUseCase` 在内存 Room 中验证各计数字段和 `recommendation` 阈值。
2. **Prompt 测试**：验证 `buildStateSection` 对 `gallerySummary = null`、`totalMedia = 0`、正常值三种情况的输出。
3. **命令解析测试**：本地 L2 和远程 tool_call 都能正确解析 `get_gallery_summary`。
4. **集成测试**：冷启动下在 Chat 输入“我有多少张照片”，验证 LLM 返回引导扫描话术；扫描完成后再次询问，验证返回真实统计。

---

## 12. 待后续扩展

- `start_tag_scan` 命令：允许 LLM 在用户同意后直接启动增量扫描。
- 更细粒度的摘要：按时间范围、按人物等过滤。
- 把摘要缓存到 `AgentContext` 的生成频率控制，避免每轮都查询数据库。
