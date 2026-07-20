# Start TAG Scan Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single `start_tag_scan` tool to the Chat agent that lets users start, pause, resume, cancel, and query TAG scans using human-friendly categories (face/scene/activity/objects/tags/summary/mlkit/auto) without understanding internal Pass numbers.

**Architecture:** Add a new `AgentCommand.StartTagScan` in `:runtime-core`, route control through existing `TagGenerationService` intents, and implement a `ChatStartTagScanCapability` in `:app` that delegates to `ChatViewModel`. The `StartTagScanUseCase` translates user-facing categories into service intents and reads progress from `TagGenerationService.sessionProgress`.

**Tech Stack:** Kotlin, Compose, Android Service + Intent actions, Room (read-only for status), kotlinx.coroutines.

---

## File map

| File | Responsibility |
|------|----------------|
| `runtime-core/.../model/command/AgentCommands.kt` | Add `AgentCommand.StartTagScan` and `getMethodName()` mapping |
| `runtime-core/.../inference/local/parser/LocalCommandParser.kt` | Parse `start_tag_scan` from local LLM JSON |
| `runtime-core/.../inference/remote/parser/ToolCallCommandParser.kt` | Parse `start_tag_scan` from remote tool_calls |
| `runtime-core/.../inference/local/prompt/LocalPromptBuilder.kt` | Add `start_tag_scan` to chat prompts and field whitelist |
| `app/.../service/tag/TagGenerationService.kt` | Add `ACTION_START_TAG_SCAN` intent + extras handling |
| `app/.../domain/usecase/StartTagScanUseCase.kt` | Translate category/mode to service intents; query progress |
| `app/.../features/chat/capability/ChatStartTagScanCapability.kt` | CHAT-scoped capability binding to ViewModel |
| `app/.../features/chat/ChatViewModel.kt` | Implement delegate, handle result, format reply |
| `app/.../features/chat/ChatViewModelDependencies.kt` | Inject `StartTagScanUseCase` |
| `app/.../di/AppContainer.kt` | Construct `StartTagScanUseCase` |
| `app/.../features/chat/ChatScreen.kt` | Register & bind capability |

---

### Task 1: Add `AgentCommand.StartTagScan`

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt`

- [ ] **Step 1: Add data class after `GetGallerySummary`**

```kotlin
/**
 * 启动/控制/查询 TAG 扫描任务
 *
 * @property action 动作：start, pause, resume, cancel, query
 * @property taskType 扫描类别：face, scene, activity, objects, tags, summary, mlkit, auto
 * @property mode 扫描模式：full, incremental（仅 start 有效）
 */
data class StartTagScan(
    override val commandId: Int = AgentIdGenerator.nextId(),
    val action: String,
    val taskType: String? = null,
    val mode: String? = null
) : AgentCommand()
```

- [ ] **Step 2: Add method name mapping in `getMethodName`**

Add inside the `when` block:

```kotlin
is StartTagScan -> "start_tag_scan"
```

- [ ] **Step 3: Verify the file compiles**

Run: `./gradlew :runtime-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 2: Add `TagGenerationService` intent for category-based scan

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt`

- [ ] **Step 1: Add new action and extra constants in companion object**

Add after `ACTION_RETRY_FAILED`:

```kotlin
/** 按用户友好的类别启动 TAG 扫描 */
const val ACTION_START_TAG_SCAN = "com.mamba.picme.tag.START_TAG_SCAN"

private const val EXTRA_TASK_TYPE = "task_type"
private const val EXTRA_MODE = "mode"
```

- [ ] **Step 2: Add intent factory**

Add after `intentRetryFailed`:

```kotlin
/**
 * 按类别启动 TAG 扫描。
 *
 * @param taskType 逗号分隔的类别名，或 "AUTO"
 * @param mode "full" 或 "incremental"
 */
fun intentStartTagScan(
    context: Context,
    taskType: String,
    mode: String
): Intent = intent(context, ACTION_START_TAG_SCAN)
    .putExtra(EXTRA_TASK_TYPE, taskType)
    .putExtra(EXTRA_MODE, mode)
```

- [ ] **Step 3: Handle the new action in `onStartCommand`**

Add a new branch inside the `when (intent.action)` block before `ACTION_PAUSE`:

```kotlin
ACTION_START_TAG_SCAN -> {
    val taskType = intent.getStringExtra(EXTRA_TASK_TYPE) ?: "AUTO"
    val modeName = intent.getStringExtra(EXTRA_MODE) ?: "incremental"
    val mode = if (modeName.equals("full", ignoreCase = true)) {
        com.mamba.picme.domain.tag.scan.ScanMode.FULL
    } else {
        com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
    }

    if (taskType.equals("AUTO", ignoreCase = true)) {
        orch.scheduleAutoScan(com.mamba.picme.domain.tag.scan.ScanQueuePolicy())
    } else {
        val categoryNames = taskType.split(",").map { it.trim().uppercase() }
        val categories = categoryNames.mapNotNull { name ->
            runCatching { com.mamba.picme.domain.tag.TagCategory.valueOf(name) }.getOrNull()
        }.toSet()
        if (categories.isNotEmpty()) {
            orch.scheduleRegenerateByQuery(
                query = com.mamba.picme.domain.tag.scan.TagScanQuery(),
                categories = categories,
                mode = mode
            )
        }
    }
}
```

- [ ] **Step 4: Verify the service compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 3: Create domain models and `StartTagScanUseCase`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/usecase/StartTagScanUseCase.kt`

- [ ] **Step 1: Write the UseCase file**

```kotlin
package com.mamba.picme.domain.usecase

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.service.tag.TagGenerationService

/**
 * 启动、控制、查询 TAG 扫描任务。
 *
 * 所有调度请求都通过 [TagGenerationService] 的 Intent 机制下发，避免生命周期耦合。
 * 状态查询直接读取 Service 暴露的 StateFlow。
 */
class StartTagScanUseCase(
    private val context: Context
) {
    suspend operator fun invoke(
        action: String,
        taskType: String? = null,
        mode: String? = null
    ): StartTagScanResult {
        val normalizedAction = action.lowercase()
        return when (normalizedAction) {
            "start" -> start(taskType, mode)
            "pause" -> pause()
            "resume" -> resume()
            "cancel" -> cancel()
            "query" -> query()
            else -> StartTagScanResult.Error("不支持的 action: $action")
        }
    }

    private fun start(taskType: String?, mode: String?): StartTagScanResult {
        val resolvedType = taskType?.takeIf { it.isNotBlank() } ?: "auto"
        val resolvedMode = mode?.takeIf { it.isNotBlank() } ?: "incremental"

        // 确保 Service 已在前台运行
        ContextCompat.startForegroundService(
            context,
            Intent(context, TagGenerationService::class.java)
        )

        val intent = TagGenerationService.intentStartTagScan(
            context = context,
            taskType = resolvedType.lowercase(),
            mode = resolvedMode.lowercase()
        )
        ContextCompat.startForegroundService(context, intent)

        return StartTagScanResult.Started(
            taskType = resolvedType.lowercase(),
            mode = resolvedMode.lowercase(),
            message = "已启动 ${displayName(resolvedType)} 扫描（${displayMode(resolvedMode)}）"
        )
    }

    private fun pause(): StartTagScanResult {
        ContextCompat.startForegroundService(
            context,
            TagGenerationService.intentPause(context)
        )
        return StartTagScanResult.ControlAck(
            action = "pause",
            message = "扫描已暂停"
        )
    }

    private fun resume(): StartTagScanResult {
        ContextCompat.startForegroundService(
            context,
            TagGenerationService.intentResume(context)
        )
        return StartTagScanResult.ControlAck(
            action = "resume",
            message = "扫描已恢复"
        )
    }

    private fun cancel(): StartTagScanResult {
        ContextCompat.startForegroundService(
            context,
            TagGenerationService.intentCancel(context)
        )
        return StartTagScanResult.ControlAck(
            action = "cancel",
            message = "扫描已取消"
        )
    }

    private fun query(): StartTagScanResult {
        val progress = TagGenerationService.sessionProgress.value
            ?: return StartTagScanResult.Error("当前没有活跃的扫描会话")
        return StartTagScanResult.Status(
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

    private fun displayName(taskType: String): String = when (taskType.lowercase()) {
        "auto" -> "默认"
        "face" -> "人脸"
        "scene" -> "场景"
        "activity" -> "活动"
        "objects" -> "物体"
        "tags" -> "标签"
        "summary" -> "摘要"
        "mlkit" -> "ML Kit 标签"
        else -> taskType
    }

    private fun displayMode(mode: String): String = when (mode.lowercase()) {
        "full" -> "全量"
        else -> "增量"
    }
}

sealed class StartTagScanResult {
    data class Started(
        val taskType: String,
        val mode: String,
        val message: String
    ) : StartTagScanResult()

    data class ControlAck(
        val action: String,
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

- [ ] **Step 2: Verify the UseCase compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 4: Create `ChatStartTagScanCapability`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/capability/ChatStartTagScanCapability.kt`

- [ ] **Step 1: Write the capability file**

```kotlin
package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.usecase.StartTagScanResult
import java.lang.ref.WeakReference

/**
 * Chat 场景 TAG 扫描控制 Capability。
 *
 * 职责：把 start_tag_scan 命令暴露给 LLM，并回调给 [Delegate] 执行。
 */
class ChatStartTagScanCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatStartTagScanCapability? = null
        fun getInstance(): ChatStartTagScanCapability =
            instance ?: synchronized(this) {
                instance ?: ChatStartTagScanCapability().also { instance = it }
            }
    }

    private val tag = "ChatStartTagScanCapability"

    override val name: String = "chat_start_tag_scan"
    override val description: String = "在聊天中启动、暂停、恢复、取消或查询 TAG 扫描任务"

    interface Delegate {
        suspend fun onStartTagScan(
            action: String,
            taskType: String?,
            mode: String?
        ): StartTagScanResult
    }

    private var delegateRef: WeakReference<Delegate>? = null

    fun bindDelegate(delegate: Delegate) {
        delegateRef = WeakReference(delegate)
        Logger.i(tag, "Delegate bound")
    }

    fun unbindDelegate() {
        delegateRef = null
        Logger.i(tag, "Delegate unbound")
    }

    override fun isAvailable(): Boolean = delegateRef?.get() != null

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf("start_tag_scan")

    override fun getCommandDescription(command: String): String = when (command) {
        "start_tag_scan" -> "启动/控制/查询 TAG 扫描。参数: action=start|pause|resume|cancel|query, task_type=face|scene|activity|objects|tags|summary|mlkit|auto, mode=full|incremental"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val d = delegateRef?.get()
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    message = "TAG 扫描控制暂不可用（聊天页未激活）"
                )
            )

        return try {
            when (command) {
                is AgentCommand.StartTagScan -> {
                    val result = d.onStartTagScan(
                        action = command.action,
                        taskType = command.taskType,
                        mode = command.mode
                    )
                    Result.success(result.toAgentAction(command.commandId))
                }
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatStartTagScanCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Start tag scan failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "扫描控制失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }
}

private fun StartTagScanResult.toAgentAction(commandId: Int): AgentAction {
    return when (this) {
        is StartTagScanResult.Started -> AgentAction.TextReply(
            commandId = commandId,
            message = message
        )
        is StartTagScanResult.ControlAck -> AgentAction.TextReply(
            commandId = commandId,
            message = message
        )
        is StartTagScanResult.Status -> AgentAction.TextReply(
            commandId = commandId,
            message = buildString {
                append("当前扫描状态：${state}")
                if (currentPass != null) append("，阶段：$currentPass")
                append("，进度：${processed}/${total}")
                if (failed > 0) append("，失败：${failed}")
                if (pending > 0) append("，待处理：${pending}")
                if (estimatedRemainingMs != null) append("，预计剩余：${estimatedRemainingMs / 1000}秒")
            }
        )
        is StartTagScanResult.Error -> AgentAction.Error(
            commandId = commandId,
            errorCode = AgentErrorCode.INVALID_PARAMS,
            message = error
        )
    }
}
```

- [ ] **Step 2: Verify the capability compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 5: Update command parsers

#### 5a. Local command parser

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt`

- [ ] **Step 1: Add parsing branch in `parseCommandByMethod`**

Add after the `get_gallery_summary` branch:

```kotlin
// ===== TAG 扫描控制命令 =====
"start_tag_scan" -> {
    val action = extractJsonField(json, "action") ?: "query"
    val taskType = extractJsonField(json, "task_type")
    val mode = extractJsonField(json, "mode")
    AgentCommand.StartTagScan(
        commandId = commandId,
        action = action,
        taskType = taskType,
        mode = mode
    )
}
```

- [ ] **Step 2: Verify `:runtime-core` compiles**

Run: `./gradlew :runtime-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

#### 5b. Tool call parser

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/parser/ToolCallCommandParser.kt`

- [ ] **Step 1: Add dispatch branch in `parse`**

Add after `"get_gallery_summary" -> parseGetGallerySummary(args)`:

```kotlin
"start_tag_scan" -> parseStartTagScan(args)
```

- [ ] **Step 2: Add parser function at the end of the file**

```kotlin
private fun parseStartTagScan(args: JSONObject): AgentCommand.StartTagScan {
    return AgentCommand.StartTagScan(
        action = args.optString("action", "query"),
        taskType = args.optString("task_type", "auto").takeIf { it.isNotEmpty() },
        mode = args.optString("mode", "incremental").takeIf { it.isNotEmpty() }
    )
}
```

- [ ] **Step 3: Verify `:runtime-core` compiles**

Run: `./gradlew :runtime-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 6: Wire `StartTagScanUseCase` into dependencies

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`

- [ ] **Step 1: Add the UseCase to dependencies**

```kotlin
import com.mamba.picme.domain.usecase.StartTagScanUseCase

class ChatViewModelDependencies(
    val context: Context,
    val chatMessageDao: ChatMessageDao,
    val chatSessionDao: ChatSessionDao,
    val userSettingsRepository: UserSettingsRepository,
    val mediaSearchEngine: MediaSearchEngine,
    val mediaFeedbackRepository: MediaFeedbackRepository,
    val picMeAuthClient: PoLangAuthClient,
    val getGallerySummaryUseCase: GetGallerySummaryUseCase,
    val startTagScanUseCase: StartTagScanUseCase
)
```

- [ ] **Step 2: Construct the UseCase in `AppContainer`**

Add after `getGallerySummaryUseCase` lazy block:

```kotlin
private val startTagScanUseCase: StartTagScanUseCase by lazy {
    StartTagScanUseCase(context = context)
}
```

Update `chatViewModelDependencies` to pass it:

```kotlin
private val chatViewModelDependencies: ChatViewModelDependencies by lazy {
    ChatViewModelDependencies(
        context = context,
        chatMessageDao = database.chatMessageDao(),
        chatSessionDao = database.chatSessionDao(),
        userSettingsRepository = userPreferencesRepository,
        mediaSearchEngine = mediaSearchEngine,
        mediaFeedbackRepository = mediaFeedbackRepository,
        picMeAuthClient = PoLangAuthClient(),
        getGallerySummaryUseCase = getGallerySummaryUseCase,
        startTagScanUseCase = startTagScanUseCase
    )
}
```

- [ ] **Step 3: Verify `:app` compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 7: Update `ChatViewModel`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 1: Add interface implementation and import**

Add imports:

```kotlin
import com.mamba.picme.domain.usecase.StartTagScanResult
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import com.mamba.picme.features.chat.capability.ChatStartTagScanCapability
```

Update class signature:

```kotlin
class ChatViewModel(
    dependencies: ChatViewModelDependencies
) : ViewModel(),
    ChatSearchCapability.Delegate,
    ChatGallerySummaryCapability.Delegate,
    ChatStartTagScanCapability.Delegate {
```

Add property:

```kotlin
private val startTagScanUseCase = dependencies.startTagScanUseCase
```

- [ ] **Step 2: Implement delegate method**

Add after `onGetGallerySummary`:

```kotlin
// ── ChatStartTagScanCapability.Delegate：TAG 扫描控制 ─────────────

override suspend fun onStartTagScan(
    action: String,
    taskType: String?,
    mode: String?
): StartTagScanResult {
    return startTagScanUseCase(action = action, taskType = taskType, mode = mode)
}
```

- [ ] **Step 3: Ensure `describeCommandResult` handles `StartTagScan`**

It already falls through to `"✅ 已执行 ${AgentCommand.getMethodName(command)}"`, which is sufficient. If you want a friendlier message, add:

```kotlin
is AgentCommand.StartTagScan -> "✅ 已执行扫描控制"
```

- [ ] **Step 4: Verify `:app` compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 8: Update `ChatScreen`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: Add import**

```kotlin
import com.mamba.picme.features.chat.capability.ChatStartTagScanCapability
```

- [ ] **Step 2: Register capability**

Add after `RegisterCapability(ChatGallerySummaryCapability.getInstance())`:

```kotlin
RegisterCapability(ChatStartTagScanCapability.getInstance())
```

- [ ] **Step 3: Bind delegate**

Add after the `ChatGallerySummaryCapability` binding block:

```kotlin
// 绑定 ChatStartTagScanCapability Delegate
DisposableEffect(Unit) {
    ChatStartTagScanCapability.getInstance().bindDelegate(viewModel)
    onDispose { ChatStartTagScanCapability.getInstance().unbindDelegate() }
}
```

- [ ] **Step 4: Verify `:app` compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 9: Update `LocalPromptBuilder`

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/prompt/LocalPromptBuilder.kt`

- [ ] **Step 1: Add `start_tag_scan` to chat L2 capability section**

In `buildL2CapabilitiesSection`, add after the `ai_optimize` line inside `if (scene == null || scene == SceneManager.Scene.CHAT)`:

```kotlin
appendLine("start_tag_scan(action=start|pause|resume|cancel|query, task_type=face|scene|activity|objects|tags|summary|mlkit|auto, mode=full|incremental): 启动或控制本地 TAG 扫描。用户说'扫描照片''开始人脸分组''继续扫描''取消扫描''扫描进度'时调用。未指定类别用 auto，未指定模式用 incremental。")
```

- [ ] **Step 2: Add `start_tag_scan` to chat base prompt**

In `chatBasePrompt`, add `start_tag_scan` to the available commands list and add an example:

Replace the `【可用命令】` block in `chatBasePrompt` with:

```text
【可用命令】
- text_reply(params.message): 闲聊、问答、解释、不知道说什么
- navigate_to(params.destination=camera|gallery|settings|debug): 页面导航
- go_back: 返回上一页
- launch_app(params.package_name|app_name): 打开本机应用
- open_system_settings(params.setting=wifi|bluetooth|display|location|app_notifications): 打开系统设置
- start_tag_scan(params.action, params.task_type, params.mode): 启动/控制/查询 TAG 扫描
```

Add to `【字段约束】`:

```text
- params 只允许：destination, package_name, app_name, setting, message, action, task_type, mode。
```

Add example:

```text
「帮我扫描照片」→ [{"method":"start_tag_scan","params":{"action":"start","task_type":"auto","mode":"incremental"}}]
「扫描进度怎么样」→ [{"method":"start_tag_scan","params":{"action":"query"}}]
```

- [ ] **Step 3: Add `start_tag_scan` to base prompt field whitelist**

In `basePrompt`, update the `【字段约束】` line:

```text
- params 中只允许这些键：smoothing, whitening, slim_face, big_eyes, lip_color, blush, eyebrow, filter, style, scene, ratio, exposure, zoom, mode, destination, package_name, app_name, activity_class, setting, action, target, text, message, delay_ms, constraint, image_uri, task_type。
```

- [ ] **Step 4: Verify `:runtime-core` compiles**

Run: `./gradlew :runtime-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 10: Update existing tests that construct `ChatViewModelDependencies`

**Files:**
- Search for all `ChatViewModelDependencies(` instantiations in `app/src/test/...`

- [ ] **Step 1: Add `startTagScanUseCase = StartTagScanUseCase(context)` to each instantiation**

Example test update:

```kotlin
import com.mamba.picme.domain.usecase.StartTagScanUseCase

ChatViewModelDependencies(
    context = context,
    chatMessageDao = chatMessageDao,
    chatSessionDao = chatSessionDao,
    userSettingsRepository = mockk(relaxed = true),
    mediaSearchEngine = mockk(relaxed = true),
    mediaFeedbackRepository = mockk(relaxed = true),
    picMeAuthClient = mockk(relaxed = true),
    getGallerySummaryUseCase = mockk(relaxed = true),
    startTagScanUseCase = StartTagScanUseCase(context)
)
```

- [ ] **Step 2: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (tests may be mockk-based; if they fail only due to unrelated changes, note it)

---

### Task 11: Full build and smoke test

- [ ] **Step 1: Assemble debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install and smoke test**

1. Install APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Open Chat page.
3. Send: "帮我扫描照片" → expect `start_tag_scan(action=start, task_type=auto)` command and reply "已启动默认扫描".
4. Send: "扫描进度怎么样" → expect progress reply with processed/total.
5. Send: "暂停扫描" → expect "扫描已暂停".
6. Send: "恢复扫描" → expect "扫描已恢复".
7. Send: "取消扫描" → expect "扫描已取消".

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(chat): add start_tag_scan tool for TAG scan control

- Add AgentCommand.StartTagScan and parser support
- Add TagGenerationService ACTION_START_TAG_SCAN for category-based scans
- Add StartTagScanUseCase to route intents and query progress
- Add ChatStartTagScanCapability bound to ChatViewModel/ChatScreen
- Update LocalPromptBuilder with start_tag_scan schema and examples"
```

---

## Self-review checklist

- [ ] **Spec coverage:**
  - `start_tag_scan` single tool with action/start/pause/resume/cancel/query → Task 1, 5, 7
  - User-facing categories face|scene|activity|objects|tags|summary|mlkit|auto → Task 2, 3
  - `auto` = FACE+SCENE+ACTIVITY+OBJECTS+TAGS+SUMMARY incremental → Task 2
  - Query returns current session `TagScanSessionProgress` → Task 3 via StateFlow
  - Error handling returns `Error` result for LLM to relay → Task 3, 4
  - Prompt rules guide LLM when to call the tool → Task 9

- [ ] **Placeholder scan:** No TODO/TBD/similar-to references; all code shown.

- [ ] **Type consistency:**
  - `AgentCommand.StartTagScan` fields: `action`, `taskType`, `mode`.
  - Parsers use the same field names.
  - UseCase accepts the same names.
  - Capability passes them through unchanged.

- [ ] **Known deviation from spec:** The implementation routes start/pause/resume/cancel through `TagGenerationService` intents rather than calling `TagScanOrchestrator` directly. This avoids lifecycle coupling (Service owns the orchestrator instance) and matches the existing architecture. The observable behavior and query data are identical to the spec.
