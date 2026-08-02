# AI 工程师气泡可读性治理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 claude-tunnel「AI 工程师」气泡在手机上可读——代码块默认折叠可展开/复制、回答被网关截断时有标识 + 「继续」按钮、源头 prompt 约束 Claude 简洁输出。

**Architecture:** 三层改动。① 显示层（app）：抽出可测的 `ClaudeMarkdownSegmenter`（新增 CODE 段）+ 自定义 `CodeBlock` 组件（折叠/横滚/复制），流式与最终渲染统一走分段器。② 状态层（app）：`ClaudeEvent.Done/Error` 携带 `truncated`+`reason`，`ClaudeAgentState` 加粘滞的 `truncatedReason`，气泡显标识 + 继续按钮（同 sid 走 `--resume`）。③ 源头层（网关）：`APP_TOOL_SYSTEM_PROMPT` 加简洁约束 + pump 标注截断。

**Tech Stack:** Kotlin/Compose（app，`compose-markdown` 0.5.4）、Python/aiohttp（网关）、pytest（网关）、JUnit（app JVM 单测，本仓真门槛）。

**Spec:** `docs/superpowers/specs/2026-08-02-claude-agent-bubble-readability-design.md`

**依赖顺序:** Task 1 → Task 2 → Task 3 → **Task 7（文案）→ Task 4 → Task 5**（Task 4/5 的 Compose 引用 Task 7 的 string，须先有文案否则 `assembleDebug` 报缺资源）；Task 6（网关，Python）可任意时刻并行；Task 8 收尾。

**本仓红线（实施时遵守）:** 禁 `com.mamba.picme.*` 全限定名、禁 wildcard import、lambda 参数显式命名、日志 tag `PoLang:*`、Kotlin 4 空格缩进；新文案三语同步。

---

### Task 1: ClaudeEvent 扩展 Done/Error 截断字段 + SSE 解析

把 `Done` 从 `data object` 改为 `data class`（带 `turns`/`truncated`/`reason`），`Error` 加 `truncated`/`reason`；SSE 解析消费新字段；修 collateral 编译点。纯逻辑，JVM 单测。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/remote/picme/ClaudeEvent.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/remote/picme/ClaudeChatClient.kt:42,51`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ClaudeAgentRenderer.kt:106`
- Modify: `app/src/test/java/com/mamba/picme/features/chat/ClaudeAgentRendererTest.kt:81`
- Test: `app/src/test/java/com/mamba/picme/data/remote/picme/ClaudeSseParserTest.kt`

- [ ] **Step 1: 写失败测试（SSE 解析新字段）**

在 `ClaudeSseParserTest.kt` 末尾（`}` 之前）追加：

```kotlin
    @Test
    fun `parses done with truncation fields`() {
        val sse = "event: done\ndata: {\"turns\":20,\"truncated\":true,\"reason\":\"max_turns\"}\n\n"
        val ev = ClaudeSseParser.parse(sse).single() as ClaudeEvent.Done
        assertEquals(20, ev.turns)
        assertTrue(ev.truncated)
        assertEquals("max_turns", ev.reason)
    }

    @Test
    fun `parses done without truncation defaults`() {
        val sse = "event: done\ndata: {\"turns\":3}\n\n"
        val ev = ClaudeSseParser.parse(sse).single() as ClaudeEvent.Done
        assertEquals(3, ev.turns)
        assertFalse(ev.truncated)
        assertNull(ev.reason)
    }

    @Test
    fun `parses error with truncation fields`() {
        val sse = "event: error\ndata: {\"message\":\"phase timeout 300s\",\"truncated\":true,\"reason\":\"phase_timeout\"}\n\n"
        val ev = ClaudeSseParser.parse(sse).single() as ClaudeEvent.Error
        assertEquals("phase timeout 300s", ev.message)
        assertTrue(ev.truncated)
        assertEquals("phase_timeout", ev.reason)
    }
```

并在该测试文件 import 区加 `import org.junit.Assert.assertFalse` 和 `import org.junit.Assert.assertNull`（若未有）。

- [ ] **Step 2: 运行测试确认失败（编译错误：Done 无参数/字段）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.ClaudeSseParserTest"`
Expected: 编译失败——`ClaudeEvent.Done` 无 `turns/truncated/reason`，`ClaudeEvent.Error` 无 `truncated/reason`。

- [ ] **Step 3: 改 ClaudeEvent 类型**

`ClaudeEvent.kt` 全文替换为：

```kotlin
package com.mamba.picme.data.remote.picme

import org.json.JSONObject

/** spec §6 事件（claude-tunnel 网关 → server SSE 透传 → app 消费）。 */
sealed class ClaudeEvent {
    data class Session(val sid: String) : ClaudeEvent()
    data class AssistantText(val delta: String) : ClaudeEvent()
    data class ToolUse(val tool: String, val input: JSONObject) : ClaudeEvent()
    data class ToolResult(val ok: Boolean, val summary: String) : ClaudeEvent()
    data class FileChange(val path: String, val action: String) : ClaudeEvent()
    /** spec §6 cost：本轮 turns 与费用（分）。可选事件，app 仅用于额度提示。 */
    data class Cost(val turns: Int, val cents: Int) : ClaudeEvent()
    /** 网关截断时 truncated=true 且 reason 非空（"max_turns"|"phase_timeout"）；否则为普通收尾。 */
    data class Error(
        val message: String,
        val truncated: Boolean = false,
        val reason: String? = null,
    ) : ClaudeEvent()
    /** spec §4.4：网关下行的 App 数据请求（MCP tool call → App 采集回传）。 */
    data class AppToolRequest(val requestId: String, val tool: String, val args: JSONObject) : ClaudeEvent()
    /** 本轮结束；truncated=true 时 app 显截断标识 + 继续按钮。turns=claude result num_turns。 */
    data class Done(
        val turns: Int = 0,
        val truncated: Boolean = false,
        val reason: String? = null,
    ) : ClaudeEvent()
}
```

- [ ] **Step 4: 改 SSE 解析消费新字段**

`ClaudeChatClient.kt:42` 替换：

```kotlin
                "error" -> ClaudeEvent.Error(
                    message = json.optString("message"),
                    truncated = json.optBoolean("truncated", false),
                    reason = json.optString("reason").takeIf { it.isNotBlank() },
                )
```

`ClaudeChatClient.kt:51`（`"done" ->` 那行）替换：

```kotlin
                "done" -> ClaudeEvent.Done(
                    turns = json.optInt("turns", 0),
                    truncated = json.optBoolean("truncated", false),
                    reason = json.optString("reason").takeIf { it.isNotBlank() },
                )
```

- [ ] **Step 5: 修 collateral 编译点（Done 不再是单例）**

`ClaudeAgentRenderer.kt:106` 把 `ClaudeEvent.Done`（无 `is`）改为 `is ClaudeEvent.Done`：

```kotlin
        is ClaudeEvent.Session, is ClaudeEvent.Done, is ClaudeEvent.Cost, is ClaudeEvent.AppToolRequest -> cur
```

`ClaudeAgentRendererTest.kt:81` 把 `r.apply(ClaudeEvent.Done)` 改为：

```kotlin
        r.apply(ClaudeEvent.Done())
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.ClaudeSseParserTest" --tests "com.mamba.picme.features.chat.ClaudeAgentRendererTest"`
Expected: PASS（全绿，含新 3 例 + 既有回归）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/mamba/picme/data/remote/picme/ClaudeEvent.kt \
  app/src/main/java/com/mamba/picme/data/remote/picme/ClaudeChatClient.kt \
  app/src/main/java/com/mamba/picme/features/chat/ClaudeAgentRenderer.kt \
  app/src/test/java/com/mamba/picme/features/chat/ClaudeAgentRendererTest.kt \
  app/src/test/java/com/mamba/picme/data/remote/picme/ClaudeSseParserTest.kt
git commit -m "feat(claude-tunnel): Done/Error 事件携带截断字段 + SSE 解析"
```

---

### Task 2: ClaudeAgentState 加粘滞 truncatedReason + Renderer 折叠 + 持久化

新增 `truncatedReason: String?`（粘滞——置位后只设不清，防 phase-timeout 后兜底 `done:{}` 擦除）；Renderer 的 Done/Error 截断分支置位；toJson/fromJson 持久化。纯逻辑，JVM 单测。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ClaudeAgentRenderer.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ClaudeAgentRendererTest.kt`

- [ ] **Step 1: 写失败测试**

在 `ClaudeAgentRendererTest.kt` 的 `reset clears state` 测试之后追加：

```kotlin
    @Test
    fun `done with truncation sets sticky reason`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("partial…"))
        r.apply(ClaudeEvent.Done(turns = 20, truncated = true, reason = "max_turns"))
        assertEquals("max_turns", r.state.truncatedReason)
        // 粘滞：后续无截断的 done 不清除
        r.apply(ClaudeEvent.Done(turns = 20, truncated = false, reason = null))
        assertEquals("max_turns", r.state.truncatedReason)
    }

    @Test
    fun `truncated error sets reason without warning text`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("partial…"))
        r.apply(ClaudeEvent.Error(message = "phase timeout 300s", truncated = true, reason = "phase_timeout"))
        assertEquals("phase_timeout", r.state.truncatedReason)
        assertFalse(r.state.text.contains("phase timeout"))  // 不再当 ⚠️ 灌正文
    }

    @Test
    fun `non-truncated error still appends warning`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("working"))
        r.apply(ClaudeEvent.Error(message = "boom"))
        assertTrue(r.state.text.contains("boom"))
        assertNull(r.state.truncatedReason)
    }

    @Test
    fun `truncated reason survives json round-trip`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.Done(turns = 20, truncated = true, reason = "max_turns"))
        val restored = ClaudeAgentState.fromJson(r.state.toJson())
        assertEquals("max_turns", restored.truncatedReason)
    }
```

并加 `import org.junit.Assert.assertNull`（若未有）。

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ClaudeAgentRendererTest"`
Expected: 编译失败——`ClaudeAgentState` 无 `truncatedReason` 字段。

- [ ] **Step 3: 给 ClaudeAgentState 加字段 + 持久化**

`ClaudeAgentRenderer.kt` 的 `ClaudeAgentState` data class（约 17-21 行）替换为：

```kotlin
data class ClaudeAgentState(
    val text: String = "",
    val steps: List<ClaudeStepUi> = emptyList(),
    val hasFileChange: Boolean = false,
    val truncatedReason: String? = null,
) {
```

其 `toJson()`（约 23-37 行）在 `.put("hasFileChange", hasFileChange)` 之后、`return` 之前加一行：

```kotlin
            .put("truncatedReason", truncatedReason ?: JSONObject.NULL)
```

`fromJson`（约 40-57 行）的 `return ClaudeAgentState(...)` 加 `truncatedReason`：

```kotlin
            return ClaudeAgentState(
                text = obj.optString("text"),
                steps = steps,
                hasFileChange = obj.optBoolean("hasFileChange", false),
                truncatedReason = obj.isNull("truncatedReason").let { if (it) null else obj.optString("truncatedReason") },
            )
```

- [ ] **Step 4: 改 Renderer fold（Done/Error 截断分支）**

`ClaudeAgentRenderer.kt` fold 的两个分支替换。先移除 Done 出无操作分支（Task 1 已加 `is`，现拆出独立分支）：

```kotlin
        is ClaudeEvent.Session, is ClaudeEvent.Cost, is ClaudeEvent.AppToolRequest -> cur
        is ClaudeEvent.Done -> if (event.truncated && event.reason != null) {
            cur.copy(truncatedReason = event.reason)
        } else {
            cur
        }
```

再把 `is ClaudeEvent.Error -> { ... }`（约 138-142 行）整段替换为：

```kotlin
        is ClaudeEvent.Error -> if (event.truncated && event.reason != null) {
            cur.copy(truncatedReason = event.reason)
        } else {
            val prefix = if (cur.text.isBlank()) "" else "\n"
            cur.copy(text = "${cur.text}${prefix}⚠️ ${event.message}")
        }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ClaudeAgentRendererTest"`
Expected: PASS（新 4 例 + 既有 11 例全绿）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ClaudeAgentRenderer.kt \
  app/src/test/java/com/mamba/picme/features/chat/ClaudeAgentRendererTest.kt
git commit -m "feat(claude-tunnel): ClaudeAgentState 粘滞截断原因 + Renderer 折叠截断事件"
```

---

### Task 3: 抽出 ClaudeMarkdownSegmenter（CODE 段 + 纯函数）

新建可测文件 `ClaudeMarkdownSegmenter.kt`，新增 `CODE` 段类型与围栏切分，提供 `extractCodeBody`/`codeLineCount`/`previewCode` 纯函数。纯逻辑，JVM 单测。

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/ClaudeMarkdownSegmenter.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ClaudeMarkdownSegmenterTest.kt`

- [ ] **Step 1: 写失败测试**

新建 `app/src/test/java/com/mamba/picme/features/chat/ClaudeMarkdownSegmenterTest.kt`：

```kotlin
package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeMarkdownSegmenterTest {

    @Test
    fun `splits prose and fenced code block`() {
        val md = "intro\n```kotlin\nval x = 1\nval y = 2\n```\noutro"
        val segs = segmentMarkdown(md)
        assertEquals(3, segs.size)
        assertEquals(AgentSegmentType.MARKDOWN, segs[0].type)
        assertEquals("intro", segs[0].text)
        assertEquals(AgentSegmentType.CODE, segs[1].type)
        assertTrue(segs[1].text.contains("```kotlin"))
        assertEquals(AgentSegmentType.MARKDOWN, segs[2].type)
        assertEquals("outro", segs[2].text)
    }

    @Test
    fun `code fence lines belong to code segment`() {
        val md = "```\nfoo\nbar\n```"
        val segs = segmentMarkdown(md)
        assertEquals(1, segs.size)
        assertEquals(AgentSegmentType.CODE, segs[0].type)
        assertEquals("foo\nbar", extractCodeBody(segs[0].text))
        assertEquals(2, codeLineCount(extractCodeBody(segs[0].text)))
    }

    @Test
    fun `unterminated fence streams remaining lines as code`() {
        val md = "text\n```python\nprint(1)"
        val segs = segmentMarkdown(md)
        assertEquals(2, segs.size)
        assertEquals(AgentSegmentType.CODE, segs[1].type)
        assertEquals("print(1)", extractCodeBody(segs[1].text))
    }

    @Test
    fun `pipe inside code fence is not treated as table`() {
        val md = "```\na|b\nc|d\n```"
        val segs = segmentMarkdown(md)
        assertEquals(1, segs.size)
        assertEquals(AgentSegmentType.CODE, segs[0].type)
    }

    @Test
    fun `table segment still recognized outside fence`() {
        val md = "| h1 | h2 |\n| --- | --- |\n| a | b |"
        val segs = segmentMarkdown(md)
        assertEquals(1, segs.size)
        assertEquals(AgentSegmentType.TABLE, segs[0].type)
    }

    @Test
    fun `preview code takes first n lines`() {
        val code = "1\n2\n3\n4\n5"
        assertEquals("1\n2\n3", previewCode(code, 3))
    }

    @Test
    fun `extract body with no fence returns as-is`() {
        assertEquals("plain", extractCodeBody("plain"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败（类不存在）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ClaudeMarkdownSegmenterTest"`
Expected: 编译失败——`segmentMarkdown`/`AgentSegmentType` 等未定义。

- [ ] **Step 3: 实现 ClaudeMarkdownSegmenter**

新建 `app/src/main/java/com/mamba/picme/features/chat/ClaudeMarkdownSegmenter.kt`：

```kotlin
package com.mamba.picme.features.chat

/** agent 气泡 markdown 分段类型。CODE 段交由 CodeBlock 折叠渲染。 */
enum class AgentSegmentType { MARKDOWN, TABLE, CODE }

data class AgentSegment(val type: AgentSegmentType, val text: String)

/** GFM 表格分隔行，如 `|---|---|`、`| --- | ---: |`、`---|---`（可无前后导 `|`）。 */
private val TABLE_DELIMITER = Regex("""^\s*\|?(\s*:?-{2,}:?\s*\|)+(\s*:?-{2,}:?\s*)\|?\s*$""")

private val CODE_FENCE = Regex("""^\s*```""")

/**
 * 把 agent 回复切成 MARKDOWN / TABLE / CODE 段。表格段纯文本直出（防 Markwon 位图抖动）；
 * 代码段（围栏内，含围栏行）交给 CodeBlock 折叠。一条回复可含多个表格/代码块。
 * 流式期间末围栏可能缺失：未闭合的 ``` 之后所有行均归 CODE。
 */
fun segmentMarkdown(content: String): List<AgentSegment> {
    val lines = content.split("\n")
    val isTableLine = BooleanArray(lines.size)
    var inCodeFence = false
    for (i in lines.indices) {
        if (CODE_FENCE.containsMatchIn(lines[i])) inCodeFence = !inCodeFence
        if (!inCodeFence && i > 0 && TABLE_DELIMITER.matches(lines[i]) && lines[i - 1].contains("|")) {
            isTableLine[i - 1] = true
            isTableLine[i] = true
            var j = i + 1
            while (j < lines.size && lines[j].isNotBlank() && lines[j].contains("|")) {
                isTableLine[j] = true
                j++
            }
        }
    }
    // 逐行三分类：CODE（围栏内含围栏行）/ TABLE / MARKDOWN
    val types = ArrayList<AgentSegmentType>(lines.size)
    var inFence = false
    for (i in lines.indices) {
        val line = lines[i]
        if (CODE_FENCE.containsMatchIn(line)) {
            inFence = !inFence
            types += AgentSegmentType.CODE
            continue
        }
        types += when {
            inFence -> AgentSegmentType.CODE
            isTableLine[i] -> AgentSegmentType.TABLE
            else -> AgentSegmentType.MARKDOWN
        }
    }
    // 合并连续同类型
    val segments = mutableListOf<AgentSegment>()
    var start = 0
    for (i in 1..lines.size) {
        if (i == lines.size || types[i] != types[start]) {
            segments += AgentSegment(types[start], lines.subList(start, i).joinToString("\n"))
            start = i
        }
    }
    return segments
}

/** 从 CODE 段原文（含首尾围栏行）提取代码体：去首围栏行、去末围栏行（若存在）。 */
fun extractCodeBody(raw: String): String {
    val lines = raw.split("\n")
    if (lines.isEmpty()) return ""
    val startIdx = if (CODE_FENCE.containsMatchIn(lines.first())) 1 else 0
    val body = lines.subList(startIdx, lines.size)
    val endIdx = if (body.isNotEmpty() && CODE_FENCE.containsMatchIn(body.last())) body.lastIndex else body.size
    return body.subList(0, endIdx).joinToString("\n")
}

/** 代码体的逻辑行数（空串算 0 行）。 */
fun codeLineCount(code: String): Int = if (code.isEmpty()) 0 else code.count { it == '\n' } + 1

/** 折叠态预览：取前 [limit] 行。 */
fun previewCode(code: String, limit: Int): String = code.split("\n").take(limit).joinToString("\n")
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ClaudeMarkdownSegmenterTest"`
Expected: PASS（7 例全绿）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ClaudeMarkdownSegmenter.kt \
  app/src/test/java/com/mamba/picme/features/chat/ClaudeMarkdownSegmenterTest.kt
git commit -m "feat(claude-tunnel): 抽出 ClaudeMarkdownSegmenter，新增 CODE 段 + 纯函数"
```

---

### Task 4: ChatScreen 接入分段器 + CodeBlock 组件（显示层）

删除 ChatScreen 内旧的 `segmentStreamingMarkdown`/`StreamSegment`/`StreamSegmentType`，改用 `segmentMarkdown`；新增 `CodeBlock` 组件（默认折叠 12 行 + 横滚 + 复制）；最终态也走分段器。Compose，验证 = 编译 + 真机截图（本仓 Compose 测试放 androidTest 且环境不稳，真门槛是编译）。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（imports、895-938 段、1112-1150 渲染分支）

**前置:** Task 3（分段器）+ Task 7（文案 `claude_code_*`）已完成，否则编译缺符号/资源。

- [ ] **Step 1: 补导入**

在 `ChatScreen.kt` import 区（icons 段）加：

```kotlin
import androidx.compose.material.icons.rounded.ContentCopy
```

（foundation 段）加：

```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
```

（coroutines 段）加：

```kotlin
import kotlinx.coroutines.delay
```

- [ ] **Step 2: 删除旧分段器（895-938 行）**

删除 `ChatScreen.kt` 中的 `private enum class StreamSegmentType`、`private data class StreamSegment`、`private val TABLE_DELIMITER`、`private val CODE_FENCE`、`private fun segmentStreamingMarkdown(...)`（约 895-938 行整段）。它们已被 `ClaudeMarkdownSegmenter.kt` 取代（`TABLE_DELIMITER`/`CODE_FENCE` 也在新文件内私有重建，删除不丢功能）。

- [ ] **Step 3: 新增 CodeBlock 组件**

在 `ChatScreen.kt` 的 `ClaudeAgentSteps` composable（约 944 行 `@Composable private fun ClaudeAgentSteps`）之前插入：

```kotlin
/** 折叠阈值：超过此行数的代码块默认折叠。 */
private const val CODE_COLLAPSE_LINES = 12

/**
 * agent 气泡代码块（spec §3.2）：默认折叠前 [CODE_COLLAPSE_LINES] 行 + 「展开/收起」，
 * 横向滚动（长行不换行撑屏），可复制全文。代码体由 [extractCodeBody] 从围栏段提取。
 */
@Composable
private fun CodeBlock(raw: String) {
    val code = remember(raw) { extractCodeBody(raw) }
    val total = remember(code) { codeLineCount(code) }
    val expandable = total > CODE_COLLAPSE_LINES
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val shown = if (!expandable || expanded) code else previewCode(code, CODE_COLLAPSE_LINES)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Text(
            text = shown,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(end = 4.dp),
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (expandable) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) stringResource(R.string.claude_code_collapse)
                        else stringResource(R.string.claude_code_expand_n, total),
                        fontSize = 12.sp,
                    )
                }
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.claude_code_copy),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (copied) {
                Text(
                    text = stringResource(R.string.claude_code_copied),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LaunchedEffect(copied) {
                    delay(1500)
                    copied = false
                }
            }
        }
    }
}

/** agent 文本分段渲染（流式与最终态共用）：MARKDOWN→MarkdownText、TABLE→纯文本、CODE→CodeBlock。 */
@Composable
private fun SegmentedAgentText(displayText: String) {
    segmentMarkdown(displayText).forEach { segment ->
        when (segment.type) {
            AgentSegmentType.TABLE -> Text(
                text = segment.text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
            )
            AgentSegmentType.MARKDOWN -> MarkdownText(
                markdown = segment.text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            AgentSegmentType.CODE -> CodeBlock(raw = segment.text)
        }
    }
}
```

- [ ] **Step 4: 流式分支改用 SegmentedAgentText**

`ChatScreen.kt` 流式分支（约 1118-1137 行）把内层 `Column { segmentStreamingMarkdown(displayText).forEach { ... } }` 整段替换为：

```kotlin
                                Column(modifier = Modifier.weight(1f)) {
                                    SegmentedAgentText(displayText)
                                }
```

（保留外层 `Row` 与 `if (message.showCursor) BlinkCursor()`）。

- [ ] **Step 5: 最终态分支改用 SegmentedAgentText**

`ChatScreen.kt` 最终态分支（约 1143-1150 行 `else { MarkdownText(markdown = displayText, ...) }`）替换为：

```kotlin
                    } else {
                        SegmentedAgentText(displayText)
                    }
```

- [ ] **Step 6: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。若报 `segmentStreamingMarkdown`/`StreamSegmentType` 残留引用，按报错处改成 `segmentMarkdown`/`AgentSegmentType`。

- [ ] **Step 7: 真机验证（截图）**

Run: `./scripts/shot.sh`（或 `scripts/auto-dev-loop.sh`）触发一条含代码块的 AI 工程师回复，截图确认：代码块默认折叠 12 行 + 展开按钮 + 复制图标 + 横向可滚。
Expected: 截图里代码块折叠态正常，无撑屏。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(claude-tunnel): 代码块折叠组件 CodeBlock + 流式/最终渲染统一分段"
```

---

### Task 5: 截断标识 + 继续按钮（UX）+ ViewModel.continueClaude

气泡底部在 `truncatedReason != null` 时显「ⓘ 回答较长已截断（原因）」+「继续」按钮；继续 = 用同 sid 发"继续"（走 `sendClaudeMessage`，复用 `--resume`）。Compose + ViewModel，验证 = 编译 + 真机。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（加 `continueClaude`）
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（ChatMessageItem 参数 + 气泡底部 UI + 调用点穿线 512-526）

**前置:** Task 2（`truncatedReason` 字段）+ Task 7（文案 `claude_truncated*`/`claude_continue`）已完成。

- [ ] **Step 1: ViewModel 加 continueClaude**

`ChatViewModel.kt` 在 `confirmClaudeDeliver`（约 475 行）之前加：

```kotlin
    /**
     * 截断后「继续」：用当前 session 的 sid 发"继续"（[sendClaudeMessage] 复用 --resume）。
     * 注：继续的是本会话最新 sid，与具体气泡无关（一会话一 sid）。
     */
    fun continueClaude() {
        sendClaudeMessage("继续")
    }
```

- [ ] **Step 2: ChatMessageItem 加 onClaudeContinue 参数**

`ChatScreen.kt:986`（`onClaudeDeliver` 参数行）下面加一行参数：

```kotlin
    onClaudeContinue: () -> Unit = {},
```

- [ ] **Step 3: 气泡底部加截断标识 + 继续按钮**

`ChatScreen.kt` 在 claude agent 步骤列表块（约 1153-1159 `message.claudeAgent?.let { cs -> if (cs.steps.isNotEmpty()) ... }`）之后、交付按钮块（约 1160 `message.claudeDeliver?.let`）之前插入：

```kotlin
            // 截断标识 + 继续（spec §3.4）：truncatedReason 粘滞，置位后只设不清。
            message.claudeAgent?.truncatedReason?.let { reason ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ⓘ " + stringResource(R.string.claude_truncated) +
                            " " + truncationReasonLabel(reason),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onClaudeContinue) {
                        Text(stringResource(R.string.claude_continue), fontSize = 12.sp)
                    }
                }
            }
```

- [ ] **Step 4: 加 truncationReasonLabel 辅助函数**

在 `ChatScreen.kt` 的 `ClaudeAgentSteps`（约 944 行）之前加：

```kotlin
/** 把网关 reason 串映射成本地化后缀文案。未知 reason 返回空串。 */
@Composable
private fun truncationReasonLabel(reason: String): String = when (reason) {
    "max_turns" -> stringResource(R.string.claude_truncated_reason_max_turns)
    "phase_timeout" -> stringResource(R.string.claude_truncated_reason_timeout)
    else -> ""
}
```

- [ ] **Step 5: 调用点穿线**

`ChatScreen.kt:526`（`onClaudeDeliver = { id, mode -> viewModel.confirmClaudeDeliver(id, mode) },`）下面加一行：

```kotlin
                                    onClaudeContinue = { viewModel.continueClaude() },
```

- [ ] **Step 6: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 真机验证（截图）**

触发一条会被截断的长回答（或临时把网关 `CT_MAX_TURNS` 设小复现 max_turns），确认气泡底部出现「ⓘ 回答较长已截断（达最大轮数）」+「继续」按钮；点继续后开始新一轮流式。
Expected: 截图见标识 + 按钮；点继续有新一轮回复。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt \
  app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(claude-tunnel): 截断标识 + 继续按钮（同 sid 走 --resume）"
```

---

### Task 6: 网关 annotate_truncated + phase_timeout_event + 简洁 prompt（源头层）

server.py 加两个纯函数（pump 标注 max_turns done；phase_timeout error 携带截断）；pump 调用；prompt 追加简洁约束。pytest TDD。

**Files:**
- Modify: `scripts/claude-tunnel/gateway/server.py`（44-53 prompt、160-168 pump、176-178 timeout）
- Test: `scripts/claude-tunnel/gateway/test_server.py`

- [ ] **Step 1: 写失败测试**

在 `test_server.py` 末尾追加：

```python
def test_annotate_truncated_marks_max_turns():
    ev = {"event": "done", "data": {"turns": 20}}
    out = server.annotate_truncated(ev, max_turns=20)
    assert out["data"]["truncated"] is True
    assert out["data"]["reason"] == "max_turns"


def test_annotate_truncated_leaves_short_turns():
    ev = {"event": "done", "data": {"turns": 3}}
    out = server.annotate_truncated(ev, max_turns=20)
    assert "truncated" not in out["data"]


def test_annotate_truncated_ignores_non_done():
    ev = {"event": "assistant_text", "data": {"delta": "hi"}}
    assert server.annotate_truncated(ev, max_turns=20) is ev


def test_phase_timeout_event_carries_truncation():
    ev = server.phase_timeout_event(300)
    assert ev["event"] == "error"
    assert ev["data"]["truncated"] is True
    assert ev["data"]["reason"] == "phase_timeout"
    assert "300" in ev["data"]["message"]


def test_system_prompt_requires_concise_output():
    assert "不要整段" in server.APP_TOOL_SYSTEM_PROMPT
    assert "≤30 行" in server.APP_TOOL_SYSTEM_PROMPT
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_server.py -k "annotate_truncated or phase_timeout_event or system_prompt" -v`
Expected: FAIL——`server.annotate_truncated`/`server.phase_timeout_event` 不存在；prompt 无 "不要整段"。

- [ ] **Step 3: 追加简洁约束到 prompt**

`server.py:44` 的 `APP_TOOL_SYSTEM_PROMPT` 末尾（`"""` 之前）追加一段（保留原有内容不动）：

```python

输出风格（用户在手机上读你的回复，必须简洁可读）：
- 结论先行；用要点 + 关键代码片段回答，不要整段粘贴源文件或完整构建/命令日志。
- 必须展示代码时，只贴关键 ≤30 行片段并注明文件位置，省略部分用注释代替。
- 日志/构建输出只摘录关键行（报错行 + 上下文），不要全量回灌。
- 单次正文控制在约 ≤800 字；长内容分多条消息，每条聚焦一个要点。
```

- [ ] **Step 4: 加 annotate_truncated + phase_timeout_event 纯函数**

`server.py` 在 `build_cmd` 函数（约 70 行）之前加：

```python
def annotate_truncated(ev, max_turns=None):
    """pump 转发 done 前调用：达 max_turns 则标注截断。返回（可能改写的）ev。"""
    if ev["event"] != "done":
        return ev
    mt = int(MAX_TURNS) if max_turns is None else max_turns
    turns = ev.get("data", {}).get("turns")
    if isinstance(turns, int) and turns >= mt:
        data = dict(ev["data"])
        data["truncated"] = True
        data["reason"] = "max_turns"
        return {"event": "done", "data": data}
    return ev


def phase_timeout_event(seconds):
    """CT_PHASE_TIMEOUT 触发的截断 error 事件。"""
    return {"event": "error", "data": {
        "message": "phase timeout {}s".format(seconds),
        "truncated": True,
        "reason": "phase_timeout",
    }}
```

- [ ] **Step 5: pump 调用 annotate_truncated**

`server.py:163-168`（pump 内 `for ev in claude_events.translate_stream_line(...):` 循环）在 `if ev["event"] == "session":` 之后、`if ev["event"] == "done":` 之前插一行：

```python
                ev = annotate_truncated(ev)
```

- [ ] **Step 6: phase timeout 改用 phase_timeout_event**

`server.py:178` 替换为：

```python
            await _send(resp, phase_timeout_event(timeout))
```

- [ ] **Step 7: 运行测试确认通过**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_server.py test_claude_events.py -v`
Expected: PASS（新 5 例 + 既有全绿；claude_events 翻译未改，回归通过）。

- [ ] **Step 8: 提交**

```bash
git add scripts/claude-tunnel/gateway/server.py scripts/claude-tunnel/gateway/test_server.py
git commit -m "feat(claude-tunnel): 网关标注截断(max_turns/phase_timeout) + 简洁输出 prompt"
```

---

### Task 7: i18n 三语 strings 同步

为 Task 4/5 的 UI 加全部新文案，三语（values / values-zh-rCN / values-zh-rTW）同步。

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: values（EN 默认）追加**

在 `values/strings.xml` 的 `claude_file_change_label` 行（约 1002）之后插入：

```xml
    <string name="claude_code_expand_n">Expand (%1$d lines)</string>
    <string name="claude_code_collapse">Collapse</string>
    <string name="claude_code_copy">Copy</string>
    <string name="claude_code_copied">Copied</string>
    <string name="claude_truncated">Response truncated</string>
    <string name="claude_truncated_reason_max_turns">(max turns reached)</string>
    <string name="claude_truncated_reason_timeout">(timeout)</string>
    <string name="claude_continue">Continue</string>
```

- [ ] **Step 2: values-zh-rCN 追加**

在 `values-zh-rCN/strings.xml` 的 `claude_file_change_label` 行（约 996）之后插入：

```xml
    <string name="claude_code_expand_n">展开（共 %1$d 行）</string>
    <string name="claude_code_collapse">收起</string>
    <string name="claude_code_copy">复制</string>
    <string name="claude_code_copied">已复制</string>
    <string name="claude_truncated">回答较长已截断</string>
    <string name="claude_truncated_reason_max_turns">（达最大轮数）</string>
    <string name="claude_truncated_reason_timeout">（超时）</string>
    <string name="claude_continue">继续</string>
```

- [ ] **Step 3: values-zh-rTW 追加**

在 `values-zh-rTW/strings.xml` 的 `claude_file_change_label` 行（约 974）之后插入：

```xml
    <string name="claude_code_expand_n">展開（共 %1$d 行）</string>
    <string name="claude_code_collapse">收起</string>
    <string name="claude_code_copy">複製</string>
    <string name="claude_code_copied">已複製</string>
    <string name="claude_truncated">回答較長已截斷</string>
    <string name="claude_truncated_reason_max_turns">（達最大輪數）</string>
    <string name="claude_truncated_reason_timeout">（逾時）</string>
    <string name="claude_continue">繼續</string>
```

- [ ] **Step 4: 校验三语 key 一致**

Run: `grep -h "claude_code_\|claude_truncated\|claude_continue" app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml | sed 's/.*name="//' | sort | uniq -c`
Expected: 每个 key 计数 == 3（三语各一）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "i18n(claude-tunnel): 代码折叠 + 截断标识 + 继续按钮文案三语同步"
```

---

### Task 8: 全量编译 + JVM 回归 + 收尾

跑全量 JVM 单测 + 编译，确认无回归；更新文档验收勾选。

**Files:**
- 无代码改动（验证 + spec 验收勾选）

- [ ] **Step 1: 全量 JVM 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（关注 ClaudeSseParser/ClaudeAgentRenderer/ClaudeMarkdownSegmenter 三套全绿；其余既有预存失败按本仓惯例不计入门槛——见 memory `polang-quality-gates-reality`，但本次新增/改动用例必须绿）。

- [ ] **Step 2: 全量 debug 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 网关测试回归**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest -v`
Expected: 全绿。

- [ ] **Step 4: 勾选 spec 验收项**

把 `docs/superpowers/specs/2026-08-02-claude-agent-bubble-readability-design.md` §12 验收标准里已满足的项打勾（编译/单测/网关/i18n 相关项），提交。

```bash
git add docs/superpowers/specs/2026-08-02-claude-agent-bubble-readability-design.md
git commit -m "docs(spec): AI 工程师气泡可读性——勾选已实现验收项"
```

- [ ] **Step 5: 真机 E2E（人工）**

跑一遍：真机 AI 工程师问一个会触发长代码回答的问题 → 代码折叠可展开 + 复制 + 横滚；触发截断 → 标识 + 继续；继续后新一轮流式。记录到 spec §12 最后一项。
