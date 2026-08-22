# Chat 助手性格/语言风格设定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 设置页新增「助手性格」预设选择（默认/温暖贴心/活泼幽默/简洁干练），Chat 回复按所选性格与界面语言注入对应 prompt 性格段，双端（Android/iOS）同源落地。

**Architecture:** `AssistantPersona` 枚举与三语性格段常量定义在 `:shared` commonMain（双端同源）；经 `AgentContext.persona` + `AgentContext.replyLanguage` 逐条消息显式传递；`RemoteChatEngine.getChatAgent` 在拼 system prompt 尾段（日期行之后）追加性格段，persona/语言变化触发 agent 重建，下一条消息即生效。`DEFAULT` 不注入任何段落，现有 golden 逐字节不变。本计划同时落地 `ReplyLanguage` 模型（同日 spec `2026-08-22-chat-reply-language-design.md` 的先行依赖，该 spec 后续复用）。

**Tech Stack:** Kotlin Multiplatform（`:shared` commonMain/iosMain）、Jetpack Compose + DataStore（Android）、SwiftUI + @AppStorage + SharedKit XCFramework（iOS）、kotlin.test（commonTest/jvmTest）。

**Spec:** `docs/superpowers/specs/2026-08-22-assistant-persona-design.md`

**工作区约定：** 按根 AGENTS.md §3.4，执行前先用 superpowers:using-git-worktrees 建隔离工作区；每个 Task 的 commit 只 `git add` 本任务文件。

---

## 文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguage.kt` | `ReplyLanguage` 三语枚举 + `AppLanguage.toReplyLanguage(systemLocaleTag)` | 新建 |
| `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/config/AssistantPersona.kt` | `AssistantPersona` 枚举 + `personaPromptSegment(persona, language)` 三语性格段 | 新建 |
| `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/AgentModels.kt` | `AgentContext` 加 `persona`/`replyLanguage` 字段 | 修改 |
| `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/RemoteChatEngine.kt` | companion 加 `buildPromptSuffix`；`getChatAgent`/`processChatReAct`/`streamChatReAct` 接线 persona+language | 修改 |
| `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguageTest.kt` | toReplyLanguage 全分支 | 新建 |
| `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/model/config/AssistantPersonaTest.kt` | personaPromptSegment 全分支 | 新建 |
| `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/PromptSuffixTest.kt` | buildPromptSuffix 行为锁定 | 新建 |
| `androidApp/.../data/preferences/UserPreferencesRepository.kt` | `assistant_persona` key + flow + update | 修改 |
| `androidApp/.../domain/repository/UserSettingsRepository.kt` | 接口加成员 | 修改 |
| `androidApp/.../features/settings/SettingsViewModel.kt` | stateIn + setter | 修改 |
| `androidApp/.../features/settings/SettingsAiAgent.kt` | `AssistantPersonaSelection` composable | 修改 |
| `androidApp/.../features/settings/SettingsScreen.kt` | REMOTE_MODEL 区块接入 | 修改 |
| `androidApp/src/main/res/values{,-zh-rCN,-zh-rTW}/strings.xml` | 9 个 i18n key ×3 | 修改 |
| `androidApp/.../features/chat/ChatViewModel.kt` | sendMessage 填充 persona/replyLanguage | 修改 |
| `shared/src/iosMain/kotlin/com/mamba/picme/agent/core/inference/remote/ChatAgentBridge.kt` | sendMessage 加 persona/replyLanguage 参数 | 修改 |
| `iosApp/PoLang/Features/Chat/ChatViewModel.swift` | 调用点传 persona + replyLanguage | 修改 |
| `iosApp/PoLang/Features/Settings/SettingsSubPages.swift` | AiAgentSettingsView 加性格区块 | 修改 |
| `iosApp/PoLang/Resources/Localizable.xcstrings` | iOS 文案 | 修改 |
| `specs/screens/settings.yaml` | 固化新设置条目 | 修改 |
| `androidApp/src/main/java/com/mamba/picme/features/settings/AGENTS.md` | 模块文档同步 | 修改 |

---

### Task 1: commonMain `ReplyLanguage` 模型

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

class ReplyLanguageTest {

    @Test
    fun `explicit app languages map directly`() {
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.ENGLISH.toReplyLanguage("zh-CN"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.CHINESE.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.TRADITIONAL_CHINESE.toReplyLanguage("en-US"))
    }

    @Test
    fun `system resolves simplified chinese locales`() {
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-CN"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hans"))
        assertEquals(ReplyLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-SG"))
    }

    @Test
    fun `system resolves traditional chinese locales`() {
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-TW"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-Hant-HK"))
        assertEquals(ReplyLanguage.TRADITIONAL_CHINESE, AppLanguage.SYSTEM.toReplyLanguage("zh-MO"))
    }

    @Test
    fun `system falls back to english for non chinese locales`() {
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("en-US"))
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("ja-JP"))
        assertEquals(ReplyLanguage.ENGLISH, AppLanguage.SYSTEM.toReplyLanguage("fr-FR"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*ReplyLanguageTest*"`
Expected: 编译失败（`ReplyLanguage` / `toReplyLanguage` 未定义）

- [ ] **Step 3: 实现**

创建 `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguage.kt`：

```kotlin
package com.mamba.picme.agent.core.model.context

import com.mamba.picme.domain.model.AppLanguage

/**
 * Chat 回复语言（解析后的具体语言，非设置项）。
 *
 * 与 [AppLanguage] 分离：`AppLanguage.SYSTEM` 不是具体回复语言，必须经
 * [toReplyLanguage] 结合系统 locale 解析后使用。
 *
 * 先行落地供助手性格段选语言（2026-08-22-assistant-persona-design.md）；
 * 「回复语言跟随界面语言」规则段复用本枚举（2026-08-22-chat-reply-language-design.md）。
 */
enum class ReplyLanguage { SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ENGLISH }

/**
 * 把 App 界面语言设置解析为具体回复语言。
 *
 * @param systemLocaleTag 系统 locale 的 BCP-47 tag（Android: `Locale.getDefault().toLanguageTag()`），
 *   仅当设置为 [AppLanguage.SYSTEM] 时参与解析。
 */
fun AppLanguage.toReplyLanguage(systemLocaleTag: String): ReplyLanguage = when (this) {
    AppLanguage.ENGLISH -> ReplyLanguage.ENGLISH
    AppLanguage.CHINESE -> ReplyLanguage.SIMPLIFIED_CHINESE
    AppLanguage.TRADITIONAL_CHINESE -> ReplyLanguage.TRADITIONAL_CHINESE
    AppLanguage.SYSTEM -> resolveSystemReplyLanguage(systemLocaleTag)
}

private fun resolveSystemReplyLanguage(localeTag: String): ReplyLanguage {
    val tag = localeTag.lowercase()
    if (!tag.startsWith("zh")) return ReplyLanguage.ENGLISH
    return if (tag.contains("hant") || tag.contains("-tw") ||
        tag.contains("-hk") || tag.contains("-mo")
    ) {
        ReplyLanguage.TRADITIONAL_CHINESE
    } else {
        ReplyLanguage.SIMPLIFIED_CHINESE
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*ReplyLanguageTest*"`
Expected: PASS（4 用例）

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguage.kt shared/src/commonTest/kotlin/com/mamba/picme/agent/core/model/context/ReplyLanguageTest.kt
git commit -m "feat(shared): ReplyLanguage 三语枚举 + AppLanguage 解析"
```

---

### Task 2: commonMain `AssistantPersona` 枚举与三语性格段

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/config/AssistantPersona.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/model/config/AssistantPersonaTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/model/config/AssistantPersonaTest.kt`：

```kotlin
package com.mamba.picme.agent.core.model.config

import com.mamba.picme.agent.core.model.context.ReplyLanguage
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantPersonaTest {

    @Test
    fun `default persona injects nothing`() {
        ReplyLanguage.entries.forEach { language ->
            assertNull(personaPromptSegment(AssistantPersona.DEFAULT, language))
        }
    }

    @Test
    fun `warm persona has segment per language`() {
        assertTrue(personaPromptSegment(AssistantPersona.WARM, ReplyLanguage.SIMPLIFIED_CHINESE)!!.contains("温暖贴心"))
        assertTrue(personaPromptSegment(AssistantPersona.WARM, ReplyLanguage.TRADITIONAL_CHINESE)!!.contains("溫暖貼心"))
        assertTrue(personaPromptSegment(AssistantPersona.WARM, ReplyLanguage.ENGLISH)!!.contains("warm and caring"))
    }

    @Test
    fun `lively persona has segment per language`() {
        assertTrue(personaPromptSegment(AssistantPersona.LIVELY, ReplyLanguage.SIMPLIFIED_CHINESE)!!.contains("emoji"))
        assertTrue(personaPromptSegment(AssistantPersona.LIVELY, ReplyLanguage.TRADITIONAL_CHINESE)!!.contains("emoji"))
        assertTrue(personaPromptSegment(AssistantPersona.LIVELY, ReplyLanguage.ENGLISH)!!.contains("lively and humorous"))
    }

    @Test
    fun `concise persona has segment per language`() {
        assertTrue(personaPromptSegment(AssistantPersona.CONCISE, ReplyLanguage.SIMPLIFIED_CHINESE)!!.contains("简洁干练"))
        assertTrue(personaPromptSegment(AssistantPersona.CONCISE, ReplyLanguage.TRADITIONAL_CHINESE)!!.contains("簡潔幹練"))
        assertTrue(personaPromptSegment(AssistantPersona.CONCISE, ReplyLanguage.ENGLISH)!!.contains("crisp and efficient"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*AssistantPersonaTest*"`
Expected: 编译失败（`AssistantPersona` / `personaPromptSegment` 未定义）

- [ ] **Step 3: 实现**

创建 `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/config/AssistantPersona.kt`：

```kotlin
package com.mamba.picme.agent.core.model.config

import com.mamba.picme.agent.core.model.context.ReplyLanguage

/**
 * Chat 助手性格预设（spec：docs/superpowers/specs/2026-08-22-assistant-persona-design.md）。
 *
 * 仅影响 Chat 回复的语气/语言方式；相机指令、打标等链路不消费。
 * [DEFAULT] 不注入任何 prompt 段落（行为与引入本特性前逐字节一致）。
 */
enum class AssistantPersona {
    DEFAULT, // 默认（中性简洁，不注入性格段）
    WARM,    // 温暖贴心
    LIVELY,  // 活泼幽默
    CONCISE  // 简洁干练
}

/**
 * 按性格 + 回复语言取 prompt 性格描述段；[AssistantPersona.DEFAULT] 返回 null（不注入）。
 *
 * 三语文本为 commonMain 常量，Android/iOS 双端共用同一份（[PARITY]）。
 * 段文本用目标语言书写（自我强化），拼接位置在日期行之后（近因效应位）。
 */
fun personaPromptSegment(persona: AssistantPersona, language: ReplyLanguage): String? =
    when (persona) {
        AssistantPersona.DEFAULT -> null
        AssistantPersona.WARM -> when (language) {
            ReplyLanguage.SIMPLIFIED_CHINESE ->
                "你的语气温暖贴心：先回应用户的情绪，共情之后再给出回答或建议；多使用肯定与鼓励的措辞，让用户感到被理解和支持。"
            ReplyLanguage.TRADITIONAL_CHINESE ->
                "你的語氣溫暖貼心：先回應使用者的情緒，共情之後再給出回答或建議；多使用肯定與鼓勵的措辭，讓使用者感到被理解和支持。"
            ReplyLanguage.ENGLISH ->
                "Your tone is warm and caring: acknowledge the user's feelings first, then respond or advise; use affirming and encouraging words so the user feels understood and supported."
        }
        AssistantPersona.LIVELY -> when (language) {
            ReplyLanguage.SIMPLIFIED_CHINESE ->
                "你的语气轻松活泼、幽默有趣：可适度使用 emoji 和俏皮表达，偶尔玩梗，让聊天氛围轻松愉快；但回答的实质内容必须保持准确、有用。"
            ReplyLanguage.TRADITIONAL_CHINESE ->
                "你的語氣輕鬆活潑、幽默有趣：可適度使用 emoji 和俏皮表達，偶爾玩梗，讓聊天氛圍輕鬆愉快；但回答的實質內容必須保持準確、有用。"
            ReplyLanguage.ENGLISH ->
                "Your tone is lively and humorous: use emojis and playful expressions in moderation and crack the occasional joke to keep the conversation fun — while keeping the substance of your answers accurate and useful."
        }
        AssistantPersona.CONCISE -> when (language) {
            ReplyLanguage.SIMPLIFIED_CHINESE ->
                "你的语气简洁干练：直接给出结论与建议，省去寒暄与铺垫，优先使用结构化输出（列表/要点）。"
            ReplyLanguage.TRADITIONAL_CHINESE ->
                "你的語氣簡潔幹練：直接給出結論與建議，省去寒暄與鋪陳，優先使用結構化輸出（列表/要點）。"
            ReplyLanguage.ENGLISH ->
                "Your tone is crisp and efficient: lead with conclusions and recommendations, skip pleasantries, and prefer structured output (lists and bullet points)."
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*AssistantPersonaTest*"`
Expected: PASS（4 用例）

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/config/AssistantPersona.kt shared/src/commonTest/kotlin/com/mamba/picme/agent/core/model/config/AssistantPersonaTest.kt
git commit -m "feat(shared): AssistantPersona 预设枚举 + 三语性格段"
```

---

### Task 3: `AgentContext` 扩展字段

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/AgentModels.kt:24-41`

- [ ] **Step 1: 加字段**

`AgentModels.kt` 的 `AgentContext` 数据类中，`traceId` 字段之后追加（import 区加 `import com.mamba.picme.agent.core.model.config.AssistantPersona`）：

```kotlin
    /** 一次用户消息的关联 ID，贯穿该轮 LLM/tool/JS 三层日志；非 chat 来源为 null。 */
    val traceId: String? = null,
    /** Chat 助手性格预设；非 chat 调用方用默认值（不注入性格段，行为不变）。 */
    val persona: AssistantPersona = AssistantPersona.DEFAULT,
    /** Chat 回复语言（已解析）；非 chat 调用方用默认值。 */
    val replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE
)
```

（`ReplyLanguage` 与本类同包 `model.context`，无需 import。）

- [ ] **Step 2: 编译验证**

Run: `JITPACK=true ./gradlew :shared:compileAndroidMain`
Expected: BUILD SUCCESSFUL（默认值保证全部现有调用方零变更）

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/AgentModels.kt
git commit -m "feat(shared): AgentContext 增加 persona/replyLanguage 字段"
```

---

### Task 4: `RemoteChatEngine` prompt 尾段注入与重建

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/RemoteChatEngine.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/PromptSuffixTest.kt`

关键约束：`buildChatSystemPrompt(toolDescriptors)` 签名与输出**不变**（golden 测试 `ChatSystemPromptGoldenTest` 逐字节锁定，本任务不得触碰 golden 文件）。性格段在 `getChatAgent` 拼尾段时追加。

- [ ] **Step 1: 写失败测试**

创建 `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/PromptSuffixTest.kt`：

```kotlin
package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptSuffixTest {

    private val today = "2026-08-22"

    @Test
    fun `default persona suffix is date line only`() {
        val suffix = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.DEFAULT, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertEquals(
            "\n\n当前日期：2026-08-22。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。",
            suffix
        )
    }

    @Test
    fun `persona segment appended after date line`() {
        val suffix = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.WARM, ReplyLanguage.SIMPLIFIED_CHINESE, today
        )
        assertTrue(suffix.startsWith("\n\n当前日期：2026-08-22"))
        assertTrue(suffix.contains("温暖贴心"))
    }

    @Test
    fun `persona segment follows reply language`() {
        val en = RemoteChatEngine.buildPromptSuffix(
            AssistantPersona.CONCISE, ReplyLanguage.ENGLISH, today
        )
        assertTrue(en.contains("crisp and efficient"))
        assertFalse(en.contains("简洁干练"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "*PromptSuffixTest*"`
Expected: 编译失败（`buildPromptSuffix` 未定义）

- [ ] **Step 3: 实现**

`RemoteChatEngine.kt` 三处改动（import 区加 `import com.mamba.picme.agent.core.model.config.AssistantPersona`、`import com.mamba.picme.agent.core.model.config.personaPromptSegment`、`import com.mamba.picme.agent.core.model.context.ReplyLanguage`）：

① companion object 内（`buildChatSystemPrompt` 之后）新增：

```kotlin
        /**
         * chat system prompt 的动态尾段：当前日期行 + 性格段（按 persona + 回复语言选段）。
         * 在 agent 构建期拼接（非 buildChatSystemPrompt 内），DEFAULT 不注入性格段——
         * 保证 `buildChatSystemPrompt` 输出与 golden 逐字节不变。
         */
        fun buildPromptSuffix(
            persona: AssistantPersona,
            replyLanguage: ReplyLanguage,
            today: String
        ): String =
            "\n\n当前日期：$today。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。" +
                (personaPromptSegment(persona, replyLanguage)?.let { "\n\n$it" } ?: "")
```

② 缓存字段区（`cachedChatAgentConfig` 之后）加：

```kotlin
    private var cachedChatAgentPersona: AssistantPersona? = null
    private var cachedChatAgentReplyLanguage: ReplyLanguage? = null
```

③ `getChatAgent()` 改为带参 + 重建比对扩展 + systemPrompt 用 `buildPromptSuffix`：

```kotlin
    private fun getChatAgent(
        persona: AssistantPersona,
        replyLanguage: ReplyLanguage
    ): KoogChatAgent? {
        val existing = cachedChatAgent
        val currentConfig = configurator.getUserRemoteConfig() ?: RemoteModelConfig.PICME_SERVER_DEFAULT
        if (existing != null && cachedChatAgentConfig != null) {
            val configChanged = cachedChatAgentConfig?.modelId != currentConfig.modelId
                || cachedChatAgentConfig?.baseUrl != currentConfig.baseUrl
                || cachedChatAgentConfig?.apiKey != currentConfig.apiKey
                || cachedChatAgentConfig?.gatewayToken != currentConfig.gatewayToken
                || cachedChatAgentConfig?.protocol != currentConfig.protocol
                || cachedChatAgentConfig?.providerId != currentConfig.providerId
                || cachedChatAgentPersona != persona
                || cachedChatAgentReplyLanguage != replyLanguage
            if (configChanged) {
                Logger.i(tag, "Remote config or persona changed (model=${currentConfig.modelId}, persona=$persona), rebuilding Chat Agent")
                cachedChatAgent = null
                cachedChatAgentConfig = null
            } else {
                return existing
            }
        } else if (existing != null) {
            return existing
        }
        // …（memProvider/cfg 构建段不变，仅 systemPrompt 一行改为：）
        //     .systemPrompt(chatSystemPrompt + buildPromptSuffix(persona, replyLanguage, today()))
        // …agent 创建段不变，缓存写入处加：
        // cachedChatAgentPersona = persona
        // cachedChatAgentReplyLanguage = replyLanguage
    }
```

> 注：上面省略号部分保持原代码不动；`.systemPrompt(...)` 原行为 `chatSystemPrompt + "\n\n当前日期：${today()}。…"`，替换为 `chatSystemPrompt + buildPromptSuffix(persona, replyLanguage, today())`（`today()` 为文件底部已有的 private 函数）。

④ `processChatReAct` 签名加默认参数并透传：

```kotlin
    internal suspend fun processChatReAct(
        input: String,
        sessionId: String,
        timeoutMs: Long = 120_000L,
        traceId: String? = null,
        persona: AssistantPersona = AssistantPersona.DEFAULT,
        replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE,
        onEvent: ((ChatStreamEvent) -> Unit)? = null
    ): Result<Pair<String, AgentExecutionMetrics?>> = withContext(...) {
        // …
        val agent = getChatAgent(persona, replyLanguage) ?: return@withContext …
```

⑤ `streamChatReAct` 调用点透传：

```kotlin
            processChatReAct(
                input,
                agentContext.memorySessionId,
                traceId = agentContext.traceId,
                persona = agentContext.persona,
                replyLanguage = agentContext.replyLanguage,
                onEvent = onEvent
            ).fold(
```

- [ ] **Step 4: 跑测试确认通过 + golden 回归**

Run: `JITPACK=true ./gradlew :shared:jvmTest`
Expected: PASS（含 `ChatSystemPromptGoldenTest` 依然绿——golden 文件未改动）

- [ ] **Step 5: iOS metadata 编译门槛**

Run: `JITPACK=true ./gradlew :shared:assemble`
Expected: BUILD SUCCESSFUL（commonMain 改动经 iOS target metadata 编译验证）

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/RemoteChatEngine.kt shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/PromptSuffixTest.kt
git commit -m "feat(shared): chat prompt 尾段注入助手性格段，persona/语言变化触发 agent 重建"
```

---

### Task 5: Android 存储层（DataStore + 接口）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt`

- [ ] **Step 1: PreferencesKeys 加 key**

`UserPreferencesRepository.kt` 的 `PreferencesKeys` 中，`AI_AGENT_PRIVACY_LEVEL` 行（L81）之后加：

```kotlin
        val ASSISTANT_PERSONA = stringPreferencesKey("assistant_persona")
```

- [ ] **Step 2: flow + update 实现**

`updateAiAgentMode` 实现块（L598-602）之后加（import 区加 `import com.mamba.picme.agent.core.model.config.AssistantPersona`）：

```kotlin
    override val assistantPersonaFlow: Flow<AssistantPersona> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val personaName = preferences[PreferencesKeys.ASSISTANT_PERSONA] ?: AssistantPersona.DEFAULT.name
            runCatching { AssistantPersona.valueOf(personaName) }
                .getOrDefault(AssistantPersona.DEFAULT)
        }

    override suspend fun updateAssistantPersona(persona: AssistantPersona) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ASSISTANT_PERSONA] = persona.name
        }
    }
```

- [ ] **Step 3: 接口加成员**

`UserSettingsRepository.kt` 的 `updateAiAgentMode`（L87）之后加（import 区加 `import com.mamba.picme.agent.core.model.config.AssistantPersona`）：

```kotlin
    val assistantPersonaFlow: Flow<AssistantPersona>
    suspend fun updateAssistantPersona(persona: AssistantPersona)
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt
git commit -m "feat(android): assistant_persona 偏好存储（DataStore，默认 DEFAULT）"
```

---

### Task 6: SettingsViewModel 暴露状态与 setter

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt`

- [ ] **Step 1: stateIn + setter**

`aiAgentMode` stateIn 块（L217-222）之后加（import 区加 `import com.mamba.picme.agent.core.model.config.AssistantPersona`）：

```kotlin
    val assistantPersona: StateFlow<AssistantPersona> = repository.assistantPersonaFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AssistantPersona.DEFAULT
        )
```

`setAiAgentMode`（L950-954）之后加：

```kotlin
    fun setAssistantPersona(persona: AssistantPersona) {
        viewModelScope.launch {
            repository.updateAssistantPersona(persona)
        }
    }
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt
git commit -m "feat(android): SettingsViewModel 暴露助手性格状态与设置入口"
```

---

### Task 7: Android 设置 UI + 三语 strings

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsAiAgent.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`
- Modify: `androidApp/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

- [ ] **Step 1: strings 三语（9 key × 3 文件）**

`values/strings.xml` 在 `ai_agent_mode_remote`（L397）之后加：

```xml
    <string name="assistant_persona">Assistant Personality</string>
    <string name="assistant_persona_default">Default</string>
    <string name="assistant_persona_default_desc">Balanced, neutral standard replies</string>
    <string name="assistant_persona_warm">Warm &amp; Caring</string>
    <string name="assistant_persona_warm_desc">Empathizes first, encouraging and supportive</string>
    <string name="assistant_persona_lively">Lively &amp; Playful</string>
    <string name="assistant_persona_lively_desc">Relaxed and fun, with light emoji use</string>
    <string name="assistant_persona_concise">Crisp &amp; Direct</string>
    <string name="assistant_persona_concise_desc">Straight to conclusions, minimal pleasantries</string>
```

`values-zh-rCN/strings.xml` 在 `ai_agent_mode_remote`（L385）之后加：

```xml
    <string name="assistant_persona">助手性格</string>
    <string name="assistant_persona_default">默认</string>
    <string name="assistant_persona_default_desc">中性简洁的标准回复</string>
    <string name="assistant_persona_warm">温暖贴心</string>
    <string name="assistant_persona_warm_desc">先共情再回答，多肯定鼓励</string>
    <string name="assistant_persona_lively">活泼幽默</string>
    <string name="assistant_persona_lively_desc">轻松俏皮，适度使用 emoji</string>
    <string name="assistant_persona_concise">简洁干练</string>
    <string name="assistant_persona_concise_desc">直给结论，少寒暄</string>
```

`values-zh-rTW/strings.xml` 在 `ai_agent_mode_remote`（L385）之后加：

```xml
    <string name="assistant_persona">助手性格</string>
    <string name="assistant_persona_default">預設</string>
    <string name="assistant_persona_default_desc">中性簡潔的標準回覆</string>
    <string name="assistant_persona_warm">溫暖貼心</string>
    <string name="assistant_persona_warm_desc">先共情再回答，多肯定鼓勵</string>
    <string name="assistant_persona_lively">活潑幽默</string>
    <string name="assistant_persona_lively_desc">輕鬆俏皮，適度使用 emoji</string>
    <string name="assistant_persona_concise">簡潔幹練</string>
    <string name="assistant_persona_concise_desc">直給結論，少寒暄</string>
```

- [ ] **Step 2: `AssistantPersonaSelection` composable**

`SettingsAiAgent.kt` 中 `AiAgentModeSelection`（L48-73）之后加（import 区加 `import com.mamba.picme.agent.core.model.config.AssistantPersona`）：

```kotlin
@Composable
internal fun AssistantPersonaSelection(
    currentPersona: AssistantPersona,
    onPersonaSelected: (AssistantPersona) -> Unit
) {
    val options = listOf(
        AssistantPersona.DEFAULT to stringResource(R.string.assistant_persona_default),
        AssistantPersona.WARM to stringResource(R.string.assistant_persona_warm),
        AssistantPersona.LIVELY to stringResource(R.string.assistant_persona_lively),
        AssistantPersona.CONCISE to stringResource(R.string.assistant_persona_concise)
    )
    val descRes = when (currentPersona) {
        AssistantPersona.DEFAULT -> R.string.assistant_persona_default_desc
        AssistantPersona.WARM -> R.string.assistant_persona_warm_desc
        AssistantPersona.LIVELY -> R.string.assistant_persona_lively_desc
        AssistantPersona.CONCISE -> R.string.assistant_persona_concise_desc
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.assistant_persona),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(descRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
        )
        CompactOptionChips(
            options = options,
            currentValue = currentPersona,
            maxLines = 2,
            onSelected = onPersonaSelected
        )
    }
}
```

- [ ] **Step 3: SettingsScreen 接线**

`SettingsScreen.kt` 顶部 collectAsState 区块（`allModels` 行 L215 之后）加：

```kotlin
    val assistantPersona by viewModel.assistantPersona.collectAsState()
```

REMOTE_MODEL 区块（L508-517）中 `RemoteModelsListSection(...)` 之后加：

```kotlin
                SettingsSection(title = stringResource(R.string.assistant_persona)) {
                    AssistantPersonaSelection(
                        currentPersona = assistantPersona,
                        onPersonaSelected = { viewModel.setAssistantPersona(it) }
                    )
                }
```

（`SettingsSection` 为 SettingsScreen.kt 内已有的分组容器，签名以现场为准；若其参数不含 title 则直接用裸 `AssistantPersonaSelection(...)`。）

- [ ] **Step 4: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: i18n 自检（三语 key 对齐）**

Run: `for f in values values-zh-rCN values-zh-rTW; do grep -c 'assistant_persona' androidApp/src/main/res/$f/strings.xml; done`
Expected: 三个文件都输出 `9`

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/settings/SettingsAiAgent.kt androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt androidApp/src/main/res/values/strings.xml androidApp/src/main/res/values-zh-rCN/strings.xml androidApp/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(android): 设置新增助手性格单选区块（远程模型页，三语）"
```

---

### Task 8: Android ChatViewModel 填充 AgentContext

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt:1208-1216`

- [ ] **Step 1: sendMessage 读设置并填入**

`sendMessage` 中「4. 构建 Agent 上下文」处（L1209-1216），在构建前读取当前值（import 区加 `import com.mamba.picme.agent.core.model.context.toReplyLanguage`、`import kotlinx.coroutines.flow.first`（若已有则跳过）、`import java.util.Locale`（若已有则跳过））：

```kotlin
                // 4. 构建 Agent 上下文（性格/回复语言每次发消息时读取，设置变更下条消息即生效）
                val assistantPersona = userSettingsRepository.assistantPersonaFlow.first()
                val replyLanguage = userSettingsRepository.appLanguageFlow.first()
                    .toReplyLanguage(Locale.getDefault().toLanguageTag())
                val agentContext = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId,
                    recentSearchResults = sessionSearchSnapshots[sessionId].orEmpty(),
                    lastUserImageUri = _lastUserImageUri.value,
                    gallerySummary = gallerySummary,
                    traceId = java.util.UUID.randomUUID().toString(),
                    persona = assistantPersona,
                    replyLanguage = replyLanguage
                )
```

（`userSettingsRepository` 是 ChatViewModel 已有字段，L1169 已用于 `incrementGuestChatMessageCount()`。）

- [ ] **Step 2: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(android): chat 发送时按当前设置注入助手性格与回复语言"
```

---

### Task 9: iOS 桥接与调用点（ChatAgentBridge + Swift ChatViewModel）

**Files:**
- Modify: `shared/src/iosMain/kotlin/com/mamba/picme/agent/core/inference/remote/ChatAgentBridge.kt:60-98`
- Modify: `iosApp/PoLang/Features/Chat/ChatViewModel.swift:206-228`

- [ ] **Step 1: ChatAgentBridge.sendMessage 加参数**

`sendMessage`（L60-65）改为（import 区加 `import com.mamba.picme.agent.core.model.config.AssistantPersona`、`import com.mamba.picme.agent.core.model.context.ReplyLanguage`）：

```kotlin
    /**
     * 发送消息（启动流式远程推理）。返回 void（K/N 多参数方法丢返回类型，
     * watcher 生命周期内部管理，Swift 经 [cancelCurrent] 取消）。
     *
     * @param persona 助手性格枚举 name（`AssistantPersona.name`），非法值回落 DEFAULT
     * @param replyLanguage 回复语言枚举 name（`ReplyLanguage.name`），非法值回落 SIMPLIFIED_CHINESE
     *   （String 参数而非枚举：规避 K/N 枚举导出的互操作摩擦，解析失败兜底不炸）
     */
    fun sendMessage(
        input: String,
        persona: String,
        replyLanguage: String,
        onText: (String) -> Unit,
        onToolCall: () -> Unit,
        onComplete: (summary: String, errorMessage: String?) -> Unit
    ) {
        val personaEnum = runCatching { AssistantPersona.valueOf(persona) }
            .getOrDefault(AssistantPersona.DEFAULT)
        val languageEnum = runCatching { ReplyLanguage.valueOf(replyLanguage) }
            .getOrDefault(ReplyLanguage.SIMPLIFIED_CHINESE)
        currentJob = bridgeScope.launch {
            try {
                val context = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId,
                    persona = personaEnum,
                    replyLanguage = languageEnum
                )
                // …后续 orchestrator.remoteChatEngine.streamChat(...) 起全部保持原样
```

- [ ] **Step 2: iOS 侧 Kotlin 编译 + framework 重建**

Run: `JITPACK=true ./gradlew :shared:assembleSharedKitDebugXCFramework`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Swift 调用点传参**

`iosApp/PoLang/Features/Chat/ChatViewModel.swift` 的 `bridge.sendMessage`（L206-228）改为：

```swift
        bridge.sendMessage(
            input: Self.llmInput(text: trimmed, stagedImageUri: stagedLocalId),
            persona: UserDefaults.standard.string(forKey: "assistant_persona") ?? "DEFAULT",
            replyLanguage: Self.currentReplyLanguage(),
            onText: { [weak self] snapshot in
            // …三个回调闭包保持原样
```

文件内（`send` 方法附近）新增静态helper：

```swift
    /// App 界面语言 → ReplyLanguage 枚举 name（与 Android commonMain `toReplyLanguage` 同规则）
    private static func currentReplyLanguage() -> String {
        switch AppSettings.shared.appLanguage {
        case "english": return "ENGLISH"
        case "chinese_simplified": return "SIMPLIFIED_CHINESE"
        case "chinese_traditional": return "TRADITIONAL_CHINESE"
        default:
            let tag = (Locale.preferredLanguages.first ?? "en").lowercased()
            guard tag == "zh" || tag.hasPrefix("zh-") else { return "ENGLISH" }
            return (tag.contains("-hant") || tag.contains("-tw") ||
                    tag.contains("-hk") || tag.contains("-mo"))
                ? "TRADITIONAL_CHINESE" : "SIMPLIFIED_CHINESE"
        }
    }
```

（若该文件未 import AppSettings 可见性所需模块，AppSettings 在同 target `App/PoLangApp.swift`，直接可用。）

- [ ] **Step 4: iOS 编译验证**

Run: `cd iosApp && xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS Simulator' -quiet build`
Expected: BUILD SUCCEEDED（细节参照 `skills/ios-build-debug`）

- [ ] **Step 5: Commit**

```bash
git add shared/src/iosMain/kotlin/com/mamba/picme/agent/core/inference/remote/ChatAgentBridge.kt iosApp/PoLang/Features/Chat/ChatViewModel.swift
git commit -m "feat(ios): chat 桥接注入助手性格与回复语言"
```

---

### Task 10: iOS 设置 UI + xcstrings

**Files:**
- Modify: `iosApp/PoLang/Features/Settings/SettingsSubPages.swift`（`AiAgentSettingsView`，L6-79）
- Modify: `iosApp/PoLang/Resources/Localizable.xcstrings`

- [ ] **Step 1: AiAgentSettingsView 加性格区块**

`AiAgentSettingsView` 顶部加存储：

```swift
    @AppStorage("assistant_persona") private var assistantPersona: String = "DEFAULT"
```

「Section: AI 智能助手」区块内「推理模式」HStack 之后追加：

```swift
                        SettingsM3Divider()

                        // 助手性格
                        VStack(alignment: .leading, spacing: 6) {
                            Text(L("Assistant Personality")).font(.system(size: 14))
                            Text(personaDescription)
                                .font(.system(size: 12))
                                .foregroundColor(.secondary)
                            HStack(spacing: 8) {
                                personaChip("DEFAULT", L("Default"))
                                personaChip("WARM", L("Warm & Caring"))
                                personaChip("LIVELY", L("Lively & Playful"))
                                personaChip("CONCISE", L("Crisp & Direct"))
                            }
                        }
                        .padding(.vertical, 8)
```

`AiAgentSettingsView` 内新增两个私有成员（沿用 `LocalModelsSettingsView` 的可点 chip 风格）：

```swift
    private var personaDescription: String {
        switch assistantPersona {
        case "WARM": return L("Empathizes first, encouraging and supportive")
        case "LIVELY": return L("Relaxed and fun, with light emoji use")
        case "CONCISE": return L("Straight to conclusions, minimal pleasantries")
        default: return L("Balanced, neutral standard replies")
        }
    }

    private func personaChip(_ value: String, _ label: String) -> some View {
        Text(label)
            .font(.system(size: 13, weight: assistantPersona == value ? .semibold : .regular))
            .foregroundColor(assistantPersona == value ? .white : .primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(assistantPersona == value ? Color.accentColor : Color(.tertiarySystemBackground))
            .clipShape(Capsule())
            .onTapGesture { assistantPersona = value }
    }
```

- [ ] **Step 2: xcstrings 三语**

`Localizable.xcstrings` 为以下 9 个 key 补 zh-Hans / zh-Hant 翻译（与 Android strings 文案一致）：

| key | zh-Hans | zh-Hant |
|---|---|---|
| Assistant Personality | 助手性格 | 助手性格 |
| Default | 默认 | 預設 |
| Balanced, neutral standard replies | 中性简洁的标准回复 | 中性簡潔的標準回覆 |
| Warm & Caring | 温暖贴心 | 溫暖貼心 |
| Empathizes first, encouraging and supportive | 先共情再回答，多肯定鼓励 | 先共情再回答，多肯定鼓勵 |
| Lively & Playful | 活泼幽默 | 活潑幽默 |
| Relaxed and fun, with light emoji use | 轻松俏皮，适度使用 emoji | 輕鬆俏皮，適度使用 emoji |
| Crisp & Direct | 简洁干练 | 簡潔幹練 |
| Straight to conclusions, minimal pleasantries | 直给结论，少寒暄 | 直給結論，少寒暄 |

（`Default`/`Assistant Personality` 等若 xcstrings 已有同名 key 且译文一致则跳过，避免重复 key。）

- [ ] **Step 3: iOS 编译验证**

Run: `cd iosApp && xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS Simulator' -quiet build`
Expected: BUILD SUCCEEDED

- [ ] **Step 4: Commit**

```bash
git add iosApp/PoLang/Features/Settings/SettingsSubPages.swift iosApp/PoLang/Resources/Localizable.xcstrings
git commit -m "feat(ios): 设置新增助手性格选择（三语）"
```

---

### Task 11: PARITY spec 固化与文档同步

**Files:**
- Modify: `specs/screens/settings.yaml`
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/AGENTS.md`

- [ ] **Step 1: settings.yaml 固化**

在 `specs/screens/settings.yaml` 的远程模型（remote model）分类区块中追加条目（锚定现有 `remote_models`/`REMOTE_MODEL` 段落，按其现有 YAML 风格）：

```yaml
  assistant_persona:
    type: option_chips              # CompactOptionChips 单选，maxLines=2
    title: "Assistant Personality"  # 助手性格
    options: [default, warm_caring, lively_playful, crisp_direct]
    default: default
    persistence:
      android: "DataStore assistant_persona (enum name)"
      ios: "@AppStorage(\"assistant_persona\")"
    effect: "chat system prompt 尾段注入性格段（三语按界面语言选段），下一条消息生效；default 不注入"
    desc_per_option: true           # 选中项下方显示一句话描述
```

- [ ] **Step 2: 模块 AGENTS.md 同步**

`androidApp/src/main/java/com/mamba/picme/features/settings/AGENTS.md` 的「Agent 模式设置」小节末尾加一行：

```markdown
- **助手性格（2026-08-22 新增）**：远程模型页「助手性格」单选（默认/温暖贴心/活泼幽默/简洁干练，`assistant_persona` DataStore 枚举），经 `AgentContext.persona` 注入 chat system prompt 尾段；DEFAULT 不注入
```

- [ ] **Step 3: Commit**

```bash
git add specs/screens/settings.yaml androidApp/src/main/java/com/mamba/picme/features/settings/AGENTS.md
git commit -m "docs(parity): 固化助手性格设置条目 spec 与模块文档"
```

---

### Task 12: 全量验证

- [ ] **Step 1: shared 全量测试 + 全 target 编译**

Run: `JITPACK=true ./gradlew :shared:jvmTest :shared:assemble`
Expected: BUILD SUCCESSFUL，全部测试 PASS（含 golden 未变红）

- [ ] **Step 2: Android 编译**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: iOS framework + App 编译**

Run: `JITPACK=true ./gradlew :shared:assembleSharedKitDebugXCFramework && cd iosApp && xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS Simulator' -quiet build`
Expected: BUILD SUCCEEDED

- [ ] **Step 4: 实机/模拟器验收（spec §2.9）**

1. 设置 → 远程模型 → 助手性格选「温暖贴心」→ Chat 发消息 → 回复带共情/鼓励语气
2. 同会话切「简洁干练」→ 发下一条 → 回复直给结论（验证无需新会话即生效）
3. 界面语言切 English + 性格 WARM → 回复英文且带温暖语气（性格段英文注入）
4. iOS 同流程复验（双端一致）
5. 性格设回「默认」→ prompt 行为与改动前一致（golden 已锁定）

---

## Self-Review 记录

- **Spec 覆盖**：§2.1 模型（Task 1/2）、§2.2 AgentContext（Task 3）、§2.3 数据流 Android/iOS（Task 4/8/9）、§2.4 性格段内容（Task 2 逐字落地）、§2.5 Android 设置链路（Task 5/6/7）、§2.6 iOS+PARITY（Task 9/10/11）、§2.7 i18n（Task 7/10）、§2.8 边界（Task 5 坏值回落、Task 4 DEFAULT 零注入、Task 9 bridge 兜底）、§2.9 测试验收（Task 1/2/4 单测 + Task 12 实机）。
- **与回复语言 spec 的关系**：`ReplyLanguage`/`toReplyLanguage` 由本计划先行落地（同日 spec 未实现，已确认仓库中不存在），回复语言 spec 落地时复用；语言规则段（强制回复语言）不在本计划范围。
- **类型一致性**：`AssistantPersona`（config 包）/ `ReplyLanguage`（context 包）/ `personaPromptSegment(persona, language)` / `buildPromptSuffix(persona, replyLanguage, today)` 全计划统一。
- **Golden 护栏**：`buildChatSystemPrompt` 签名与输出不动，golden 文件不重生成。
