# LLM 日志 traceId 关联 + 详情页横滑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给三张日志表(`llm_call_log`/`tool_call_log`/`js_run_log`)加 `traceId`(一条用户消息一个),沿 dispatch 链路贯穿 LLM/tool/JS 三层;日志详情页变 `HorizontalPager`,同 traceId 的三层记录按时间横滑浏览,顶部指示器标注层级与计数。

**Architecture:** `ChatViewModel.sendMessage` 生成 traceId,装入 `AgentContext.traceId`。tool 层 `CommandExecutor`(已有 context)与 JS 层 `JsRuntime`(加 traceId 参数)直接读 context;LLM 层因 `CapturingChatModelListener` 在 langchain4j listener 边界拿不到 context,用 `TraceIdHolder` 由 `RemoteReActAgent` 每轮设置、listener 读取。详情页把三表同 traceId 记录合并排序喂给 pager。

**Tech Stack:** Room(`fallbackToDestructiveMigration`,version 3→4)、Compose `HorizontalPager`、langchain4j `ChatModelListener`。

**Spec:** `docs/superpowers/specs/2026-07-27-chat-preview-swipe-and-llmlog-traceid-design.md` 功能②。

---

## File Structure

`:runtime-core`(纯 Kotlin):
- Modify: `model/context/AgentModels.kt` — `AgentContext` 加 `traceId`。
- Modify: `inference/remote/log/LlmCallRecord.kt` — 加 `traceId`。
- Modify: `inference/remote/log/CapturingChatModelListener.kt` — 读 holder。
- Create: `inference/remote/log/TraceIdHolder.kt` — 可变持有器。
- Modify: `runtime/capability/CommandExecutionRecorder.kt` — `record(...)` 加 `traceId`。
- Modify: `runtime/capability/CommandExecutor.kt` — `notifyRecorder` 传 `context.traceId`。
- Modify: `js/JsRunEvent`(同文件)— 加 `traceId`。
- Modify: `js/JsRuntime.kt` — `eval/evalAsync/callFunction` 加 traceId 重载 + `runRecorded`。
- Modify: `inference/remote/react/RemoteReActAgent.kt` — `executeTask(traceId)` + 设 holder。
- Modify: `facade/AgentOrchestrator.kt` — `processChatReAct(traceId)`。
- Modify: `remote/config/RemoteModelFactory.kt` — `createBuilder(..., holder)` 透传 holder。

`:app`:
- Modify: `data/local/llmlog/{LlmCallLogEntity,ToolCallLogEntity,JsRunLogEntity}.kt` — 加 `traceId` 列。
- Modify: `data/local/llmlog/{LlmCallLogDao,ToolCallLogDao,JsRunLogDao}.kt` — `getByTraceId`。
- Modify: `data/local/llmlog/LlmLogDatabase.kt` — version 3→4。
- Modify: `data/local/llmlog/Room{LlmCall,ToolCall,JsRun}Recorder.kt` — 写 traceId。
- Modify: `features/debug/LlmCallLogViewModel.kt` — `loadTurn` + `TurnRecordItem`。
- Modify: `features/debug/LlmCallLogScreen.kt` — `LlmTurnDetailPager` + 指示器 + `ToolCallLogDetail` + Tool row onClick。
- Modify: `features/chat/ChatViewModel.kt` — `sendMessage` 生成 traceId。

**测试策略**:traceId 透传(伪 recorder 断言收到正确 traceId)、turn 记录合并排序(纯函数)→ JVM 单测(TDD)。Compose UI / Room DAO → 编译 + 手动。

---

### Task 1: 表结构 — 三实体加 traceId + DB 升级 + DAO 查询

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/LlmCallLogEntity.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/ToolCallLogEntity.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/JsRunLogEntity.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/LlmCallLogDao.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/ToolCallLogDao.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/JsRunLogDao.kt`(若存在;否则在对应 DAO 文件)
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/LlmLogDatabase.kt`

- [ ] **Step 1: 三实体加列**

在每个实体的字段末尾(`errorMessage` 之后)加:

```kotlin
    /** 关联 ID:一条用户消息一个 traceId;非 chat 来源/老数据为 null(详情页按无关联处理)。 */
    val traceId: String? = null
```

三处一致(字段名 `traceId`,可空,默认 null)。

- [ ] **Step 2: DB version 3 → 4**

`LlmLogDatabase.kt` 的 `@Database(... version = 3, ...)` 改为 `version = 4`。`fallbackToDestructiveMigration(true)` 已配,无需写迁移(诊断数据可丢,用户已确认)。

- [ ] **Step 3: 三 DAO 加 getByTraceId**

先读各 DAO 现有 `@Query` 风格(`LlmCallLogDao.kt` 等),在末尾加(以 LLM 为例):

```kotlin
    @Query("SELECT * FROM llm_call_log WHERE traceId = :traceId ORDER BY createdAt ASC")
    suspend fun getByTraceId(traceId: String): List<LlmCallLogEntity>
```

`ToolCallLogDao`:

```kotlin
    @Query("SELECT * FROM tool_call_log WHERE traceId = :traceId ORDER BY createdAt ASC")
    suspend fun getByTraceId(traceId: String): List<ToolCallLogEntity>
```

`JsRunLogDao`(读文件确认表名 `js_run_log`):

```kotlin
    @Query("SELECT * FROM js_run_log WHERE traceId = :traceId ORDER BY createdAt ASC")
    suspend fun getByTraceId(traceId: String): List<JsRunLogEntity>
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/data/local/llmlog/
git commit -m "feat(llmlog): 三表加 traceId 列 + getByTraceId 查询(DB v3→4)"
```

---

### Task 2: AgentContext.traceId + ChatViewModel 生成(TDD)

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt:39`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt:707`

- [ ] **Step 1: AgentContext 加字段**

`AgentModels.kt:39` 的 `gallerySummary` 字段后加:

```kotlin
    ,
    /** 一次用户消息的关联 ID,贯穿该轮 LLM/tool/JS 三层日志;非 chat 来源为 null。 */
    val traceId: String? = null
```

(保持 data class 语法:`gallerySummary: GallerySummary? = null,` 后追加该字段,逗号在前。)

- [ ] **Step 2: ChatViewModel 生成 traceId 并注入**

`ChatViewModel.kt:707-713` 的 `agentContext` 构造改为:

```kotlin
                val agentContext = AgentContext(
                    scene = AgentScene.CHAT,
                    memorySessionId = sessionId,
                    recentSearchResults = sessionSearchSnapshots[sessionId].orEmpty(),
                    lastUserImageUri = _lastUserImageUri.value,
                    gallerySummary = gallerySummary,
                    traceId = java.util.UUID.randomUUID().toString()
                )
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin :runtime-core:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/AgentModels.kt \
        app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(agent): AgentContext 加 traceId + chat sendMessage 生成注入"
```

---

### Task 3: tool 层 traceId 透传(TDD)

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/runtime/capability/CommandExecutionRecorder.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/runtime/capability/CommandExecutor.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/RoomToolCallRecorder.kt`
- Test: `runtime-core/src/test/java/.../runtime/capability/CommandExecutorTraceIdTest.kt`

- [ ] **Step 1: 写失败测试 — 验证 executor 把 context.traceId 传给 recorder**

创建 `runtime-core/src/test/java/com/mamba/picme/agent/core/runtime/capability/CommandExecutorTraceIdTest.kt`:

```kotlin
package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.PageContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandExecutorTraceIdTest {

    @Test
    fun `executor passes context traceId to recorder`() = runBlocking {
        val captor = mutableListOf<String?>()
        CommandExecutor.recorder = object : CommandExecutionRecorder {
            override fun record(
                capability: String, commandType: String, latencyMs: Long,
                success: Boolean, errorCode: Int?, errorMessage: String?,
                traceId: String?
            ) {
                captor.add(traceId)
            }
        }
        val executor = CommandExecutor(timeoutMs = 1000L)
        val stub = object : Capability {
            override val name: String = "stub"
            override suspend fun execute(
                command: AgentCommand, context: AgentContext, pageContext: PageContext?
            ): Result<AgentAction> = Result.success(AgentAction.Success(""))
        }
        executor.execute(
            command = AgentCommand.TextReply("hi"),
            context = AgentContext(scene = AgentScene.CHAT, traceId = "trace-123"),
            pageContext = null,
            capability = stub
        )
        CommandExecutor.recorder = null
        assertEquals(listOf("trace-123"), captor)
    }
}
```

> 若 `AgentCommand.TextReply` / `AgentAction.Success` 构造与代码库不符,运行 Step 2 报错后按实际签名修正(读 `AgentCommand` / `AgentAction` 定义)。

- [ ] **Step 2: 运行测试,确认失败**

Run: `./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.runtime.capability.CommandExecutorTraceIdTest"`
Expected: FAIL —— `CommandExecutionRecorder.record` 无 `traceId` 参数(编译错)。

- [ ] **Step 3: recorder 接口加 traceId**

`CommandExecutionRecorder.kt` 的 `fun record(...)` 改为(末尾加参数):

```kotlin
    fun record(
        capability: String,
        commandType: String,
        latencyMs: Long,
        success: Boolean,
        errorCode: Int?,
        errorMessage: String?,
        traceId: String?
    )
```

- [ ] **Step 4: executor 传 context.traceId**

`CommandExecutor.kt`:
- `execute(...)` 内三处 `notifyRecorder(...)` 调用(`:55-60`、`:68`、`:76`)追加 `context.traceId` 实参。
- `notifyRecorder` 签名加 `traceId: String?`,并把它传给 `recorder?.record(..., traceId)`:

```kotlin
    private fun notifyRecorder(
        capability: String,
        commandType: String,
        startMs: Long,
        success: Boolean,
        errorCode: Int?,
        errorMessage: String?,
        traceId: String?
    ) {
        try {
            recorder?.record(
                capability = capability,
                commandType = commandType,
                latencyMs = System.currentTimeMillis() - startMs,
                success = success,
                errorCode = errorCode,
                errorMessage = errorMessage,
                traceId = traceId
            )
        } catch (e: Exception) {
            Logger.w(TAG, "recorder.record failed", e)
        }
    }
```

- [ ] **Step 5: RoomToolCallRecorder 实现同步**

`RoomToolCallRecorder.kt` 的 `record(...)` 签名加 `traceId: String?`,并写入实体:

```kotlin
    override fun record(
        capability: String,
        commandType: String,
        latencyMs: Long,
        success: Boolean,
        errorCode: Int?,
        errorMessage: String?,
        traceId: String?
    ) {
        scope.launch {
            try {
                dao.insert(
                    ToolCallLogEntity(
                        createdAt = System.currentTimeMillis(),
                        capability = capability,
                        commandType = commandType,
                        latencyMs = latencyMs,
                        success = success,
                        errorCode = errorCode,
                        errorMessage = LlmCallRecord.cap(errorMessage, ERROR_MESSAGE_MAX_CHARS),
                        traceId = traceId
                    )
                )
                pruneIfNeeded()
            } catch (e: Exception) {
                Logger.w(TAG, "record failed", e)
            }
        }
    }
```

- [ ] **Step 6: 运行测试,确认通过**

Run: `./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.runtime.capability.CommandExecutorTraceIdTest" :app:compileDebugKotlin`
Expected: executor 测试 PASS;app 编译通过(其它 `CommandExecutionRecorder` 实现若有无 traceId 的需同步,搜索 `object : CommandExecutionRecorder` / `CommandExecutionRecorder {`)。

- [ ] **Step 7: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/runtime/capability/ \
        runtime-core/src/test/java/com/mamba/picme/agent/core/runtime/capability/CommandExecutorTraceIdTest.kt \
        app/src/main/java/com/mamba/picme/data/local/llmlog/RoomToolCallRecorder.kt
git commit -m "feat(llmlog): tool 层 traceId 透传 CommandExecutor→recorder"
```

---

### Task 4: JS 层 traceId 透传(TDD)

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsRunEvent`(在 JsRuntime.kt 内或独立文件,按现状)
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsRuntime.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/RoomJsRunRecorder.kt`
- Modify: 调用 `jsRuntime.eval(...)` 的 chat JS capability(搜索定位)
- Test: `runtime-core/src/test/java/.../js/JsRuntimeTraceIdTest.kt`

- [ ] **Step 1: 写失败测试 — 验证 eval 带 traceId 时事件携带它**

创建 `runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsRuntimeTraceIdTest.kt`:

```kotlin
package com.mamba.picme.agent.core.js

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class JsRuntimeTraceIdTest {

    @Test
    fun `eval with traceId stamps event`() {
        val captor = mutableListOf<JsRunEvent>()
        JsRuntime.recorder = object : JsRunRecorder {
            override fun record(event: JsRunEvent) { captor.add(event) }
        }
        val engine = object : JsEngine {
            override fun eval(script: String): JsValue = JsValue.text("ok")
            override fun eval(script: String, timeoutMs: Long): JsValue = JsValue.text("ok")
            override fun evalAsync(code: String, timeoutMs: Long): JsValue = JsValue.text("ok")
            override fun callFunction(name: String, vararg args: JsValue): JsValue = JsValue.text("ok")
            override fun installBridge(bridge: JsBridge) {}
        }
        val rt = JsRuntime(engine = engine, scope = CoroutineScope(Dispatchers.Unconfined), source = "chat")
        rt.eval("1+1", traceId = "trace-js")
        JsRuntime.recorder = null
        assertEquals("trace-js", captor.first().traceId)
    }
}
```

> 若 `JsEngine` / `JsValue` API 与上不符(如 `JsValue.text` 不存在),读 `JsEngine.kt`/`JsValue.kt` 后修正测试桩。`runRecorded` 当前是 `private inline`,加 traceId 参数后测试可覆盖。

- [ ] **Step 2: 运行测试,确认失败**

Run: `./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.js.JsRuntimeTraceIdTest"`
Expected: FAIL —— `eval(script, traceId)` 重载不存在。

- [ ] **Step 3: JsRunEvent 加 traceId**

`JsRunEvent` data class 末尾(`latencyMs` 后)加:

```kotlin
    ,
    val traceId: String? = null
```

- [ ] **Step 4: JsRuntime 加 traceId 重载 + runRecorded**

`JsRuntime.kt`:
- 现有 `eval/evalAsync/callFunction`(`override`)保持不变(走 traceId=null 的 runRecorded)。
- 新增非 override 重载(JsRuntime 专有,带 traceId):

```kotlin
    fun eval(script: String, traceId: String?): JsValue =
        runRecorded(JsRunEvent.KIND_EVAL, script, traceId) { engine.eval(script) }

    fun eval(script: String, timeoutMs: Long, traceId: String?): JsValue =
        runRecorded(JsRunEvent.KIND_EVAL, script, traceId) { engine.eval(script, timeoutMs) }

    fun evalAsync(code: String, timeoutMs: Long, traceId: String?): JsValue =
        runRecorded(JsRunEvent.KIND_EVAL_ASYNC, code, traceId) { engine.evalAsync(code, timeoutMs) }

    fun callFunction(name: String, traceId: String?, vararg args: JsValue): JsValue =
        runRecorded(
            JsRunEvent.KIND_CALL_FUNCTION,
            name + "(" + args.joinToString(",") { it.toJson() } + ")",
            traceId
        ) { engine.callFunction(name, *args) }
```

- 把现有 `runRecorded(kind, script, block)` 改为带 traceId 并给原 override 调用补默认值:

```kotlin
    private inline fun runRecorded(
        kind: String, script: String, traceId: String? = null, block: () -> JsValue
    ): JsValue {
```

并在两处 `JsRunEvent(...)` 构造(success 与 failure)末尾加 `traceId = traceId,`。

- 原 `override fun eval(script)` 等保持调用 `runRecorded(KIND, script) { ... }`(traceId 默认 null)。

- [ ] **Step 5: RoomJsRunRecorder 写 traceId**

`RoomJsRunRecorder.kt` 的 `record(event)` 里 `JsRunLogEntity(...)` 构造末尾加 `traceId = event.traceId,`。

- [ ] **Step 6: chat JS capability 透传 context.traceId**

搜索调用 `jsRuntime.eval(` / `.evalAsync(` / `.callFunction(` 的 chat capability(典型:`ChatRunScriptCapability`)。把调用改为带 traceId 重载,传入 capability `execute(command, context, pageContext)` 里的 `context.traceId`:

```kotlin
jsRuntime.eval(script, traceId = context.traceId)
```

> 若调用点拿不到 `context`(如经中间层),该处 traceId 留 null(详情页按无关联处理),不阻塞。

- [ ] **Step 7: 运行测试 + 编译**

Run: `./gradlew :runtime-core:test --tests "com.mamba.picme.agent.core.js.JsRuntimeTraceIdTest" :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL。

- [ ] **Step 8: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/js/ \
        runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsRuntimeTraceIdTest.kt \
        app/src/main/java/com/mamba/picme/data/local/llmlog/RoomJsRunRecorder.kt
git commit -m "feat(llmlog): JS 层 traceId 透传 JsRuntime→recorder"
```

---

### Task 5: LLM 层 traceId 透传 — TraceIdHolder + listener(最难)

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/log/TraceIdHolder.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/log/LlmCallRecord.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/log/CapturingChatModelListener.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/remote/config/RemoteModelFactory.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgent.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/llmlog/RoomLlmCallRecorder.kt`

- [ ] **Step 1: TraceIdHolder**

创建 `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/log/TraceIdHolder.kt`:

```kotlin
package com.mamba.picme.agent.core.inference.remote.log

/**
 * traceId 跨边界持有器:LLM 调用经 langchain4j ChatModelListener,拿不到 AgentContext,
 * 故由调用方(RemoteReActAgent)在每轮开始时写入、listener 在 onResponse/onError 时读取。
 *
 * 每个 RemoteReActAgent 持有自己的 holder(其 chatModel 与 listener 也是 per-agent 懒建),
 * 且 agent 用单线程 executor 串行执行任务 → 单 agent 内无并发竞态。
 */
class TraceIdHolder {
    @Volatile
    var value: String? = null
}
```

- [ ] **Step 2: LlmCallRecord 加 traceId**

`LlmCallRecord.kt` data class 末尾(`errorMessage` 后)加:

```kotlin
    ,
    val traceId: String? = null
```

- [ ] **Step 3: CapturingChatModelListener 读 holder**

构造器加 `private val traceIdHolder: TraceIdHolder? = null`:

```kotlin
class CapturingChatModelListener(
    private val source: String,
    private val recorder: LlmCallRecorder,
    private val captureContent: Boolean = true,
    private val traceIdHolder: TraceIdHolder? = null
) : ChatModelListener {
```

`onResponse` 与 `onError` 两处 `LlmCallRecord(...)` 构造末尾加 `traceId = traceIdHolder?.value,`。

- [ ] **Step 4: RemoteModelFactory.createBuilder 接 holder**

读 `RemoteModelFactory.kt` 的 `createBuilder` 签名,加可选参数 `traceIdHolder: TraceIdHolder? = null`,并传给 `CapturingChatModelListener(sourceLabel, rec, captureContent, traceIdHolder)`(`:89`)。

```kotlin
    fun createBuilder(
        config: RemoteModelConfig,
        sourceLabel: String,
        traceIdHolder: TraceIdHolder? = null
    ): /* 原返回类型 */ {
        ...
        builder.listeners(CapturingChatModelListener(sourceLabel, rec, captureContent, traceIdHolder))
        ...
    }
```

- [ ] **Step 5: RemoteReActAgent 持 holder + executeTask(traceId) + 每轮设置**

`RemoteReActAgent.kt`:
- 类加字段:`private val traceIdHolder = TraceIdHolder()`。
- `chatModel` 的 `RemoteModelFactory.createBuilder(remoteModelConfig, "react")` 改为 `createBuilder(remoteModelConfig, "react", traceIdHolder)`。
- `executeTask` 加 traceId 参数:

```kotlin
    fun executeTask(userPrompt: String, taskCallback: RemoteReActAgentCallback? = null, traceId: String? = null) {
```

并把 `traceId` 透传给 `runAgentWithAiServices(userPrompt, taskCallback, traceId)`。

- `runAgentWithAiServices` 加 `traceId` 参数,在 `try {` 开头设、`finally` 清:

```kotlin
    private fun runAgentWithAiServices(
        userPrompt: String,
        taskCallback: RemoteReActAgentCallback? = null,
        traceId: String? = null
    ) {
        val cb = taskCallback ?: callback
        cb.onLoopStart(1)
        val startTime = System.currentTimeMillis()
        traceIdHolder.value = traceId   // 设:本轮 listener 读取
        try {
            ...
            val result = assistant.chat(userPrompt)
            ...
        } catch (e: Exception) {
            ...
        } finally {
            // 注意:runAgentWithAiServices 末尾原有 Logger.d("...end");此处再加 traceIdHolder.value = null
        }
        traceIdHolder.value = null
        Logger.d(TAG, "runAgentWithAiServices end")
    }
```

> 仔细读现有 `runAgentWithAiServices` 的 try/catch/finally 结构,把 `traceIdHolder.value = null` 放在确保执行的位置(方法末尾,return 之前)。`executeTask` 的 `executor.submit { runAgentWithAiServices(...) }` 在单线程 executor 上跑,串行无竞态。

- [ ] **Step 6: AgentOrchestrator 传 traceId**

`AgentOrchestrator.kt`:
- `processChatReAct(input, sessionId, timeoutMs)` 加 `traceId: String? = null`:

```kotlin
    suspend fun processChatReAct(
        input: String,
        sessionId: String,
        timeoutMs: Long = 120_000L,
        traceId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
```

- `:1159` 的 `agent.executeTask(input, callback)` 改为 `agent.executeTask(input, callback, traceId)`。
- `:478` 调用方 `streamChatReAct` 把 traceId 传下去:

```kotlin
            processChatReAct(input, agentContext.memorySessionId, traceId = agentContext.traceId).fold(
```

- [ ] **Step 7: RoomLlmCallRecorder 写 traceId**

`RoomLlmCallRecorder.kt` 的 `record(record)` 里 `LlmCallLogEntity(...)` 构造末尾加 `traceId = record.traceId,`。

- [ ] **Step 8: 处理 agent_stream 路径(若 chat 用到)**

搜索 `RemoteModelFactory.createBuilder` 的另一调用点 `AgentConfigurator.kt:198`(`"agent_stream"`)。读其上下文:若该路径也服务 chat 远程流式(非 ReAct),则同样传一个 holder 并在调用模型前设置 traceId。**若该路径非 chat 来源**(如纯设置/诊断),保持 holder=null(那些记录 traceId 为 null,详情页按无关联处理)。在提交信息里注明判断结果。

- [ ] **Step 9: 编译验证**

Run: `./gradlew :app:compileDebugKotlin :runtime-core:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 10: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/log/ \
        runtime-core/src/main/java/com/mamba/picme/agent/core/remote/config/RemoteModelFactory.kt \
        runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgent.kt \
        runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt \
        app/src/main/java/com/mamba/picme/data/local/llmlog/RoomLlmCallRecorder.kt
git commit -m "feat(llmlog): LLM 层 traceId 经 TraceIdHolder 贯穿 listener(ReAct 每轮设置)"
```

---

### Task 6: turn 记录合并排序纯函数(TDD)

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/debug/LlmCallLogViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/debug/TurnRecordMergerTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mamba/picme/features/debug/TurnRecordMergerTest.kt`:

```kotlin
package com.mamba.picme.features.debug

import com.mamba.picme.data.local.llmlog.JsRunLogEntity
import com.mamba.picme.data.local.llmlog.LlmCallLogEntity
import com.mamba.picme.data.local.llmlog.ToolCallLogEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnRecordMergerTest {

    private fun llm(t: Long) = LlmCallLogEntity(createdAt = t, source = "s", model = "m", success = true, latencyMs = 1, promptTokens = null, completionTokens = null, totalTokens = null, requestJson = "{}", responseJson = null, errorMessage = null, traceId = "T")
    private fun tool(t: Long) = ToolCallLogEntity(createdAt = t, capability = "c", commandType = "cmd", latencyMs = 1, success = true, errorCode = null, errorMessage = null, traceId = "T")
    private fun js(t: Long) = JsRunLogEntity(createdAt = t, source = "chat", kind = "eval", script = null, scriptLength = 0, success = true, errorCode = null, errorMessage = null, resultPreview = null, latencyMs = 1, traceId = "T")

    @Test
    fun `merges three layers sorted by createdAt ascending`() {
        val merged = mergeTurnRecords(
            llm = listOf(llm(300), llm(100)),
            tool = listOf(tool(200)),
            js = listOf(js(400))
        )
        assertEquals(4, merged.size)
        assertEquals(100L, merged[0].createdAt)
        assertEquals(400L, merged.last().createdAt)
    }

    @Test
    fun `counts per kind correct`() {
        val merged = mergeTurnRecords(
            llm = listOf(llm(100), llm(200)),
            tool = listOf(tool(150), tool(250), tool(350)),
            js = listOf(js(300))
        )
        val counts = countByKind(merged)
        assertEquals(2, counts[TurnKind.LLM])
        assertEquals(3, counts[TurnKind.TOOL])
        assertEquals(1, counts[TurnKind.JS])
    }

    @Test
    fun `empty inputs yield empty`() {
        assertTrue(mergeTurnRecords(emptyList(), emptyList(), emptyList()).isEmpty())
    }
}
```

(`assertTrue` import 自 `org.junit.Assert`。)

- [ ] **Step 2: 运行测试,确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.debug.TurnRecordMergerTest"`
Expected: FAIL —— `mergeTurnRecords` / `TurnKind` / `countByKind` 未定义。

- [ ] **Step 3: 实现**

在 `LlmCallLogViewModel.kt`(或新建 `TurnRecord.kt` 同包)加:

```kotlin
enum class TurnKind { LLM, TOOL, JS }

sealed class TurnRecordItem {
    abstract val createdAt: Long
    data class Llm(val entity: LlmCallLogEntity) : TurnRecordItem() {
        override val createdAt: Long get() = entity.createdAt
    }
    data class Tool(val entity: ToolCallLogEntity) : TurnRecordItem() {
        override val createdAt: Long get() = entity.createdAt
    }
    data class Js(val entity: JsRunLogEntity) : TurnRecordItem() {
        override val createdAt: Long get() = entity.createdAt
    }
}

fun mergeTurnRecords(
    llm: List<LlmCallLogEntity>,
    tool: List<ToolCallLogEntity>,
    js: List<JsRunLogEntity>
): List<TurnRecordItem> = (
    llm.map { TurnRecordItem.Llm(it) } +
        tool.map { TurnRecordItem.Tool(it) } +
        js.map { TurnRecordItem.Js(it) }
).sortedBy { it.createdAt }

fun countByKind(items: List<TurnRecordItem>): Map<TurnKind, Int> = buildMap {
    put(TurnKind.LLM, items.count { it is TurnRecordItem.Llm })
    put(TurnKind.TOOL, items.count { it is TurnRecordItem.Tool })
    put(TurnKind.JS, items.count { it is TurnRecordItem.Js })
}
```

- [ ] **Step 4: 运行测试,确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.debug.TurnRecordMergerTest"`
Expected: PASS(3/3)。

- [ ] **Step 5: ViewModel.loadTurn**

`LlmCallLogViewModel` 加(用已注入的三 DAO,读其字段名确认):

```kotlin
    suspend fun loadTurn(traceId: String): List<TurnRecordItem> = mergeTurnRecords(
        llm = llmCallLogDao.getByTraceId(traceId),
        tool = toolCallLogDao.getByTraceId(traceId),
        js = jsRunLogDao.getByTraceId(traceId)
    )
```

> 读 `LlmCallLogViewModel` 确认三 DAO 字段名(`llmCallLogDao`/`toolCallLogDao`/`jsRunLogDao` 或别名),按实际改。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/LlmCallLogViewModel.kt \
        app/src/test/java/com/mamba/picme/features/debug/TurnRecordMergerTest.kt
git commit -m "feat(llmlog): turn 记录合并排序 + loadTurn + 单测"
```

---

### Task 7: 详情页横滑 pager + 指示器 + Tool 详情

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/debug/LlmCallLogScreen.kt`
- Modify: `app/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml` + `values-zh-rTW/strings.xml`

- [ ] **Step 1: 新增字符串(三语言)**

`values/strings.xml` 加:

```xml
    <string name="llm_log_turn_indicator">LLM %1$d · TOOL %2$d · JS %3$d</string>
    <string name="llm_log_no_trace">No association</string>
```

`values-zh-rCN/strings.xml`:

```xml
    <string name="llm_log_turn_indicator">LLM %1$d · 工具 %2$d · JS %3$d</string>
    <string name="llm_log_no_trace">无关联</string>
```

`values-zh-rTW/strings.xml`:

```xml
    <string name="llm_log_turn_indicator">LLM %1$d · 工具 %2$d · JS %3$d</string>
    <string name="llm_log_no_trace">無關聯</string>
```

- [ ] **Step 2: ToolCallLogDetail 组件(缺口补全)**

`LlmCallLogScreen.kt` 加(纯指标卡片,不含业务内容;复用 `LlmCallLogDetail` 的排版风格):

```kotlin
@Composable
private fun ToolCallLogDetail(item: ToolCallLogEntity, modifier: Modifier) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DetailRow("时间", timeFormat.format(Date(item.createdAt)))
        DetailRow("Capability", item.capability)
        DetailRow("Command", item.commandType)
        DetailRow("耗时", "${item.latencyMs} ms")
        DetailRow("结果", if (item.success) "成功" else "失败")
        if (!item.success) {
            item.errorCode?.let { DetailRow("错误码", it.toString()) }
            item.errorMessage?.let { DetailRow("错误信息", it) }
        }
        item.traceId?.let { DetailRow("Trace", it) }
    }
}
```

> 若 `DetailRow(label, value)` 帮助组件不存在,参考 `LlmCallLogDetail`(:430)里现成的 label/value 行写法,或就地用 `Text` 两行实现。

- [ ] **Step 3: Tool row 接 onClick(进 turn pager)**

`LlmCallLogScreen.kt` 列表分支 `LogTab.TOOL -> items(toolItems, ...) { row -> ToolCallLogRow(row = row) }` 改为可点:`ToolCallLogRow(row = row) { selected = it }`(给 `ToolCallLogRow` 加 `onClick` 参数,签名对齐 `LlmCallLogRow`/`JsRunLogRow`)。同时把 `selected` 的类型从 `LlmCallLogEntity?` 扩展为可持 tool —— 见 Step 4 用统一 `anchor`。

- [ ] **Step 4: LlmTurnDetailPager**

详情分支(`:124` `when { selectedItem != null -> ... }`)替换为:

```kotlin
    val anchor = selectedItem
    when {
        anchor != null -> LlmTurnDetailPager(
            anchor = anchor,
            vm = vm,
            onBack = { selected = null },
            modifier = Modifier.fillMaxSize().padding(padding)
        )
        selectedJsItem != null -> JsRunLogDetail(item = selectedJsItem, modifier = Modifier.fillMaxSize().padding(padding))
        else -> { /* 原 Tab 列表 */ }
    }
```

> 上面的 `selected` 需能持 LLM 或 Tool:把 `var selected` 改为持 `LlmCallLogEntity?` 的同时,Tool 行点击也设一个 `var selectedTool: ToolCallLogEntity?`,`anchor` 取二者之一。或更简单:定义 `var detailAnchor: TurnRecordItem? = null`,所有 row 点击 `detailAnchor = TurnRecordItem.Llm(row)` / `.Tool(row)`,JS 仍走 `selectedJs`。**推荐后者** —— 让 pager 入口统一为 `TurnRecordItem`。

新增 pager 组件:

```kotlin
@Composable
private fun LlmTurnDetailPager(
    anchor: TurnRecordItem,
    vm: LlmCallLogViewModel,
    onBack: () -> Unit,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TurnRecordItem>?>(null) }
    val traceId = when (anchor) {
        is TurnRecordItem.Llm -> anchor.entity.traceId
        is TurnRecordItem.Tool -> anchor.entity.traceId
        is TurnRecordItem.Js -> anchor.entity.traceId
    }

    LaunchedEffect(traceId) {
        items = if (traceId != null) {
            runCatching { vm.loadTurn(traceId) }.getOrDefault(emptyList())
        } else {
            listOf(anchor)   // 无关联:只显示自己
        }
    }

    val list = items
    if (list == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val initialPage = list.indexOfFirst {
        (it is TurnRecordItem.Llm && anchor is TurnRecordItem.Llm && it.entity.id == anchor.entity.id) ||
            (it is TurnRecordItem.Tool && anchor is TurnRecordItem.Tool && it.entity.id == anchor.entity.id) ||
            (it is TurnRecordItem.Js && anchor is TurnRecordItem.Js && it.entity.id == anchor.entity.id)
    }.let { if (it >= 0) it else 0 }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { list.size })
    val counts = remember(list) { countByKind(list) }

    Column(modifier.fillMaxSize()) {
        // 指示器
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            if (traceId == null) {
                Text(stringResource(R.string.llm_log_no_trace))
            } else {
                Text(
                    stringResource(
                        R.string.llm_log_turn_indicator,
                        counts[TurnKind.LLM] ?: 0, counts[TurnKind.TOOL] ?: 0, counts[TurnKind.JS] ?: 0
                    )
                )
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val m = Modifier.fillMaxSize()
            when (val item = list[page]) {
                is TurnRecordItem.Llm -> LlmCallLogDetail(item.entity, m)
                is TurnRecordItem.Tool -> ToolCallLogDetail(item.entity, m)
                is TurnRecordItem.Js -> JsRunLogDetail(item.entity, m)
            }
        }
    }
}
```

> import:`HorizontalPager`/`rememberPagerState`/`CircularProgressIndicator`/`rememberCoroutineScope`(按需)。`LlmCallLogDetail`(:430)与 `JsRunLogDetail`(:255)已存在,复用。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。修复 import/未解析符号(`DetailRow` 等)。

- [ ] **Step 6: 手动验证**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/polang-debug.apk
```
设置→Debug→LLM 调用日志:在 chat 里发一条会触发 tool(如「搜一下猫的照片」)的消息 → 回到日志页 → 点开该轮的任一记录 → 验证:
- 顶部指示器 `LLM · TOOL · JS` 计数;
- 横滑可遍历同轮三层记录;
- traceId 为 null 的老记录显示「无关联」且只有一页;
- Tool 记录现在可点进详情(之前不可点)。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/LlmCallLogScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(llmlog): 详情页横滑 turn pager + 层级指示器 + Tool 详情组件"
```

---

## Self-Review(写完核对)

- **Spec 覆盖**:① traceId 三表(Task 1)② 生成+AgentContext(Task 2)③ tool 透传(Task 3)④ JS 透传(Task 4)⑤ LLM 透传 holder(Task 5)⑥ 详情横滑 pager + 指示器(Task 7)⑦ Tool 详情缺口(Task 7)⑧ 合并排序(Task 6)—— 全覆盖。
- **占位符**:agent_stream 路径(Task 5 Step 8)留为「读后判定」,非 TBD 而是明确分支决策;`DetailRow` 给了就地实现退路。
- **类型一致**:`TurnRecordItem`/`TurnKind`/`mergeTurnRecords`/`countByKind` 在 Task 6-7 名称一致;`CommandExecutionRecorder.record(...,traceId)`、`LlmCallRecord.traceId`、`JsRunEvent.traceId`、`executeTask(traceId)`、`processChatReAct(traceId)` 跨 Task 一致。
- **执行顺序**:Task 1(表)→ 2(context 生成)→ 3/4/5(三层透传,可并行但建议 5 最后)→ 6(合并)→ 7(UI)。每个 Task 后均可编译通过。
