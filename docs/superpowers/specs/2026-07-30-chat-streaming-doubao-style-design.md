# Chat 流式吐字豆包风格调整设计

- **日期**：2026-07-30
- **状态**：已批准（待实现）
- **范围**：仅 app 模块 `features/chat/`（重写 `StreamingPacingController` 节奏算法 + 调整 `BlinkCursor`）。不涉及 server / runtime-core。
- **背景**：流式 UX 已于 2026-07-29 实现（智能混合「帧驱动 step」节奏器 + 淡入上滑气泡 + 闪烁光标）。本次将其吐字节奏与光标调整为**豆包风格**——按字符时间轴播放（固定 45-60ms/字 + 标点/换行停顿 + 词块），而非 append 后端 token。

## 1. 豆包规格（目标参数）

| 维度 | 豆包参数 | 采用值 |
|---|---|---|
| 吐字速率 | 45-60ms/字 | `BASE_CHAR_MS = 50` |
| 词块 | 中文 1-3 字一跳 | 中文连串 **2 字一跳**（`CHUNK_CJK = 2`），标点/空格/换行单字成跳 |
| 标点停顿 | ，。？！后 +80-120ms | `PUNCT_MS = 100`（字符集 `，。？！：；,.?!:;`） |
| 换行/代码块停顿 | 前 +150-250ms | `LINE_MS = 200`（触发：上一字符为 `\n`，或处于 ``` 代码块内换行） |
| 光标周期 | 1000ms，opacity 1→0.3→1 ease-in-out | `tween(500)`，`initialValue=1f` / `targetValue=0.3f`，`FastOutSlowInEasing` |
| 尾字余闪 | 完成后再闪 2-3 次才停 | `TAIL_BLINK_MS = 2500`（≈2.5 个周期） |
| Markdown | 边流边解析 | 现有 `segmentStreamingMarkdown` 满足，不改 |

## 2. 目标 / 非目标

**目标**
- 节奏器从「帧驱动 step」重写为「按字符时间轴播放」：固定 50ms/字，词块推进，标点/换行处加停顿。网络快时也按此固定节奏播放（不加速蹦字），复刻豆包「前端按速率播放而非 append token」的质感。
- 光标调成 1000ms 周期、1→0.3 呼吸；完成（`finish`）后余闪 ~2.5s 再隐。

**非目标（本期简化，已与用户确认）**
- **首字 TTFT 380ms 超时显思考中**：保持现状（start 立即显 `STREAMING_THINKING_HINT`，首 token 到则覆盖）。豆包的「380ms 内不显」差别极小，性价比低。
- **纠错/截断 200-300ms 淡出重来**：暂不做（reset 直覆盖）。触发场景罕见且需 UI 层淡出动画，后续可选。
- **精确中文分词**：不引入分词依赖；词块用「边界切分 + 中文 2 字一跳」近似。

## 3. 设计

### 3.1 节奏器新算法（`StreamingPacingController` 重写）

**状态**：`latestFullText`、`shownLength`、`finished`（保留）；移除帧 step 相关（`MIN_STEP`/`MAX_STEP`/`BACKLOG_DIVISOR`/`FRAME_MS`）。

**`paceLoop`（按字符时间轴）**：
```
while (active && !finished):
  full = latestFullText
  target = full.length
  if target == 0: delay(50); continue          // 等首 token
  if shownLength < target:
    chunkEnd = nextChunkEnd(full, shownLength)  // 本跳推进到哪
    delayMs = nextDelay(full, shownLength, chunkEnd)
    delay(delayMs)
    shownLength = chunkEnd
    onPaced(full.substring(0, shownLength), cursorVisible = true)
  else:
    // 追平：交由 finish() 处理余闪；循环空转等待新 token 或 finish
    delay(50)
```

**`nextChunkEnd(full, start)`（词块推进，简化边界切分）**：
- `start` 处字符是**边界**（标点 / 空格 / `\n`）→ 推进 1 字（`start + 1`）
- 否则（中文/字母连串）→ 推进 `min(CHUNK_CJK, target - start)` 字（中文 2 字一跳）
- 不越过 `target`

**`nextDelay(full, start, end)`（本跳延迟）**：
- 基础：`BASE_CHAR_MS * (end - start)`（按本跳字数）
- 若 `start > 0` 且 `full[start-1]` ∈ 标点集 → `+ PUNCT_MS`
- 若 `start > 0` 且 `full[start-1] == '\n'` → `+ LINE_MS`（代码块 ``` 后通常紧跟 `\n`，已被覆盖，无需独立解析代码块状态）
- 首跳（`start == 0`）无附加停顿

> 注：延迟基于「上一跳末尾字符」——即显示完含标点的那跳后，下一跳前加停顿，符合豆包「标点后停顿」语义。

### 3.2 光标（`BlinkCursor` 调整）

- `animateFloat`：`initialValue = 1f`，`targetValue = 0.3f`，`infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse)`（周期 1000ms）
- 细竖线尺寸保持 `2.dp × 14.dp`

### 3.3 尾字余闪（`finish` 改）

```
fun finish():
  finished = true
  loopJob?.cancel()
  full = latestFullText
  if (full.isNotEmpty()):
    shownLength = full.length
    onPaced(full, cursorVisible = true)          // 保持光标
    scope.launch { delay(TAIL_BLINK_MS); onPaced(full, cursorVisible = false) }  // 余闪后隐
```
- `finish` 不再立即隐光标，而是余闪 ~2.5s 后隐。
- 余闪协程绑定 `scope`，新 `start` 时 `finished=false` + `loopJob.cancel()` 不影响已调度的余闪（余闪自然完成或被下一次流式覆盖）。

> **ViewModel 配合（必须）**：`finish()` 后 `onSuccess` 默认会立即清流式气泡（`_streamingMessage = null`），导致余闪光标看不到。需在 `onSuccess` 清除流式占位**前**先 `delay(TAIL_BLINK_MS)`，使余闪在流式气泡上可见；落库随之延后 ~2.5s，但 UI 上回复内容始终在（流式气泡 → 落库消息无缝替换，用户无感）。`onError` 不延迟（错误应即时反馈）。

### 3.4 常量（companion）

```kotlin
const val BASE_CHAR_MS = 50L
const val PUNCT_MS = 100L
const val LINE_MS = 200L
const val CHUNK_CJK = 2
const val TAIL_BLINK_MS = 2500L
const val IDLE_POLL_MS = 50L   // 追平后空转轮询间隔
```
（移除 `MIN_STEP` / `MAX_STEP` / `BACKLOG_DIVISOR` / `FRAME_MS` / `IDLE_CURSOR_TIMEOUT_MS`）

## 4. 数据流（不变）

```
SSE token → StreamingSyncChatModel.onTextSnapshot(全文)
  → ChatViewModel.pacingController.onTextSnapshot(全文)   ← 只写 latestFullText 缓冲
  → paceLoop 按字符时间轴：delay(50ms×字数 + 停顿) → shownLength 推进词块
  → onPaced(substring, cursor) → _streamingMessage.update
  → ChatScreen：ChatMessageItem（Markdown 边流渲染）+ BlinkCursor
```
ViewModel 接线（start / onTextSnapshot / reset / finish）**不变**；`reset()` 语义不变（清缓冲，ToolCallStarted 协调）。

## 5. 边界 / 错误处理

- `fullText` 回退/新轮（非连续扩展）→ `onTextSnapshot` 重置 `shownLength = 0`（既有逻辑保留）。
- `finish` 中途调用 → 立即把 `shownLength` 追平全文 + 启动余闪。
- 余闪期间用户发新消息（新 `start`）→ 新流式覆盖 `_streamingMessage`，旧余闪的 `onPaced` 对已替换的 message 无害（`update` 基于 current）。
- `target == 0`（思考中/reset 后）→ `paceLoop` 不 `onPaced`，UI 保持思考提示。
- 协程取消 → `finish` 兜底收尾。

## 6. 测试（`StreamingPacingControllerTest` 重写）

注入 `timeSource = { testScheduler.currentTime }`，`tickPacer` 改为 `advanceTimeBy + runCurrent`（既有基建）。用例：
- **基础速率**：灌入「abcdefgh」，验证每跳推进、虚拟时间累计 ≈ 字数 × 50ms。
- **中文词块**：灌入「你好世界」，验证每跳 2 字（shownLength: 2→4）。
- **标点停顿**：灌入「你好，世界」，验证「，」后下一跳前多 +100ms。
- **换行停顿**：灌入「你好\n世界」，验证 `\n` 后下一跳前多 +200ms。
- **finish 余闪**：finish 后 `cursorVisible` 仍 true，`advanceTimeBy(2500)` 后变 false。
- **回退重置**：既有用例保留。
- ViewModel 接线回归：既有 `ChatViewModelStreamingWiringTest` 保留（黑盒，不依赖节奏细节）。

## 7. 简化 / 后续候选

- 首字 TTFT 380ms 超时显思考中（start 后延迟显，首 token 到则不显）。
- 纠错/截断 200-300ms 淡出重来（需 `ChatMessageItem` 内容变化淡出动画）。
- 精确中文分词（引入 jieba 等）替代「2 字一跳」近似。
