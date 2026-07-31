# Claude Tunnel Phase 3（app 接入）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans（inline）。`- [ ]` 跟踪。

**Goal:** app 端接入 claude-tunnel：chat 输入栏「AI 工程师」toggle → 消息走 `ClaudeChatClient`（POST `/v1/claude-chat` + AppToken + SSE 流式）→ 消费 §6 事件 → agent 气泡（文本 + 步骤列表）渲染 → 多轮 + 交付 + 图片禁用。

**Architecture:** `ClaudeChatClient`（新，镜像 `DiagClient` 风格 + OkHttp SSE 流式）→ §6 事件解析为 `ClaudeEvent` → 映射到现有 `AgentMessage`（`assistant_text`→`AgentText`，`tool_use`/`tool_result`→`CommandExecution`，`file_change`→`CommandExecution` detail）→ `ChatViewModel` 加 claude-chat 模式（toggle 路由 + session_id + 事件流）→ `ChatScreen` 加 toggle + agent 气泡渲染（复用 `AgentMessage` 渲染组件）。

**Tech:** Android Compose + OkHttp SSE + ViewModel + 复用 `AgentMessage`/`DiagClient` 模式 + i18n 三语。

**关联：** spec §6/§7.4/§11；Phase 1（隧道+网关）/Phase 2（server 反代）已验收，后端 `/v1/claude-chat` 可用。

**探索结论（2026-08-01）：**
- `features/common/chat/AgentMessage.kt`（sealed）：`UserText`/`AgentText`/`CommandExecution`（status PENDING/RUNNING/SUCCESS/FAILED + commandName/detail/index/total）/`PlanPreview`/...。为本地 AgentOrchestrator 设计，但 `CommandExecution` 可复用渲染 claude-tunnel 的 tool 步骤。
- `AgentChatComponents`/`AiChatScreen`：本地 agent（语音命令）UI，**不直接复用**，但参考其 `AgentMessage` 渲染。
- `DiagClient`（112 行）：OkHttp + `X-App-Token` + org.json + `withContext(IO)` —— `ClaudeChatClient` 镜像它 + 加 SSE 流式。
- 接入 `ChatScreen`/`ChatViewModel`（现有远程 chat，`features/chat/`）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| Create: `app/.../data/remote/picme/ClaudeChatClient.kt` | POST `/v1/claude-chat`（AppToken + body）+ OkHttp SSE 流式读 + 解析 §6 事件为 `ClaudeEvent` 回调 |
| Create: `app/.../data/remote/picme/ClaudeEvent.kt` | §6 事件 sealed class（Session/AssistantText/ToolUse/ToolResult/FileChange/Error/Done） |
| Create: `app/src/test/.../ClaudeSseParserTest.kt` | SSE 文本 → `ClaudeEvent` 解析单测（纯逻辑） |
| Modify: `app/.../features/chat/ChatViewModel.kt` | claude-chat 模式（toggle 路由 + session_id 持有 + 事件流 → AgentMessage） |
| Modify: `app/.../features/chat/ChatScreen.kt` | 「AI 工程师」toggle + agent 气泡渲染（复用 `AgentMessage` 组件）+ 图片禁用 |
| Create: `app/.../features/chat/ClaudeAgentRenderer.kt` | `ClaudeEvent` → `AgentMessage` 映射（tool_use→CommandExecution RUNNING，tool_result→SUCCESS/FAILED） |
| Modify: `values*/strings.xml` | 三语：toggle / 步骤标签 / 交付 / 离线提示 |

---

## Task 1: ClaudeChatClient + SSE 解析（TDD，纯逻辑）

**Files:**
- Create: `app/.../data/remote/picme/ClaudeEvent.kt`
- Create: `app/.../data/remote/picme/ClaudeChatClient.kt`
- Test: `app/src/test/.../ClaudeSseParserTest.kt`

- [ ] **Step 1: 写 `ClaudeEvent.kt`**

```kotlin
package com.mamba.picme.data.remote.picme

import org.json.JSONObject

/** spec §6 事件（claude-tunnel 网关 → server 透传 → app 消费）。 */
sealed class ClaudeEvent {
    data class Session(val sid: String) : ClaudeEvent()
    data class AssistantText(val delta: String) : ClaudeEvent()
    data class ToolUse(val tool: String, val input: JSONObject) : ClaudeEvent()
    data class ToolResult(val ok: Boolean, val summary: String) : ClaudeEvent()
    data class FileChange(val path: String, val action: String) : ClaudeEvent()
    data class Error(val message: String) : ClaudeEvent()
    data object Done : ClaudeEvent()
}
```

- [ ] **Step 2: 写失败测试（SSE 解析纯函数）**

`app/src/test/java/com/mamba/picme/data/remote/picme/ClaudeSseParserTest.kt`:
```kotlin
package com.mamba.picme.data.remote.picme

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSseParserTest {
    @Test
    fun `parses session + assistant_text + done`() {
        val sse = "event: session\ndata: {\"sid\":\"s1\"}\n\n" +
            "event: assistant_text\ndata: {\"delta\":\"hi\"}\n\n" +
            "event: done\ndata: {}\n\n"
        val ev = ClaudeSseParser.parse(sse)
        assertEquals(3, ev.size)
        assertEquals("s1", (ev[0] as ClaudeEvent.Session).sid)
        assertEquals("hi", (ev[1] as ClaudeEvent.AssistantText).delta)
        assertTrue(ev[2] is ClaudeEvent.Done)
    }

    @Test
    fun `parses tool_use and tool_result and file_change`() {
        val sse = "event: tool_use\ndata: {\"tool\":\"Bash\",\"input\":{\"command\":\"ls\"}}\n\n" +
            "event: file_change\ndata: {\"path\":\"a.kt\",\"action\":\"modified\"}\n\n" +
            "event: tool_result\ndata: {\"ok\":true,\"summary\":\"ok\"}\n\n"
        val ev = ClaudeSseParser.parse(sse)
        assertEquals("Bash", (ev[0] as ClaudeEvent.ToolUse).tool)
        assertEquals("a.kt", (ev[1] as ClaudeEvent.FileChange).path)
        assertTrue((ev[2] as ClaudeEvent.ToolResult).ok)
    }

    @Test
    fun `parses error event`() {
        val sse = "event: error\ndata: {\"message\":\"boom\"}\n\n"
        val ev = ClaudeSseParser.parse(sse)
        assertEquals("boom", (ev[0] as ClaudeEvent.Error).message)
    }

    @Test
    fun `ignores malformed lines`() {
        val sse = "garbage\n\nevent: done\ndata: {}\n\n"
        val ev = ClaudeSseParser.parse(sse)
        assertEquals(1, ev.size)
        assertTrue(ev[0] is ClaudeEvent.Done)
    }
}
```

- [ ] **Step 3: 跑确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*ClaudeSseParserTest" 2>&1 | tail -15`
Expected: FAIL（`ClaudeSseParser` 不存在）

- [ ] **Step 4: 实现 `ClaudeChatClient.kt`（含 `ClaudeSseParser`）**

```kotlin
package com.mamba.picme.data.remote.picme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** SSE 文本 → ClaudeEvent 解析（spec §6）。纯逻辑，单测覆盖。 */
object ClaudeSseParser {
    fun parse(sse: String): List<ClaudeEvent> {
        val events = mutableListOf<ClaudeEvent>()
        val blocks = sse.split("\n\n")
        for (block in blocks) {
            var type: String? = null
            var data: String? = null
            for (line in block.split("\n")) {
                when {
                    line.startsWith("event:") -> type = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> data = line.removePrefix("data:").trim()
                }
            }
            val t = type ?: continue
            val json = try { JSONObject(data ?: "{}") } catch (_: Throwable) { continue }
            val ev = when (t) {
                "session" -> json.optString("sid").takeIf { it.isNotBlank() }?.let { ClaudeEvent.Session(it) }
                "assistant_text" -> ClaudeEvent.AssistantText(json.optString("delta"))
                "tool_use" -> ClaudeEvent.ToolUse(json.optString("tool"), json.optJSONObject("input") ?: JSONObject())
                "tool_result" -> ClaudeEvent.ToolResult(json.optBoolean("ok"), json.optString("summary"))
                "file_change" -> ClaudeEvent.FileChange(json.optString("path"), json.optString("action"))
                "error" -> ClaudeEvent.Error(json.optString("message"))
                "done" -> ClaudeEvent.Done
                else -> null
            }
            ev?.let { events.add(it) }
        }
        return events
    }
}

/**
 * claude-tunnel chat 客户端。镜像 [DiagClient] 风格（OkHttp + X-App-Token + org.json），
 * 加 SSE 流式读：POST /v1/claude-chat，逐 chunk 累积，按双换行切事件，回调 onEvent。
 */
class ClaudeChatClient(private val baseUrl: String = DEFAULT_BASE_URL) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接，不超时
        .build()
    private val jsonMedia = "application/json".toMediaType()

    /** 流式 chat：onEvent 在 IO 线程回调每个 §6 事件；返回 session 事件给的 sid（多轮用）。 */
    suspend fun chat(
        token: String,
        message: String,
        sid: String? = null,
        onEvent: (ClaudeEvent) -> Unit,
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("message", message).also {
                sid?.takeIf { s -> s.isNotBlank() }?.let { s -> it.put("sid", s) }
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/v1/claude-chat")
                .header("X-App-Token", token)
                .post(body.toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
            val source = resp.body?.byteStream() ?: throw RuntimeException("empty body")
            val sb = StringBuilder()
            var sessionSid: String? = null
            val buf = ByteArray(4 * 1024)
            while (true) {
                val n = source.read(buf)
                if (n == -1) break
                sb.append(String(buf, 0, n))
                while (true) {
                    val idx = sb.indexOf("\n\n")
                    if (idx == -1) break
                    val block = sb.substring(0, idx)
                    sb.delete(0, idx + 2)
                    for (ev in ClaudeSseParser.parse(block + "\n\n")) {
                        if (ev is ClaudeEvent.Session) sessionSid = ev.sid
                        onEvent(ev)
                    }
                }
            }
            sessionSid
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
```

- [ ] **Step 5: 跑测试通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*ClaudeSseParserTest" 2>&1 | tail -15`
Expected: PASS（4 tests）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/remote/picme/ClaudeEvent.kt \
        app/src/main/java/com/mamba/picme/data/remote/picme/ClaudeChatClient.kt \
        app/src/test/java/com/mamba/picme/data/remote/picme/ClaudeSseParserTest.kt
git commit -m "feat(app): ClaudeChatClient + SSE 解析（§6 事件）+ 单测"
```

---

## Task 2: ClaudeEvent → AgentMessage 映射

**Files:**
- Create: `app/.../features/chat/ClaudeAgentRenderer.kt`
- Test: `app/src/test/.../ClaudeAgentRendererTest.kt`

- [ ] **Step 1-5（TDD）**：`ClaudeAgentRenderer.toAgentMessages(event)`：
  - `Session` → （不显示，ViewModel 存 sid）
  - `AssistantText(delta)` → 追加到当前 `AgentText`（流式累积）
  - `ToolUse(tool, input)` → `CommandExecution(commandName=tool, status=RUNNING, detail=input 简述)`
  - `ToolResult(ok, summary)` → 把上一个 RUNNING 的 `CommandExecution` 改为 SUCCESS/FAILED + detail=summary
  - `FileChange(path, action)` → `CommandExecution(commandName="改文件", detail="$action: $path", status=SUCCESS)`
  - `Error(msg)` → `AgentText("⚠️ $msg")`
  - `Done` → 标记结束
  
  单测：喂事件序列，断言产出的 `AgentMessage` 列表（参考 `AgentChatComponents.agentActionToExecutionMessages` 模式）。

- [ ] **Step 6: Commit**

---

## Task 3: ChatViewModel claude-chat 模式

**Files:** Modify `app/.../features/chat/ChatViewModel.kt`

- [ ] claude 模式 toggle（`isClaudeMode`）+ session_id 持有 + `ClaudeChatClient` 注入
- [ ] toggle 开 → 发送键路由到 `claudeChatClient.chat(...)`，onEvent → `ClaudeAgentRenderer` → 追加 `AgentMessage` 到会话
- [ ] 多轮：session_id 后续消息带上
- [ ] 复用现有 chat 状态/会话管理（参考 diag 在 ChatViewModel 的集成方式，`DiagChatSession`）

> 执行前先 Read `ChatViewModel.kt` 的 diag 集成段（`submitDiagnosis`/diag session 模式），对齐 claude 模式怎么挂进现有 send/sendMessage 流程。

---

## Task 4: ChatScreen toggle + agent 气泡渲染

**Files:** Modify `app/.../features/chat/ChatScreen.kt`；可能 Create `app/.../features/chat/components/ClaudeAgentBubble.kt`

- [ ] 输入栏「AI 工程师」toggle（图标 + 二态，参考现有 diag toggle/按钮）
- [ ] claude 模式下 `AgentMessage`（AgentText + CommandExecution）渲染为 agent 气泡：文本流式 + 步骤列表（CommandExecution status icon + commandName + detail）+ 文件改动徽标
- [ ] 图片禁用：claude 模式下隐藏图片输入（spec §11 红线）

> 执行前 Read `ChatScreen.kt` 输入栏 + 现有消息气泡渲染，对齐 Compose 结构。

---

## Task 5: 交付 + i18n + 收尾

**Files:** Modify `ChatScreen.kt`（交付按钮）；`values*/strings.xml`

- [ ] claude 模式 `file_change` 后气泡出现「交付」按钮 → 选 push/pr/auto（复用 diag 三模式 UI）→ POST `/deliver`（网关，经 `/v1/claude-chat` 同隧道；或 server 加 `/v1/claude-deliver`——决定）→ 结果回气泡
- [ ] 三语文案：toggle / 步骤标签 / 交付 / 离线提示（`values/`、`values-zh-rCN/`、`values-zh-rTW/`）
- [ ] 编译：`./gradlew :app:assembleDebug`

---

## Phase 3 完成标准

- [ ] `ClaudeSseParserTest` + `ClaudeAgentRendererTest` 全绿。
- [ ] chat「AI 工程师」toggle：激活后消息走 claude-chat，agent 气泡渲染文本 + 步骤 + 文件改动；多轮 session；图片禁用。
- [ ] 交付：push/pr/auto 至少 push 可用（推 claude-chat/<sid> 分支）。
- [ ] 三语同步；`./gradlew :app:assembleDebug` 无新增错误。
- [ ] E2E：真机 chat 描述一个简单改动 → 看流式 + 步骤 → 交付分支。
