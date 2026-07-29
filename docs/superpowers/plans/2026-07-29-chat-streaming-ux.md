# Chat 流式输出体验优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 chat 流式输出的吐字做成「智能混合」逐字平滑（快时节流、慢时追赶），新气泡淡入+8dp 上滑，流式中末尾闪烁打字光标。

**Architecture:** 在 `ChatViewModel` 与 `ChatScreen` 之间插入一个纯 Kotlin 节奏器 `StreamingPacingController`：事件链把「全文快照」喂给节奏器缓冲，节奏器按 ~60fps 帧节奏把 substring 前缀逐字回放给 `_streamingMessage`（积压自适应步长）。气泡动画用 `AnimatedVisibility` + `MutableTransitionState` 精确控制 8dp 上滑；光标用独立 `BlinkCursor` Composable。

**Tech Stack:** Kotlin coroutines（`delay` 驱动节奏，纯 JVM 可测）、Jetpack Compose（`AnimatedVisibility`/`slideInVertically`/`infiniteTransition`）、JUnit + `kotlinx-coroutines-test`（`runTest` 虚拟时间）。

**Spec:** `docs/superpowers/specs/2026-07-29-chat-streaming-ux-design.md`

**范围：** 仅 app 模块 `features/chat/`。不碰 server / runtime-core SSE 链路。

**可测性细化（对 spec 的补充说明）：** spec §5.1 写的是 `withFrameNanos`（`MonotonicFrameClock`）。纯 JVM 单测无 Compose frame clock，故改用 `delay(FRAME_MS=16L)` 驱动节奏（实际设备 ≈60fps），并注入 `timeSource: () -> Long` 让 `runTest` 虚拟时间控制停顿判断。行为语义与 spec 一致。

---

## File Structure

- **Create** `app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt` — 纯 Kotlin 节奏器（无 Android/Compose 依赖），单一职责：全文缓冲 + 帧节奏逐字回放。
- **Create** `app/src/test/java/com/mamba/picme/features/chat/streaming/StreamingPacingControllerTest.kt` — 节奏器单测（纯 JVM，`runTest` 虚拟时间）。
- **Create** `app/src/main/java/com/mamba/picme/features/chat/BlinkCursor.kt` — 流式打字光标 Composable。
- **Modify** `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` — `ChatMessageUi` 加 `showCursor` 字段；`LazyColumn` items 包 `AnimatedVisibility`；流式渲染段接 `BlinkCursor`。
- **Modify** `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` — 持有 `StreamingPacingController`，流式事件经节奏器。
- **Create**（可选回归）`app/src/test/java/com/mamba/picme/features/chat/ChatViewModelStreamingWiringTest.kt` — 端到端不崩回归。

---

## Task 1: 节奏器骨架 + 循环生命周期 + 无内容静默

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/streaming/StreamingPacingControllerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mamba/picme/features/chat/streaming/StreamingPacingControllerTest.kt`:

```kotlin
package com.mamba.picme.features.chat.streaming

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPacingControllerTest {

    private data class Paced(val text: String, val cursorVisible: Boolean)

    @Test
    fun `start with no content stays silent`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { currentTime },
        )
        ctrl.start()
        advanceTimeBy(16L * 10) // 10 帧
        assertTrue(paced.isEmpty(), "无内容时不应触发 onPaced")
    }

    @Test
    fun `finish stops the loop and emits nothing more`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { currentTime },
        )
        ctrl.start()
        ctrl.finish()
        val sizeAfterFinish = paced.size
        advanceTimeBy(16L * 10)
        assertEquals(sizeAfterFinish, paced.size, "finish 后循环应停止")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.streaming.StreamingPacingControllerTest" 2>&1 | tail -20`
Expected: FAIL — `StreamingPacingController` 未定义（编译错误）。

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt`:

```kotlin
package com.mamba.picme.features.chat.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 流式吐字节奏器：把高频到达的「全文快照」按固定帧节奏逐字平滑回放给 UI。
 *
 * - [onTextSnapshot] 只更新缓冲，立即返回，不触发 UI。
 * - 节奏循环每 [FRAME_MS] 一帧：积压大→步长放大加速追赶（仍逐字不蹦）；
 *   无积压→直接追平如实显示（智能混合）。
 * - 流式中有内容时光标可见；全文无变化超过 [IDLE_CURSOR_TIMEOUT_MS] 后隐藏。
 *
 * 可测性：用 [delay] 驱动节奏（实际设备 ≈60fps），注入 [timeSource] 以便 runTest
 * 虚拟时间控制停顿判断；不依赖 Compose MonotonicFrameClock。
 */
class StreamingPacingController(
    private val scope: CoroutineScope,
    private val onPaced: (text: String, cursorVisible: Boolean) -> Unit,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val MIN_STEP = 1
        const val MAX_STEP = 6
        const val BACKLOG_DIVISOR = 8
        const val IDLE_CURSOR_TIMEOUT_MS = 1200L
        const val FRAME_MS = 16L
    }

    @Volatile private var latestFullText: String = ""
    @Volatile private var shownLength: Int = 0
    @Volatile private var lastChangedAtMs: Long = 0L
    @Volatile private var finished: Boolean = false
    private var loopJob: Job? = null

    /** 流式开始：重置缓冲并启动节奏循环。 */
    fun start() {
        loopJob?.cancel()
        finished = false
        latestFullText = ""
        shownLength = 0
        lastChangedAtMs = timeSource()
        loopJob = scope.launch { paceLoop() }
    }

    /** 轮次完成 / 取消收尾：停循环（内容追平见 Task 5）。 */
    fun finish() {
        finished = true
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun paceLoop() {
        while (scope.isActive && !finished) {
            delay(FRAME_MS)
            val full = latestFullText
            val target = full.length
            if (target == 0) continue // 无内容（思考中/reset 后）：静默，不干预 UI
            // 追赶 / 光标逻辑见 Task 2/3
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.streaming.StreamingPacingControllerTest" 2>&1 | tail -20`
Expected: PASS（2 tests）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt \
        app/src/test/java/com/mamba/picme/features/chat/streaming/StreamingPacingControllerTest.kt
git commit -m "feat(chat): StreamingPacingController 骨架与循环生命周期"
```

---

## Task 2: 增长追赶 + 自适应步长 + 追平

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt`
- Test: same test file.

- [ ] **Step 1: Write the failing test**

Append to `StreamingPacingControllerTest.kt` (inside the class):

```kotlin
    @Test
    fun `grows substring each frame with step capped at MAX_STEP and catches up`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { currentTime },
        )
        ctrl.start()
        ctrl.onTextSnapshot("x".repeat(100))

        advanceTimeBy(16L) // 帧 1：backlog=100 → step=100/8=12 → coerceIn(1,6)=6
        assertEquals(6, paced.last().text.length)

        advanceTimeBy(16L) // 帧 2：backlog=94 → step=6 → shown=12
        assertEquals(12, paced.last().text.length)

        advanceTimeBy(16L * 30) // 推进到追平
        assertEquals(100, paced.last().text.length)
        assertEquals("x".repeat(100), paced.last().text)
    }

    @Test
    fun `small backlog advances one char per frame`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { currentTime },
        )
        ctrl.start()
        ctrl.onTextSnapshot("hi") // backlog=2 → step=2/8=0 → coerceIn(1,6)=1
        advanceTimeBy(16L)
        assertEquals(1, paced.last().text.length)
        advanceTimeBy(16L)
        assertEquals(2, paced.last().text.length) // 追平
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.streaming.StreamingPacingControllerTest" 2>&1 | tail -20`
Expected: FAIL — `onTextSnapshot` 未定义 / 不追赶。

- [ ] **Step 3: Write minimal implementation**

Add `onTextSnapshot` to `StreamingPacingController` (after `start()`):

```kotlin
    /**
     * 来自事件链的全文快照：只更新缓冲，立即返回。
     * 若 [fullText] 不是当前缓冲的连续扩展（回退/新轮），重置已展示长度从 0 重新累计。
     */
    fun onTextSnapshot(fullText: String) {
        val prev = latestFullText
        if (fullText == prev) {
            lastChangedAtMs = timeSource()
            return
        }
        val isContinuousGrowth = fullText.length > prev.length && fullText.startsWith(prev)
        latestFullText = fullText
        lastChangedAtMs = timeSource()
        if (!isContinuousGrowth) {
            shownLength = 0
        }
    }
```

Replace the body of `paceLoop` (the `// 追赶 / 光标逻辑见 Task 2/3` placeholder) with:

```kotlin
            val full = latestFullText
            val target = full.length
            if (target == 0) continue
            if (shownLength < target) {
                val backlog = target - shownLength
                val step = (backlog / BACKLOG_DIVISOR).coerceIn(MIN_STEP, MAX_STEP)
                shownLength = (shownLength + step).coerceAtMost(target)
                onPaced(full.substring(0, shownLength), true)
            } else {
                // 光标可见性见 Task 3
                onPaced(full, true)
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.streaming.StreamingPacingControllerTest" 2>&1 | tail -20`
Expected: PASS（4 tests）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt \
        app/src/test/java/com/mamba/picme/features/chat/streaming/StreamingPacingControllerTest.kt
git commit -m "feat(chat): 节奏器逐字追赶 + 积压自适应步长"
```

---

## Task 3: 光标可见性（追平后停顿超时隐藏）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt`
- Test: same test file.

- [ ] **Step 1: Write the failing test**

Append to `StreamingPacingControllerTest.kt`:

```kotlin
    @Test
    fun `cursor visible while caught up, hidden after idle timeout`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { currentTime },
        )
        ctrl.start()
        ctrl.onTextSnapshot("hello")
        advanceTimeBy(16L * 5) // 追平（5 字，每帧 1）
        assertTrue(paced.last().cursorVisible, "追平后短期内光标应可见")

        advanceTimeBy(16L * 80) // 80 帧 ≈ 1280ms > 1200ms 超时
        assertEquals(false, paced.last().cursorVisible, "停顿超时后光标应隐藏")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.streaming.StreamingPacingControllerTest" 2>&1 | tail -20`
Expected: FAIL — 停顿后 `cursorVisible` 仍为 true。

- [ ] **Step 3: Write minimal implementation**

Replace the `else` branch of `paceLoop` with:

```kotlin
            } else {
                val cursor = timeSource() - lastChangedAtMs <= IDLE_CURSOR_TIMEOUT_MS
                onPaced(full, cursor)
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.streaming.StreamingPacingControllerTest" 2>&1 | tail -20`
Expected: PASS（5 tests）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt \
        app/src/test/java/com/mamba/picme/features/chat/streaming/StreamingPacingControllerTest.kt
git commit -m "feat(chat): 节奏器光标可见性（停顿超时隐藏）"
```

---

## Task 4: reset() + finish 中途追平 + 隐藏光标

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt`
- Test: same test file.

- [ ] **Step 1: Write the failing test**

Append to `StreamingPacingControllerTest.kt`:

```kotlin
    @Test
    fun `reset clears buffer and new text replays from zero`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { currentTime },
        )
        ctrl.start()
        ctrl.onTextSnapshot("hello")
        advanceTimeBy(16L * 5) // 追平 hello
        ctrl.reset()
        advanceTimeBy(16L * 3) // reset 后 target=0，静默
        val sizeAfterReset = paced.size
        assertEquals(sizeAfterReset, paced.size, "reset 后无内容应静默")

        ctrl.onTextSnapshot("world") // 从空扩展 → 连续增长，shownLength 保持 0
        advanceTimeBy(16L)
        assertEquals(1, paced.last().text.length) // 从 0 重新逐字
        advanceTimeBy(16L * 5)
        assertEquals("world", paced.last().text)
    }

    @Test
    fun `non-continuous snapshot resets shown length`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { currentTime },
        )
        ctrl.start()
        ctrl.onTextSnapshot("hello world")
        advanceTimeBy(16L * 5)
        ctrl.onTextSnapshot("xyz") // 非 "hello world" 的前缀扩展 → 重置
        advanceTimeBy(16L)
        assertEquals(1, paced.last().text.length) // 从 0 重追 "xyz"
        advanceTimeBy(16L * 5)
        assertEquals("xyz", paced.last().text)
    }

    @Test
    fun `finish mid-stream catches up full text and hides cursor`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { currentTime },
        )
        ctrl.start()
        ctrl.onTextSnapshot("x".repeat(100))
        advanceTimeBy(16L) // 仅追到 6
        ctrl.finish()
        assertEquals(100, paced.last().text.length)
        assertEquals(false, paced.last().cursorVisible)
        val sizeAfterFinish = paced.size
        advanceTimeBy(16L * 10)
        assertEquals(sizeAfterFinish, paced.size, "finish 后循环应停止")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.streaming.StreamingPacingControllerTest" 2>&1 | tail -20`
Expected: FAIL — `reset()` 未定义；`finish()` 未追平。

- [ ] **Step 3: Write minimal implementation**

Add `reset()` (after `onTextSnapshot`):

```kotlin
    /** 清空缓冲（供 ToolCallStarted 切换状态文案时协调，避免节奏器用旧全文覆盖）。 */
    fun reset() {
        latestFullText = ""
        shownLength = 0
        lastChangedAtMs = timeSource()
    }
```

Replace `finish()` with:

```kotlin
    /** 轮次完成 / 取消收尾：一次性追平全文、隐藏光标、停循环。 */
    fun finish() {
        finished = true
        loopJob?.cancel()
        loopJob = null
        val full = latestFullText
        if (full.isNotEmpty()) {
            shownLength = full.length
            onPaced(full, false)
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.streaming.StreamingPacingControllerTest" 2>&1 | tail -20`
Expected: PASS（8 tests）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/streaming/StreamingPacingController.kt \
        app/src/test/java/com/mamba/picme/features/chat/streaming/StreamingPacingControllerTest.kt
git commit -m "feat(chat): 节奏器 reset + finish 追平收尾"
```

---

## Task 5: ChatMessageUi 加 showCursor 字段

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt:1813`

- [ ] **Step 1: Add the field**

In `ChatScreen.kt`, find the `ChatMessageUi` data class `isStreaming` field (line ~1813):

```kotlin
    /** 流式输出中的瞬态消息（不落 Room）；UI 据此对未闭合表格做防抖动处理。 */
    val isStreaming: Boolean = false
)
```

Replace with:

```kotlin
    /** 流式输出中的瞬态消息（不落 Room）；UI 据此对未闭合表格做防抖动处理。 */
    val isStreaming: Boolean = false,
    /** 流式打字光标是否可见（由节奏器驱动：吐字中 true，停顿超时/完成 false）。 */
    val showCursor: Boolean = false
)
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL（新字段有默认值，不破坏既有构造）。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): ChatMessageUi 增加 showCursor 字段"
```

---

## Task 6: ChatViewModel 接线节奏器

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 1: Add imports**

In `ChatViewModel.kt` imports (after line 63 `import kotlinx.coroutines.flow.combine`), add:

```kotlin
import kotlinx.coroutines.flow.update
import com.mamba.picme.features.chat.streaming.StreamingPacingController
```

- [ ] **Step 2: Instantiate the pacing controller**

After the `_streamingMessage` / `streamingMessage` declarations (line ~263, after `val streamingMessage: StateFlow<ChatMessageUi?> = _streamingMessage.asStateFlow()`), add:

```kotlin
    private val pacingController = StreamingPacingController(
        scope = viewModelScope,
        onPaced = { text, cursor ->
            _streamingMessage.update { current ->
                current?.copy(content = text, showCursor = cursor)
            }
        }
    )
```

- [ ] **Step 3: start() on streaming placeholder creation**

Find the streaming placeholder creation (line ~707-714):

```kotlin
                val streamingId = "streaming_${System.currentTimeMillis()}"
                _streamingMessage.value = ChatMessageUi(
                    id = streamingId,
                    type = ChatMessageType.AGENT_TEXT,
                    content = STREAMING_THINKING_HINT,
                    modelUsed = currentModelLabel(),
                    isStreaming = true
                )
```

Replace with (add `pacingController.start()` after):

```kotlin
                val streamingId = "streaming_${System.currentTimeMillis()}"
                _streamingMessage.value = ChatMessageUi(
                    id = streamingId,
                    type = ChatMessageType.AGENT_TEXT,
                    content = STREAMING_THINKING_HINT,
                    modelUsed = currentModelLabel(),
                    isStreaming = true
                )
                pacingController.start()
```

- [ ] **Step 4: Route TextSnapshot through pacer; reset on ToolCallStarted**

Find the `onEvent` lambda (line ~756-765):

```kotlin
                    onEvent = { event ->
                        when (event) {
                            is ChatStreamEvent.TextSnapshot ->
                                _streamingMessage.value = _streamingMessage.value?.copy(content = event.text)
                            ChatStreamEvent.ToolCallStarted ->
                                _streamingMessage.value = _streamingMessage.value?.copy(
                                    content = context.getString(R.string.chat_calling_tool)
                                )
                        }
                    }
```

Replace with:

```kotlin
                    onEvent = { event ->
                        when (event) {
                            is ChatStreamEvent.TextSnapshot ->
                                pacingController.onTextSnapshot(event.text)
                            ChatStreamEvent.ToolCallStarted -> {
                                pacingController.reset()
                                _streamingMessage.value = _streamingMessage.value?.copy(
                                    content = context.getString(R.string.chat_calling_tool),
                                    showCursor = false
                                )
                            }
                        }
                    }
```

- [ ] **Step 5: finish() when streamChat returns**

Find (line ~753-766) the `val result = orchestrator.remoteChatEngine.streamChat(...) { onEvent = ... }` block end. Immediately after the closing `)` of `streamChat(...)` and before `// 6. 处理结果` / `result.fold`, insert:

```kotlin
                // 流式已结束（streamChat 返回 = onCompleteResponse 已触发）：节奏器追平收尾
                pacingController.finish()
```

(The exact insertion point: between the `streamChat(...)` call closing paren and the `// 6. 处理结果` comment at line ~768.)

- [ ] **Step 6: Write the regression test**

Create `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelStreamingWiringTest.kt`:

```kotlin
package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.R
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.ChatStreamEvent
import com.mamba.picme.agent.core.local.llm.StreamChatResult
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ChatViewModel 流式接线回归：TextSnapshot 经节奏器、streamChat 返回后 finish，
 * 最终 streamingMessage 被清除、不崩。节奏细节由 StreamingPacingControllerTest 覆盖。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelStreamingWiringTest {

    private val context: Context = mockk(relaxed = true)
    private val chatMessageDao: ChatMessageDao = mockk(relaxed = true)
    private val chatSessionDao: ChatSessionDao = mockk(relaxed = true)
    private val mediaSearchEngine: MediaSearchEngine = mockk(relaxed = true)
    private val mediaFeedbackRepository: MediaFeedbackRepository = mockk(relaxed = true)
    private val authClient: PoLangAuthClient = mockk(relaxed = true)
    private val userSettingsRepository: UserSettingsRepository = mockk(relaxed = true)
    private val orchestrator: AgentOrchestrator = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        every { context.applicationContext } returns context
        every { context.getString(R.string.new_chat) } returns "New Chat"
        every { context.getString(R.string.chat_title_image_first) } returns "Image Chat"
        every { context.getString(R.string.chat_calling_tool) } returns "正在调用工具"

        every { userSettingsRepository.serverAuthTokenFlow } returns MutableStateFlow("")
        every { userSettingsRepository.aiAgentInferencePreferenceFlow } returns
            MutableStateFlow(AiAgentInferencePreference.FORCE_REMOTE)
        every { chatMessageDao.getMessagesBySession(any()) } returns flowOf(emptyList())
        coEvery { chatMessageDao.getLastMessageForSession(any()) } returns null
        every { chatSessionDao.getAllSessions() } returns flowOf(emptyList())

        mockkObject(AgentOrchestrator.Companion)
        every { AgentOrchestrator.getInstance(any()) } returns orchestrator
        every { orchestrator.getInferencePreference() } returns AiAgentInferencePreference.FORCE_REMOTE
    }

    @After
    fun tearDown() {
        unmockkObject(AgentOrchestrator.Companion)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun newViewModel() = ChatViewModel(
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = chatMessageDao,
            chatSessionDao = chatSessionDao,
            userSettingsRepository = userSettingsRepository,
            mediaSearchEngine = mediaSearchEngine,
            mediaFeedbackRepository = mediaFeedbackRepository,
            mediaRepository = mockk(relaxed = true),
            picMeAuthClient = authClient,
            getGallerySummaryUseCase = mockk(relaxed = true),
            queryGalleryMediaUseCase = mockk(relaxed = true),
            startTagScanUseCase = StartTagScanUseCase(context),
            personDao = mockk(relaxed = true),
            controlledVocab = ControlledVocab(),
            chatEditStateHolder = ChatEditStateHolder(),
            chatEditProcessor = mockk(relaxed = true),
            chatImageStore = mockk(relaxed = true),
            saveChatEditResultUseCase = mockk(relaxed = true)
        )
    )

    @Test
    fun `text snapshot is paced and streaming message clears on finish`() = runTest {
        coEvery { chatSessionDao.getSession("default") } returns
            ChatSessionEntity(sessionId = "default", title = "New Chat")
        coEvery { chatMessageDao.getMessageCount("default") } returns 1
        val eventSlot = slot<(ChatStreamEvent) -> Unit>()
        coEvery { orchestrator.remoteChatEngine.streamChat(any(), any(), capture(eventSlot)) } answers {
            eventSlot.captured(ChatStreamEvent.TextSnapshot("你好世界"))
            Result.success(StreamChatResult(fullResponse = "你好世界"))
        }

        val vm = newViewModel()
        advanceUntilIdle()
        vm.sendMessage("测试")
        advanceUntilIdle()

        assertNull(vm.streamingMessage.value, "流式结束后 streamingMessage 应被清除")
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatViewModelStreamingWiringTest" 2>&1 | tail -20`
Expected: PASS（1 test）。若 `ChatViewModelDependencies` 字段名与本测试不符，以 `ChatViewModelTitleUpdateTest.kt:94-113` 的实际构造为准对齐字段名。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt \
        app/src/test/java/com/mamba/picme/features/chat/ChatViewModelStreamingWiringTest.kt
git commit -m "feat(chat): ChatViewModel 流式事件经节奏器（start/onTextSnapshot/reset/finish）"
```

---

## Task 7: 新气泡 AnimatedVisibility 动画

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

> 无自动化 UI 测试（项目无 Compose UI 测试基建）；靠编译 + 手动验证（Task 9）。

- [ ] **Step 1: Add imports**

In `ChatScreen.kt` imports, after `import androidx.compose.animation.fadeOut` (line 19), add:

```kotlin
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
```

（确认 `androidx.compose.ui.unit.dp`、`androidx.compose.foundation.layout.Row`、`Alignment` 已存在；若缺失一并补。）

- [ ] **Step 2: Wrap each item in AnimatedVisibility**

Find the `items(messages, key = { it.id }) { message ->` block (line ~449-484). The block currently is:

```kotlin
                        items(messages, key = { it.id }) { message ->
                            val mr = message.mediaResults
                            if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null) {
                                MediaResultsCarousel(
                                    mediaResults = mr,
                                    onCardClick = { index ->
                                        previewAssets = mr.assets
                                        previewIndex = index
                                    },
                                    onViewAll = {
                                        onNavigateToGallery(mr.query)
                                    },
                                    onFeedback = { mediaId, action ->
                                        viewModel.onMediaFeedback(mediaId, mr.query, action)
                                    }
                                )
                            } else if (message.type == ChatMessageType.CHART && message.chartSvg != null) {
                                ChartSvgCard(svg = message.chartSvg, onClick = { previewChartSvg = message.chartSvg })
                            } else {
                                ChatMessageItem(
                                    message = message,
                                    onImageClick = { msg ->
                                        val pages = buildImagePreviewPages(messages)
                                        if (pages.isNotEmpty()) {
                                            val isEdit = msg.type == ChatMessageType.AGENT_IMAGE ||
                                                msg.type == ChatMessageType.AGENT_EDIT_RESULT
                                            if (isEdit) viewModel.touchEditImage(msg.imageUri)
                                            imagePreview = ChatImagePreviewState(
                                                pages = pages,
                                                initialIndex = indexOfPage(pages, msg.id)
                                            )
                                        }
                                    }
                                )
                            }
                        }
```

Replace with (wrap body in `AnimatedVisibility`; keep inner logic identical):

```kotlin
                        items(messages, key = { it.id }) { message ->
                            val transitionState = remember(message.id) {
                                MutableTransitionState(false).apply { targetState = true }
                            }
                            val density = LocalDensity.current
                            AnimatedVisibility(
                                visibleState = transitionState,
                                enter = fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                                    slideInVertically(
                                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                                        initialOffsetY = { with(density) { 8.dp.roundToPx() } }
                                    )
                            ) {
                                val mr = message.mediaResults
                                if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null) {
                                    MediaResultsCarousel(
                                        mediaResults = mr,
                                        onCardClick = { index ->
                                            previewAssets = mr.assets
                                            previewIndex = index
                                        },
                                        onViewAll = {
                                            onNavigateToGallery(mr.query)
                                        },
                                        onFeedback = { mediaId, action ->
                                            viewModel.onMediaFeedback(mediaId, mr.query, action)
                                        }
                                    )
                                } else if (message.type == ChatMessageType.CHART && message.chartSvg != null) {
                                    ChartSvgCard(svg = message.chartSvg, onClick = { previewChartSvg = message.chartSvg })
                                } else {
                                    ChatMessageItem(
                                        message = message,
                                        onImageClick = { msg ->
                                            val pages = buildImagePreviewPages(messages)
                                            if (pages.isNotEmpty()) {
                                                val isEdit = msg.type == ChatMessageType.AGENT_IMAGE ||
                                                    msg.type == ChatMessageType.AGENT_EDIT_RESULT
                                                if (isEdit) viewModel.touchEditImage(msg.imageUri)
                                                imagePreview = ChatImagePreviewState(
                                                    pages = pages,
                                                    initialIndex = indexOfPage(pages, msg.id)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): 新气泡淡入+8dp 上滑动画（AnimatedVisibility）"
```

---

## Task 8: BlinkCursor Composable + ChatMessageItem 接入

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/BlinkCursor.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt:962-984`

- [ ] **Step 1: Create BlinkCursor**

Create `app/src/main/java/com/mamba/picme/features/chat/BlinkCursor.kt`:

```kotlin
package com.mamba.picme.features.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 流式打字光标：与 AI 文本同色的细竖线，alpha 周期闪烁（≈530ms 一周期）。
 * 纯装饰、无字符串资源（非语义文本）。
 */
@Composable
fun BlinkCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "blink")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 265, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blinkAlpha"
    )
    val cursorColor = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .padding(start = 2.dp, top = 2.dp, bottom = 2.dp)
            .size(width = 2.dp, height = 14.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(cursorColor)
            .alpha(alpha),
        contentAlignment = Alignment.Center
    ) {}
}
```

- [ ] **Step 2: Wire BlinkCursor into the streaming branch**

In `ChatScreen.kt`, find the streaming rendering branch in `ChatMessageItem` (line ~962-984):

```kotlin
                else -> {
                    if (message.isStreaming) {
                        // 流式防抖动：表格段（可多个）一律纯文本直出，流式期间零表格位图；
                        // Markdown 段照常渲染。消息落库后走下方完整 Markdown，表格一次性定型。
                        Column {
                            segmentStreamingMarkdown(message.content).forEach { segment ->
                                when (segment.type) {
                                    StreamSegmentType.TABLE -> Text(
                                        text = segment.text,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    StreamSegmentType.MARKDOWN -> MarkdownText(
                                        markdown = segment.text,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    } else {
```

Replace with (wrap segments in `Row(Bottom)` + add cursor):

```kotlin
                else -> {
                    if (message.isStreaming) {
                        // 流式防抖动：表格段（可多个）一律纯文本直出，流式期间零表格位图；
                        // Markdown 段照常渲染。消息落库后走下方完整 Markdown，表格一次性定型。
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column(modifier = Modifier.weight(1f)) {
                                segmentStreamingMarkdown(message.content).forEach { segment ->
                                    when (segment.type) {
                                        StreamSegmentType.TABLE -> Text(
                                            text = segment.text,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        StreamSegmentType.MARKDOWN -> MarkdownText(
                                            markdown = segment.text,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp
                                        )
                                    }
                                }
                            }
                            if (message.showCursor) {
                                BlinkCursor()
                            }
                        }
                    } else {
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/BlinkCursor.kt \
        app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): 流式打字光标 BlinkCursor 接入气泡"
```

---

## Task 9: 全量测试 + release 编译 + i18n 核查 + 手动验证

**Files:** 无（验证 + 收尾）。

- [ ] **Step 1: Run full JVM unit tests**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL（含节奏器 8 tests + VM 接线回归 1 test，且现有 chat 测试全绿）。

- [ ] **Step 2: i18n 核查**

本特性无新增用户可见语义字符串：
- `BlinkCursor` 是纯图形（小竖线），无字符串。
- 气泡动画无字符串。
- 「思考中」「正在调用工具」文案沿用既有 `strings.xml`（`STREAMING_THINKING_HINT` / `R.string.chat_calling_tool`）。

Run: `grep -rn "showCursor\|BlinkCursor" app/src/main/res` 
Expected: 无输出（确认未误加字符串资源）。

- [ ] **Step 3: Build release APK（验证 R8/混淆无回归）**

Run: `./scripts/build.sh release 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL，产物 `app/build/outputs/apk/release/polang-release.apk`。

- [ ] **Step 4: 手动验证（设备）**

装包后在 chat 发一条会触发长回复的消息，观察：
1. 吐字逐字平滑、网络快时不蹦字、网络停顿后光标继续闪约 1.2s 后消失。
2. 用户消息与 AI 流式气泡首次出现有淡入 + 轻微上滑。
3. 多轮对话滚动正常，旧气泡不重放动画。

（可选截图对比：`scripts/screenshot-diff.py`）

- [ ] **Step 5: Commit any residue / final**

若手动验证中发现参数需微调（`MAX_STEP` / `IDLE_CURSOR_TIMEOUT_MS` / 动画时长），直接改常量并 amend 最后一个相关 commit。无代码改动则跳过。

---

## Self-Review（计划作者自查）

- **Spec 覆盖**：吐字智能混合（Task 1-4 节奏器）✓；气泡淡入+8dp 上滑（Task 7）✓；末尾闪烁光标 + 停顿超时/完成隐藏（Task 3/8）✓；范围仅 app（全部文件在 `features/chat/`）✓；性能/测试（Task 9 + 节奏器单测）✓；可选优化（spec §9）本期不做、已在 spec 注明 ✓。
- **占位符**：无 TBD/TODO；每步含完整代码或精确 old/new 对照。
- **类型一致**：`StreamingPacingController.start/onTextSnapshot/reset/finish`、`onPaced(text, cursorVisible)`、`ChatMessageUi.showCursor`、`BlinkCursor()` 在各 Task 间签名一致。
- **可测性细化**：已注明 `delay` + `timeSource` 替代 `withFrameNanos`（纯 JVM 可测），与 spec 行为一致。
