# Chat 多轮图片发现：对话式反馈 Implementation Plan

> **For agentic workers:** REQUIRED SUB-LEVEL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Chat 场景支持自然语言对话反馈（👍/👎/🔁/🚫）与跨轮图片指代，LLM 通过 AgentCommand 解析意图，ViewModel 执行反馈并重排/搜索。

**Architecture:** 扩展 `AgentContext` 携带最近搜索结果快照，新增 `feedback`/`more`/`exclude` 三个短名 AgentCommand，复用 `ChatSearchCapability` 分发给 `ChatViewModel` 执行。点击按钮作为快速路径保留。

**Tech Stack:** Kotlin, Jetpack Compose, Room, MockK, JUnit4, Robolectric

---

## File Structure

| 文件 | 责任 |
|------|------|
| `runtime-core/.../model/context/AgentModels.kt` | `AgentContext` 加 `recentSearchResults`；新增 `SearchResultSnapshot`、`ResultItem` |
| `runtime-core/.../model/command/AgentCommands.kt` | 新增 `RecordMediaFeedback`、`MoreLikeThis`、`ExcludeConstraint`、`FeedbackTarget` |
| `runtime-core/.../model/command/AgentCommandExt.kt`（或原文件） | `getMethodName()` 映射短方法名 |
| `runtime-core/.../inference/local/prompt/LocalPromptBuilder.kt` | 加 schema、示例、状态片段 |
| `runtime-core/.../inference/local/parser/LocalCommandParser.kt` | 解析 `feedback`/`more`/`exclude` |
| `runtime-core/.../inference/remote/parser/ToolCallCommandParser.kt` | tool_calls 解析到新 AgentCommand |
| `runtime-core/.../inference/remote/prompt/RemotePromptBuilder.kt` | 状态片段加搜索结果 |
| `app/.../domain/search/FeedbackAction.kt` | **删除**（上移到 runtime-core） |
| `app/.../features/chat/capability/ChatSearchCapability.kt` | 支持新命令，扩展 Delegate |
| `app/.../features/chat/ChatViewModel.kt` | 构建快照、解析 target、执行命令、更新消息 |
| `app/.../domain/search/MediaFeedbackUseCase.kt` | 新增 exclude 约束接口 |
| `app/.../features/chat/model/MediaResultsUi.kt`（如存在）或 `ChatViewModel.kt` 内部 | `MediaResultsUi` 加 `highlightedMediaId` |
| `app/.../features/chat/components/MediaResultsCarousel.kt` | 高亮描边支持 |
| `app/src/main/res/values*/strings.xml` | 确认文案四语 |

---

### Task 1: 将 FeedbackAction 上移到 runtime-core

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/FeedbackAction.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/search/FeedbackAction.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/model/command/FeedbackActionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.model.command

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackActionTest {
    @Test
    fun ` FeedbackAction has LIKE DISLIKE MORE_LIKE_THIS`() {
        assertEquals(3, FeedbackAction.entries.size)
        assertEquals("LIKE", FeedbackAction.LIKE.name)
        assertEquals("DISLIKE", FeedbackAction.DISLIKE.name)
        assertEquals("MORE_LIKE_THIS", FeedbackAction.MORE_LIKE_THIS.name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.command.FeedbackActionTest"`
Expected: FAIL with `Class not found`

- [ ] **Step 3: Create FeedbackAction in runtime-core**

```kotlin
package com.mamba.picme.agent.core.model.command

enum class FeedbackAction {
    LIKE,
    DISLIKE,
    MORE_LIKE_THIS
}
```

- [ ] **Step 4: Delete old FeedbackAction in app**

Delete `app/src/main/java/com/mamba/picme/domain/search/FeedbackAction.kt`

- [ ] **Step 5: Verify existing app tests still compile**

Run: `./gradlew :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/FeedbackAction.kt
rm app/src/main/java/com/mamba/picme/domain/search/FeedbackAction.kt
git add -A
git commit -m "refactor(feedback): move FeedbackAction to runtime-core"
```

---

### Task 2: 扩展 AgentContext 携带搜索结果快照

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/model/context/AgentContextTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.model.context

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentContextTest {
    @Test
    fun `AgentContext can carry recent search snapshots`() {
        val snapshot = SearchResultSnapshot(
            query = "海边",
            results = listOf(ResultItem("m1", listOf("海", "日落"))),
            totalCount = 1,
            isRefinement = false,
            timestamp = 1234L
        )
        val ctx = AgentContext(
            scene = AgentScene.CHAT,
            recentSearchResults = listOf(snapshot)
        )
        assertEquals(1, ctx.recentSearchResults.size)
        assertEquals("海边", ctx.recentSearchResults[0].query)
        assertEquals("m1", ctx.recentSearchResults[0].results[0].mediaId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.context.AgentContextTest"`
Expected: FAIL with `Unresolved reference: SearchResultSnapshot`

- [ ] **Step 3: Add SearchResultSnapshot and ResultItem**

In `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt`, add to the same file:

```kotlin
data class SearchResultSnapshot(
    val query: String,
    val results: List<ResultItem>,
    val totalCount: Int,
    val isRefinement: Boolean,
    val timestamp: Long
)

data class ResultItem(
    val mediaId: String,
    val tags: List<String>
)
```

And update `AgentContext`:

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
    val recentSearchResults: List<SearchResultSnapshot> = emptyList()
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.context.AgentContextTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt
runtime-core/src/test/java/com/mamba/picme/agent/core/model/context/AgentContextTest.kt
git add -A
git commit -m "feat(chat): add SearchResultSnapshot to AgentContext"
```

---

### Task 3: 新增 FeedbackTarget 和 AgentCommand

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/model/command/AgentCommandsFeedbackTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.model.command

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentCommandsFeedbackTest {
    @Test
    fun `RecordMediaFeedback holds target action queryHint`() {
        val cmd = AgentCommand.RecordMediaFeedback(
            target = FeedbackTarget.Ordinal(3),
            action = FeedbackAction.LIKE,
            queryHint = "海边"
        )
        assertEquals(FeedbackTarget.Ordinal(3), cmd.target)
        assertEquals(FeedbackAction.LIKE, cmd.action)
        assertEquals("海边", cmd.queryHint)
    }

    @Test
    fun `getMethodName maps feedback commands to short names`() {
        assertEquals("feedback", AgentCommand.getMethodName(AgentCommand.RecordMediaFeedback(target = FeedbackTarget.LastShown, action = FeedbackAction.LIKE)))
        assertEquals("more", AgentCommand.getMethodName(AgentCommand.MoreLikeThis(target = FeedbackTarget.LastShown)))
        assertEquals("exclude", AgentCommand.getMethodName(AgentCommand.ExcludeConstraint(constraint = "夜景")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.command.AgentCommandsFeedbackTest"`
Expected: FAIL with `Unresolved reference: RecordMediaFeedback`

- [ ] **Step 3: Add FeedbackTarget and new commands**

Add to `AgentCommands.kt` before `sealed class AgentCommand` closing:

```kotlin
sealed interface FeedbackTarget {
    data class Ordinal(val index: Int) : FeedbackTarget
    data class Description(val text: String) : FeedbackTarget
    data class MediaId(val id: String) : FeedbackTarget
    data object LastShown : FeedbackTarget
}
```

Add new command classes inside `sealed class AgentCommand`:

```kotlin
data class RecordMediaFeedback(
    override val commandId: Int = AgentIdGenerator.nextId(),
    val target: FeedbackTarget,
    val action: FeedbackAction,
    val queryHint: String? = null
) : AgentCommand()

data class MoreLikeThis(
    override val commandId: Int = AgentIdGenerator.nextId(),
    val target: FeedbackTarget,
    val queryHint: String? = null
) : AgentCommand()

data class ExcludeConstraint(
    override val commandId: Int = AgentIdGenerator.nextId(),
    val constraint: String
) : AgentCommand()
```

Update `getMethodName()` mapping:

```kotlin
is RecordMediaFeedback -> "feedback"
is MoreLikeThis -> "more"
is ExcludeConstraint -> "exclude"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.command.AgentCommandsFeedbackTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt
runtime-core/src/test/java/com/mamba/picme/agent/core/model/command/AgentCommandsFeedbackTest.kt
git add -A
git commit -m "feat(chat): add RecordMediaFeedback, MoreLikeThis, ExcludeConstraint"
```

---

### Task 4: 更新 LocalPromptBuilder

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/prompt/LocalPromptBuilder.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/inference/local/prompt/LocalPromptBuilderChatSearchTest.kt`

- [ ] **Step 1: Write the failing test**

Append to existing `LocalPromptBuilderChatSearchTest`:

```kotlin
    @Test
    fun `CHAT scene advertises feedback more and exclude commands`() {
        val section = builder.buildL2CapabilitiesSection(SceneManager.Scene.CHAT)
        assertTrue("CHAT 应广告 feedback 命令，实际:\n$section", section.contains("feedback"))
        assertTrue("CHAT 应广告 more 命令，实际:\n$section", section.contains("more"))
        assertTrue("CHAT 应广告 exclude 命令，实际:\n$section", section.contains("exclude"))
    }

    @Test
    fun `buildStateSection includes recentSearchResults when present`() {
        val snapshot = com.mamba.picme.agent.core.model.context.SearchResultSnapshot(
            query = "海边日落",
            results = listOf(com.mamba.picme.agent.core.model.context.ResultItem("img_001", listOf("海", "日落", "沙滩"))),
            totalCount = 8,
            isRefinement = false,
            timestamp = 0L
        )
        val ctx = com.mamba.picme.agent.core.model.context.AgentContext(
            scene = com.mamba.picme.agent.core.model.context.AgentScene.CHAT,
            recentSearchResults = listOf(snapshot)
        )
        val state = builder.buildStateSection(ctx, SceneManager.Scene.CHAT)
        assertTrue("状态片段应包含 query，实际:\n$state", state.contains("海边日落"))
        assertTrue("状态片段应包含 mediaId，实际:\n$state", state.contains("img_001"))
        assertTrue("状态片段应包含 tags，实际:\n$state", state.contains("日落"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.local.prompt.LocalPromptBuilderChatSearchTest"`
Expected: FAIL with `feedback` not found

- [ ] **Step 3: Update basePrompt schema and allowed keys**

In `basePrompt`, after existing schema entries add:

```text
- feedback: {"method":"feedback","params":{"target":"ordinal:3|desc:海边|last","action":"like|dislike"}}
- more: {"method":"more","params":{"target":"ordinal:3|desc:海边|last"}}
- exclude: {"method":"exclude","params":{"constraint":"夜景"}}
```

Update `【字段约束】` allowed keys list to include `target`, `action`, `constraint`.

- [ ] **Step 4: Add semantic mapping examples**

Append to `【语义映射规则】`:

```text
- 第三张不错/喜欢第三张 → feedback(target="ordinal:3", action="like")
- 不喜欢有人物的 → exclude(constraint="人物")
- 再来点这种/类似的 → more(target="last")
- 前面海边的再多来点 → more(target="desc:海边")
```

- [ ] **Step 5: Add concrete examples to basePrompt**

Append to `【示例】`:

```text
「第三张不错」→ [{"method":"feedback","params":{"target":"ordinal:3","action":"like"}}]
「不喜欢有人物的」→ [{"method":"exclude","params":{"constraint":"人物"}}]
「再来点这种」→ [{"method":"more","params":{"target":"last"}}]
「前面海边的再多来点」→ [{"method":"more","params":{"target":"desc:海边"}}]
```

- [ ] **Step 6: Implement buildStateSection with search results**

Find `buildStateSection()` and append recent search results formatting:

```kotlin
private fun buildSearchResultsSection(recentSearchResults: List<SearchResultSnapshot>): String {
    if (recentSearchResults.isEmpty()) return ""
    return buildString {
        appendLine("【最近搜索结果】")
        recentSearchResults.forEachIndexed { index, snapshot ->
            appendLine("- 第 ${index + 1} 轮 (query=\"${snapshot.query}\", 共 ${snapshot.totalCount} 张${if (snapshot.isRefinement) ", 细化" else ""}):")
            snapshot.results.forEachIndexed { i, item ->
                appendLine("  [${i + 1}] id=${item.mediaId} tags=[${item.tags.joinToString(", ")}]")
            }
        }
    }
}
```

And call it from `buildStateSection()`:

```kotlin
append(buildSearchResultsSection(context.recentSearchResults))
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.local.prompt.LocalPromptBuilderChatSearchTest"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/prompt/LocalPromptBuilder.kt
runtime-core/src/test/java/com/mamba/picme/agent/core/inference/local/prompt/LocalPromptBuilderChatSearchTest.kt
git commit -m "feat(chat): add feedback/more/exclude to local prompt and state section"
```

---

### Task 5: 更新 LocalCommandParser

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParserFeedbackTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.inference.local.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandParserFeedbackTest {

    private val parser = LocalCommandParser()

    @Test
    fun `parse feedback ordinal like`() {
        val json = """[{"method":"feedback","params":{"target":"ordinal:3","action":"like"}}]"""
        val commands = parser.parse(json)
        assertEquals(1, commands.size)
        val cmd = commands[0] as AgentCommand.RecordMediaFeedback
        assertEquals(FeedbackTarget.Ordinal(3), cmd.target)
        assertEquals(FeedbackAction.LIKE, cmd.action)
    }

    @Test
    fun `parse more with description target`() {
        val json = """[{"method":"more","params":{"target":"desc:海边"}}]"""
        val commands = parser.parse(json)
        val cmd = commands[0] as AgentCommand.MoreLikeThis
        assertEquals(FeedbackTarget.Description("海边"), cmd.target)
    }

    @Test
    fun `parse exclude constraint`() {
        val json = """[{"method":"exclude","params":{"constraint":"夜景"}}]"""
        val commands = parser.parse(json)
        val cmd = commands[0] as AgentCommand.ExcludeConstraint
        assertEquals("夜景", cmd.constraint)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.local.parser.LocalCommandParserFeedbackTest"`
Expected: FAIL with `Unknown method: feedback`

- [ ] **Step 3: Add parsing logic**

In `LocalCommandParser.kt`, in the method dispatch, add:

```kotlin
"feedback" -> parseFeedback(params)
"more" -> parseMoreLikeThis(params)
"exclude" -> parseExclude(params)
```

Add helper functions:

```kotlin
private fun parseFeedback(params: JSONObject): AgentCommand {
    val target = parseFeedbackTarget(params.getString("target"))
    val action = FeedbackAction.valueOf(params.getString("action").uppercase())
    return AgentCommand.RecordMediaFeedback(target = target, action = action)
}

private fun parseMoreLikeThis(params: JSONObject): AgentCommand {
    val target = parseFeedbackTarget(params.getString("target"))
    return AgentCommand.MoreLikeThis(target = target)
}

private fun parseExclude(params: JSONObject): AgentCommand {
    val constraint = params.getString("constraint")
    return AgentCommand.ExcludeConstraint(constraint = constraint)
}

private fun parseFeedbackTarget(target: String): FeedbackTarget {
    return when {
        target == "last" -> FeedbackTarget.LastShown
        target.startsWith("ordinal:") -> FeedbackTarget.Ordinal(target.removePrefix("ordinal:").toInt())
        target.startsWith("desc:") -> FeedbackTarget.Description(target.removePrefix("desc:"))
        target.startsWith("mediaId:") -> FeedbackTarget.MediaId(target.removePrefix("mediaId:"))
        else -> FeedbackTarget.Description(target)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.local.parser.LocalCommandParserFeedbackTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt
runtime-core/src/test/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParserFeedbackTest.kt
git add -A
git commit -m "feat(chat): parse feedback/more/exclude in local command parser"
```

---

### Task 6: 更新远程 ToolCallCommandParser

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/parser/ToolCallCommandParser.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/inference/remote/parser/ToolCallCommandParserFeedbackTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.inference.remote.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.tool.ToolExecutionRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallCommandParserFeedbackTest {

    private val parser = ToolCallCommandParser()

    @Test
    fun `parse feedback tool call`() {
        val request = ToolExecutionRequest.builder()
            .name("feedback")
            .arguments("""{"target":"ordinal:3","action":"like"}""")
            .build()
        val cmd = parser.parse(request) as AgentCommand.RecordMediaFeedback
        assertEquals(FeedbackTarget.Ordinal(3), cmd.target)
        assertEquals(FeedbackAction.LIKE, cmd.action)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.remote.parser.ToolCallCommandParserFeedbackTest"`
Expected: FAIL with unsupported command

- [ ] **Step 3: Add tool call parsing**

In `ToolCallCommandParser.kt`, add branches:

```kotlin
"feedback" -> AgentCommand.RecordMediaFeedback(
    target = parseFeedbackTarget(arguments.getString("target")),
    action = FeedbackAction.valueOf(arguments.getString("action").uppercase())
)
"more" -> AgentCommand.MoreLikeThis(
    target = parseFeedbackTarget(arguments.getString("target"))
)
"exclude" -> AgentCommand.ExcludeConstraint(
    constraint = arguments.getString("constraint")
)
```

And add `parseFeedbackTarget()` helper matching Task 5.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.remote.parser.ToolCallCommandParserFeedbackTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/parser/ToolCallCommandParser.kt
runtime-core/src/test/java/com/mamba/picme/agent/core/inference/remote/parser/ToolCallCommandParserFeedbackTest.kt
git add -A
git commit -m "feat(chat): parse feedback/more/exclude tool calls"
```

---

### Task 7: 扩展 ChatSearchCapability Delegate

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/capability/ChatSearchCapability.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/capability/ChatSearchCapabilityFeedbackTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchCapabilityFeedbackTest {

    private val delegate = mockk<ChatSearchCapability.Delegate>(relaxed = true)
    private val capability = ChatSearchCapability.getInstance().apply {
        bindDelegate(delegate)
    }

    @Test
    fun `execute feedback delegates to onRecordMediaFeedback`() = runTest {
        val command = AgentCommand.RecordMediaFeedback(
            target = FeedbackTarget.Ordinal(3),
            action = FeedbackAction.LIKE
        )
        val result = capability.execute(command, AgentContext(scene = com.mamba.picme.agent.core.model.context.AgentScene.CHAT), null)
        assertTrue(result.isSuccess)
        coVerify { delegate.onRecordMediaFeedback(command) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.capability.ChatSearchCapabilityFeedbackTest"`
Expected: FAIL with `Unresolved reference: onRecordMediaFeedback`

- [ ] **Step 3: Extend Delegate and execute**

In `ChatSearchCapability.kt`:

```kotlin
interface Delegate {
    suspend fun onSearchMedia(query: String): SearchOutcome
    suspend fun onRefineMediaSearch(constraint: String): SearchOutcome
    suspend fun onRecordMediaFeedback(command: AgentCommand.RecordMediaFeedback)
    suspend fun onMoreLikeThis(command: AgentCommand.MoreLikeThis)
    suspend fun onExcludeConstraint(command: AgentCommand.ExcludeConstraint)
}
```

Update `supportedCommands()`:

```kotlin
override fun supportedCommands(): List<String> = listOf(
    "search_media",
    "refine_media_search",
    "feedback",
    "more",
    "exclude"
)
```

Update `getCommandDescription()`:

```kotlin
"feedback" -> "记录用户对搜索结果的反馈，参数: target (ordinal:N|desc:描述|last), action (like|dislike)"
"more" -> "基于指定图片推荐更多相似照片，参数: target"
"exclude" -> "在后续搜索中排除某类约束，参数: constraint"
```

Update `execute()`:

```kotlin
is AgentCommand.RecordMediaFeedback -> d.onRecordMediaFeedback(command)
is AgentCommand.MoreLikeThis -> d.onMoreLikeThis(command)
is AgentCommand.ExcludeConstraint -> d.onExcludeConstraint(command)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.capability.ChatSearchCapabilityFeedbackTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/capability/ChatSearchCapability.kt
app/src/test/java/com/mamba/picme/features/chat/capability/ChatSearchCapabilityFeedbackTest.kt
git add -A
git commit -m "feat(chat): extend ChatSearchCapability with feedback delegate methods"
```

---

### Task 8: ChatViewModel 构建搜索结果快照

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/SearchSnapshotBuilder.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/SearchSnapshotBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSnapshotBuilderTest {

    @Test
    fun `build snapshots from media results and assets`() {
        val results = listOf(
            MediaResultsUi(
                query = "海边",
                assets = listOf(MediaAsset(id = 1, uri = "", type = MediaType.PHOTO, captureDate = 0, fileName = "")),
                totalCount = 1,
                isRefinement = false
            )
        )
        val assets = listOf(
            MediaAsset(id = 1, uri = "", type = MediaType.PHOTO, captureDate = 0, fileName = "", labels = """{"tags":["海","日落"]}""")
        )
        val snapshots = SearchSnapshotBuilder.build(results, assets)
        assertEquals(1, snapshots.size)
        assertEquals("海边", snapshots[0].query)
        assertEquals("1", snapshots[0].results[0].mediaId)
        assertEquals(listOf("海", "日落"), snapshots[0].results[0].tags)
    }
}
```

- [ ] **Step 2: Implement SearchSnapshotBuilder**

Create `app/src/main/java/com/mamba/picme/features/chat/SearchSnapshotBuilder.kt`:

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.ResultItem
import com.mamba.picme.agent.core.model.context.SearchResultSnapshot
import org.json.JSONObject

object SearchSnapshotBuilder {

    internal const val MAX_ROUNDS = 3
    private const val MAX_ITEMS = 10
    private const val MAX_TAGS = 3

    fun build(
        results: List<MediaResultsUi>,
        allAssets: List<MediaAsset>
    ): List<SearchResultSnapshot> {
        val assetMap = allAssets.associateBy { it.id.toString() }
        return results.takeLast(MAX_ROUNDS).map { mr ->
            val items = mr.assets
                .take(MAX_ITEMS)
                .mapNotNull { assetMap[it.id.toString()] }
                .map { asset ->
                    ResultItem(
                        mediaId = asset.id.toString(),
                        tags = parseLabels(asset.labels ?: "").take(MAX_TAGS)
                    )
                }
            SearchResultSnapshot(
                query = mr.query,
                results = items,
                totalCount = mr.totalCount,
                isRefinement = mr.isRefinement,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun parseLabels(labelsJson: String): List<String> {
        return try {
            val json = JSONObject(labelsJson)
            val tags = json.optJSONArray("tags")
            (0 until (tags?.length() ?: 0)).map { tags!!.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
```

- [ ] **Step 3: Wire into ChatViewModel**

In `ChatViewModel.kt`, replace private `buildSearchSnapshots` with:

```kotlin
private fun buildSearchSnapshots(sessionId: String): List<SearchResultSnapshot> {
    val results = _messages.value
        .filter { it.type == ChatMessageType.MEDIA_RESULTS && it.mediaResults != null }
        .mapNotNull { it.mediaResults }
        .takeLast(SearchSnapshotBuilder.MAX_ROUNDS)
    return SearchSnapshotBuilder.build(results, lastResultAssets[sessionId].orEmpty())
}
```

- [ ] **Step 4: Wire into sendMessage**

In `sendMessage()` where `AgentContext` is built:

```kotlin
val agentContext = AgentContext(
    scene = AgentScene.CHAT,
    memorySessionId = sessionId,
    recentSearchResults = buildSearchSnapshots(sessionId)
)
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/SearchSnapshotBuilder.kt
app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
app/src/test/java/com/mamba/picme/features/chat/SearchSnapshotBuilderTest.kt
git add -A
git commit -m "feat(chat): build recent search snapshots for AgentContext"
```

---

### Task 9: ChatViewModel 解析 FeedbackTarget

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelTargetResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatViewModelTargetResolverTest {

    @Test
    fun `resolve ordinal target`() {
        val assets = listOf(
            MediaAsset(id = 1, uri = "", type = MediaType.PHOTO, captureDate = 0, fileName = ""),
            MediaAsset(id = 2, uri = "", type = MediaType.PHOTO, captureDate = 0, fileName = "")
        )
        val result = ChatViewModel.resolveTargetForTest(FeedbackTarget.Ordinal(2), assets)
        assertEquals("2", result)
    }

    @Test
    fun `resolve ordinal out of bounds returns null`() {
        val assets = listOf(MediaAsset(id = 1, uri = "", type = MediaType.PHOTO, captureDate = 0, fileName = ""))
        val result = ChatViewModel.resolveTargetForTest(FeedbackTarget.Ordinal(5), assets)
        assertNull(result)
    }
}
```

> `resolveTargetForTest` 是 package-private 或 internal 测试钩子；实现时可用 `@VisibleForTesting`。

- [ ] **Step 2: Implement resolveTarget**

In `ChatViewModel.kt`:

```kotlin
@VisibleForTesting
internal fun resolveTarget(target: FeedbackTarget, sessionId: String? = null): String? {
    val sid = sessionId ?: _currentSessionId.value
    val prior = lastResultAssets[sid].orEmpty()
    return when (target) {
        is FeedbackTarget.Ordinal -> prior.getOrNull(target.index - 1)?.id?.toString()
        is FeedbackTarget.Description -> prior.firstOrNull { matchesTags(it, target.text) }?.id?.toString()
        is FeedbackTarget.MediaId -> target.id
        is FeedbackTarget.LastShown -> prior.firstOrNull()?.id?.toString()
    }
}

private fun matchesTags(asset: MediaAsset, description: String): Boolean {
    val tags = parseLabels(asset.labels ?: "").map { it.lowercase() }
    val keywords = description.split(" ", "、", ",").filter { it.isNotBlank() }.map { it.lowercase() }
    return keywords.any { it in tags || asset.fileName.contains(it, ignoreCase = true) }
}
```

- [ ] **Step 3: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatViewModelTargetResolverTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
app/src/test/java/com/mamba/picme/features/chat/ChatViewModelTargetResolverTest.kt
git add -A
git commit -m "feat(chat): add FeedbackTarget resolver in ChatViewModel"
```

---

### Task 10: ChatViewModel 处理 feedback 命令

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelFeedbackCommandTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.features.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.agent.core.model.command.FeedbackTarget
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.PicMeAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaFeedbackUseCase
import com.mamba.picme.domain.search.MediaSearchEngine
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatViewModelFeedbackCommandTest {

    private lateinit var viewModel: ChatViewModel
    private lateinit var feedbackUseCase: MediaFeedbackUseCase
    private lateinit var chatMessageDao: ChatMessageDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        feedbackUseCase = mockk(relaxed = true)
        chatMessageDao = mockk(relaxed = true)
        val deps = ChatViewModelDependencies(
            context = context,
            chatMessageDao = chatMessageDao,
            chatSessionDao = mockk(relaxed = true),
            userSettingsRepository = mockk(relaxed = true),
            mediaSearchEngine = mockk(relaxed = true),
            mediaFeedbackRepository = mockk(relaxed = true),
            picMeAuthClient = mockk(relaxed = true)
        )
        viewModel = ChatViewModel(deps)
        // 注入 mock useCase（如通过反射或可见性重构）
        // 实际实现时可把 mediaFeedbackUseCase 作为 constructor 参数，避免反射
    }

    @Test
    fun `onRecordMediaFeedback records like and inserts confirmation`() = runTest {
        viewModel.onRecordMediaFeedback(
            AgentCommand.RecordMediaFeedback(
                target = FeedbackTarget.Ordinal(1),
                action = FeedbackAction.LIKE
            )
        )
        coVerify { feedbackUseCase.record(any(), any(), any(), FeedbackAction.LIKE) }
    }
}
```

> 实现时建议把 `mediaFeedbackUseCase` 从 ViewModel 内部 new 改为通过 `ChatViewModelDependencies` 注入，方便测试。

- [ ] **Step 2: Implement onRecordMediaFeedback**

```kotlin
override suspend fun onRecordMediaFeedback(command: AgentCommand.RecordMediaFeedback) {
    val sessionId = _currentSessionId.value
    val mediaId = resolveTarget(command.target, sessionId)
        ?: return insertAgentMessage(sessionId, resolveFailureText(command.target), currentModelLabel())

    val query = command.queryHint ?: findCurrentQuery(sessionId) ?: return
    mediaFeedbackUseCase.record(mediaId, query, sessionId, command.action)
    updateCurrentResultsFeedback(mediaId, command.action, query)
    insertAgentMessage(sessionId, confirmationText(command.action), currentModelLabel())
}

private fun findCurrentQuery(sessionId: String): String? {
    return _messages.value
        .findLast { it.type == ChatMessageType.MEDIA_RESULTS }
        ?.mediaResults?.query
}

private fun confirmationText(action: FeedbackAction): String {
    return when (action) {
        FeedbackAction.LIKE -> context.getString(R.string.feedback_confirmed_like)
        FeedbackAction.DISLIKE -> context.getString(R.string.feedback_confirmed_dislike)
        else -> context.getString(R.string.feedback_confirmed)
    }
}

private fun resolveFailureText(target: FeedbackTarget): String {
    return when (target) {
        is FeedbackTarget.Ordinal -> context.getString(R.string.feedback_resolve_failure_ordinal)
        is FeedbackTarget.Description -> context.getString(R.string.feedback_resolve_failure_description)
        else -> context.getString(R.string.feedback_resolve_failure)
    }
}
```

- [ ] **Step 3: Update command processing loop**

Find where `ChatViewModel` processes `AgentCommand` results (around `streamResult.commands.forEach`) and add:

```kotlin
is AgentCommand.RecordMediaFeedback -> onRecordMediaFeedback(command)
is AgentCommand.MoreLikeThis -> onMoreLikeThis(command)
is AgentCommand.ExcludeConstraint -> onExcludeConstraint(command)
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
app/src/test/java/com/mamba/picme/features/chat/ChatViewModelFeedbackCommandTest.kt
git add -A
git commit -m "feat(chat): handle RecordMediaFeedback command"
```

---

### Task 11: ChatViewModel 处理 more 和 exclude 命令

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelMoreExcludeTest.kt`

- [ ] **Step 1: Write failing test for exclude filtering**

```kotlin
package com.mamba.picme.features.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.PicMeAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatViewModelMoreExcludeTest {

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        viewModel = ChatViewModel(
            ChatViewModelDependencies(
                context = context,
                chatMessageDao = mockk(relaxed = true),
                chatSessionDao = mockk(relaxed = true),
                userSettingsRepository = mockk(relaxed = true),
                mediaSearchEngine = mockk(relaxed = true),
                mediaFeedbackRepository = mockk(relaxed = true),
                picMeAuthClient = mockk(relaxed = true)
            )
        )
    }

    @Test
    fun `onExcludeConstraint accepts constraint without crash`() = runTest {
        viewModel.onExcludeConstraint(AgentCommand.ExcludeConstraint(constraint = "夜景"))
        // 无初始 MEDIA_RESULTS 消息时也应安全结束，不抛异常
    }
}
```

- [ ] **Step 2: Implement onMoreLikeThis**

```kotlin
override suspend fun onMoreLikeThis(command: AgentCommand.MoreLikeThis) {
    val sessionId = _currentSessionId.value
    val mediaId = resolveTarget(command.target, sessionId)
    val asset = mediaId?.let { id -> lastResultAssets[sessionId]?.find { it.id.toString() == id } }
    val tags = asset?.let { parseLabels(it.labels ?: "").take(3) } ?: emptyList()
    val constraint = when {
        tags.isNotEmpty() -> "和这张照片类似的：${tags.joinToString("、")}"
        mediaId != null -> "更多类似这张照片的"
        else -> command.queryHint ?: return insertAgentMessage(sessionId, context.getString(R.string.feedback_resolve_failure), currentModelLabel())
    }
    val outcome = onRefineMediaSearch(constraint)
    val refinedAssets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
    if (refinedAssets.isNotEmpty()) {
        insertMediaResultsMessage(
            sessionId,
            MediaResultsUi(
                query = constraint,
                assets = refinedAssets,
                totalCount = outcome.totalCount,
                isRefinement = true
            )
        )
    } else {
        insertAgentMessage(sessionId, context.getString(R.string.feedback_no_more_results), currentModelLabel())
    }
}
```

- [ ] **Step 3: Implement onExcludeConstraint**

```kotlin
private val activeExcludes = mutableSetOf<String>()

override suspend fun onExcludeConstraint(command: AgentCommand.ExcludeConstraint) {
    val sessionId = _currentSessionId.value
    activeExcludes.add(command.constraint)
    mediaFeedbackUseCase.recordExclude(command.constraint, sessionId)
    reapplyFiltersToCurrentResults(sessionId)
    insertAgentMessage(
        sessionId,
        context.getString(R.string.feedback_excluded, command.constraint),
        currentModelLabel()
    )
}

private suspend fun reapplyFiltersToCurrentResults(sessionId: String) {
    val currentMessages = _messages.value
    val updated = currentMessages.map { message ->
        val mr = message.mediaResults
        if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null) {
            val filtered = mr.assets.filter { asset ->
                val tags = parseLabels(asset.labels ?: "").map { it.lowercase() }
                activeExcludes.none { exclude ->
                    tags.any { it.contains(exclude, ignoreCase = true) }
                }
            }
            message.copy(mediaResults = mr.copy(assets = filtered))
        } else message
    }
    _messages.value = updated
    chatMessageDao.updateMessages(updated.map { it.toEntity() })
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
app/src/test/java/com/mamba/picme/features/chat/ChatViewModelMoreExcludeTest.kt
git add -A
git commit -m "feat(chat): handle more and exclude commands"
```

---

### Task 12: MediaFeedbackUseCase 支持 exclude

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/search/MediaFeedbackUseCase.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/repository/MediaFeedbackRepository.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/repository/MediaFeedbackRepositoryImpl.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/search/MediaFeedbackUseCaseTest.kt`

- [ ] **Step 1: Add failing test**

Append to `MediaFeedbackUseCaseTest`:

```kotlin
    @Test
    fun `recordExclude should call repository`() = runTest {
        useCase.recordExclude("夜景", "session_1")

        coVerify {
            repository.recordExclude("夜景", "session_1")
        }
    }
```

- [ ] **Step 2: Implement repository interface and use case**

In `MediaFeedbackRepository.kt`:

```kotlin
suspend fun recordExclude(constraint: String, sessionId: String)
suspend fun getExcludedConstraints(sessionId: String): List<String>
```

In `MediaFeedbackRepositoryImpl.kt`:

```kotlin
override suspend fun recordExclude(constraint: String, sessionId: String) {
    dao.insertExclude(ExcludeConstraintEntity(constraint = constraint, sessionId = sessionId, createdAt = System.currentTimeMillis()))
}

override suspend fun getExcludedConstraints(sessionId: String): List<String> {
    return dao.getExcludedConstraints(sessionId).map { it.constraint }
}
```

In `MediaFeedbackUseCase.kt`:

```kotlin
suspend fun recordExclude(constraint: String, sessionId: String) {
    repository.recordExclude(constraint, sessionId)
}

suspend fun getExcludedConstraints(sessionId: String): List<String> {
    return repository.getExcludedConstraints(sessionId)
}
```

- [ ] **Step 3: Add ExcludeConstraintEntity (可选第一版可放内存)**

> 如果追求最小改动，第一版可以只在 ViewModel 内存中维护 `activeExcludes`，`recordExclude` 只操作内存。等后续需要跨会话排除时再持久化。
>
> 建议第一版先内存实现，避免 DB migration：

```kotlin
suspend fun recordExclude(constraint: String, sessionId: String) {
    // 第一版仅内存，后续可接入 repository
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.MediaFeedbackUseCaseTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/MediaFeedbackUseCase.kt
git commit -m "feat(chat): add exclude constraint support to MediaFeedbackUseCase"
```

---

### Task 13: UI 高亮支持

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt`
- Test: 无需新测试（UI 可选）

- [ ] **Step 1: Add highlightedMediaId to MediaResultsUi**

```kotlin
data class MediaResultsUi(
    val query: String,
    val assets: List<MediaAsset>,
    val totalCount: Int,
    val isRefinement: Boolean = false,
    val feedbackState: Map<String, FeedbackAction> = emptyMap(),
    val highlightedMediaId: String? = null   // 新增
)
```

- [ ] **Step 2: Highlight on feedback command**

In `onRecordMediaFeedback` and `onMoreLikeThis`, after resolving `mediaId`:

```kotlin
highlightMediaInCurrentResults(mediaId)
```

Add helper:

```kotlin
private fun highlightMediaInCurrentResults(mediaId: String) {
    viewModelScope.launch {
        val updated = _messages.value.map { message ->
            val mr = message.mediaResults
            if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null) {
                message.copy(mediaResults = mr.copy(highlightedMediaId = mediaId))
            } else message
        }
        _messages.value = updated
        delay(300)
        val cleared = updated.map { message ->
            val mr = message.mediaResults
            if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null) {
                message.copy(mediaResults = mr.copy(highlightedMediaId = null))
            } else message
        }
        _messages.value = cleared
    }
}
```

- [ ] **Step 3: Update MediaResultsCarousel**

In `MediaCard` composable, add border when `asset.id.toString() == highlightedMediaId`:

```kotlin
Modifier.then(
    if (isHighlighted) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
    } else Modifier
)
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt
git commit -m "feat(chat): highlight referred media card"
```

---

### Task 14: I18N 确认文案

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: Add strings**

`values/strings.xml`:

```xml
<string name="feedback_confirmed_like">Got it, I\'ll prioritize similar photos.</string>
<string name="feedback_confirmed_dislike">Got it, I\'ll show fewer similar photos.</string>
<string name="feedback_confirmed">Recorded.</string>
<string name="feedback_excluded">Excluded "%1$s" from future results.</string>
<string name="feedback_resolve_failure">I couldn\'t find the photo you meant. Can you describe it differently?</string>
<string name="feedback_resolve_failure_ordinal">I couldn\'t find that photo. Please say "the Nth one" or describe it.</string>
<string name="feedback_resolve_failure_description">I couldn\'t find a photo matching that description.</string>
<string name="feedback_no_more_results">No more similar photos found.</string>
```

Add corresponding translations to zh/zh-rCN/zh-rTW.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/values*
git commit -m "feat(chat): i18n for conversational feedback confirmations"
```

---

### Task 15: 全量编译与测试

**Files:** N/A

- [ ] **Step 1: Compile**

Run: `./gradlew :app:compileDebugKotlin :runtime-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.*" --tests "com.mamba.picme.features.chat.*"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run runtime-core tests**

Run: `./gradlew :runtime-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git commit -m "test(chat): all tests pass for conversational feedback" --allow-empty
```

---

## Self-Review

### Spec Coverage

| Spec 章节 | 对应 Task |
|-----------|-----------|
| 4.1 AgentContext + SearchResultSnapshot | Task 2 |
| 4.2 FeedbackTarget | Task 3 |
| 4.3 新增 AgentCommand | Task 3 |
| 5.1-5.3 Local Prompt | Task 4 |
| 5.4 远程 Prompt/Tool | Task 6 |
| 6.1 构建快照 | Task 8 |
| 6.2 Capability 分发 | Task 7 |
| 6.3 ViewModel 处理 | Task 10, 11 |
| 6.4 Target 解析 | Task 9 |
| 7.1 确认消息 | Task 14 |
| 7.2 高亮 | Task 13 |
| 7.3 结果策略 | Task 10, 11 |
| 8. 错误处理 | Task 9, 10, 11 |
| 9. 测试计划 | All tasks |
| 10. 红线 | Code review |

### Placeholder Scan

- 无 TBD/TODO
- 无 "add appropriate error handling" 类模糊描述
- 每个 Task 都有具体文件路径、代码、测试、命令

### Type Consistency

- `FeedbackAction` 统一在 `runtime-core`
- `FeedbackTarget` 密封接口/类在各处一致
- `AgentCommand.getMethodName()` 短名映射一致
- `SearchResultSnapshot.results` 为 `List<ResultItem>`，避免早期 `List<String>` 歧义

### 已知简化

- Task 12 建议第一版 `exclude` 仅内存实现，避免 DB migration；后续可补持久化
- Task 13 高亮为可选 UX 增强，可第一版跳过
