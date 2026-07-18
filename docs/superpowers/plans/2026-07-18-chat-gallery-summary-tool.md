# Chat 相册摘要工具 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Chat 页给 LLM 提供本地相册摘要（照片/视频/人脸/人物/已打标/未打标/扫描建议），并在冷启动无数据时引导用户启动 TAG 扫描。

**Architecture:** 在 `:runtime-core` 定义 `GallerySummary` 数据类与 `GetGallerySummary` 命令；`:app` 提供 `GetGallerySummaryUseCase` 查询 Room 并计算推荐；通过 `ChatGallerySummaryCapability` 把能力注入 Chat 场景，同时给 `LocalPromptBuilder` 的 state section 追加摘要文本；`PoLangToolService` 新增同名 `@Tool` 供飞书 ReAct 复用。

**Tech Stack:** Kotlin, Room, Kotlin Coroutines/Flow, Compose, Hilt-less manual DI (`AppContainer`), LangChain4j `@Tool` annotations.

---

## Pre-Read Files

Before touching code, read these files to understand existing patterns:

- `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt` — `AgentContext` definition.
- `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt` — sealed command class and `getMethodName` mapping.
- `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt` — method → command parsing.
- `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/prompt/LocalPromptBuilder.kt` — state section builder.
- `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/parser/ToolCallCommandParser.kt` — OpenAI tool_calls parsing.
- `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/PoLangToolService.kt` — `@Tool` service.
- `runtime-core/src/main/java/com/mamba/picme/agent/core/capability/BaseCapability.kt` — capability base class.
- `app/src/main/java/com/mamba/picme/features/chat/capability/ChatSearchCapability.kt` — reference Chat capability pattern.
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` — where delegate methods and `AgentContext` are built.
- `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` — where capabilities are registered/bound.
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt` — dependency bag for `ChatViewModel`.
- `app/src/main/java/com/mamba/picme/di/AppContainer.kt` — where `ChatViewModelDependencies` is constructed.
- `app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt` — `TagScanDbStats` and `getDbStats(db)`.
- `app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt` — `isScanning` / `sessionProgress` StateFlow.
- `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt` — existing count queries.
- `app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt` — has `type: MediaType` (`PHOTO`, `VIDEO`, `DOCUMENT`).

---

## Task 1: Add `GallerySummary` data model in `:runtime-core`

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/GallerySummary.kt`

- [ ] **Step 1: Write the data class**

```kotlin
package com.mamba.picme.agent.core.model.context

/**
 * 本地相册摘要，供 LLM 在 Chat 中感知相册状态。
 *
 * 所有数字均为计数；不包含任何媒体 URI 或 embedding 等大字段，保持轻量。
 */
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
    val recommendation: ScanRecommendation,
    /** 命令请求时是否包含 details；仅影响格式化输出，不影响统计口径。 */
    val includeDetails: Boolean = false
) {
    enum class ScanRecommendation {
        NONE,
        INCREMENTAL,
        PASS3_FULL,
        PASS1_FIRST
    }
}
```

- [ ] **Step 2: Verify no compilation issues**

This file has no external dependencies beyond `kotlin`/`kotlinx`; no build step needed yet.

---

## Task 2: Add `GetGallerySummary` command

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt`

- [ ] **Step 1: Add the command data class inside `sealed class AgentCommand`**

Insert after the `AiOptimize` block (around line 322), before `// ==================== 系统/外部 App 命令 ====================`:

```kotlin
    /**
     * 获取本地相册摘要
     *
     * @property includeDetails 是否返回包含剩余任务数的完整摘要
     */
    data class GetGallerySummary(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val includeDetails: Boolean = false
    ) : AgentCommand()
```

- [ ] **Step 2: Register method name in `getMethodName`**

Add a branch in the `when` expression of `getMethodName` (around line 439), after `is AiOptimize -> "ai_optimize"`:

```kotlin
            is GetGallerySummary -> "get_gallery_summary"
```

- [ ] **Step 3: Verify command compiles**

No standalone build needed yet; will compile with Task 8.

---

## Task 3: Parse `get_gallery_summary` in local parser

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt`

- [ ] **Step 1: Add parsing branch in `parseCommandByMethod`**

Insert after the `ai_optimize` branch (around line 454), before `// ===== 设置命令 =====`:

```kotlin
            // ===== 相册摘要命令 =====
            "get_gallery_summary" -> {
                val includeDetails = extractJsonBoolean(json, "include_details") ?: false
                AgentCommand.GetGallerySummary(
                    commandId = commandId,
                    includeDetails = includeDetails
                )
            }
```

- [ ] **Step 2: Verify parser compiles**

Will be verified in Task 8.

---

## Task 4: Parse `get_gallery_summary` in remote tool-call parser

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/parser/ToolCallCommandParser.kt`

- [ ] **Step 1: Add branch in `parse`**

Insert after `"ai_optimize" -> parseAiOptimize(args)` (around line 69), before `// Gallery 命令`:

```kotlin
            "get_gallery_summary" -> parseGetGallerySummary(args)
```

- [ ] **Step 2: Add helper function**

Insert after `parseAiOptimize` (around line 180):

```kotlin
    private fun parseGetGallerySummary(args: JSONObject): AgentCommand.GetGallerySummary {
        return AgentCommand.GetGallerySummary(
            includeDetails = args.optBoolean("include_details", false)
        )
    }
```

- [ ] **Step 3: Verify parser compiles**

Will be verified in Task 8.

---

## Task 5: Extend `AgentContext` with gallery summary

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt`

- [ ] **Step 1: Add `gallerySummary` property**

Add to `AgentContext` data class (after `lastUserImageUri`, around line 36):

```kotlin
    /** 当前相册摘要，供 LLM 回答「有多少照片/人脸/是否需扫描」等问题 */
    val gallerySummary: GallerySummary? = null
```

- [ ] **Step 2: Verify model compiles**

Will be verified in Task 8.

---

## Task 6: Format gallery summary in prompt

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/prompt/LocalPromptBuilder.kt`

- [ ] **Step 1: Add helper to format `GallerySummary`**

Add a private method near `buildSearchResultsSection` (after line 507):

```kotlin
    private fun formatGallerySummary(summary: GallerySummary?): String {
        if (summary == null || summary.totalMedia == 0) {
            return "{status:no_data}"
        }
        return buildString {
            append("{totalMedia:${summary.totalMedia}")
            append(",photos:${summary.totalPhotos}")
            append(",videos:${summary.totalVideos}")
            append(",faces:${summary.hasFaceCount}")
            append(",persons:${summary.personClusterCount}")
            append(",named:${summary.namedPersonCount}")
            append(",labeled:${summary.labeledCount}")
            append(",unlabeled:${summary.unlabeledCount}")
            append(",mlKit:${summary.mlKitLabeledCount}")
            append(",semantic:${summary.semanticEncodedCount}")
            append(",scanning:${if (summary.isScanning) "1" else "0"}")
            append(",recommendation:${summary.recommendation.name}")
            if (summary.currentPass != null) {
                append(",currentPass:${summary.currentPass}")
            }
            if (summary.scanProgressText != null) {
                append(",progress:\"${summary.scanProgressText}\"")
            }
            if (summary.includeDetails) {
                append(",remainingPass1:${summary.remainingPass1}")
                append(",remainingPass3:${summary.remainingPass3}")
                append(",remainingMlKit:${summary.remainingMlKit}")
            }
            append("}")
        }
    }
```

- [ ] **Step 2: Inject summary into `buildStateSection`**

Modify `buildStateSection` (around line 491) to append the summary after `last_user_image_uri`:

Replace:
```kotlin
            append(", last_user_image_uri=")
            append(context.lastUserImageUri ?: "null")
            append(buildSearchResultsSection(context.recentSearchResults))
```

With:
```kotlin
            append(", last_user_image_uri=")
            append(context.lastUserImageUri ?: "null")
            append(", gallery_summary=")
            append(formatGallerySummary(context.gallerySummary))
            append(buildSearchResultsSection(context.recentSearchResults))
```

- [ ] **Step 3: Add prompt instructions for no-data guidance**

In `chatBasePrompt` (around line 145), add after the existing rules:

```markdown
【相册摘要使用规则】
- 当前相册摘要见【当前状态】中的 gallery_summary。
- 用户问照片数量、人脸数量、是否需要扫描时，直接根据 gallery_summary 回答。
- 如果 gallery_summary={status:no_data}，说明相册尚未完成首次扫描，请友好地告诉用户“还没有照片数据，可能需要先同步相册或启动 TAG 扫描”，并询问是否需要前往 TAG 生成控制页开始扫描。
```

In `buildL2SystemPrompt` (around line 312), after the navigation/system/delay lines and before `【语义映射】`, add:

```kotlin
            appendLine("${if (isChatScene) 9 else 11}. 【相册摘要】gallery_summary 见【当前状态】；用户问照片/人脸/扫描建议时直接引用该摘要。status=no_data 时引导启动 TAG 扫描。")
```

Adjust the hard-coded numbering of subsequent lines (`${if (isChatScene) 5 else 7}` etc.) by incrementing their base numbers by 1.

- [ ] **Step 4: Verify prompt builder compiles**

Will be verified in Task 8.

---

## Task 7: Add DAO count queries for photos and videos

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt`

- [ ] **Step 1: Add count queries by media type**

Insert after `getTotalCount()` (around line 110):

```kotlin
    /** 获取照片数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE type = 'PHOTO'")
    suspend fun getPhotoCount(): Int

    /** 获取视频数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE type = 'VIDEO'")
    suspend fun getVideoCount(): Int
```

- [ ] **Step 2: Verify DAO compiles**

Will be verified in Task 8.

---

## Task 8: Implement `GetGallerySummaryUseCase`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/usecase/GetGallerySummaryUseCase.kt`

- [ ] **Step 1: Write the UseCase**

```kotlin
package com.mamba.picme.domain.usecase

import android.content.Context
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 获取本地相册摘要。
 *
 * - 读取-only，不写数据库，不触发扫描。
 * - 全部使用 COUNT(*) 查询 + StateFlow 读取，目标耗时 < 50ms。
 * - 返回 null 表示读取失败（如 Room 异常），上层应按“无数据”处理。
 */
class GetGallerySummaryUseCase(
    private val context: Context,
    private val db: AppDatabase
) {
    suspend operator fun invoke(includeDetails: Boolean = false): GallerySummary? = withContext(Dispatchers.IO) {
        runCatching {
            val stats = TagScanOrchestrator.getDbStats(db)
            val progress = TagGenerationService.sessionProgress.value
            val isScanning = TagGenerationService.isScanning.value

            val recommendation = when {
                stats.totalMedia > 0 && stats.remainingForPass1 > stats.totalMedia * 0.1 ->
                    GallerySummary.ScanRecommendation.PASS1_FIRST
                stats.totalMedia > 0 && stats.remainingForPass3 > stats.totalMedia * 0.3 ->
                    GallerySummary.ScanRecommendation.PASS3_FULL
                stats.remainingForPass3 > 0 ->
                    GallerySummary.ScanRecommendation.INCREMENTAL
                else ->
                    GallerySummary.ScanRecommendation.NONE
            }

            val currentPass = progress?.currentPass?.name
            val scanProgressText = if (isScanning && progress != null) {
                "${progress.processed}/${progress.total}"
            } else null

            GallerySummary(
                totalPhotos = db.mediaDao().getPhotoCount(),
                totalVideos = db.mediaDao().getVideoCount(),
                totalMedia = stats.totalMedia,
                hasFaceCount = stats.withFace,
                personClusterCount = stats.personCount,
                namedPersonCount = stats.namedPersonCount,
                labeledCount = stats.withLabels,
                unlabeledCount = stats.remainingForPass3,
                mlKitLabeledCount = stats.withMlKitLabels,
                semanticEncodedCount = stats.withSemantic,
                remainingPass1 = stats.remainingForPass1,
                remainingPass3 = stats.remainingForPass3,
                remainingMlKit = stats.remainingForMlKit,
                isScanning = isScanning,
                currentPass = currentPass,
                scanProgressText = scanProgressText,
                recommendation = recommendation,
                includeDetails = includeDetails
            )
        }.getOrNull()
    }
}
```

Wait — `GallerySummary` does not have an `includeDetails` field. Remove that assignment from the constructor call. The prompt formatter decides whether to include details based on `summary.includeDetails`, so keep the parameter in `invoke()` but do not pass it to the data class; instead store it in a local and use it only when building the formatted string. However, `formatGallerySummary` currently reads `summary.includeDetails`. That is fine because the data class already has `includeDetails: Boolean` in the spec? Re-check Task 1: the spec did not include `includeDetails` in `GallerySummary`. To keep it simple, **add `includeDetails` to `GallerySummary`** as an internal metadata field in Task 1, so the formatter can read it. Update Task 1 data class:

```kotlin
data class GallerySummary(
    ...,
    val recommendation: ScanRecommendation,
    /** 命令请求时是否包含 details；仅影响格式化输出，不影响统计口径。 */
    val includeDetails: Boolean = false
)
```

Then the UseCase can pass `includeDetails = includeDetails`.

- [ ] **Step 2: Verify UseCase compiles**

Will be verified in Task 13.

---

## Task 9: Add `ChatGallerySummaryCapability`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/capability/ChatGallerySummaryCapability.kt`

- [ ] **Step 1: Write the capability**

```kotlin
package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import java.lang.ref.WeakReference

/**
 * Chat 场景相册摘要 Capability。
 *
 * 职责：在 CHAT 场景暴露 `get_gallery_summary`，把命令回调给 [Delegate]（ChatViewModel）执行。
 */
class ChatGallerySummaryCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatGallerySummaryCapability? = null

        fun getInstance(): ChatGallerySummaryCapability =
            instance ?: synchronized(this) {
                instance ?: ChatGallerySummaryCapability().also { instance = it }
            }
    }

    private val tag = "ChatGallerySummaryCapability"

    override val name: String = "chat_gallery_summary"
    override val description: String = "在聊天中获取本地相册摘要，包括照片数、人脸数、人物数、已/未打标数量以及扫描建议"

    interface Delegate {
        suspend fun onGetGallerySummary(includeDetails: Boolean): GallerySummary?
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

    override fun supportedCommands(): List<String> = listOf("get_gallery_summary")

    override fun getCommandDescription(command: String): String = when (command) {
        "get_gallery_summary" -> "获取本地相册摘要，参数: include_details (boolean, 默认 false)"
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
                    message = "相册摘要暂不可用（聊天页未激活）"
                )
            )
        return try {
            when (command) {
                is AgentCommand.GetGallerySummary -> {
                    val summary = d.onGetGallerySummary(command.includeDetails)
                    val message = summary?.let { formatSummaryForReply(it) }
                        ?: "我还没拿到你的相册数据，可能是首次使用或尚未完成同步。我可以帮你启动 TAG 扫描，让人脸、场景和物体标签都生成出来。要开始吗？"
                    Result.success(
                        AgentAction.TextReply(
                            commandId = command.commandId,
                            message = message
                        )
                    )
                }
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatGallerySummaryCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Get gallery summary failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "获取相册摘要失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }

    private fun formatSummaryForReply(summary: GallerySummary): String {
        return buildString {
            append("当前相册共有 ${summary.totalMedia} 个媒体")
            if (summary.totalPhotos > 0 || summary.totalVideos > 0) {
                append("（${summary.totalPhotos} 张照片")
                if (summary.totalVideos > 0) append("，${summary.totalVideos} 个视频")
                append("）")
            }
            append("；检测到 ${summary.hasFaceCount} 张含人脸的照片，聚类出 ${summary.personClusterCount} 个人物")
            if (summary.namedPersonCount > 0) {
                append("（其中 ${summary.namedPersonCount} 个已命名）")
            }
            append("。已打标 ${summary.labeledCount} 张，未打标 ${summary.unlabeledCount} 张")
            when (summary.recommendation) {
                GallerySummary.ScanRecommendation.NONE -> append("。目前状态良好，无需扫描。")
                GallerySummary.ScanRecommendation.INCREMENTAL -> append("。建议运行增量扫描补齐未打标照片。")
                GallerySummary.ScanRecommendation.PASS3_FULL -> append("。未打标比例较高，建议执行 Pass 3 全量扫描。")
                GallerySummary.ScanRecommendation.PASS1_FIRST -> append("。大量照片尚未完成人脸检测，建议先执行 Pass 1 扫描。")
            }
            if (summary.isScanning) {
                append(" [当前扫描中")
                summary.currentPass?.let { append(" · $it") }
                summary.scanProgressText?.let { append(" · $it") }
                append("]")
            }
        }
    }
}
```

- [ ] **Step 2: Verify capability compiles**

Will be verified in Task 13.

---

## Task 10: Wire dependencies into `ChatViewModelDependencies` and `AppContainer`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`

- [ ] **Step 1: Add `getGallerySummaryUseCase` to dependency bag**

In `ChatViewModelDependencies`, add:

```kotlin
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase

class ChatViewModelDependencies(
    ...,
    val getGallerySummaryUseCase: GetGallerySummaryUseCase
)
```

- [ ] **Step 2: Construct the UseCase in `AppContainerImpl`**

In `AppContainerImpl`, add a lazy property:

```kotlin
    private val getGallerySummaryUseCase: GetGallerySummaryUseCase by lazy {
        GetGallerySummaryUseCase(context = context, db = database)
    }
```

Then update `chatViewModelDependencies` (around line 449) to pass it:

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
            getGallerySummaryUseCase = getGallerySummaryUseCase
        )
    }
```

- [ ] **Step 3: Verify DI compiles**

Will be verified in Task 13.

---

## Task 11: Update `ChatViewModel` to provide gallery summary

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 1: Implement `ChatGallerySummaryCapability.Delegate`**

Change class declaration (line 73):

```kotlin
class ChatViewModel(
    dependencies: ChatViewModelDependencies
) : ViewModel(), ChatSearchCapability.Delegate, ChatGallerySummaryCapability.Delegate {
```

Add import:

```kotlin
import com.mamba.picme.features.chat.capability.ChatGallerySummaryCapability
import com.mamba.picme.agent.core.model.context.GallerySummary
```

Add property:

```kotlin
    private val getGallerySummaryUseCase = dependencies.getGallerySummaryUseCase
```

- [ ] **Step 2: Inject summary into `AgentContext` in `sendMessage`**

In `sendMessage()` (around line 384), before building `agentContext`, fetch the summary:

```kotlin
                // 3.5 获取相册摘要并注入上下文
                val gallerySummary = getGallerySummaryUseCase(includeDetails = false)

                // 4. 构建 Agent 上下文
                val agentContext = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId,
                    recentSearchResults = sessionSearchSnapshots[sessionId].orEmpty(),
                    lastUserImageUri = _lastUserImageUri.value,
                    gallerySummary = gallerySummary
                )
```

Renumber the existing comments `// 4. ...` → `// 5. ...` etc. in this function.

- [ ] **Step 3: Implement delegate method**

Add near the other ChatSearchCapability delegate methods (after `onExcludeConstraint`, around line 917):

```kotlin
    // ── ChatGallerySummaryCapability.Delegate：相册摘要 ─────────────

    override suspend fun onGetGallerySummary(includeDetails: Boolean): GallerySummary? {
        return getGallerySummaryUseCase(includeDetails)
    }
```

- [ ] **Step 4: Handle `AgentAction.TextReply` from gallery summary command**

No extra code needed; `handleAgentAction` already handles `AgentAction.TextReply` by inserting the message.

- [ ] **Step 5: Verify ViewModel compiles**

Will be verified in Task 13.

---

## Task 12: Register and bind capability in `ChatScreen`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: Add import**

```kotlin
import com.mamba.picme.features.chat.capability.ChatGallerySummaryCapability
```

- [ ] **Step 2: Register the capability**

After `RegisterCapability(ChatSearchCapability.getInstance())` (line 281):

```kotlin
    RegisterCapability(ChatGallerySummaryCapability.getInstance())
```

- [ ] **Step 3: Bind the delegate**

After the `ChatSearchCapability` binding `DisposableEffect` (line 284), add:

```kotlin
    // 绑定 ChatGallerySummaryCapability Delegate
    DisposableEffect(Unit) {
        ChatGallerySummaryCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatGallerySummaryCapability.getInstance().unbindDelegate() }
    }
```

- [ ] **Step 4: Verify screen compiles**

Will be verified in Task 13.

---

## Task 13: Add `@Tool` and `callTool` branch in `PoLangToolService`

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/PoLangToolService.kt`

- [ ] **Step 1: Add `@Tool` method**

Insert after `searchPhotos` (around line 406), before `click_gallery_item`:

```kotlin
    @Tool(name = "get_gallery_summary", value = ["获取本地相册摘要，包括照片数、人脸数、人物数、已/未打标数量以及扫描建议。参数 include_details 为 true 时返回剩余 Pass 1/Pass 3/ML Kit 任务数。"])
    fun getGallerySummary(
        @P(name = "include_details", value = "是否返回包含剩余任务数的完整摘要，默认 false") includeDetails: Boolean = false
    ): String {
        return dispatchCommand(AgentCommand.GetGallerySummary(includeDetails = includeDetails))
    }
```

- [ ] **Step 2: Add `callTool` branch**

In `callTool` (around line 663), add after `search_photos`:

```kotlin
            "get_gallery_summary" -> getGallerySummary(
                includeDetails = args.optBoolean("include_details", false)
            )
```

- [ ] **Step 3: Verify service compiles**

Will be verified in Task 14.

---

## Task 14: Compile the project

**Files:** none

- [ ] **Step 1: Run debug compile**

Run:

```bash
./gradlew :app:compileDebugKotlin :runtime-core:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Fix any compile errors**

Common issues:
- Missing import for `GallerySummary` in `LocalPromptBuilder`.
- `includeDetails` not passed to `GallerySummary` constructor (must update Task 1 data class).
- `AgentCommand.GetGallerySummary` not handled exhaustively in `AgentCommand.getMethodName`.

---

## Task 15: Add unit/Android tests

**Files:**
- Create: `app/src/test/java/com/mamba/picme/domain/usecase/GetGallerySummaryUseCaseTest.kt`

- [ ] **Step 1: Write test for recommendation logic**

Since `GetGallerySummaryUseCase` uses `AppDatabase` and `TagGenerationService` singleton StateFlow, a pure JVM test is not trivial. Use an Android instrumented test instead.

Create: `app/src/androidTest/java/com/mamba/picme/domain/usecase/GetGallerySummaryUseCaseTest.kt`

```kotlin
package com.mamba.picme.domain.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.agent.core.model.context.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GetGallerySummaryUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var useCase: GetGallerySummaryUseCase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        useCase = GetGallerySummaryUseCase(context, db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun emptyGallery_returnsNoData(): Unit = runBlocking {
        val summary = useCase(includeDetails = true)
        assertEquals(0, summary?.totalMedia)
        assertEquals(0, summary?.unlabeledCount)
        assertEquals(GallerySummary.ScanRecommendation.NONE, summary?.recommendation)
    }

    @Test
    fun manyUnlabeled_recommendsPass3Full(): Unit = runBlocking {
        val media = (1..100).map {
            MediaEntity(
                id = 0,
                uri = "file:///test/$it.jpg",
                type = MediaType.PHOTO,
                captureDate = System.currentTimeMillis(),
                fileName = "$it.jpg"
            )
        }
        db.mediaDao().insertAll(media)

        val summary = useCase(includeDetails = true)
        assertEquals(100, summary?.totalMedia)
        assertEquals(100, summary?.unlabeledCount)
        assertEquals(GallerySummary.ScanRecommendation.PASS3_FULL, summary?.recommendation)
    }

    @Test
    fun fewUnlabeled_recommendsIncremental(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        val labeled = (1..90).map {
            MediaEntity(
                id = 0,
                uri = "file:///test/labeled_$it.jpg",
                type = MediaType.PHOTO,
                captureDate = now,
                fileName = "labeled_$it.jpg",
                labels = "[\"户外\"]"
            )
        }
        val unlabeled = (91..100).map {
            MediaEntity(
                id = 0,
                uri = "file:///test/unlabeled_$it.jpg",
                type = MediaType.PHOTO,
                captureDate = now,
                fileName = "unlabeled_$it.jpg"
            )
        }
        db.mediaDao().insertAll(labeled + unlabeled)

        val summary = useCase()
        assertEquals(100, summary?.totalMedia)
        assertEquals(10, summary?.unlabeledCount)
        assertEquals(GallerySummary.ScanRecommendation.INCREMENTAL, summary?.recommendation)
    }
}
```

Note: If `MediaDao.insertAll(List<MediaEntity>)` does not exist, use `mediaDao().insert(entity)` in a loop or check the actual DAO method name.

- [ ] **Step 2: Run instrumented tests**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.mamba.picme.domain.usecase.GetGallerySummaryUseCaseTest"
```

Expected: tests pass.

---

## Task 16: Install and manually verify

**Files:** none

- [ ] **Step 1: Build and install debug APK**

```bash
./gradlew :app:installDebug
```

Expected: INSTALL SUCCESS.

- [ ] **Step 2: Cold-start test**

1. Clear app data or use fresh install.
2. Open Chat.
3. Send: "我有多少张照片？"
4. Expected: LLM replies with guidance like "还没有照片数据……要不要启动 TAG 扫描？"

- [ ] **Step 3: Post-scan test**

1. Trigger TAG scan (via control page or command) and let it complete some items.
2. Return to Chat.
3. Send: "我有多少张照片？"
4. Expected: LLM replies with real counts and a scan recommendation.

- [ ] **Step 4: Active tool test**

Send: "获取相册摘要" or "summary".
Expected: LLM returns a structured summary message (via `ChatGallerySummaryCapability`).

---

## Task 17: Commit and push

**Files:** none

- [ ] **Step 1: Stage changes**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/GallerySummary.kt
```

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/GallerySummary.kt
```

Also stage all modified/new files:

```bash
git add -A
```

- [ ] **Step 2: Commit**

```bash
git commit -m "feat(chat): add gallery summary tool for chat and remote ReAct

- Add GallerySummary data model and GetGallerySummary command
- Inject gallery_summary into local prompt state section
- Add ChatGallerySummaryCapability + GetGallerySummaryUseCase
- Wire PoLangToolService get_gallery_summary for Feishu ReAct
- Guide users to start TAG scan on cold-start no_data"
```

- [ ] **Step 3: Push**

```bash
git push origin main
```

Expected: push succeeds.

---

## Self-Review Checklist

Before execution, verify:

1. **Spec coverage:**
   - `GallerySummary` data model → Task 1
   - `get_gallery_summary` command + parsers → Tasks 2-4
   - Prompt injection + no_data guidance → Task 6
   - UseCase with recommendation rules → Task 8
   - Chat capability + ViewModel binding → Tasks 9-11
   - Screen registration → Task 12
   - Remote tool reuse → Task 13
   - Tests → Task 15
   - Manual verification → Task 16

2. **Placeholder scan:** No "TBD", "TODO", "implement later", or "similar to Task X".

3. **Type consistency:**
   - `GallerySummary.includeDetails` exists and is used by formatter and UseCase.
   - `AgentCommand.GetGallerySummary.includeDetails` matches parser params.
   - `ScanRecommendation` enum values match in data class, UseCase, and formatter.

4. **Known gaps / follow-ups:**
   - `start_tag_scan` command is out of scope; this plan only provides guidance.
   - Fine-tuning recommendation thresholds (10% / 30%) is expected after user testing.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-18-chat-gallery-summary-tool.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
