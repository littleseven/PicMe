# Chat 回复语言跟随 App 界面语言 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chat 的 LLM 回复始终跟随 App 界面语言（English/简中/繁中），双端（Android + iOS）同步落地。

**Architecture:** `AgentContext` 显式携带 `replyLanguage`（默认 `SIMPLIFIED_CHINESE` 保持 camera/飞书等存量调用方零变化）；语言规则段在 `KoogChatAgent.composeSystemPrompt`（双端共享的 per-run 组装点）拼到 system prompt 最末尾（含日期行与记忆快照之后，近因效应最强）；`Android ChatViewModel` 从 `userSettingsRepository.getAppLanguageBlocking()` + 系统 Locale 解析，iOS 经 `ChatAgentBridge.sendMessage` 新增 `replyLanguageTag` 参数由 Swift 侧传入。

**Spec:** `docs/superpowers/specs/2026-08-22-chat-reply-language-design.md`

**⚠️ 对 spec 的一处注入点修正（规划期发现，意图不变）：** spec §2.3/§2.4 写「`buildChatSystemPrompt`/`IosChatPrompt.build` 加 `replyLanguage` 参数」。勘查确认 base prompt 在 `RemoteChatEngine` 构建期一次性烘焙（`chatSystemPrompt` val + `cachedChatAgent` 缓存），而 `KoogChatAgent.composeSystemPrompt` 是 **per-run** 组装点且**双端共享**（iOS 仅注入不同 base prompt builder）。改在 `composeSystemPrompt` 追加规则段：语言切换下一条消息即生效（重建键含 replyLanguage）、iOS 零 prompt 代码改动、现有 golden test 保持绿色。规则文本常量仍集中 commonMain 双端共用。spec 文档在 Task 7 回写此修正。

**Tech Stack:** Kotlin Multiplatform（`:shared` commonMain/iosMain）、Koog `AIAgent`、Android Compose ViewModel + DataStore、SwiftUI。

---

### Task 0: 创建隔离工作区

按根 `AGENTS.md` §3.4，代码改动必须在隔离 worktree 进行。

- [ ] **Step 1: 创建 worktree 与专用分支**

```bash
git worktree add .worktrees/chat-reply-language -b feat/chat-reply-language
cd .worktrees/chat-reply-language
```

后续所有 Task 均在该 worktree 内执行，提交落在 `feat/chat-reply-language` 分支。

---

### Task 1: ReplyLanguage 模型 + 解析函数 + 三语规则段（commonMain）

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguage.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguageTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguageTest.kt`：

```kotlin
package com.mamba.picme.agent.core.model.context

import com.mamba.picme.domain.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ReplyLanguage 解析与规则段契约测试（2026-08-22 chat 回复语言跟随界面语言）。
 *
 * 钉住三条规则：
 * 1. AppLanguage 显式选择直达对应 ReplyLanguage；
 * 2. SYSTEM 按 locale tag 解析（zh-Hant/TW/HK/MO → 繁中，其余 zh → 简中，非 zh → ENGLISH）；
 * 3. 三语规则段文本非空且含各自语言的关键指令（防常量被误改/误删）。
 */
class ReplyLanguageTest {

    @Test
    fun `explicit app language maps directly`() {
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.ENGLISH.toReplyLanguage("zh-CN"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.CHINESE.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.TRADITIONAL_CHINESE.toReplyLanguage("en-US"))
    }

    @Test
    fun `system resolves by locale tag`() {
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-CN"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hans-CN"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-SG"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-TW"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hant-TW"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-HK"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-MO"))
    }

    @Test
    fun `system falls back to english for non-chinese locales`() {
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("ja-JP"))
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("ko-KR"))
    }

    @Test
    fun `replyLanguageFromTag tolerates underscore separators`() {
        // iOS Locale.current.identifier 用下划线（如 zh_TW / en_US）
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, replyLanguageFromTag("zh_TW"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, replyLanguageFromTag("zh_CN"))
        assertEquals(ReplyLanguage.ENGLISH, replyLanguageFromTag("en_US"))
    }

    @Test
    fun `rule segments contain language imperative`() {
        assertTrue(replyLanguageRuleSegment(ReplyLanguage.ENGLISH).contains("Always reply to the user in English"))
        assertTrue(replyLanguageRuleSegment(ReplyLanguage.SIMPLIFIED_CHINESE).contains("始终用简体中文回复"))
        assertTrue(replyLanguageRuleSegment(ReplyLanguage.TRADITIONAL_CHINESE).contains("始終用繁體中文回覆"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
JITPACK=true ./gradlew :shared:jvmTest --tests "*ReplyLanguageTest*"
```

预期：FAIL（编译错误，`ReplyLanguage` 未定义）。

- [ ] **Step 3: 实现**

创建 `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguage.kt`：

```kotlin
package com.mamba.picme.agent.core.model.context

import com.mamba.picme.domain.model.AppLanguage

/**
 * Chat 回复语言（2026-08-22，spec: docs/superpowers/specs/2026-08-22-chat-reply-language-design.md）。
 *
 * 与 [AppLanguage] 分离：AppLanguage.SYSTEM 不是具体回复语言，必须经
 * [AppLanguage.toReplyLanguage] 结合系统 locale 解析后使用。
 * 默认值见 [AgentContext.replyLanguage]——SIMPLIFIED_CHINESE 保持 camera/飞书等
 * 存量调用方（不传该字段）行为与现状一致。
 */
enum class ReplyLanguage {
    SIMPLIFIED_CHINESE,
    TRADITIONAL_CHINESE,
    ENGLISH
}

/**
 * 按 BCP-47 locale tag 解析回复语言。容忍 `_` 分隔符（iOS `Locale.current.identifier`）。
 * zh-Hant/TW/HK/MO → 繁中；其余 zh → 简中；非 zh（含日/韩/法等）→ ENGLISH。
 */
fun replyLanguageFromTag(localeTag: String): ReplyLanguage {
    val tag = localeTag.replace('_', '-').lowercase()
    if (!tag.startsWith("zh")) return ReplyLanguage.ENGLISH
    return if (tag.contains("hant") ||
        tag.contains("-tw") || tag.contains("-hk") || tag.contains("-mo")
    ) {
        ReplyLanguage.TRADITIONAL_CHINESE
    } else {
        ReplyLanguage.SIMPLIFIED_CHINESE
    }
}

/**
 * App 语言设置 → 回复语言。[systemLocaleTag] 仅在 SYSTEM 时消费
 *（Android 传 `Locale.getDefault().toLanguageTag()`；iOS 传 `Locale.current.identifier`）。
 */
fun AppLanguage.toReplyLanguage(systemLocaleTag: String): ReplyLanguage = when (this) {
    AppLanguage.ENGLISH -> ReplyLanguage.ENGLISH
    AppLanguage.CHINESE -> ReplyLanguage.SIMPLIFIED_CHINESE
    AppLanguage.TRADITIONAL_CHINESE -> ReplyLanguage.TRADITIONAL_CHINESE
    AppLanguage.SYSTEM -> replyLanguageFromTag(systemLocaleTag)
}

/**
 * 追加到 chat system prompt 最末尾的语言规则段（KoogChatAgent.composeSystemPrompt 拼装）。
 *
 * 规则文本本身用目标语言书写（自我强化），并显式对抗全中文 base prompt 与
 * 中文工具输出的引力。三语文本集中此处，双端共用，防漂移。
 */
internal fun replyLanguageRuleSegment(language: ReplyLanguage): String = when (language) {
    ReplyLanguage.ENGLISH ->
        "The app's UI language is English. Always reply to the user in English, " +
            "regardless of the language of this prompt, tool descriptions, or tool outputs. " +
            "Tool results may be in Chinese — summarize and present them in English."
    ReplyLanguage.SIMPLIFIED_CHINESE ->
        "App 界面语言为简体中文。请始终用简体中文回复用户，无论本提示词、工具描述或工具返回内容" +
            "使用何种语言。工具返回内容可能是英文或其他语言——请用简体中文总结转述。"
    ReplyLanguage.TRADITIONAL_CHINESE ->
        "App 介面語言為繁體中文。請始終用繁體中文回覆使用者，無論本提示詞、工具描述或工具回傳內容" +
            "使用何種語言。工具回傳內容可能是英文或其他語言——請用繁體中文總結轉述。"
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
JITPACK=true ./gradlew :shared:jvmTest --tests "*ReplyLanguageTest*"
```

预期：PASS（5 用例）。

- [ ] **Step 5: 提交**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguage.kt \
        shared/src/commonTest/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguageTest.kt
git commit -m "feat(shared): ReplyLanguage 模型 + locale 解析 + 三语 chat 回复规则段"
```

---

### Task 2: AgentContext 增加 replyLanguage 字段

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/AgentModels.kt:24-41`

- [ ] **Step 1: 加字段**

`AgentModels.kt` 的 `AgentContext` 在 `traceId` 字段后追加（data class 末尾、带默认值，存量构造点零改动）：

```kotlin
    /** 一次用户消息的关联 ID，贯穿该轮 LLM/tool/JS 三层日志；非 chat 来源为 null。 */
    val traceId: String? = null,
    /**
     * Chat 回复语言（默认 SIMPLIFIED_CHINESE = 现状行为）。
     * 仅 chat 链路消费（KoogChatAgent 拼 prompt 语言规则段）；
     * camera/飞书等调用方不传即保持现状。
     */
    val replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE
```

同时在类 KDoc 的 `@property` 列表追加一行：

```kotlin
 * @property replyLanguage chat 回复语言（仅 chat 链路消费，默认简体中文保持现状）
```

- [ ] **Step 2: 编译 + 全量单测确认零回归**

```bash
JITPACK=true ./gradlew :shared:jvmTest
```

预期：PASS（含既有 `ChatSystemPromptGoldenTest`——base prompt 未动，golden 必须保持绿色）。

- [ ] **Step 3: 提交**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/AgentModels.kt
git commit -m "feat(shared): AgentContext 增加 replyLanguage 字段（默认简体中文保持现状）"
```

---

### Task 3: KoogChatAgent per-run 语言规则注入

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogChatAgent.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogChatAgentReplyLanguageTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogChatAgentReplyLanguageTest.kt`（fixture 复刻 `KoogAgentProtocolPassthroughTest`）：

```kotlin
package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.Message
import com.mamba.picme.agent.core.inference.remote.react.RemoteReActAgentConfig
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import com.mamba.picme.agent.core.platform.storage.ChatMemoryStore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * chat system prompt 语言规则段注入契约测试（2026-08-22）。
 *
 * 钉住：规则段拼在 base prompt（含日期行）与【关于用户】记忆快照**之后**
 * （prompt 最末尾，近因效应最强），且三语各取对应文本。
 */
class KoogChatAgentReplyLanguageTest {

    private val fakeMemoryStore = object : ChatMemoryStore {
        override suspend fun load(sessionId: String): List<Message> = emptyList()
        override suspend fun save(sessionId: String, messages: List<Message>) {}
        override suspend fun clear(sessionId: String) {}
    }

    private fun buildAgent(): KoogChatAgent =
        KoogChatAgent(
            config = RemoteReActAgentConfig.Builder()
                .apiKey("test-key")
                .baseUrl("https://example.com/")
                .modelName("test-model")
                .systemPrompt("BASE_PROMPT\n\n当前日期：2026-08-22。")
                .build(),
            toolRegistry = ToolRegistry {},
            memoryStore = fakeMemoryStore,
        )

    @Test
    fun `english rule appended after memory snapshot`() {
        val prompt = buildAgent().composeSystemPrompt("【关于用户】喜欢猫", ReplyLanguage.ENGLISH)
        assertTrue(prompt.startsWith("BASE_PROMPT"))
        assertTrue(prompt.contains("【关于用户】喜欢猫"))
        assertTrue(prompt.endsWith("summarize and present them in English."))
    }

    @Test
    fun `simplified chinese rule appended when no snapshot`() {
        val prompt = buildAgent().composeSystemPrompt(null, ReplyLanguage.SIMPLIFIED_CHINESE)
        assertTrue(prompt.endsWith("请用简体中文总结转述。"))
    }

    @Test
    fun `traditional chinese rule segment`() {
        val prompt = buildAgent().composeSystemPrompt(null, ReplyLanguage.TRADITIONAL_CHINESE)
        assertTrue(prompt.endsWith("請用繁體中文總結轉述。"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
JITPACK=true ./gradlew :shared:jvmTest --tests "*KoogChatAgentReplyLanguageTest*"
```

预期：FAIL（编译错误，`composeSystemPrompt` 为 private 且签名不符）。

- [ ] **Step 3: 实现 KoogChatAgent 改动**

`KoogChatAgent.kt` 三处改动：

① import 追加：

```kotlin
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import com.mamba.picme.agent.core.model.context.replyLanguageRuleSegment
```

② `runChat` 增加 per-run 参数 + 字段持有（在 `traceId` 参数后；带默认值保持其他调用点/测试零改动）：

```kotlin
    suspend fun runChat(
        input: String,
        traceId: String?,
        replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE,
        onPartialText: (snapshot: String) -> Unit,
        onToolCall: (toolName: String, args: String) -> Unit,
    ): Pair<String, AgentExecutionMetrics> {
        running = true
        traceIdHolder.value = traceId
        currentReplyLanguage = replyLanguage
        // ……以下保持原样
```

字段声明（放在 `builtSnapshot`/`builtAgent` 声明旁）：

```kotlin
    /** 当轮回复语言（runChat 开头写入；参与 AIAgent 重建键，语言切换下一条消息即生效）。 */
    @Volatile private var currentReplyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE
    private var builtReplyLanguage: ReplyLanguage? = null
```

③ `agent()` 重建键 + `composeSystemPrompt` 改签名并追加规则段（`private` → `internal`，注释说明测试可见性）：

```kotlin
    /** 取或按记忆快照新鲜度 + 回复语言重建 AIAgent。 */
    private fun agent(): AIAgent<String, String> {
        val snapshot = config.memoryContextProvider?.snapshot()?.trim()?.ifEmpty { null }
        val cached = builtAgent
        if (cached != null && snapshot == builtSnapshot && currentReplyLanguage == builtReplyLanguage) {
            return cached
        }
        val agent = buildAgent(composeSystemPrompt(snapshot, currentReplyLanguage))
        builtAgent = agent
        builtSnapshot = snapshot
        builtReplyLanguage = currentReplyLanguage
        Logger.i(tag, "Built Koog AIAgent: model=${config.modelName}, snapshotLen=${snapshot?.length ?: 0}, replyLanguage=$currentReplyLanguage")
        return agent
    }

    /**
     * base system prompt + 【关于用户】记忆快照 + 回复语言规则段（规则段在最末尾，近因效应最强；
     * 快照空则零开销）。internal：commonTest 契约测试直接断言拼装结果。
     */
    internal fun composeSystemPrompt(snapshot: String?, replyLanguage: ReplyLanguage): String {
        val base = config.systemPrompt
        val withMemory = if (snapshot.isNullOrBlank()) base else "$base\n\n$snapshot"
        return withMemory + "\n\n" + replyLanguageRuleSegment(replyLanguage)
    }
```

同时把类 KDoc「记忆快照新鲜度」一段末尾补一句：「重建键 = 记忆快照 + 回复语言（2026-08-22）。」

- [ ] **Step 4: 跑测试确认通过**

```bash
JITPACK=true ./gradlew :shared:jvmTest --tests "*KoogChatAgent*"
```

预期：PASS（新 3 用例 + 既有 `KoogAgentProtocolPassthroughTest` 保持绿色——`runChat` 新参数带默认值）。

- [ ] **Step 5: 提交**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogChatAgent.kt \
        shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogChatAgentReplyLanguageTest.kt
git commit -m "feat(shared): KoogChatAgent per-run 回复语言规则注入（重建键含 replyLanguage）"
```

---

### Task 4: RemoteChatEngine 透传 replyLanguage

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/RemoteChatEngine.kt:150-252`

- [ ] **Step 1: 改透传链**

① import 追加：

```kotlin
import com.mamba.picme.agent.core.model.context.ReplyLanguage
```

② `streamChatReAct`（:162-198）调 `processChatReAct` 处加参数：

```kotlin
            processChatReAct(
                input,
                agentContext.memorySessionId,
                replyLanguage = agentContext.replyLanguage,
                traceId = agentContext.traceId,
                onEvent = onEvent
            )
```

③ `processChatReAct`（:210-216）签名加参数（带默认值，internal 方法的其他调用点/测试零改动）：

```kotlin
    internal suspend fun processChatReAct(
        input: String,
        sessionId: String,
        replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE,
        timeoutMs: Long = 120_000L,
        traceId: String? = null,
        onEvent: ((ChatStreamEvent) -> Unit)? = null
    )
```

④ 同方法内 `agent.runChat(...)` 调用加 `replyLanguage = replyLanguage`（:231-242 的参数块中，放 `traceId = traceId,` 之后）。

- [ ] **Step 2: 全量单测 + 编译确认**

```bash
JITPACK=true ./gradlew :shared:jvmTest
```

预期：PASS（base prompt 零变化，`ChatSystemPromptGoldenTest` 保持绿色；`streamChat` 签名未变，androidApp 侧 mock 测试不受影响）。

- [ ] **Step 3: 提交**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/RemoteChatEngine.kt
git commit -m "feat(shared): RemoteChatEngine 透传 AgentContext.replyLanguage 至 KoogChatAgent"
```

---

### Task 5: Android ChatViewModel 接线

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt:1208-1216`

- [ ] **Step 1: sendMessage 构建 AgentContext 时解析并填入回复语言**

import 追加（`java.util.Locale` 若已有则复用；先检查文件头部 import 区）：

```kotlin
import com.mamba.picme.agent.core.model.context.toReplyLanguage
```

`sendMessage` 第 4 步（:1209-1216）改为：

```kotlin
                // 4. 构建 Agent 上下文
                // 回复语言跟随 App 界面语言（spec: 2026-08-22-chat-reply-language-design.md）：
                // 每次发消息时重新解析——设置页切语言后下一条消息即生效。
                // 注意：AppLanguage 非 SYSTEM 时系统 locale 不参与解析，故 MainActivity
                // 覆盖 Activity locale 不影响正确性。
                val replyLanguage = userSettingsRepository.getAppLanguageBlocking()
                    .toReplyLanguage(java.util.Locale.getDefault().toLanguageTag())
                val agentContext = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId,
                    recentSearchResults = sessionSearchSnapshots[sessionId].orEmpty(),
                    lastUserImageUri = _lastUserImageUri.value,
                    gallerySummary = gallerySummary,
                    traceId = java.util.UUID.randomUUID().toString(),
                    replyLanguage = replyLanguage
                )
```

（`userSettingsRepository` 字段已存在于 :159，且 `getAppLanguageBlocking()` 在 :2561 已有调用先例。）

- [ ] **Step 2: 编译 + androidApp 相关单测确认**

```bash
./gradlew :androidApp:compileDebugKotlin
./gradlew :androidApp:testDebugUnitTest --tests "*ChatViewModel*"
```

预期：编译成功；`ChatViewModelTitleUpdateTest`/`ChatViewModelStreamingWiringTest`/`ChatViewModelGuestModeTest`/`ChatViewModelNavigationGuardTest` 全部 PASS（`streamChat` 签名未变，mock 不受影响）。

- [ ] **Step 3: 提交**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(androidApp): chat 回复语言跟随 App 界面语言（sendMessage 解析注入 AgentContext）"
```

---

### Task 6: iOS ChatAgentBridge + Swift 接线

**Files:**
- Modify: `shared/src/iosMain/kotlin/com/mamba/picme/agent/core/inference/remote/ChatAgentBridge.kt:60-98`
- Modify: `iosApp/PoLang/Features/Chat/ChatViewModel.swift:206`

- [ ] **Step 1: ChatAgentBridge.sendMessage 加 replyLanguageTag 参数**

import 追加：

```kotlin
import com.mamba.picme.agent.core.model.context.replyLanguageFromTag
```

`sendMessage` 签名与 context 构造改为（参数放 `input` 之后；String 参数规避 K/N 枚举互操作摩擦，解析复用 commonMain 的 `replyLanguageFromTag`）：

```kotlin
    fun sendMessage(
        input: String,
        replyLanguageTag: String,
        onText: (String) -> Unit,
        onToolCall: () -> Unit,
        onComplete: (summary: String, errorMessage: String?) -> Unit
    ) {
        currentJob = bridgeScope.launch {
            try {
                val context = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId,
                    replyLanguage = replyLanguageFromTag(replyLanguageTag)
                )
                // ……其余保持原样
```

同步更新方法 KDoc 加一句：`@param replyLanguageTag 界面语言 locale tag（Swift 侧 AppSettings.locale.identifier），解析为回复语言注入 prompt`。

- [ ] **Step 2: Swift 调用点传入界面语言**

`iosApp/PoLang/Features/Chat/ChatViewModel.swift:206` 调用改为：

```swift
        bridge.sendMessage(
            input: Self.llmInput(text: trimmed, stagedImageUri: stagedLocalId),
            // 回复语言跟随界面语言（spec: 2026-08-22-chat-reply-language-design.md）；
            // AppSettings.locale 已处理 system→Locale.current 回退，每次发送实时读取
            replyLanguageTag: AppSettings.shared.locale.identifier,
            onText: { [weak self] snapshot in
```

（`AppSettings.shared.locale` 见 `iosApp/PoLang/App/PoLangApp.swift:42-49`：english→`en`、chinese_simplified→`zh-Hans`、chinese_traditional→`zh-Hant`、system→`Locale.current`；`replyLanguageFromTag` 内部容忍 `_` 分隔符。）

- [ ] **Step 3: shared 整体编译（含 iOS 三 target metadata）**

```bash
JITPACK=true ./gradlew :shared:assemble
```

预期：BUILD SUCCESSFUL（iosMain 编译错误只有这里能暴露，见 shared/AGENTS.md §5 坑位④）。

- [ ] **Step 4: 重建 SharedKit XCFramework + iOS 工程编译**

按 `skills/ios-build-debug` 流程（XcodeGen + build-shared-kit 增量）：

```bash
bash iosApp/scripts/build-shared-kit.sh
cd iosApp && xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -configuration Debug -destination 'generic/platform=iOS Simulator' build
```

预期：BUILD SUCCEEDED。

- [ ] **Step 5: 提交**

```bash
git add shared/src/iosMain/kotlin/com/mamba/picme/agent/core/inference/remote/ChatAgentBridge.kt \
        iosApp/PoLang/Features/Chat/ChatViewModel.swift
git commit -m "feat(ios): chat 回复语言跟随界面语言（bridge 加 replyLanguageTag 参数）"
```

---

### Task 7: 实机验收 + 文档回写

**Files:**
- Modify: `docs/superpowers/specs/2026-08-22-chat-reply-language-design.md`（回写注入点修正与执行结果）
- Modify: `shared/AGENTS.md` §2 `model/` 行（补 ReplyLanguage）

- [ ] **Step 1: Android 实机验收**

按 `skills/dev-loop` / `skills/android-build-debug` 流程编译安装后人工/自动验证：

1. 设置 → 语言 → English；chat 发英文消息（如 "How many photos do I have?"）→ 回复应为**全英文**；
2. 不重启 App，设置 → 语言 → 繁體中文；chat 再发一条 → 回复应为**繁體中文**；
3. 设置 → 语言 → 简体中文 → 回复简体中文（现状回归）。

- [ ] **Step 2: iOS 模拟器/真机验收**

同三档验证（iOS 设置页语言项：English / 中文 / 繁體中文 / 跟随系统）。

- [ ] **Step 3: spec 回写**

在 spec 文件状态行改为「**已落地（2026-08-22）**」，并追加执行注记：

```markdown
> **执行结果注记（2026-08-22）**：注入点较 §2.3/§2.4 有一处修正——规则段未加进
> `buildChatSystemPrompt`/`IosChatPrompt.build`（base prompt 在 RemoteChatEngine 构建期一次烘焙，
> 改造需动缓存键且 iOS 需另改 prompt builder），实际改在双端共享的 per-run 组装点
> `KoogChatAgent.composeSystemPrompt` 追加（重建键 = 记忆快照 + replyLanguage），
> 语言切换下一条消息即生效、iOS prompt 代码零改动、既有 golden test 保持绿色。
> 意图（规则段在 prompt 最末尾、三语常量 commonMain 集中共用）不变。
```

`shared/AGENTS.md` §2 表格 `model/` 行 `context/` 列举中 `AgentContext` 后补 `ReplyLanguage`。

- [ ] **Step 4: 提交文档回写**

```bash
git add docs/superpowers/specs/2026-08-22-chat-reply-language-design.md shared/AGENTS.md
git commit -m "docs: chat 回复语言 spec 回写执行结果 + shared AGENTS 补 ReplyLanguage"
```

---

## Self-Review 记录

- **Spec 覆盖**：Q1-Q4 裁决 → Task 1-6；prompt 规则段（spec §2.4）→ Task 1 常量 + Task 3 注入（注入点修正已标注并将在 Task 7 回写）；边界情况（spec §2.5）→ Task 1 解析测试 + Task 3 默认值；测试验收（spec §2.6）→ Task 1/3 单测 + Task 7 实机；YAGNI 项全部未触及。
- **类型一致性**：`ReplyLanguage`（Task 1 定义）→ Task 2/3/4 一致引用；`replyLanguageRuleSegment` internal → Task 3 同模块调用 ✓；`toReplyLanguage`/`replyLanguageFromTag` public → androidApp/iosMain 跨 source set 调用 ✓（同模块不同 source set 对 internal 也可见，但 androidApp 是另一模块，故 `toReplyLanguage` 必须 public——已确认定义为 public）。
- **Golden test**：base prompt 全程零改动，`ChatSystemPromptGoldenTest` 在 Task 2/4 作为回归门禁。
