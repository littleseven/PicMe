# Chat 相册搜索 + 卡片 Carousel 实现计划

> **状态**：✅ 已完成（App 已落地）
> **实现位置**：`app/src/main/java/com/mamba/picme/features/chat/capability/ChatSearchCapability.kt`
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在独立 ChatScreen 中支持自然语言搜索相册照片，结果以横滑卡片 carousel 插入对话，点击进 MediaPager 预览，支持多轮 in-set 细化。

**Architecture:** Agent（LLM）解析意图并发出 `SearchMedia`/`RefineMediaSearch` 命令；新增 `ChatSearchCapability`（CHAT 场景，Delegate 模式）把命令回调给 `ChatViewModel`；VM 直接调 `MediaSearchEngine` 执行搜索、按 session 持有结果集做 in-set 细化；结果经新 `AgentAction.MediaResults` → `handleAgentAction` 渲染为 `MediaResultsUi` 消息（横滑卡片）。全程使用 `context.MediaAsset`（runtime-core），与 `MediaPager` 一致。

**Tech Stack:** Kotlin、Jetpack Compose、Room、kotlinx.coroutines、Agent Runtime（runtime-core）。测试：JUnit4 + kotlinx-coroutines-test（JVM）；Compose UI 测试（androidTest）。

**Spec:** `docs/superpowers/specs/2026-07-14-chat-gallery-search-design.md`

**实现细化（相对 spec）：** 细化采用**内存内 in-set 过滤**（对 VM 持有的上一轮 `List<MediaAsset>` 按 `labels/ocrText/locationName/fileName` 包含 constraint 过滤），等价于 spec 的 in-set 意图，且避免 `MediaDao.searchLabelsInIds` 返回 `MediaEntity` 的类型映射；in-set 为空时回退全局重搜（同 spec §8）。

---

## File Structure

**新建：**
- `app/src/main/java/com/mamba/picme/features/chat/capability/ChatSearchCapability.kt` — CHAT 场景搜索 capability + `ChatSearchDelegate` 接口 + `SearchOutcome`。
- `app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt` — 横滑卡片 carousel + `MediaCard`。
- `app/src/test/java/com/mamba/picme/features/chat/MediaResultsSerializationTest.kt` — 持久化往返测试。
- `app/src/test/java/com/mamba/picme/features/chat/GallerySearchRefinementTest.kt` — 细化逻辑测试。
- `runtime-core/src/test/java/com/mamba/picme/agent/core/model/RefineMediaSearchTest.kt` — 命令测试。

**修改：**
- `runtime-core/.../model/command/AgentCommands.kt` — 加 `RefineMediaSearch`。
- `runtime-core/.../model/context/AgentModels.kt` — 加 `MediaResults` action + `getCommandId` 分支。
- `app/.../features/chat/ChatViewModel.kt` — 实现 Delegate、`lastResultAssets`、`handleAgentAction(MediaResults)`、回退直连、`toUiModel` 反序列化。
- `app/.../features/chat/ChatViewModelDependencies.kt` — 加 `mediaSearchEngine`。
- `app/.../features/chat/ChatScreen.kt` — `ChatMessageUi`/`ChatMessageType` 扩展 + 渲染分发 + 预览接入 + Delegate 绑定。
- `app/.../data/local/ChatMessageEntity.kt` — 确认字段（type/content/metadata），序列化在 VM 侧处理。
- `app/.../features/chat/components/QuickActionBar.kt` — 加「搜相册」chip。
- `app/.../di/AppContainer.kt` — deps 注入 `mediaSearchEngine`。
- `app/.../PoLangApplication.kt` — 注册 `ChatSearchCapability`。
- `app/.../features/gallery/GalleryScreen.kt` + 导航 — `initialQuery` 参数。

---

### Task 1: 新增 `RefineMediaSearch` 命令

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt`（`SearchMedia` 定义在 ~167 行）
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/model/RefineMediaSearchTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.model

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.RefineMediaSearch // 期待 import
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RefineMediaSearchTest {
    @Test
    fun `constraint is retained and commandId auto-assigned`() {
        val cmd = RefineMediaSearch(constraint = "海边的")
        assertEquals("海边的", cmd.constraint)
        assertNotEquals(0, cmd.commandId)
    }

    @Test
    fun `two commands get distinct ids`() {
        val a = RefineMediaSearch("夜景")
        val b = RefineMediaSearch("夜景")
        assertNotEquals(a.commandId, b.commandId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.model.RefineMediaSearchTest"`
Expected: FAIL（`RefineMediaSearch` 未定义，编译错误）

- [ ] **Step 3: Add the command**

在 `AgentCommands.kt` 的 `SearchMedia` data class 之后（约 171 行后）插入：

```kotlin
    /**
     * 细化上一轮相册搜索结果（in-set 过滤）。
     * 由 Agent 在识别到用户对上一轮结果收窄时发出；mediaId 集合由 ChatViewModel 按 session 持有。
     */
    data class RefineMediaSearch(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val constraint: String
    ) : AgentCommand()
```

> 注：`AgentCommands.kt` 是单文件 sealed class，`RefineMediaSearch` 作为 `AgentCommand` 的子类必须在同一文件内定义。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.model.RefineMediaSearchTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt runtime-core/src/test/java/com/mamba/picme/agent/core/model/RefineMediaSearchTest.kt
git commit -m "feat(agent): add RefineMediaSearch command for in-set gallery refinement"
```

---

### Task 2: 新增 `AgentAction.MediaResults` action

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt`（`AgentAction` sealed class + `getCommandId` 函数 ~170-176 行）

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.model

import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.getCommandId
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaResultsActionTest {
    @Test
    fun `getCommandId resolves MediaResults`() {
        val action = AgentAction.MediaResults(
            commandId = 42,
            query = "海边",
            mediaIds = listOf(1L, 2L, 3L),
            totalCount = 3,
            isRefinement = true
        )
        assertEquals(42, getCommandId(action))
        assertEquals(listOf(1L, 2L, 3L), action.mediaIds)
        assertEquals(true, action.isRefinement)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.model.MediaResultsActionTest"`
Expected: FAIL（`MediaResults` 未定义）

- [ ] **Step 3: Add the action**

在 `AgentModels.kt` 的 `AgentAction` sealed class 内（`BatchResult` 之后）加：

```kotlin
    /**
     * 相册搜索结果（媒体 id 列表）。只带 id，避免 runtime-core 依赖 app Room 实体；
     * 调用方（ChatViewModel）按 id 从 session 持有的 List<MediaAsset> 取展示数据。
     */
    data class MediaResults(
        override val commandId: Int,
        val query: String,
        val mediaIds: List<Long>,
        val totalCount: Int,
        val isRefinement: Boolean
    ) : AgentAction()
```

> 若 `AgentAction` 子类用的是 `val commandId: Int`（非 `override`），按现有 `TextReply` 的写法保持一致（去掉 `override`）。实现时参照同文件 `TextReply(commandId, message)` 的确切声明形式。

在 `getCommandId(action)` 的 `when` 分支末尾加：

```kotlin
            is MediaResults -> action.commandId
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.model.MediaResultsActionTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt runtime-core/src/test/java/com/mamba/picme/agent/core/model/MediaResultsActionTest.kt
git commit -m "feat(agent): add AgentAction.MediaResults for gallery search results"
```

---

### Task 3: 新增 `ChatSearchCapability` + `ChatSearchDelegate`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/capability/ChatSearchCapability.kt`

- [ ] **Step 1: Write the capability**

```kotlin
package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import java.lang.ref.WeakReference

/**
 * Chat 场景相册搜索 Capability。
 *
 * 职责：在 CHAT 场景把 search_media / refine_media_search 暴露为可用工具，
 * 并把命令回调给 [Delegate]（由 ChatViewModel 实现）执行。
 * 镜像 GalleryCapability 的 WeakReference Delegate 套路。
 */
class ChatSearchCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatSearchCapability? = null
        fun getInstance(): ChatSearchCapability =
            instance ?: synchronized(this) {
                instance ?: ChatSearchCapability().also { instance = it }
            }
    }

    private val tag = "ChatSearchCapability"

    override val name: String = "chat_gallery_search"
    override val description: String = "在聊天中搜索相册照片，结果以卡片展示；支持多轮细化筛选"

    interface Delegate {
        suspend fun onSearchMedia(query: String): SearchOutcome
        suspend fun onRefineMediaSearch(constraint: String): SearchOutcome
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

    override fun supportedCommands(): List<String> = listOf("search_media", "refine_media_search")

    override fun getCommandDescription(command: String): String = when (command) {
        "search_media" -> "搜索相册照片，参数: query (自然语言，如'去年夏天'、'海边的')"
        "refine_media_search" -> "在上一轮相册搜索结果中细化筛选，参数: constraint (如'海边的'、'夜景')"
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
                    message = "相册搜索暂不可用（聊天页未激活）"
                )
            )
        return try {
            val outcome = when (command) {
                is AgentCommand.SearchMedia -> d.onSearchMedia(command.query)
                is AgentCommand.RefineMediaSearch -> d.onRefineMediaSearch(command.constraint)
                else -> {
                    return Result.success(
                        AgentAction.Error(
                            commandId = command.commandId,
                            errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                            message = "ChatSearchCapability 不支持此命令"
                        )
                    )
                }
            }
            Result.success(
                AgentAction.MediaResults(
                    commandId = command.commandId,
                    query = outcome.query,
                    mediaIds = outcome.mediaIds,
                    totalCount = outcome.totalCount,
                    isRefinement = outcome.isRefinement
                )
            )
        } catch (e: Exception) {
            Logger.e(tag, "Search failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "搜索失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }
}

/**
 * Delegate 执行搜索后的结果。mediaIds 为全量命中 id（供 ChatViewModel 持有做细化）。
 */
data class SearchOutcome(
    val query: String,
    val mediaIds: List<Long>,
    val totalCount: Int,
    val isRefinement: Boolean
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（若 `BaseCapability`/`SceneManager`/`AgentErrorCode` 签名与上述不符，按 `GalleryCapability` 实际签名对齐——它们在同目录 `features/gallery/capability/GalleryCapability.kt`）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/capability/ChatSearchCapability.kt
git commit -m "feat(chat): add ChatSearchCapability with delegate pattern"
```

---

### Task 4: 注册 `ChatSearchCapability`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/PoLangApplication.kt`（`GalleryCapability` 注册在 ~530 行：`orchestrator.registerCapability(GalleryCapability.getInstance())`）

- [ ] **Step 1: Add registration**

在 `PoLangApplication.kt` 顶部 import 区加：

```kotlin
import com.mamba.picme.features.chat.capability.ChatSearchCapability
```

在 `orchestrator.registerCapability(GalleryCapability.getInstance())`（~530 行）之后加：

```kotlin
        orchestrator.registerCapability(ChatSearchCapability.getInstance())
        Logger.i(TAG, "ChatSearchCapability registered for CHAT scene")
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/PoLangApplication.kt
git commit -m "feat(chat): register ChatSearchCapability globally"
```

---

### Task 5: 注入 `MediaSearchEngine` 到 ChatViewModelDependencies

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`（`chatViewModelDependencies` 构造在 ~429 行）

- [ ] **Step 1: Add field to deps**

`ChatViewModelDependencies.kt` 加 import 与字段：

```kotlin
package com.mamba.picme.features.chat

import android.content.Context
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine

class ChatViewModelDependencies(
    val context: Context,
    val chatMessageDao: ChatMessageDao,
    val chatSessionDao: ChatSessionDao,
    val userSettingsRepository: UserSettingsRepository,
    val mediaSearchEngine: MediaSearchEngine
)
```

- [ ] **Step 2: Supply it in AppContainer**

`AppContainer.kt` ~429 行 `chatViewModelDependencies` 构造加一行：

```kotlin
    private val chatViewModelDependencies: ChatViewModelDependencies by lazy {
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = database.chatMessageDao(),
            chatSessionDao = database.chatSessionDao(),
            userSettingsRepository = userPreferencesRepository,
            mediaSearchEngine = mediaSearchEngine
        )
    }
```

> `mediaSearchEngine` 已是 AppContainer 的 `val`（~110 行），直接引用。

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt app/src/main/java/com/mamba/picme/di/AppContainer.kt
git commit -m "feat(chat): inject MediaSearchEngine into ChatViewModelDependencies"
```

---

### Task 6: 扩展聊天消息模型（`MediaResultsUi` + `MEDIA_RESULTS` 类型）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（`ChatMessageUi` ~1134 行、`ChatMessageType` ~1143 行）

- [ ] **Step 1: Add the UI models**

在 `ChatScreen.kt` import 区加：

```kotlin
import com.mamba.picme.agent.core.model.context.MediaAsset
```

在 `ChatMessageUi` data class（~1134 行）末尾加字段：

```kotlin
data class ChatMessageUi(
    val id: String,
    val type: ChatMessageType,
    val content: String,
    val modelUsed: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val performance: LlmPerformance? = null,
    val mediaResults: MediaResultsUi? = null
)
```

在 `ChatMessageType` enum（~1143 行）加一项：

```kotlin
enum class ChatMessageType {
    USER_TEXT,
    AGENT_TEXT,
    USER_IMAGE,
    AGENT_IMAGE,
    COMMAND,
    PLAN_PREVIEW,
    MEDIA_RESULTS
}
```

在 `ChatMessageType` enum 之后新增：

```kotlin
/**
 * 相册搜索结果 carousel 的 UI 数据。
 * assets 已截到展示上限（20），totalCount 为全量命中数。
 */
data class MediaResultsUi(
    val query: String,
    val assets: List<MediaAsset>,
    val totalCount: Int,
    val isRefinement: Boolean
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): add MediaResultsUi + MEDIA_RESULTS message type"
```

---

### Task 7: 持久化往返（序列化/反序列化）+ `toUiModel` 扩展

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（`toUiModel` ~718 行；新增序列化辅助）
- Test: `app/src/test/java/com/mamba/picme/features/chat/MediaResultsSerializationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaResultsSerializationTest {

    private val assets = listOf(
        MediaAsset(id = 1, uri = "content://a/1", type = MediaType.PHOTO, captureDate = 1000L, fileName = "a.jpg"),
        MediaAsset(id = 2, uri = "content://a/2", type = MediaType.PHOTO, captureDate = 2000L, fileName = "b.jpg")
    )

    @Test
    fun `serialize then deserialize round-trips assets and metadata`() {
        val content = ChatViewModel.serializeMediaResultsContent(assets)
        val metadata = ChatViewModel.serializeMediaResultsMetadata(query = "海边", totalCount = 5, isRefinement = true)

        val parsed = ChatViewModel.deserializeMediaResults(content, metadata)

        assertEquals("海边", parsed.query)
        assertEquals(5, parsed.totalCount)
        assertTrue(parsed.isRefinement)
        assertEquals(2, parsed.assets.size)
        assertEquals(1L, parsed.assets[0].id)
        assertEquals("content://a/1", parsed.assets[0].uri)
        assertEquals(MediaType.PHOTO, parsed.assets[1].type)
    }

    @Test
    fun `missing metadata yields empty-query non-refinement result`() {
        val content = ChatViewModel.serializeMediaResultsContent(assets)
        val parsed = ChatViewModel.deserializeMediaResults(content, metadata = null)
        assertEquals("", parsed.query)
        assertEquals(false, parsed.isRefinement)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.MediaResultsSerializationTest"`
Expected: FAIL（`ChatViewModel.serializeMediaResultsContent` 等未定义）

- [ ] **Step 3: Add serialization helpers to ChatViewModel**

在 `ChatViewModel.kt` 加 import：

```kotlin
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
```

在 `ChatViewModel` companion（若无则在类内顶层加 `companion object`，或与现有 companion 合并）加：

```kotlin
    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_MESSAGES = 500
        private const val MAX_PREVIEW_LENGTH = 60
        const val MAX_CARDS = 20

        /** 把展示用 assets 序列化为 Room content JSON（id/uri/type/captureDate/fileName）。 */
        fun serializeMediaResultsContent(assets: List<MediaAsset>): String {
            val arr = org.json.JSONArray()
            for (a in assets) {
                val o = org.json.JSONObject()
                o.put("id", a.id)
                o.put("uri", a.uri)
                o.put("type", a.type.name)
                o.put("captureDate", a.captureDate)
                o.put("fileName", a.fileName)
                arr.put(o)
            }
            return arr.toString()
        }

        /** query/totalCount/isRefinement 存入 metadata JSON。 */
        fun serializeMediaResultsMetadata(query: String, totalCount: Int, isRefinement: Boolean): String =
            org.json.JSONObject()
                .put("query", query)
                .put("totalCount", totalCount)
                .put("isRefinement", isRefinement)
                .toString()

        /** 从 Room content + metadata 反序列化为 MediaResultsUi（重建最小 MediaAsset）。 */
        fun deserializeMediaResults(content: String, metadata: String?): MediaResultsUi {
            val arr = org.json.JSONArray(content)
            val assets = ArrayList<MediaAsset>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                assets.add(
                    MediaAsset(
                        id = o.getLong("id"),
                        uri = o.getString("uri"),
                        type = MediaType.valueOf(o.optString("type", "PHOTO")),
                        captureDate = o.getLong("captureDate"),
                        fileName = o.optString("fileName", "")
                    )
                )
            }
            val meta = metadata?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            return MediaResultsUi(
                query = meta?.optString("query", "") ?: "",
                assets = assets,
                totalCount = meta?.optInt("totalCount", assets.size) ?: assets.size,
                isRefinement = meta?.optBoolean("isRefinement", false) ?: false
            )
        }
    }
```

> 若 `ChatViewModel` 已有 `companion object`（含 TAG 等常量），将上述函数合并进去，不要重复声明 `TAG`/`MAX_MESSAGES`。

在 `toUiModel()`（~718 行）的 `type = when (type)` 分支加：

```kotlin
                "media_results" -> ChatMessageType.MEDIA_RESULTS
```

并在 `toUiModel()` 返回的 `ChatMessageUi(...)` 加 `mediaResults` 反序列化。修改 `toUiModel()` 返回值为：

```kotlin
    private fun ChatMessageEntity.toUiModel(): ChatMessageUi {
        val performance = metadata?.let { parsePerformanceMetadata(it) }
        val mediaResults = if (type == "media_results") {
            deserializeMediaResults(content, metadata)
        } else {
            null
        }
        return ChatMessageUi(
            id = id,
            type = when (type) {
                "user_text" -> ChatMessageType.USER_TEXT
                "agent_text" -> ChatMessageType.AGENT_TEXT
                "user_image" -> ChatMessageType.USER_IMAGE
                "agent_image" -> ChatMessageType.AGENT_IMAGE
                "command" -> ChatMessageType.COMMAND
                "plan_preview" -> ChatMessageType.PLAN_PREVIEW
                "media_results" -> ChatMessageType.MEDIA_RESULTS
                else -> ChatMessageType.AGENT_TEXT
            },
            content = content,
            modelUsed = modelUsed,
            timestamp = timestamp,
            performance = performance,
            mediaResults = mediaResults
        )
    }
```

> 注意：`media_results` 消息的 `metadata` 同时存性能与搜索元信息时可能冲突。v1 搜索消息不写性能指标（`metadata` 只放搜索元信息），`performance` 为 null，无冲突。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.MediaResultsSerializationTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt app/src/test/java/com/mamba/picme/features/chat/MediaResultsSerializationTest.kt
git commit -m "feat(chat): serialize/deserialize media_results messages"
```

---

### Task 8: ChatViewModel 实现 Delegate + 搜索/细化逻辑

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/GallerySearchRefinementTest.kt`

- [ ] **Step 1: Write the failing test（细化逻辑）**

测试针对纯函数 `filterInSet(assets, constraint)`，避免依赖 Android/Engine。

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class GallerySearchRefinementTest {

    private fun asset(id: Long, labels: String?, ocr: String?, loc: String?, name: String) =
        MediaAsset(id = id, uri = "u$id", type = MediaType.PHOTO, captureDate = 0L, fileName = name,
            labels = labels, ocrText = ocr, locationName = loc)

    private val set = listOf(
        asset(1, labels = "beach,sea", ocr = null, loc = "三亚", name = "a.jpg"),
        asset(2, labels = "night,city", ocr = "霓虹", loc = "上海", name = "b.jpg"),
        asset(3, labels = "beach,sunset", ocr = null, loc = "海南", name = "c.jpg"),
        asset(4, labels = "mountain", ocr = null, loc = null, name = "d.jpg")
    )

    @Test
    fun `constraint matches labels ocr location filename case-insensitive`() {
        val r = ChatViewModel.filterInSet(set, "beach")
        assertEquals(listOf(1L, 3L), r.map { it.id })
    }

    @Test
    fun `constraint matches ocr text`() {
        val r = ChatViewModel.filterInSet(set, "霓虹")
        assertEquals(listOf(2L), r.map { it.id })
    }

    @Test
    fun `constraint matches location`() {
        val r = ChatViewModel.filterInSet(set, "海南")
        assertEquals(listOf(3L), r.map { it.id })
    }

    @Test
    fun `no match returns empty`() {
        val r = ChatViewModel.filterInSet(set, "太空")
        assertEquals(0, r.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.GallerySearchRefinementTest"`
Expected: FAIL（`ChatViewModel.filterInSet` 未定义）

- [ ] **Step 3: Implement Delegate + state + filter**

在 `ChatViewModel` 加 import：

```kotlin
import com.mamba.picme.features.chat.capability.ChatSearchCapability
import com.mamba.picme.features.chat.capability.SearchOutcome
```

让 `ChatViewModel` 实现 `ChatSearchCapability.Delegate`：

```kotlin
class ChatViewModel(
    dependencies: ChatViewModelDependencies
) : ViewModel(), ChatSearchCapability.Delegate {
```

在类字段区（`private val orchestrator = ...` 附近）加：

```kotlin
    private val mediaSearchEngine = dependencies.mediaSearchEngine
    private val lastResultAssets = mutableMapOf<String, List<MediaAsset>>() // sessionId -> 全量命中
```

在 companion 加纯函数：

```kotlin
        /** in-set 过滤：对已持有的结果集按 constraint 命中 labels/ocr/location/fileName。 */
        fun filterInSet(assets: List<MediaAsset>, constraint: String): List<MediaAsset> {
            val kw = constraint.trim()
            if (kw.isEmpty()) return assets
            return assets.filter { a ->
                listOfNotNull(a.labels, a.ocrText, a.locationName, a.fileName)
                    .any { it.contains(kw, ignoreCase = true) }
            }
        }
```

实现两个 Delegate 方法（放在类内任意位置，如 `cleanupIfNeeded` 之前）：

```kotlin
    override suspend fun onSearchMedia(query: String): SearchOutcome {
        val sessionId = _currentSessionId.value
        val result = runCatching { mediaSearchEngine.search(query) }.getOrElse {
            return SearchOutcome(query = query, mediaIds = emptyList(), totalCount = 0, isRefinement = false)
        }
        val photos = result.media.filter { it.type == MediaType.PHOTO }
        lastResultAssets[sessionId] = photos
        return SearchOutcome(
            query = query,
            mediaIds = photos.map { it.id },
            totalCount = photos.size,
            isRefinement = false
        )
    }

    override suspend fun onRefineMediaSearch(constraint: String): SearchOutcome {
        val sessionId = _currentSessionId.value
        val prior = lastResultAssets[sessionId].orEmpty()
        // 无上一轮 → 当 fresh 全局搜
        if (prior.isEmpty()) return onSearchMedia(constraint)
        val refined = filterInSet(prior, constraint)
        // in-set 空 → 回退全局重搜 constraint
        if (refined.isEmpty()) {
            val global = runCatching { mediaSearchEngine.search(constraint) }.getOrNull()
            val photos = global?.media?.filter { it.type == MediaType.PHOTO }.orEmpty()
            lastResultAssets[sessionId] = photos
            return SearchOutcome(constraint, photos.map { it.id }, photos.size, isRefinement = true)
        }
        lastResultAssets[sessionId] = refined
        return SearchOutcome(constraint, refined.map { it.id }, refined.size, isRefinement = true)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.GallerySearchRefinementTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt app/src/test/java/com/mamba/picme/features/chat/GallerySearchRefinementTest.kt
git commit -m "feat(chat): implement gallery search delegate + in-set refinement"
```

---

### Task 9: `handleAgentAction` 处理 `MediaResults` + 落库

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（`handleAgentAction` ~446 行）

- [ ] **Step 1: Add the MediaResults branch**

在 `handleAgentAction` 的 `when (action)` 加分支（`AgentAction.TextReply` 分支之后）：

```kotlin
            is AgentAction.MediaResults -> {
                val sessionId2 = sessionId
                val assets = lastResultAssets[sessionId2].orEmpty()
                    .filter { it.id in action.mediaIds }
                    .take(MAX_CARDS)
                val ui = MediaResultsUi(
                    query = action.query,
                    assets = assets,
                    totalCount = action.totalCount,
                    isRefinement = action.isRefinement
                )
                insertMediaResultsMessage(sessionId2, ui)
            }
```

加 import：

```kotlin
import com.mamba.picme.agent.core.model.context.AgentAction
```
（若已存在则跳过）

新增持久化方法（放在 `insertAgentMessage` 附近）：

```kotlin
    private suspend fun insertMediaResultsMessage(sessionId: String, ui: MediaResultsUi) {
        val content = serializeMediaResultsContent(ui.assets)
        val metadata = serializeMediaResultsMetadata(ui.query, ui.totalCount, ui.isRefinement)
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = "media_results",
                content = content,
                modelUsed = "gallery_search",
                metadata = metadata
            )
        )
        chatSessionDao.touchSession(sessionId)
    }
```

> `lastResultAssets[sessionId]` 在 Delegate 方法执行时已写入（dispatch 期间先调 `onSearchMedia`/`onRefineMediaSearch`，再返回 action，再到 `handleAgentAction`），故此处可读到。

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(chat): render AgentAction.MediaResults into persisted message"
```

---

### Task 10: 回退直连（QuickActionBar「搜相册」chip + VM 直接搜索）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/components/QuickActionBar.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 1: Add direct-search API to ChatViewModel**

在 `ChatViewModel` 加公开方法：

```kotlin
    /**
     * 回退直连：不经 Agent，直接把文本喂 MediaSearchEngine（LLM 不可用时可用）。
     * 单轮，结果作为 MediaResults 消息插入。
     */
    fun searchGalleryDirectly(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                ensureSessionExists(sessionId)
                _isProcessing.value = true
                val outcome = onSearchMedia(text)
                val assets = lastResultAssets[sessionId].orEmpty().take(MAX_CARDS)
                val ui = MediaResultsUi(
                    query = outcome.query,
                    assets = assets,
                    totalCount = outcome.totalCount,
                    isRefinement = false
                )
                // 用户消息也落一条
                chatMessageDao.insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        type = "user_text",
                        content = text,
                        modelUsed = null
                    )
                )
                chatSessionDao.touchSession(sessionId)
                insertMediaResultsMessage(sessionId, ui)
                cleanupIfNeeded(sessionId)
            } catch (e: Exception) {
                Logger.e(TAG, "Direct gallery search failed", e)
                insertAgentMessage(sessionId, "搜索失败：${e.message ?: "未知错误"}", "error")
            } finally {
                _isProcessing.value = false
            }
        }
    }
```

- [ ] **Step 2: Add「搜相册」chip to QuickActionBar**

阅读 `QuickActionBar.kt` 现有结构（它是一组 chip 的 Row），按现有 chip 的写法新增一个，点击调用回调。在 `QuickActionBar` 的参数列表加：

```kotlin
    onSearchGallery: (String) -> Unit,
```

并在 chip Row 中（仿照现有 chip 的 `FilterChip`/`AssistChip` 写法）加：

```kotlin
        AssistChip(
            onClick = { onSearchGallery("") }, // 空串触发：UI 层把当前输入框文本带入
            label = { Text("🔍 搜相册") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }
        )
```

> 具体 chip 组件类型（`FilterChip`/`AssistChip`/`SuggestionChip`）与 `QuickActionBar` 现有一致；若该组件签名与上述不符，按文件内现有 chip 的确切声明形式对齐。`onSearchGallery("")` 中的空串是占位——实际实现：让 ChatScreen 把输入框当前文本传入（见 Task 11 Step 2）。

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt app/src/main/java/com/mamba/picme/features/chat/components/QuickActionBar.kt
git commit -m "feat(chat): add direct gallery search fallback (搜相册 chip)"
```

---

### Task 11: ChatScreen 渲染 MediaResults + 卡片点击进 MediaPager + Delegate 绑定

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: Bind/unbind Delegate**

在 `ChatScreen` 的 `LaunchedEffect`/`DisposableEffect` 区（生命周期绑定处）加：

```kotlin
import com.mamba.picme.features.chat.capability.ChatSearchCapability

// 在 ChatScreen composable 内：
DisposableEffect(Unit) {
    ChatSearchCapability.getInstance().bindDelegate(viewModel)
    onDispose { ChatSearchCapability.getInstance().unbindDelegate() }
}
```

> 仿照 `GalleryScreen` 绑定 `GalleryCapability` delegate 的写法（在 `features/gallery/GalleryScreen.kt` 搜 `bindDelegate`）。

- [ ] **Step 2: Wire QuickActionBar 的搜相册 chip**

在 `ChatScreen` 调用 `QuickActionBar(...)` 处，传入：

```kotlin
    onSearchGallery = { _ -> viewModel.searchGalleryDirectly(currentInputText) }
```

> `currentInputText` 是 ChatScreen 中输入框的当前文本 state（找到 `sendMessage` 读取的那个 `var input by remember { mutableStateOf("") }`，用其变量名）。

- [ ] **Step 3: Render MEDIA_RESULTS message**

在消息列表渲染处（`displayMessages` 遍历渲染各 `ChatMessageType` 的 `when` 分支），加：

```kotlin
import com.mamba.picme.features.chat.components.MediaResultsCarousel

// 在渲染单条消息的 when(msg.type) 中：
    ChatMessageType.MEDIA_RESULTS -> {
        val mr = msg.mediaResults ?: return@item  // 跳过空
        MediaResultsCarousel(
            mediaResults = mr,
            onCardClick = { index -> previewAssets = mr.assets; previewIndex = index }
        )
    }
```

在 `ChatScreen` 顶层加预览 state：

```kotlin
    var previewAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
```

并在 `ChatScreen` 末尾叠加 `MediaPager`（仅当 `previewAssets` 非空时）：

```kotlin
import com.mamba.picme.features.gallery.components.MediaPager

    if (previewAssets.isNotEmpty()) {
        MediaPager(
            assets = previewAssets,
            initialIndex = previewIndex,
            onClose = { previewAssets = emptyList() },
            onDelete = { /* v1 stub: 仅关闭 */ previewAssets = emptyList() },
            onStartOcr = { /* v1 stub */ },
            onDismissOcr = { /* v1 stub */ },
            ocrState = kotlinx.coroutines.flow.MutableStateFlow<MediaViewModel.OcrResult?>(null),
            onNavigateToEditor = { /* v1 stub */ },
            onAiOptimize = { /* v1 stub */ },
            voiceCoordinator = null,
            onReTag = {}
        )
    }
```

> `MediaViewModel.OcrResult` 类型以 `MediaPager` 实际签名为准（`features/gallery/components/MediaPager.kt:148`）；若 import 路径不同，按该文件 import 对齐。`MediaPager` 的非必要回调首版传 stub/空值，仅 `onClose` 实装。

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（若 `MediaPager` 参数与上述不符，按 `MediaPager.kt:141` 实际签名对齐）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): render media results carousel + open MediaPager on card click"
```

---

### Task 12: `MediaResultsCarousel` + `MediaCard` 组件

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt`

- [ ] **Step 1: Write the composables**

```kotlin
package com.mamba.picme.features.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.features.chat.MediaResultsUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相册搜索结果横滑卡片 carousel。插入 chat 对话流。
 *
 * @param onCardClick 点击卡片，参数为在 assets 中的 index（用于 MediaPager initialIndex）
 * @param onViewAll 点击「在相册查看全部」
 */
@Composable
fun MediaResultsCarousel(
    mediaResults: MediaResultsUi,
    onCardClick: (Int) -> Unit,
    onViewAll: () -> Unit = {}
) {
    val mr = mediaResults
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            text = if (mr.isRefinement) "细化：${mr.query}（${mr.assets.size} 张）"
                   else "找到 ${mr.totalCount} 张「${mr.query}」的照片",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (mr.assets.isEmpty()) {
            Text(
                text = "未找到「${mr.query}」的照片，换个词试试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            return@Column
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(mr.assets) { index, asset ->
                MediaCard(asset = asset, onClick = { onCardClick(index) })
            }
            if (mr.totalCount > mr.assets.size) {
                item {
                    TextButton(onClick = onViewAll, modifier = Modifier.width(120.dp)) {
                        Text("在相册查看全部")
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCard(asset: MediaAsset, onClick: () -> Unit) {
    val dateText = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(asset.captureDate))
    }.getOrDefault("")
    Card(
        onClick = onClick,
        modifier = Modifier.size(width = 120.dp, height = 150.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = asset.uri,
                contentDescription = asset.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().size(height = 120.dp, width = 120.dp).clip(RoundedCornerShape(12.dp))
            )
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp)
            )
        }
    }
}
```

> `LazyRow` 的 `itemsIndexed` 来自 `androidx.compose.foundation.lazy.items`（已 import）。若 ktlint 报 wildcard/全限定名，按 `app` 现有 import 规范调整。

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（确认 `itemsIndexed` import 正确：`androidx.compose.foundation.lazy.itemsIndexed`）

- [ ] **Step 3: Write a Compose UI test（instrumented）**

`app/src/androidTest/java/com/mamba/picme/features/chat/MediaResultsCarouselTest.kt`：

```kotlin
package com.mamba.picme.features.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.features.chat.components.MediaResultsCarousel
import org.junit.Rule
import org.junit.Test

class MediaResultsCarouselTest {
    @get:Rule val rule = createComposeRule()

    private val assets = List(3) { i ->
        MediaAsset(id = i.toLong(), uri = "u$i", type = MediaType.PHOTO, captureDate = 0L, fileName = "f$i.jpg")
    }

    @Test
    fun renders_header_and_cards() {
        val mr = MediaResultsUi(query = "海边", assets = assets, totalCount = 3, isRefinement = false)
        rule.setContent { MediaResultsCarousel(mediaResults = mr, onCardClick = {}, onViewAll = {}) }
        rule.onAllNodesWithText("找到 3 张「海边」的照片").assertCountEquals(1)
    }

    @Test
    fun card_click_invokes_index() {
        val mr = MediaResultsUi(query = "海边", assets = assets, totalCount = 3, isRefinement = false)
        var clicked = -1
        rule.setContent { MediaResultsCarousel(mediaResults = mr, onCardClick = { clicked = it }, onViewAll = {}) }
        // 点第一张卡片（AsyncImage 的 contentDescription = fileName）
        rule.onAllNodesWithText("f0.jpg")[0].performClick()
        org.junit.Assert.assertEquals(0, clicked)
    }
}
```

> Compose 测试需设备/模拟器：`./gradlew :app:connectedDebugAndroidTest`。若项目用 Robolectric 可放 `src/test` 用 `createRobolectricRule`。按仓库现有 Compose 测试惯例对齐。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt app/src/androidTest/java/com/mamba/picme/features/chat/MediaResultsCarouselTest.kt
git commit -m "feat(chat): add MediaResultsCarousel + MediaCard composables"
```

---

### Task 13: 「在相册查看全部」跳转 GalleryScreen 带 initialQuery

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt` + 导航图（`MainActivity.kt` 或 nav 定义处）
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（接 `onViewAll`）

- [ ] **Step 1: Add initialQuery param to GalleryScreen route**

在导航图（`MainActivity.kt` 中定义 `composable("gallery")` 处，或等价路由定义）把 gallery 路由改为支持 query 参数：

```kotlin
composable("gallery?query={query}", arguments = listOf(navArgument("query") { type = NavType.StringType; defaultValue = "" })) {
    GalleryScreen(initialQuery = it.arguments?.getString("query").orEmpty())
}
```

> 按仓库实际 nav 写法对齐（`androidx.navigation` 的 `composable` + `navArgument`）。若 gallery 路由名不同，用实际名。

- [ ] **Step 2: GalleryScreen 接收并预填**

在 `GalleryScreen` 签名加 `initialQuery: String = ""`，并在进入时（`LaunchedEffect(Unit)`）若非空则把它填入 `SearchTopBar` 的查询 state 并触发搜索（仿照 `SearchTopBar` 现有「输入即搜」逻辑）：

```kotlin
@Composable
fun GalleryScreen(initialQuery: String = "", /* 现有参数 */) {
    // ... 现有 query state ...
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            searchQuery = initialQuery          // 用 SearchTopBar 实际的 query state 变量名
            onSearchTriggered(initialQuery)     // 用现有触发搜索的函数名
        }
    }
    // ...
}
```

> 变量名/函数名以 `GalleryScreen.kt` 与 `SearchTopBar.kt` 现有实现为准。

- [ ] **Step 3: ChatScreen 接 onViewAll**

在 `ChatScreen` 调 `MediaResultsCarousel` 处把 `onViewAll` 接到导航：

```kotlin
        MediaResultsCarousel(
            mediaResults = mr,
            onCardClick = { index -> previewAssets = mr.assets; previewIndex = index },
            onViewAll = { navController.navigate("gallery?query=${java.net.URLEncoder.encode(mr.query, "UTF-8")}") }
        )
```

> `navController` 用 ChatScreen 现有的 NavController 变量名。

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt app/src/main/java/com/mamba/picme/MainActivity.kt app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): view-all jumps to gallery with initialQuery filter"
```

---

### Task 14: 全量验证 + 文档同步

- [ ] **Step 1: Run JVM tests**

Run: `./gradlew :runtime-core:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（含 RefineMediaSearchTest / MediaResultsActionTest / MediaResultsSerializationTest / GallerySearchRefinementTest）

- [ ] **Step 2: Compile full app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: (若有设备) 端到端手测 + Compose 测试**

Run: `./gradlew :app:connectedDebugAndroidTest`
手测：ChatScreen 输入「找去年夏天的照片」→ 出现 carousel → 点卡片进 MediaPager → 返回；再输入「这些里有没有海边的」→ 细化结果；点「在相册查看全部」→ 跳相册带筛选。

- [ ] **Step 4: 文档同步**

按 CLAUDE.md 三层文档体系，更新 `app/features/chat/AGENTS.md`（若存在）或 `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md` 相关节点，注明 chat 相册搜索能力。若 chat 无 AGENTS.md，在 `features/common/chat/AGENTS.md` 加一节「相册搜索 carousel」。

- [ ] **Step 5: Final commit**

```bash
git add <doc paths>
git commit -m "docs(chat): document gallery search carousel feature"
```

---

## Self-Review

**Spec coverage：**
- §3 数据流 fresh/细化 → Task 8（onSearchMedia/onRefineMediaSearch）+ Task 9（handleAgentAction）✓
- §4 组件（ChatSearchCapability/Delegate/RefineMediaSearch/MediaResults/VM/消息模型/Carousel/MediaCard/ChatScreen/QuickActionBar/GalleryScreen）→ Task 3/1/2/8/6/12/11/10/13 ✓
- §5 持久化 → Task 7 ✓
- §6 回退直连 → Task 10 ✓
- §7 Carousel + 预览 + 查看全部 → Task 12/11/13 ✓
- §8 错误边界（空态/异常/in-set 空/无上一轮/删除跳过/>20/仅照片）→ Task 8（PHOTO filter、in-set 空回退、无上一轮当 fresh）、Task 12（空态卡）、Task 9（take MAX_CARDS=20）✓
- §9 测试 → Task 1/2/7/8/12 ✓

**类型一致性：** `SearchOutcome(query, mediaIds, totalCount, isRefinement)` 在 Task 3 定义，Task 8 返回、Task 9 消费——一致。`MediaResultsUi(query, assets, totalCount, isRefinement)` Task 6 定义，Task 7/9/12 消费——一致。`AgentAction.MediaResults(commandId, query, mediaIds, totalCount, isRefinement)` Task 2 定义，Task 3/9 消费——一致。`MAX_CARDS=20` Task 7 定义，Task 9/10 用——一致。

**已知需对齐项（非占位，执行时按现场签名对齐）：** `MediaPager` 参数（Task 11 Step 3）、`QuickActionBar` chip 组件（Task 10 Step 2）、`GalleryScreen`/nav 写法（Task 13）、`BaseCapability`/`AgentErrorCode` 签名（Task 3 Step 2）——这些文件已在 plan 中给出确切路径与参照位置。
