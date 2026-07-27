# 聊天记忆被动注入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 chat 与飞书 agent 每轮把"已记住的事实 + 人物关系"快照追加进 system prompt，LLM 无需主动调工具即可"知道"用户记忆。

**Architecture:** 在 `RemoteReActAgent` 的 `systemMessageProvider` lambda（每轮重调）里追加 `MemoryContextProvider.snapshot()` 返回的快照。`MemoryContextProvider` 接口在 runtime-core；app 层 `MemoryContextProviderImpl` 用 Flow 预热 `@Volatile` 缓存；快照格式化收口为纯函数 `formatMemoryContext`，按 ~1500 字符预算截断 + `recall_memory` 兜底。

**Tech Stack:** Kotlin、langchain4j（AiServices `systemMessageProvider`）、Room Flow、kotlinx-coroutines（`combine`/`Flow.catch`）、JUnit4 + Robolectric（测试）。

**Spec:** `docs/superpowers/specs/2026-07-27-chat-memory-passive-injection-design.md`

---

## File Structure

**Create:**
- `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/MemoryContextProvider.kt` — 同步快照接口（runtime-core 契约，供 `RemoteReActAgentConfig` 持有）。
- `app/src/main/java/com/mamba/picme/domain/memory/MemoryContextFormatter.kt` — DTO（`RelationLine`/`FactLine`）+ 纯函数 `formatMemoryContext` + 预算常量。纯 JVM 可测，无 Android/Room 依赖。
- `app/src/main/java/com/mamba/picme/domain/memory/MemoryContextProviderImpl.kt` — 接口实现 + 实体→快照纯映射 `formatMemoryContextFromEntities`（internal，可测）。
- `app/src/test/java/com/mamba/picme/domain/memory/MemoryContextFormatterTest.kt` — 纯 JVM 单测。
- `app/src/test/java/com/mamba/picme/domain/memory/MemoryContextProviderImplTest.kt` — 实体映射纯 JVM 单测。
- `runtime-core/src/test/java/com/mamba/picme/agent/core/inference/remote/react/ComposeSystemPromptTest.kt` — system prompt 拼接纯 JVM 单测。

**Modify:**
- `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgentConfig.kt` — 加 `memoryContextProvider` 字段 + Builder 方法 + build() 传参。
- `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgent.kt` — 加纯函数 `composeSystemPrompt`；改 `systemMessageProvider` lambda（`:172`）。
- `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentConfigurator.kt` — 加 provider 字段 + setter；`getChatAgent` 与 `getFeishuAgent` 都传 provider。
- `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt` — 加转发入口 `setMemoryContextProvider`。
- `app/src/main/java/com/mamba/picme/PoLangApplication.kt` — 构造 `MemoryContextProviderImpl` 并注入。
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` — 记一笔被动记忆注入（CLAUDE.md 三层文档原子同步）。

**任务依赖顺序：** 1（formatter）→ 2（interface）→ 3（Impl + 映射）→ 4（config + agent）→ 5（configurator + orchestrator）→ 6（app 装配）→ 7（文档）。每步独立可编译。

---

## Task 1: 纯函数 `formatMemoryContext` + DTO

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/memory/MemoryContextFormatter.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/memory/MemoryContextFormatterTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/mamba/picme/domain/memory/MemoryContextFormatterTest.kt`:

```kotlin
package com.mamba.picme.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextFormatterTest {

    @Test
    fun empty_returnsBlank() {
        assertEquals("", formatMemoryContext(emptyList(), emptyList()))
    }

    @Test
    fun relationsOnly_noFactsSection() {
        val relations = listOf(RelationLine("小宝", "女儿"))
        val out = formatMemoryContext(relations, emptyList())
        assertTrue(out.contains("关系：小宝=女儿"))
        assertFalse(out.contains("事实："))
    }

    @Test
    fun factsOnly_includesBulletWithCategory() {
        val facts = listOf(FactLine("小宝对花粉过敏", "健康", createdAt = 100L))
        val out = formatMemoryContext(emptyList(), facts)
        assertTrue(out.contains("事实："))
        assertTrue(out.contains("- 小宝对花粉过敏（健康）"))
        assertFalse(out.contains("关系："))
    }

    @Test
    fun factsOnly_nullCategory_noParens() {
        val facts = listOf(FactLine("喜欢低饱和度滤镜", null, createdAt = 1L))
        val out = formatMemoryContext(emptyList(), facts)
        assertTrue(out.contains("- 喜欢低饱和度滤镜"))
        assertFalse(out.contains("（）"))
    }

    @Test
    fun both_relationsAndFacts() {
        val relations = listOf(RelationLine("小宝", "女儿"))
        val facts = listOf(FactLine("喜欢猫", null, createdAt = 1L))
        val out = formatMemoryContext(relations, facts)
        assertTrue(out.contains("关系：小宝=女儿"))
        assertTrue(out.contains("- 喜欢猫"))
    }

    @Test
    fun facts_sortedByCreatedAtDesc_newerFirst() {
        val facts = listOf(
            FactLine("旧事实", null, createdAt = 100L),
            FactLine("新事实", null, createdAt = 300L),
            FactLine("中事实", null, createdAt = 200L)
        )
        val out = formatMemoryContext(emptyList(), facts)
        val newIdx = out.indexOf("新事实")
        val midIdx = out.indexOf("中事实")
        val oldIdx = out.indexOf("旧事实")
        assertTrue("newer must come first", newIdx < midIdx && midIdx < oldIdx)
    }

    @Test
    fun budgetTruncation_addsRecallHint_andDropsSome() {
        // 5 条事实，每条较长；预算只够装下少数
        val facts = (1..5).map { FactLine("这是第$it 条比较长的事实内容用于撑爆预算", null, createdAt = it.toLong()) }
        val out = formatMemoryContext(emptyList(), facts, charBudget = 120)
        assertTrue(out.contains("共 5 条"))
        assertTrue(out.contains("recall_memory"))
        // 第 5 条 createdAt 最大（最近），应优先出现；第 1 条最旧，很可能被截掉
        assertTrue(out.contains("第5"))
        assertFalse(out.contains("第1"))
    }

    @Test
    fun budgetTruncation_hintShowsShownCount() {
        val facts = (1..5).map { FactLine("事实$it", null, createdAt = it.toLong()) }
        val out = formatMemoryContext(emptyList(), facts, charBudget = 90)
        // 提示格式：已显示最近 K 条
        assertTrue(Regex("已显示最近 \\d+ 条").containsMatchIn(out))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.memory.MemoryContextFormatterTest"
```
Expected: 编译失败（`formatMemoryContext` / `RelationLine` / `FactLine` 未定义）。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/mamba/picme/domain/memory/MemoryContextFormatter.kt`:

```kotlin
package com.mamba.picme.domain.memory

/** 记忆快照格式化纯函数 + DTO。无 Android/Room 依赖，纯 JVM 可测。 */

const val MEMORY_CONTEXT_CHAR_BUDGET = 1500

data class RelationLine(val name: String, val label: String)
data class FactLine(val content: String, val category: String?, val createdAt: Long)

private const val SECTION_HEADER = "【关于用户（系统已记住，可直接引用，无需再问）】"

/**
 * 生成"关于用户"快照文本。无关系且无事实返回 ""（→ systemMessageProvider 不追加，零开销）。
 *
 * 关系全量，拼成 "名字=称谓"（分号分隔）。事实按 createdAt 倒序填进"扣除头部+关系段后的剩余
 * 字符预算"，超限截断并在末尾追加 recall_memory 兜底提示（与既有 recall_memory 工具闭环）。
 */
fun formatMemoryContext(
    relations: List<RelationLine>,
    facts: List<FactLine>,
    charBudget: Int = MEMORY_CONTEXT_CHAR_BUDGET
): String {
    if (relations.isEmpty() && facts.isEmpty()) return ""

    val sb = StringBuilder()
    sb.append(SECTION_HEADER).append('\n')

    if (relations.isNotEmpty()) {
        sb.append("关系：")
            .append(relations.joinToString("；") { "${it.name}=${it.label}" })
            .append('\n')
    }

    if (facts.isNotEmpty()) {
        val factsSorted = facts.sortedByDescending { it.createdAt }
        val bullets = factsSorted.map { factBullet(it) }
        val remaining = (charBudget - sb.length - "事实：\n".length).coerceAtLeast(0)
        val (shown, truncated) = fitBullets(bullets, remaining)

        sb.append("事实：")
        if (shown.isEmpty()) {
            sb.append('\n')
        } else {
            sb.append('\n').append(shown.joinToString("\n")).append('\n')
        }
        if (truncated > 0) {
            sb.append("（事实共 ${factsSorted.size} 条，已显示最近 ${shown.size} 条，更多可用 recall_memory 查询）")
        }
    }

    return sb.toString().trimEnd('\n')
}

/** 单条事实渲染：`- 内容（分类）`，分类空则无括号。 */
private fun factBullet(fact: FactLine): String {
    val category = fact.category?.trim()?.ifEmpty { null }?.let { "（$it）" } ?: ""
    return "- ${fact.content}$category"
}

/**
 * 在 [budget] 字符内尽量多地从头（最近）装下 bullet（每条按 内容长度+1 换行 计）。
 * 一条都放不下时返回空列表，由调用方决定是否出兜底提示。
 */
private fun fitBullets(bullets: List<String>, budget: Int): Pair<List<String>, Int> {
    val shown = mutableListOf<String>()
    var consumed = 0
    for (bullet in bullets) {
        val add = bullet.length + 1 // 内容 + 换行
        if (consumed + add > budget) break // 放不下就停（含第一条放不下）
        shown.add(bullet)
        consumed += add
    }
    return shown to (bullets.size - shown.size)
}
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.memory.MemoryContextFormatterTest"
```
Expected: PASS（8 个用例全绿）。若 `budgetTruncation_addsRecallHint_andDropsSome` 因预算边界不过，微调 `charBudget` 入参而非实现。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/memory/MemoryContextFormatter.kt \
        app/src/test/java/com/mamba/picme/domain/memory/MemoryContextFormatterTest.kt
git commit -m "feat(memory): 记忆快照格式化纯函数 + 单测"
```

---

## Task 2: `MemoryContextProvider` 接口（runtime-core）

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/MemoryContextProvider.kt`

纯接口，无行为可测（契约）。无独立测试。

- [ ] **Step 1: 写接口**

`runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/MemoryContextProvider.kt`:

```kotlin
package com.mamba.picme.agent.core.inference.remote.tool

/**
 * 聊天/飞书每轮被动注入的"已记住"快照供给者。
 *
 * [snapshot] 必须**非阻塞、线程安全**：langchain4j 的 `systemMessageProvider` 在每轮请求
 * 时同步回调本方法；实现方需用 Flow 预热一份内存缓存，[snapshot] 只读缓存。无内容返回 ""。
 */
interface MemoryContextProvider {
    fun snapshot(): String
}
```

- [ ] **Step 2: 编译确认**

```
./gradlew :runtime-core:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/MemoryContextProvider.kt
git commit -m "feat(runtime-core): MemoryContextProvider 被动注入接口"
```

---

## Task 3: `MemoryContextProviderImpl` + 实体映射

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/memory/MemoryContextProviderImpl.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/memory/MemoryContextProviderImplTest.kt`

- [ ] **Step 1: 写失败测试（实体→快照映射，纯 JVM）**

`app/src/test/java/com/mamba/picme/domain/memory/MemoryContextProviderImplTest.kt`:

```kotlin
package com.mamba.picme.domain.memory

import com.mamba.picme.data.local.entity.MemoryFactEntity
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextProviderImplTest {

    @Test
    fun mapsEntities_relationUsesPredicateLabelZh() {
        val relations = listOf(
            RelationDisplayItem(
                relationId = 1, subjectPersonId = 2, subjectName = "小宝",
                predicate = RelationPredicate.DAUGHTER, customLabel = null
            )
        )
        val facts = listOf(
            MemoryFactEntity(content = "小宝对花粉过敏", category = "健康", source = "CHAT_TOOL", createdAt = 100L)
        )
        val snap = formatMemoryContextFromEntities(facts, relations)
        assertTrue(snap.contains("小宝=女儿"))
        assertTrue(snap.contains("- 小宝对花粉过敏（健康）"))
    }

    @Test
    fun mapsEntities_customLabelOverridesPredicate() {
        val relations = listOf(
            RelationDisplayItem(
                relationId = 1, subjectPersonId = 2, subjectName = "大宝",
                predicate = RelationPredicate.OTHER, customLabel = "发小"
            )
        )
        val snap = formatMemoryContextFromEntities(emptyList(), relations)
        assertTrue(snap.contains("大宝=发小"))
        assertTrue(!snap.contains("大宝=其他"))
    }

    @Test
    fun mapsEntities_emptyFactsAndRelations_returnsBlank() {
        assertEquals("", formatMemoryContextFromEntities(emptyList(), emptyList()))
    }

    private fun assertEquals(expected: String, actual: String) =
        org.junit.Assert.assertEquals(expected, actual)
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.memory.MemoryContextProviderImplTest"
```
Expected: 编译失败（`formatMemoryContextFromEntities` 未定义）。

- [ ] **Step 3: 写实现**

`app/src/main/java/com/mamba/picme/domain/memory/MemoryContextProviderImpl.kt`:

```kotlin
package com.mamba.picme.domain.memory

import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.entity.MemoryFactEntity
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.domain.person.RelationDisplayItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [MemoryContextProvider] 的 app 层实现：合并 [MemoryRepository.observeAllFacts] +
 * [PersonRepository.observeRelationsToSelf]，经 [formatMemoryContextFromEntities] 格式化后
 * 缓存到 @Volatile 字段。Flow 收集异常 fail-open（保留上次有效值或 ""）。
 *
 * **线程模型**：收集在 [scope]（app 应用级作用域）跑；[snapshot] 在 langchain4j AiServices
 * 线程读。`@Volatile` 单引用原子替换，无竞态。
 */
class MemoryContextProviderImpl(
    private val memoryRepository: MemoryRepository,
    private val personRepository: PersonRepository,
    scope: CoroutineScope
) : MemoryContextProvider {

    private val tag = "PoLang:MemoryProvider"

    @Volatile
    private var cached: String = ""

    init {
        scope.launch {
            combine(
                memoryRepository.observeAllFacts(),
                personRepository.observeRelationsToSelf()
            ) { facts, relations -> formatMemoryContextFromEntities(facts, relations) }
                .catch { cause -> Logger.w(tag, "snapshot flow failed, keep last cached", cause) }
                .collect { cached = it }
        }
    }

    override fun snapshot(): String = cached

    companion object {
        /**
         * 实体 → 快照文本的纯映射（internal，可纯 JVM 单测）。无 Android/Room 运行时依赖——
         * 入参是普通 data class，测试中直接构造即可。
         */
        internal fun formatMemoryContextFromEntities(
            facts: List<MemoryFactEntity>,
            relations: List<RelationDisplayItem>
        ): String = formatMemoryContext(
            relations = relations.map {
                RelationLine(it.subjectName, it.customLabel ?: it.predicate.labelZh)
            },
            facts = facts.map { FactLine(it.content, it.category, it.createdAt) }
        )
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.memory.MemoryContextProviderImplTest"
```
Expected: PASS（3 个用例全绿）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/memory/MemoryContextProviderImpl.kt \
        app/src/test/java/com/mamba/picme/domain/memory/MemoryContextProviderImplTest.kt
git commit -m "feat(memory): MemoryContextProviderImpl + 实体→快照映射单测"
```

---

## Task 4: 注入点 `composeSystemPrompt` + Config 字段

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgent.kt`（加纯函数 + 改 lambda）
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgentConfig.kt`（加字段 + Builder）
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/inference/remote/react/ComposeSystemPromptTest.kt`

- [ ] **Step 1: 写失败测试（拼接逻辑，纯 JVM）**

`runtime-core/src/test/java/com/mamba/picme/agent/core/inference/remote/react/ComposeSystemPromptTest.kt`:

```kotlin
package com.mamba.picme.agent.core.inference.remote.react

import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeSystemPromptTest {

    @Test
    fun nullProvider_returnsBase() {
        assertEquals("BASE", composeSystemPrompt("BASE", null))
    }

    @Test
    fun blankSnapshot_returnsBase() {
        assertEquals("BASE", composeSystemPrompt("BASE", stub("   ")))
        assertEquals("BASE", composeSystemPrompt("BASE", stub("")))
    }

    @Test
    fun nonBlank_appendsWithBlankLine() {
        assertEquals("BASE\n\nMEM", composeSystemPrompt("BASE", stub("MEM")))
    }

    private fun stub(value: String): MemoryContextProvider = object : MemoryContextProvider {
        override fun snapshot(): String = value
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.remote.react.ComposeSystemPromptTest"
```
Expected: 编译失败（`composeSystemPrompt` 未定义）。

- [ ] **Step 3: 写 `composeSystemPrompt` 纯函数 + 改 lambda**

在 `RemoteReActAgent.kt` 顶部 import 区加：

```kotlin
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
```

在 `RemoteReActAgent.kt` 文件末尾（class 外，文件级）加纯函数：

```kotlin
/**
 * 把基础 system prompt 与记忆快照拼成最终 system message 文本。快照为空（无 provider / provider
 * 返回空白）时原样返回 [base]，零开销。供 [RemoteReActAgent] 的 systemMessageProvider 每轮调用。
 */
internal fun composeSystemPrompt(base: String, provider: MemoryContextProvider?): String {
    val snapshot = provider?.snapshot()?.trim()?.ifEmpty { null } ?: return base
    return "$base\n\n$snapshot"
}
```

把 `RemoteReActAgent.kt` 中（`getOrCreateAssistant()` 内，约 `:172`）的：

```kotlin
                .systemMessageProvider { SystemMessage.from(config.systemPrompt) }
```

改为：

```kotlin
                .systemMessageProvider {
                    SystemMessage.from(composeSystemPrompt(config.systemPrompt, config.memoryContextProvider))
                }
```

- [ ] **Step 4: 加 Config 字段 + Builder 方法**

在 `RemoteReActAgentConfig.kt`：

(a) data class 主构造末尾（`deviceId: String = ""` 之后）加字段：

```kotlin
    val deviceId: String = "",
    val memoryContextProvider: MemoryContextProvider? = null
```

并在文件顶部 import 区加：

```kotlin
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
```

(b) `Builder` 类内，`private var deviceId: String = ""` 之后加：

```kotlin
        private var memoryContextProvider: MemoryContextProvider? = null
```

并在 Builder 内（其他 setter 旁，如 `fun systemPrompt` 附近）加 setter：

```kotlin
        fun memoryContextProvider(provider: MemoryContextProvider) = apply {
            this.memoryContextProvider = provider
        }
```

(c) `Builder.build()` 的返回行，在 `deviceId` 之后补 `memoryContextProvider`：

把：
```kotlin
            return RemoteReActAgentConfig(apiKey, baseUrl, modelName, systemPrompt, maxIterations, temperature, streaming, gatewayToken, deviceId)
```
改为：
```kotlin
            return RemoteReActAgentConfig(apiKey, baseUrl, modelName, systemPrompt, maxIterations, temperature, streaming, gatewayToken, deviceId, memoryContextProvider)
```

- [ ] **Step 5: 跑测试确认通过**

```
./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.remote.react.ComposeSystemPromptTest"
```
Expected: PASS（3 个用例全绿）。

- [ ] **Step 6: 编译 runtime-core 确认整体无误**

```
./gradlew :runtime-core:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL（既有 AgentConfigurator 通过 Builder 构建，新字段默认 null，仍兼容）。

- [ ] **Step 7: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgent.kt \
        runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgentConfig.kt \
        runtime-core/src/test/java/com/mamba/picme/agent/core/inference/remote/react/ComposeSystemPromptTest.kt
git commit -m "feat(runtime-core): systemMessageProvider 每轮追加记忆快照 + composeSystemPrompt 单测"
```

---

## Task 5: AgentConfigurator + AgentOrchestrator 装配通路

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentConfigurator.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt`

本任务是纯装配（把 provider 经 configurator 喂给两个 agent 的 config）。无独立可单测的隔离行为；以编译 + 既有单测 + 设备验证（Task 6）兜底。

- [ ] **Step 1: AgentConfigurator 加字段 + setter + 两处 config 注入**

在 `AgentConfigurator.kt` 顶部 import 区加：

```kotlin
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
```

在 `AgentConfigurator` 类内（其他 `private val`/字段旁，例如 `private val tag = "AgentConfigurator"` 附近）加：

```kotlin
    /** 聊天/飞书 agent 每轮被动注入的记忆快照供给者；由 app 在 onCreate 注入。 */
    @Volatile
    private var memoryContextProvider: MemoryContextProvider? = null

    /** app 层注入记忆快照供给者；须在任一 agent 首次构建前调用。 */
    fun setMemoryContextProvider(provider: MemoryContextProvider) {
        memoryContextProvider = provider
    }
```

在 `getFeishuAgent(...)` 内，把构建 `cfg` 的整段（`val cfg = try { RemoteReActAgentConfig.Builder()...build() } catch ...`）替换为下面这段——仅在原链基础上多取一次 `memProvider` 并用 `.apply { ... }` 安全注入（provider 为 null 时不设，等价旧行为）：

```kotlin
        val memProvider = memoryContextProvider
        val cfg = try {
            RemoteReActAgentConfig.Builder()
                .apiKey(currentConfig.apiKey)
                .baseUrl(currentConfig.baseUrl)
                .modelName(currentConfig.modelId)
                .gatewayToken(currentConfig.gatewayToken)
                .deviceId(deviceId)
                .apply { if (memProvider != null) memoryContextProvider(memProvider) }
                .build()
        } catch (e: Exception) {
            Logger.w("AgentConfigurator", "Failed to build FeishuAgent config", e)
            return null
        }
```

在 `getChatAgent(...)` 同理：找到其 `val cfg = try { RemoteReActAgentConfig.Builder().apiKey(...).baseUrl(...).modelName(...).gatewayToken(...).deviceId(...).systemPrompt(...).build() }`，在 `.systemPrompt(...)` 之后、`.build()` 之前插入对 provider 的注入。把该段替换为：

```kotlin
        val memProvider = memoryContextProvider
        val cfg = try {
            RemoteReActAgentConfig.Builder()
                .apiKey(currentConfig.apiKey)
                .baseUrl(currentConfig.baseUrl)
                .modelName(currentConfig.modelId)
                .gatewayToken(currentConfig.gatewayToken)
                .deviceId(deviceId)
                .systemPrompt(chatSystemPrompt + "\n\n当前日期：${java.time.LocalDate.now()}。用户说「去年」「上个月」等相对时间时，据此计算具体日期范围。")
                .apply { if (memProvider != null) memoryContextProvider(memProvider) }
                .build()
        } catch (e: Exception) {
            Logger.w(tag, "Failed to build ChatAgent config", e)
            return null
        }
```
（保持原 `getChatAgent` 的 systemPrompt 文案与其它字段不变；仅新增 `memProvider` 取值 + `.apply { ... }`。）

- [ ] **Step 2: AgentOrchestrator 加转发入口**

在 `AgentOrchestrator.kt` 顶部 import 区加：

```kotlin
import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
```

在 `AgentOrchestrator` 类内、`registerCapability(...)`（约 `:116`）附近加：

```kotlin
    /**
     * 注入记忆快照供给者（转发给内部 [AgentConfigurator]）。须在 chat/飞书 agent 首次构建前
     * 调用——app 在 PoLangApplication.onCreate 注入，早于 agent 懒构建。
     */
    fun setMemoryContextProvider(provider: MemoryContextProvider) {
        configurator.setMemoryContextProvider(provider)
    }
```

- [ ] **Step 3: 编译确认**

```
./gradlew :runtime-core:compileDebugKotlin :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 既有单测回归（确保装配改动没破坏既有 chat/agent 行为）**

```
./gradlew :runtime-core:testDebugUnitTest :app:testDebugUnitTest
```
Expected: 全绿（既有用例不受影响——provider 默认 null，等价旧行为）。

- [ ] **Step 5: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentConfigurator.kt \
        runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt
git commit -m "feat(runtime-core): AgentConfigurator/Orchestrator 透传记忆快照给 chat+飞书 agent"
```

---

## Task 6: PoLangApplication 装配 + 设备端到端验证

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/PoLangApplication.kt`

- [ ] **Step 1: 注入 provider**

在 `PoLangApplication.kt` 顶部 import 区加：

```kotlin
import com.mamba.picme.domain.memory.MemoryContextProviderImpl
```

在 `initializeCapabilities()` 末尾（`orchestrator.registerCapability(container.memoryCapability)` 与其后那行 `Logger.i(TAG, "- MemoryCapability: ...")` 之后）追加：

```kotlin
        val memoryContextProvider = MemoryContextProviderImpl(
            memoryRepository = container.memoryRepository,
            personRepository = container.personRepository,
            scope = applicationScope
        )
        orchestrator.setMemoryContextProvider(memoryContextProvider)
        Logger.i(TAG, "- MemoryContextProvider: injected (chat + feishu passive memory)")
```

（`orchestrator` 即本函数上方 `val orchestrator = AgentOrchestrator.getInstance(this)`；`applicationScope` 是 PoLangApplication 的属性 `:74`；`container.memoryRepository` / `container.personRepository` 均在 AppContainer 暴露。）

- [ ] **Step 2: 编译 + 装 APK**

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/polang-debug.apk
```
Expected: BUILD SUCCESSFUL + 安装成功。

- [ ] **Step 3: 设备端到端验证（chat）**

启动 app 进聊天页，依次发：
1. `记住我对花粉过敏` —— 期望回复「已记住：我对花粉过敏」
2. `小宝是我女儿` —— 期望触发 `remember_person_relation`（若小宝未在相册命名，会回引导提示；可先在相册人物分组命名"小宝"后再发）
3. **新开会话**（清掉当轮上下文，避免模型靠历史蒙）
4. `我对什么过敏？` —— 期望**直接答出**"花粉过敏"（不调工具）
5. `我女儿叫什么？` —— 期望**直接答出**"小宝"

并核对 LLM 日志（llmlog 详情页，按 traceId）该轮的 system prompt 含【关于用户】段。

- [ ] **Step 4: 设备端到端验证（飞书）**

经飞书发：`给小宝拍张照` / `搜我女儿的照片` —— 期望 agent 能按"小宝/女儿"行动（飞书 system prompt = DEFAULT_SYSTEM_PROMPT + 【关于用户】）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/PoLangApplication.kt
git commit -m "feat(app): 注入 MemoryContextProvider，chat+飞书 agent 每轮感知记忆"
```

---

## Task 7: 文档同步（CLAUDE.md 三层文档原子要求）

**Files:**
- Modify: `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`

- [ ] **Step 1: 在 Agent 架构文档记一笔被动注入**

在 `AGENT_ARCHITECTURE.md` 描述 chat/远程 ReAct agent 的段落（或 "Memory" 相关小节）补一段：

```markdown
### 被动记忆注入（chat + 飞书，2026-07）

`RemoteReActAgent` 的 `systemMessageProvider` 每轮重调，在固定 system prompt 后追加
`MemoryContextProvider.snapshot()` 返回的【关于用户】快照（已记住的事实 + 与"我"的人物
关系）。快照由 app 层 `MemoryContextProviderImpl` 用 Room Flow（`observeAllFacts` +
`observeRelationsToSelf`）预热 `@Volatile` 缓存，按 ~1500 字符预算截断、超出用
`recall_memory` 兜底。chat 与飞书 agent 共用同一份设备本机记忆。设计 spec：
`docs/superpowers/specs/2026-07-27-chat-memory-passive-injection-design.md`。
```

- [ ] **Step 2: 提交**

```bash
git add docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md
git commit -m "docs(arch): 记录 chat+飞书 被动记忆注入"
```

---

## Self-Review（计划作者自查，已执行）

- **Spec 覆盖**：§4.1 接口→Task 2；§4.2 纯函数/DTO→Task 1；§4.3 Impl→Task 3；§4.4 注入点/Config→Task 4；§4.5 装配（chat+飞书+时序）→Task 5+6；§5 格式/预算/兜底→Task 1（含 budget 测试）；§7 fail-open→Task 3 `.catch`；§8 测试→Task 1/3/4；§9/§10 chat+飞书→Task 5/6；§11 文档→Task 7。无遗漏。
- **占位符扫描**：无 TBD/TODO；所有代码步均含完整代码；Task 5 的 `.also { /* 见下方 */ }` 仅是过渡说明，紧接给出了实际安全写法。
- **类型/命名一致**：`MemoryContextProvider.snapshot()`、`formatMemoryContext(relations, facts, charBudget)`、`RelationLine(name, label)`、`FactLine(content, category, createdAt)`、`formatMemoryContextFromEntities(facts, relations)`、`composeSystemPrompt(base, provider)`、`setMemoryContextProvider(provider)` 在各 Task 间一致；`MEMORY_CONTEXT_CHAR_BUDGET`、`SECTION_HEADER`、`relationLabel = customLabel ?: predicate.labelZh`（实现在 Task 3 映射里）一致。
- **测试可行性**：Task 1/3/4 均为纯 JVM 单测（无 Room/设备）；Task 5 为装配（编译 + 既有回归 + Task 6 设备验证）；Task 6 为端到端。
```
