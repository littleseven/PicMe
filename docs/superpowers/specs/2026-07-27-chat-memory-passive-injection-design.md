# 聊天记忆被动注入设计（事实 + 人物关系）

- 日期：2026-07-27
- 状态：已评审，待实现
- 分支：`worktree-chat-memory`
- 相关代码：
  - `runtime-core/.../inference/remote/react/RemoteReActAgent.kt`（`systemMessageProvider` lambda，每轮重调）
  - `runtime-core/.../inference/remote/react/RemoteReActAgentConfig.kt`（chat agent 配置）
  - `runtime-core/.../facade/AgentConfigurator.kt`（`chatSystemPrompt` + `getChatAgent`）
  - `runtime-core/.../facade/AgentOrchestrator.kt`（持有 `configurator`，chat dispatch 调 `getChatAgent`）
  - `app/.../features/chat/capability/MemoryCapability.kt`、`domain/memory/MemoryRepository.kt`
  - `app/.../domain/person/PersonRepository.kt`（`observeRelationsToSelf()`）

## 1. 背景与问题

设备实测发现"LLM 无法获取/理解 memory"。根因排查（静态链路全量追踪）结论：

事实记忆（`memory_facts`）与人物关系（`person_relations`）**两类"已记住"状态，LLM 都只能在主动调用 recall 类工具时才看得到**：

- **事实记忆**：`ChatToolService` 已暴露 `@Tool remember_fact / forget_fact / recall_memory`（`ChatToolService.kt:251/266/280`），`chatSystemPrompt` 也有【记忆工具】指引。但**没有被动注入**——LLM 必须自己决定调 `recall_memory`，实际经常不调，于是"失忆"。
- **人物关系**：更严重——`ChatToolService` 只暴露 `remember_person_relation / forget_person_relation`（`ChatToolService.kt:235/244`），**根本没有读取工具**。`recall_memory` 只查 `memory_facts`，不覆盖 `person_relations`。所以"我女儿是谁 / 我有哪些家人"这类问答，LLM 结构性答不出。
- 注意：相册**搜索**链路（`PersonQueryResolver.resolveByKinship`）内部会解关系，所以"搜我女儿的照片"是好使的；本设计只针对**对话问答**这条不通的链路。

**统一根因**：没有任何"已记住的事实/关系"被被动注入到 LLM 上下文。

## 2. 目标 / 非目标

**目标**

- 每一轮 chat，把"已记住的事实 + 已声明的人物关系（与'我'的关系）"以**有预算上限的快照**形式追加进 system prompt，使 LLM 无需主动调用任何工具即可"知道"并引用它们。
- 同时治好事实与关系两边的根；让"我女儿是谁""我对什么过敏"这类问答直接可答。

**非目标（本次不做）**

- 不新增 `recall_relations` 读工具（走纯被动注入路线；现有 `recall_memory` 等工具保留作截断兜底）。
- 不改飞书 RPA agent（`PoLangToolService`），只改 **chat agent**。
- 不做 FTS / 语义召回，仍维持 LIKE + 注入。
- 不改搜索链路（`PersonQueryResolver` 已工作）。

## 3. 方案总览

利用 `RemoteReActAgent` 中 langchain4j 的 `systemMessageProvider` lambda（`RemoteReActAgent.kt:172`）——该 provider **每轮请求都会被重新调用**（即便 agent 本身被缓存）。当前 lambda 返回固定的 `config.systemPrompt`；改为在其后追加一段由 `MemoryContextProvider.snapshot()` 返回的"关于用户"快照。

被动注入因此**架构成本极低**：无需重建 agent，天然有每轮刷新的注入点。

## 4. 组件与分层契约

### 4.1 `MemoryContextProvider`（接口，runtime-core）

放 runtime-core，因为 `RemoteReActAgentConfig` 要持有它。同步契约——langchain4j 的 `SystemMessageProvider` 是同步回调，而 Room 是 suspend，所以实现方需用 Flow 预热一份内存缓存，`snapshot()` 只读缓存。

```kotlin
// runtime-core/.../inference/remote/tool/MemoryContextProvider.kt
package com.mamba.picme.agent.core.inference.remote.tool

/** 聊天每轮被动注入的"已记住"快照供给者。snapshot() 必须非阻塞、线程安全；无内容返回 ""。 */
interface MemoryContextProvider {
    fun snapshot(): String
}
```

### 4.2 快照格式化纯函数（app 层，可纯 JVM 单测）

格式化全部收口到一个纯函数，输入是纯 DTO（不依赖 Room 实体），便于纯 JVM 单测（对齐仓库"纯函数 + 单测"约定，参考 `fcc50035 图片预览翻页集合构建纯函数 + 单测`）。

```kotlin
// app/.../domain/memory/MemoryContextFormatter.kt
data class RelationLine(val name: String, val label: String)
data class FactLine(val content: String, val category: String?, val createdAt: Long)

/**
 * 生成"关于用户"快照文本。无关系且无事实返回 ""。
 * 关系全量（一般很少）；事实按 createdAt 倒序填进剩余字符预算，超限截断并追加 recall 兜底提示。
 */
fun formatMemoryContext(
    relations: List<RelationLine>,
    facts: List<FactLine>,
    charBudget: Int = MEMORY_CONTEXT_CHAR_BUDGET
): String

const val MEMORY_CONTEXT_CHAR_BUDGET = 1500
```

> DTO 映射在实现侧完成：`RelationDisplayItem` → `RelationLine(name = subjectName, label = relationLabel(customLabel, predicate))`；`MemoryFactEntity` → `FactLine(content, category, createdAt)`。`relationLabel` 是一个小 helper：`customLabel` 非空优先用它，否则把 `RelationPredicate`（枚举）映射成中文称谓（经既有 `KinshipLexicon` 解析；若 `KinshipLexicon` 未直接暴露反向映射，则在实现侧加一个 `predicate → 称谓` 的小映射表，不引入新依赖）。

### 4.3 `MemoryContextProviderImpl`（app 层）

```kotlin
// app/.../domain/memory/MemoryContextProviderImpl.kt
class MemoryContextProviderImpl(
    private val memoryRepository: MemoryRepository,
    private val personRepository: PersonRepository,
    private val scope: CoroutineScope            // app 应用级作用域
) : MemoryContextProvider {

    @Volatile private var cached: String = ""

    init {
        // 两路 Flow 合流 → 重算快照写 cached。任一异常 fail-open（cached 保持上一次有效值或 ""）。
        scope.launch {
            combine(
                memoryRepository.observeAllFacts(),
                personRepository.observeRelationsToSelf()
            ) { facts, relations -> buildSnapshot(facts, relations) }
                .catch { /* log + 保留旧值 */ }
                .collect { cached = it }
        }
    }

    override fun snapshot(): String = cached

    private fun buildSnapshot(
        facts: List<MemoryFactEntity>,
        relations: List<RelationDisplayItem>
    ): String = formatMemoryContext(
        relations = relations.map { RelationLine(it.subjectName, relationLabel(it.customLabel, it.predicate)) },
        facts = facts.map { FactLine(it.content, it.category, it.createdAt) }
    )
}
```

线程模型：`@Volatile String` 单引用原子替换，`snapshot()` 在 AiServices 线程读、Flow 在 app 作用域写，无竞态。冷启首轮若 Flow 尚未首发，`cached = ""` → 该轮不注入（可接受，Flow 很快首发当前值）。

### 4.4 注入点改造

**`RemoteReActAgentConfig`** 加可空字段（Builder 一并补）：

```kotlin
data class RemoteReActAgentConfig(
    ...
    val memoryContextProvider: MemoryContextProvider? = null   // 新增；仅 chat agent 注入
)
```

**`RemoteReActAgent.getOrCreateAssistant()`**（`:172`）lambda 改为：

```kotlin
.systemMessageProvider {
    val base = config.systemPrompt
    val mem = config.memoryContextProvider?.snapshot().orEmpty()
    SystemMessage.from(if (mem.isBlank()) base else "$base\n\n$mem")
}
```

### 4.5 装配（app → runtime-core）

- `AgentOrchestrator` 暴露转发入口（内部 `configurator` 是 private）：
  ```kotlin
  fun setMemoryContextProvider(provider: MemoryContextProvider) {
      configurator.setMemoryContextProvider(provider)
  }
  ```
- `AgentConfigurator` 增加 `private var memoryContextProvider: MemoryContextProvider? = null` 与 setter；`getChatAgent(...)` 构造 `RemoteReActAgentConfig` 时把 provider 传入（`.memoryContextProvider(memoryContextProvider)`）。飞书 agent `getFeishuAgent` 不传（保持 null）。
- `PoLangApplication.onCreate`（注册 capability 的同一区域，`PoLangApplication.kt:619` 附近）：
  ```kotlin
  val memoryContextProvider = MemoryContextProviderImpl(
      memoryRepository = container.memoryRepository,
      personRepository = container.personRepository,
      scope = applicationScope
  )
  AgentOrchestrator.getInstance(this).setMemoryContextProvider(memoryContextProvider)
  ```
  `PersonRepository` 从 `container` 取（与 `personRelationCapability` 同源）。

## 5. 快照格式与预算策略

`formatMemoryContext` 输出示例（非空时）：

```
【关于用户（系统已记住，可直接引用，无需再问）】
关系：小宝=女儿；大宝=发小
事实：
- 小宝对花粉过敏（健康）
- 喜欢低饱和度滤镜（偏好）
（事实共 12 条，已显示最近 8 条，更多可用 recall_memory 查询）
```

规则：

1. 关系全量，拼成 `名字=称谓`，分号分隔。一般数量很少；若极端多到撑爆预算，则也按预算截断并加提示（边界由纯函数处理）。
2. 事实按 `createdAt` 倒序，逐条填进"扣除关系段后的剩余字符预算"。
3. 事实被截断时，末尾追加兜底提示，明确"更多可用 `recall_memory` 查询"——与现有 `recall_memory` 工具形成闭环（注入给"知道"，recall 给"穷举"）。
4. 无关系且无事实 → 返回 `""`，`systemMessageProvider` 不追加任何内容，**零开销**。
5. 预算单位为字符（非 token），纯函数内确定性计算，常量 `MEMORY_CONTEXT_CHAR_BUDGET = 1500` 可调。

## 6. 每轮数据流

```
用户发消息
 → ChatViewModel → AgentOrchestrator chat dispatch（:1111 configurator.getChatAgent）
 → 缓存的 RemoteReActAgent.executeTask（:1160）
 → AiServices 构请求 → 回调 systemMessageProvider
 → SystemMessage = chatSystemPrompt + 日期 + 【关于用户】cachedSnapshot
 → LLM（可直接答"我女儿是谁/我对什么过敏"，也可仍调工具做穷举）
```

快照由 Flow 异步刷新；事实/关系变更后，下一轮自动反映。

## 7. 边界与错误处理

- **fail-open**：`snapshot()` / Flow 收集任何异常都不影响聊天——`cached` 保留上次有效值或 `""`，绝不抛到 `systemMessageProvider`。
- **provider 未注入**：飞书 agent / 单测场景 `memoryContextProvider = null` → `snapshot()` 不调用 → 等价现状。
- **预算截断**：纯函数确定性截断 + recall 兜底提示，保证不无限膨胀 system prompt。
- **冷启首轮**：Flow 未首发时 `cached = ""`，该轮不注入；Room Flow 会很快首发当前行。
- **线程安全**：`@Volatile` 单引用；读（AiServices 线程）写（app 作用域）无竞态。

## 8. 测试计划

- **`MemoryContextFormatterTest`（纯 JVM）**：空、只关系、只事实、组合、超预算截断 + recall 提示文案、关系全量优先于事实、`createdAt` 倒序、预算恰好。
- **`MemoryContextProviderImplTest`（Robolectric / 假 Flow）**：注入假 `MemoryRepository`/`PersonRepository` 的 `Flow`，验证合并后 `cached` 被正确刷新、异常时 fail-open 不崩。
- **集成（可选，手动/设备）**：设备上"记住我对花粉过敏"+"小宝是我女儿"→ 新会话问"我对什么过敏""我女儿叫什么"→ 应直接答出（不调工具）。配合既有 llmlog（traceId）核对 system prompt 含【关于用户】段。

## 9. 上线边界（YAGNI）

- 只注入 **chat agent**；飞书 RPA（`PoLangToolService`）不动。
- **不加** `recall_relations` 读工具；现有工具集不变。
- 预算 1500 字符为初始值，上线后按实际事实量与 token 成本再调。

## 10. 记录的决策

| 决策点 | 取值 | 理由 |
|---|---|---|
| 注入预算 | ~1500 字符，按预算截断 + `recall_memory` 兜底 | 用户确认；事实可涨，需有界 |
| 事实排序 | `createdAt` 倒序（最近优先） | 用户确认；确定性、易理解 |
| 装配入口 | `AgentOrchestrator.setMemoryContextProvider` 转发给 `AgentConfigurator` | `configurator` 为 private，需经 orchestrator 暴露 |
| 注入位置 | `systemMessageProvider` lambda（每轮重调） | 无需重建 agent，天然每轮刷新 |
| 覆盖范围 | chat agent only | 飞书 RPA 无需"记忆"语义 |

## 11. 不在本次范围（后续）

- `recall_relations` 读工具（若日后需在注入截断外按谓词穷举关系）。
- FTS / 向量召回替代 LIKE。
- 把注入推广到飞书 / 本地 Qwen 路径（当前 chat 走远程 ReAct）。
- 三层文档同步（`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`、`CAPABILITY_REGISTRY.md`）在实现 PR 中按 CLAUDE.md 要求原子提交。
