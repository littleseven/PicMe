# Chat 流式输出体验优化设计

- **日期**：2026-07-29
- **状态**：已批准（待实现）
- **范围**：仅 app 模块 `features/chat/`（`ChatViewModel` + `ChatScreen`）。不涉及 server / runtime-core 的 SSE 透传链路。
- **关联提交**：`d9c9f9fc`（聊天流式输出）、`36b140f9`（server SSE 流式透传）。

## 1. 背景

聊天流式输出已打通（SSE 透传 + `StreamingSyncChatModel` 同步外观流式内核），但**渲染侧体验不够丝滑**，根因有二：

1. **吐字**：每个 SSE token 都执行 `_streamingMessage.value = …copy(content = 全文)` → 触发 `combine` 重建整个 List，且 `MarkdownText` 对整段累计文本重新做 markdown 解析/分段。链路上**没有任何帧节流**。后果两头不讨好：
   - 网络快时 `StateFlow` conflate 丢中间值 → 「一次蹦几字」的跳字；
   - markdown 较重时每 token 重组 → 掉帧卡顿。
2. **新气泡动画**：`LazyColumn` 的 `items(messages, key = { it.id })` 直接渲染 `ChatMessageItem`，全文件无任何 `animateItem` / `AnimatedVisibility` / `EnterTransition`。新消息（用户发出、assistant 流式气泡首次出现）瞬间 pop in，无淡入/滑入。

## 2. 目标 / 非目标

**目标**
- 吐字实现「智能混合」：网络快时节流到舒适节奏逐字平滑显示；网络慢/停顿时如实追赶不拖延。
- 新气泡出现有淡入 + 轻微上滑动画，作用于所有新进入列表的消息（用户 + AI）。
- 流式进行中文本末尾有闪烁打字光标；停顿超时或轮次完成时自动消失，以掩盖网络停顿的静止感。
- 性能满足 PERF 红线：60fps，低端机不卡。

**非目标（YAGNI）**
- 不改 server / runtime-core 的 SSE 透传与事件模型。
- 不做「流式中纯 Text 渲染前缀、完成后再切 MarkdownText」的性能优化（列为后续可选候选）。
- 不引入 Compose UI 自动化测试基建（项目现状无；动画/光标靠手动 + 截图验证）。

## 3. 决策摘要

| 决策点 | 选择 | 备选（未采纳） |
|---|---|---|
| 吐字策略 | **A：ViewModel 全文缓冲 + 帧节奏器（积压自适应步长）** | B：UI 层帧对齐采样（只去卡顿，非真正逐字）；C：固定速率 typewriter（快时也慢放，不符合智能混合） |
| 气泡动画 | **`AnimatedVisibility` + `MutableTransitionState`**（精确控制 8dp 上滑） | `Modifier.animateItem()`：最简，但 appear 上滑幅度不可自定义（默认全高度滑入，长气泡夸张） |
| 打字光标 | **末尾独立 `BlinkCursor` Composable**（`infiniteTransition` 闪烁） | 把光标字符塞进 MarkdownText content（每帧改 content，不优雅） |
| 吐字体验 | 智能混合（逐字平滑 + 不拖延） | — |
| 气泡风格 | 淡入 + 8dp 轻上滑，~180ms `FastOutSlowInEasing` | 弹性弹出 / 仅淡入 |

## 4. 数据流（更新后）

事件链不变，仅在「token → UI」之间插入一层节奏器缓冲：

```
SSE token → StreamingSyncChatModel.onTextSnapshot(全文)
  → RemoteReActAgent callback → ChatViewModel.pacingController.onTextSnapshot(全文)   ← 只写缓冲，立即返回
  → 帧节奏协程每 tick：substring(0, shownLen) + 计算光标可见性
  → _streamingMessage.update { copy(content = pacedText, showCursor = …) }
  → combine → displayMessages → ChatScreen
  → LazyColumn item：AnimatedVisibility(淡入+8dp上滑)
     ChatMessageItem：MarkdownText + BlinkCursor(流式中)
```

## 5. 组件设计

### 5.1 `StreamingPacingController`（节奏器，新建）

单一职责的纯逻辑类，位于 `features/chat/streaming/StreamingPacingController.kt`。设计为可单测（注入 `MonotonicFrameClock` 与协程作用域）。

**接口**
```kotlin
class StreamingPacingController(
    scope: CoroutineScope,
    clock: MonotonicFrameClock,
    private val onPaced: (text: String, cursorVisible: Boolean) -> Unit,
)

/** 流式开始时调用：重置缓冲、启动节奏循环。 */
fun start()
/** 来自事件链的全文快照：只更新缓冲与 lastChangedAt，立即返回，不触发 UI。 */
fun onTextSnapshot(fullText: String)
/** 轮次完成 / 结束 / 取消收尾：追平全文、隐藏光标、发最终文本、停循环。 */
fun finish()
```

**内部状态**：`latestFullText`、`shownLength`、`lastChangedAt`（全文最后变化时间）。

**节奏循环**（一个协程，`withFrameNanos` 驱动，~60fps，每个 tick）：
- `target = latestFullText.length`
- 若 `shownLength < target`：
  - `backlog = target - shownLength`
  - `step = clamp(backlog / k, MIN_STEP, MAX_STEP)`
  - `shownLength = min(target, shownLength + step)`
  - 发出 `onPaced(latestFullText.substring(0, shownLength), cursorVisible = true)`
- 若 `shownLength == target`（已追平）：不发新文本，维持光标；`now - lastChangedAt > IDLE_CURSOR_TIMEOUT` → `onPaced(全文不变, cursorVisible = false)`
- `finish()`：`shownLength = target`，`cursorVisible = false`，发最终全文，停循环。

**自适应语义**：积压大→步长放大加速追赶（仍逐字不蹦，保证平滑）；无积压→直接追平如实显示（不拖延）。

**参数（集中为常量，便于调优）**

| 参数 | 值 | 含义 |
|---|---|---|
| `MIN_STEP` | 1 | 最慢逐字 |
| `MAX_STEP` | 6 | 积压大时每帧推进上限（保证永远追得上） |
| `k` | 8 | 积压每 8 字 +1 步长（积压 48 字时封顶 6） |
| `IDLE_CURSOR_TIMEOUT` | 1200 ms | 全文无变化后光标隐藏 |
| 节奏 | 跟帧（~60fps） | 低端机掉帧→按实际帧率，step 自适应，不会更卡 |

**边界**
- `fullText` 变短/回退（异常情况）→ `shownLength = fullText.length` 重置。
- 协程取消（用户切走 / 新消息打断）→ 走 `finish()` 收尾，追平已收全文，避免半截残留。

### 5.2 `ChatViewModel` 接线

- `onTextSnapshot` 事件回调 → `pacingController.onTextSnapshot(event.text)`（不再直接 `copy(content = …)`）。
- 流式开始（`streamingId` 创建处）→ `pacingController.start()`。
- `onRoundFinished` / `onSuccess` / `onError` → `pacingController.finish()`；落库等既有逻辑不变。
- `onPaced` 回调：`_streamingMessage.update { it?.copy(content = pacedText, showCursor = cursorVisible) }`。
- **ToolCall 状态更新**（现有 copy toolCall 字段的路径）仍走原路径直写，**不进节奏器**（状态切换非高频文本）。文本与 toolCall 两条路径都写同一 `_streamingMessage`，故一律用 `MutableStateFlow.update { }` 避免 lost update。
- `ChatMessageUi` 新增字段 `showCursor: Boolean = false`。
- 节奏器生命周期绑定 `viewModelScope`（或当前流式 job），ViewModel 销毁时随协程作用域取消。

### 5.3 `ChatScreen` 动画 + 光标

**新气泡动画**：用 `AnimatedVisibility` + `MutableTransitionState(initiallyVisible = false)` 包裹每个 LazyColumn item 内容，首次组合后将 `visible` 置 true 触发单次 appear：

```kotlin
val density = LocalDensity.current
items(messages, key = { it.id }) { message ->
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(180, easing = FastOutSlowInEasing)) {
                with(density) { 8.dp.roundToPx() } // 固定 8dp 初始上移
            }
    ) {
        // 现有渲染：MediaResultsCarousel / ChartSvgCard / ChatMessageItem
    }
}
```

- `slideInVertically` 的 `initialOffsetY` lambda 接收的是 item 全高（px），返回初始竖向偏移。此处**忽略入参、返回固定 8dp**（经 `LocalDensity` 换算），与 item 高度无关，保证所有气泡都是「轻上滑」。
- 只对新进入列表的 item 播 appear 一次；已有 item 不重放。

**打字光标**：新增 `BlinkCursor` Composable（`infiniteTransition` 驱动 alpha 0→1 闪烁，周期 ~530ms）。在 `ChatMessageItem` 中，当 `message.isStreaming && message.content.isNotEmpty() && message.showCursor` 时，以 `Row(verticalAlignment = Alignment.Bottom)` 在文本块尾部渲染：

```kotlin
Row(verticalAlignment = Alignment.Bottom) {
    // 现有 MarkdownText / segmentStreamingMarkdown 渲染（Modifier.weight(1f)）
    if (showCursor) BlinkCursor()
}
```

- 独立 Composable，不把光标字符塞进 MarkdownText content（避免每帧改 content 触发额外解析）。

## 6. 错误处理 / 边界

- 流式中途 `onError` → `finish()` 追平已收到的 `latestFullText`、隐藏光标、走既有落库/错误提示。
- 用户切走 / 新消息打断 → 节奏器协程取消 → `finish()` 收尾。
- 非流式历史消息：不启动节奏器、无光标、无 appear 重放（`AnimatedVisibility` 已置 true 不再触发）。
- Markdown 半截语法（如未闭合 ``` 代码块）→ 沿用现有 `segmentStreamingMarkdown` 表格防抖逻辑，不改。

## 7. 性能

- 重组频率从「每 token 一次」降到「每帧一次」且帧对齐；substring 前缀每帧一次 Markdown 解析，60fps 可接受。
- 低端机掉帧时 step 自适应加快追赶，不会更卡（只是少几帧）。
- 气泡淡入 180ms 属视觉过渡，不影响交互响应（点击/输入仍即时），符合 PERF < 100ms 红线。

## 8. 测试策略

- **`StreamingPacingController` 单测**（纯 JVM，注入假 `MonotonicFrameClock` 手动 tick）：
  - 积压自适应：一次灌入 100 字 → 验证每帧 step 递增且 ≤ `MAX_STEP`，最终追平。
  - 无积压如实：逐字灌入 → `shownLength` 紧跟。
  - 停顿光标超时：全文不变 > `IDLE_CURSOR_TIMEOUT` → `cursorVisible = false`。
  - `finish()` 追平 + 隐藏光标。
  - 协程取消收尾。
- **`ChatViewModel`**：伪 `StreamingPacingController` 验证接线（`onTextSnapshot` 不直写 `_streamingMessage`；`finish` 追平；toolCall 状态不丢）。
- **动画 / 光标**：手动 + 截图验证（项目无 Compose UI 测试基建，不强求自动化）。

## 9. 可选优化（后续候选，本期不做）

- 流式中用纯 `Text` 渲染前缀、完成后再切 `MarkdownText`，进一步降低每帧解析开销。待本期上线后视 profiler 数据决定是否采纳。
