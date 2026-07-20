# Chat TAG 扫描启动工具设计

> **状态**：设计稿待实现  
> **日期**：2026-07-18  
> **范围**：在 Chat 中给 LLM 提供一个用户视角的 TAG 扫描启动/控制/查询工具。

---

## 1. 目标

在 Chat 页面给 LLM 提供一个本地工具，使其能够：

1. **启动扫描**：根据用户语义启动指定类别的 TAG 扫描（人脸、场景、活动、物体、TAG、摘要、ML Kit 标签、自动默认）。
2. **控制扫描**：暂停、恢复、取消当前扫描任务。
3. **查询状态**：返回当前活跃扫描会话的进度详情。
4. **用户友好**：调用方不需要理解 Pass1/Pass2/Pass3 的内部概念，只暴露用户可理解的类别。

---

## 2. 设计原则

- **用户视角**：`task_type` 使用类别名称（`face`, `scene`, `activity`, `objects`, `tags`, `summary`, `mlkit`, `auto`），内部映射到 `TagCategory` 与 `TagScanPass`。
- **单一入口**：合并 start / pause / resume / cancel / query 到一个 tool `start_tag_scan`，通过 `action` 参数区分。
- **复用现有能力**：底层直接调用 `TagScanOrchestrator.schedulePass()`、`scheduleAutoScan()` 及 `pause()` / `resume()` / `cancel()`。
- **状态透明**：查询返回当前活跃会话的 `TagScanSessionProgress`，包含 state、currentPass、processed/total/pending/failed、estimatedRemainingMs 等。
- **错误可解释**：所有错误通过 tool 返回值中的 `error` 字段返回，LLM 可直接转述给用户。

---

## 3. 数据模型

### 3.1 Tool 请求参数

```kotlin
data class StartTagScanRequest(
    /** 动作类型 */
    val action: StartTagScanAction,
    /** 扫描类别，仅在 action = START 时有效 */
    val taskType: TagScanTaskType? = null,
    /** 扫描模式，仅在 action = START 时有效 */
    val mode: TagScanMode? = null
)

enum class StartTagScanAction {
    START,
    PAUSE,
    RESUME,
    CANCEL,
    QUERY
}

enum class TagScanTaskType {
    FACE,       // 映射到 TagCategory.FACE -> Pass1 + Pass2
    SCENE,      // 映射到 TagCategory.SCENE -> Pass3
    ACTIVITY,   // 映射到 TagCategory.ACTIVITY -> Pass3
    OBJECTS,    // 映射到 TagCategory.OBJECTS -> Pass3
    TAGS,       // 映射到 TagCategory.TAGS -> Pass3
    SUMMARY,    // 映射到 TagCategory.SUMMARY -> Pass3
    MLKIT,      // 映射到 TagCategory.ML_KIT_LABELS -> ML_KIT_TAGGING
    AUTO        // 默认组合：FACE + SCENE + ACTIVITY + OBJECTS + TAGS + SUMMARY，INCREMENTAL
}

enum class TagScanMode {
    FULL,
    INCREMENTAL
}
```

### 3.2 Tool 返回结果

```kotlin
sealed class StartTagScanResult {
    data class Started(
        val sessionId: String,
        val taskType: TagScanTaskType,
        val mode: TagScanMode,
        val message: String
    ) : StartTagScanResult()

    data class ControlAck(
        val action: StartTagScanAction,
        val sessionId: String?,
        val message: String
    ) : StartTagScanResult()

    data class Status(
        val sessionId: String,
        val state: String,
        val currentPass: String?,
        val currentMediaId: Long?,
        val processed: Int,
        val total: Int,
        val pending: Int,
        val failed: Int,
        val estimatedRemainingMs: Long?,
        val messages: List<ScanMessageDto>
    ) : StartTagScanResult()

    data class Error(
        val error: String
    ) : StartTagScanResult()
}

data class ScanMessageDto(
    val timestamp: Long,
    val level: String,
    val text: String
)
```

### 3.3 类别映射规则

| taskType | TagCategory | 触发 Pass |
|---|---|---|
| `FACE` | `FACE` | `PASS1` + `PASS2` |
| `SCENE` | `SCENE` | `PASS3` |
| `ACTIVITY` | `ACTIVITY` | `PASS3` |
| `OBJECTS` | `OBJECTS` | `PASS3` |
| `TAGS` | `TAGS` | `PASS3` |
| `SUMMARY` | `SUMMARY` | `PASS3` |
| `MLKIT` | `ML_KIT_LABELS` | `ML_KIT_TAGGING` |
| `AUTO` | 多类别组合 | `PASS1` + `PASS2` + `PASS3` |

`AUTO` 的具体语义：

- 类别：`FACE + SCENE + ACTIVITY + OBJECTS + TAGS + SUMMARY`
- 模式：固定 `INCREMENTAL`（调用方传入的 `mode` 被忽略）
- 实现：调用 `TagScanOrchestrator.scheduleAutoScan(policy = ScanPolicy())`

---

## 4. UseCase：`StartTagScanUseCase`

位置：`app/src/main/java/com/mamba/picme/domain/usecase/StartTagScanUseCase.kt`

```kotlin
class StartTagScanUseCase(
    private val context: Context,
    private val orchestrator: TagScanOrchestrator
) {
    suspend operator fun invoke(request: StartTagScanRequest): StartTagScanResult =
        when (request.action) {
            START -> start(request.taskType, request.mode)
            PAUSE -> pause()
            RESUME -> resume()
            CANCEL -> cancel()
            QUERY -> query()
        }

    private suspend fun start(
        taskType: TagScanTaskType?,
        mode: TagScanMode?
    ): StartTagScanResult {
        val resolvedType = taskType ?: AUTO
        val resolvedMode = mode ?: INCREMENTAL

        // 1. 检查 Service 是否运行
        if (!TagGenerationService.isRunning) {
            // 尝试启动 Service
            val intent = TagGenerationService.createStartIntent(context)
            ContextCompat.startForegroundService(context, intent)
        }

        // 2. 映射并调度
        return when (resolvedType) {
            AUTO -> {
                orchestrator.scheduleAutoScan(ScanPolicy())
                Started(
                    sessionId = orchestrator.activeSessionId() ?: "",
                    taskType = AUTO,
                    mode = INCREMENTAL,
                    message = "已启动默认 TAG 扫描（增量模式）"
                )
            }
            else -> {
                val category = resolvedType.toTagCategory()
                val passes = TagCategory.toPasses(setOf(category))
                passes.forEach { pass ->
                    orchestrator.schedulePass(
                        pass = pass,
                        query = null,
                        mode = resolvedMode.toScanMode(),
                        policy = ScanPolicy()
                    )
                }
                Started(
                    sessionId = orchestrator.activeSessionId() ?: "",
                    taskType = resolvedType,
                    mode = resolvedMode,
                    message = "已启动 ${resolvedType.displayName} 扫描（${resolvedMode.displayName}）"
                )
            }
        }
    }

    private suspend fun pause(): StartTagScanResult {
        orchestrator.pause()
        return ControlAck(PAUSE, orchestrator.activeSessionId(), "扫描已暂停")
    }

    private suspend fun resume(): StartTagScanResult {
        orchestrator.resume()
        return ControlAck(RESUME, orchestrator.activeSessionId(), "扫描已恢复")
    }

    private suspend fun cancel(): StartTagScanResult {
        orchestrator.cancel()
        return ControlAck(CANCEL, orchestrator.activeSessionId(), "扫描已取消")
    }

    private fun query(): StartTagScanResult {
        val progress = orchestrator.activeSessionProgress()
            ?: return Error("当前没有活跃的扫描会话")
        return Status(
            sessionId = progress.sessionId,
            state = progress.state.name,
            currentPass = progress.currentPass?.name,
            currentMediaId = progress.currentMediaId,
            processed = progress.processed,
            total = progress.total,
            pending = progress.pending,
            failed = progress.failed,
            estimatedRemainingMs = progress.estimatedRemainingMs,
            messages = progress.messages.map { ScanMessageDto(it.timestamp, it.level.name, it.text) }
        )
    }
}
```

### 4.1 错误处理

| 场景 | 返回 |
|---|---|
| `action = START` 但 `TagGenerationService` 无法启动 | `Error("TAG 扫描服务无法启动，请检查权限或稍后重试")` |
| 没有可扫描的媒体 | `Error("相册中没有可扫描的照片或视频")` |
| 已经有扫描在进行中 | `Error("已有扫描任务在进行中，请先等待完成或取消")` |
| `action = PAUSE/RESUME/CANCEL` 但没有活跃会话 | `Error("当前没有活跃的扫描会话")` |
| `action = START` 且 `taskType` 缺失 | 默认按 `AUTO` 处理 |
| `action = START` 且 `mode` 缺失 | 默认按 `INCREMENTAL` 处理 |

---

## 5. Agent 命令与 Tool 注册

### 5.1 新增 `AgentCommand`

位置：`runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt`

```kotlin
data class StartTagScan(
    override val commandId: Int = AgentIdGenerator.nextId(),
    val action: String,       // "start", "pause", "resume", "cancel", "query"
    val taskType: String? = null,
    val mode: String? = null
) : AgentCommand()
```

method 名注册为 `start_tag_scan`。

### 5.2 解析器支持

- `LocalCommandParser` 增加 `start_tag_scan` 分支，从 LLM 输出解析 action / task_type / mode。
- `ToolCallCommandParser` 增加同名 tool 解析，支持 function calling。

### 5.3 Tool Schema（Function Calling）

```json
{
  "name": "start_tag_scan",
  "description": "启动、控制或查询本地 TAG 扫描任务。用户可以通过它开始人脸识别、场景识别、物体识别等扫描，或暂停/恢复/取消当前扫描。",
  "parameters": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["start", "pause", "resume", "cancel", "query"],
        "description": "要执行的动作"
      },
      "task_type": {
        "type": "string",
        "enum": ["face", "scene", "activity", "objects", "tags", "summary", "mlkit", "auto"],
        "description": "扫描类别，仅在 action=start 时有效。auto 会启动默认组合扫描。"
      },
      "mode": {
        "type": "string",
        "enum": ["full", "incremental"],
        "description": "扫描模式，仅在 action=start 时有效。默认 incremental。"
      }
    },
    "required": ["action"]
  }
}
```

---

## 6. Chat Capability

新增文件：`app/src/main/java/com/mamba/picme/features/chat/capability/ChatStartTagScanCapability.kt`

与 `ChatSearchCapability` 保持相同模式：

- `BaseCapability` 子类。
- 通过 `WeakReference<Delegate>` 绑定 `ChatViewModel`。
- 仅活跃于 `SceneManager.Scene.CHAT`。
- 支持命令 `start_tag_scan`。

```kotlin
interface Delegate {
    suspend fun onStartTagScan(
        action: String,
        taskType: String?,
        mode: String?
    ): StartTagScanResult
}
```

### 6.1 ChatViewModel 绑定

- 在 `ChatViewModel` 中实现 `ChatStartTagScanCapability.Delegate`。
- 调用 `StartTagScanUseCase(...)` 并返回结果。
- 在 `handleAgentAction` 中处理 `AgentCommand.StartTagScan`，把 `StartTagScanResult` 转成自然语言回复：
  - `Started`："已启动 XXX 扫描，当前进度可到 TAG 生成控制页查看。"
  - `ControlAck`："扫描已暂停/恢复/取消。"
  - `Status`："当前正在扫描 XXX，已完成 Y/Z。"
  - `Error`：直接转述 `error`。

### 6.2 ChatScreen 注册

在 `ChatScreen.kt` 中：

```kotlin
val capabilities = remember {
    listOf(
        // ... 现有能力
        ChatStartTagScanCapability(viewModel)
    )
}
```

---

## 7. Prompt 语义规则

在 `LocalPromptBuilder.kt` / `buildL2SystemPrompt()` 的 tool 说明中增加：

> `start_tag_scan`：用于启动、控制、查询本地 TAG 扫描。当用户说“帮我扫描照片”“开始人脸分组”“继续扫描”“取消扫描”“扫描进度怎么样”时调用。
>
> - `task_type` 使用用户能理解的类别：`face`（人脸）、`scene`（场景）、`activity`（活动）、`objects`（物体）、`tags`（标签）、`summary`（摘要）、`mlkit`（ML Kit 标签）、`auto`（默认组合）。
> - 如果用户没有明确指定类别，使用 `auto`。
> - 如果用户没有明确指定全量/增量，使用 `incremental`。
> - 查询状态使用 `action=query`。

---

## 8. 边界与后续优化

- **并发控制**：当前 `TagScanOrchestrator` 对活跃会话有单 session 限制，start 前需检查是否已有活跃 session。
- **后台保活**：`TagGenerationService` 负责保活，tool 只需确保 Service 已启动。
- **后续可扩展**：如需支持批量重扫某个时间段/相册，可在 `taskType` 之外增加 `filter` 参数，本次不实现。
- **冷启动**：如果相册摘要显示没有数据，LLM 应先引导用户同步相册，再调用 `start_tag_scan`。

---

## 9. 实现 checklist

- [ ] 在 `:runtime-core` 定义 `AgentCommand.StartTagScan`
- [ ] 在 `:app` 定义 `StartTagScanRequest`、`StartTagScanResult`、`StartTagScanUseCase`
- [ ] 在 `:app` 实现 `ChatStartTagScanCapability`
- [ ] 在 `ChatViewModel` 实现 Delegate 并绑定
- [ ] 在 `ChatScreen` 注册 Capability
- [ ] 更新 `LocalCommandParser` 与 `ToolCallCommandParser`
- [ ] 更新 `LocalPromptBuilder` tool 说明
- [ ] 编译验证 + 手动测试 start / pause / resume / cancel / query
