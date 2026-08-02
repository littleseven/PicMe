# AI 工程师气泡可读性治理：代码折叠 + 截断标识 + 源头约束

> **日期**：2026-08-02
> **状态**：已确认，待实施
> **范围**：`app/`（agent 气泡渲染 + 截断标识 + 继续按钮）、`scripts/claude-tunnel/gateway/`（源头 prompt + 截断信号）
> **关联**：`2026-07-31-claude-tunnel-chat-design.md`（claude-tunnel 主体设计，本设计为其气泡 UX 治理）；`2026-07-30-chat-streaming-doubao-style-design.md`（普通远程 chat 流式，**本设计不碰**）

## 1. 背景与问题

claude-tunnel「AI 工程师」模式上线后，真机使用暴露两个可读性问题（用户反馈，2026-08-02）：

1. **大段代码/调用日志难读**：Claude（GLM 后端）在 `assistant_text` 里整段粘贴源码、构建/命令日志。这些文本无任何截断，经 `MarkdownText` 整段渲染成代码块，在手机气泡里铺成几十上百行，无法快速阅读。
2. **气泡展示不全**：用户反馈"返回太多超了限"，实测有两种根因（用户确认"两种都有"）：
   - **回答中途断了**：网关 `CT_PHASE_TIMEOUT=300s`（`server.py:157`）或 `CT_MAX_TURNS=20`（`server.py:15`）硬收尾，SSE 流被切断，回答停在半句。
   - **气泡太长装不下**：内容（主要是代码块）过长，单条气泡撑得极高，阅读困难。

### 1.1 现状代码定位

| 关注点 | 位置 | 现状 |
|---|---|---|
| 气泡文本来源 | `ClaudeAgentState.text`（`ClaudeAgentRenderer.kt`） | 由网关 `assistant_text` delta 累积，**全程无长度截断** |
| 气泡渲染 | `ChatScreen.kt:1111` 起 | 流式走 `segmentStreamingMarkdown`（仅切 TABLE/MARKDOWN）；最终态 `ChatScreen.kt:1144` 直接 `MarkdownText(displayText)` **不分段** |
| markdown 库 | `compose-markdown` 0.5.4（`dev.jeziellago`） | AndroidView + Markwon 包装，**单个 TextView 渲染整段**；无"单代码块自定义 Composable"插槽；有 `maxLines`+`enableTextOverflow` |
| 工具结果摘要 | `claude_events.py:_summarize` | tool_result **已裁成 300 字**——工具日志本身不灌屏；问题文本来自 `assistant_text`（不受此限） |
| 截断硬闸 | `server.py:157`（phase timeout）/ `:15`（max-turns） | 超时发 `error` 事件（`:178`）；max-turns 由 claude `result` 自然结束 |

## 2. 选型依据（为何方案 A）

针对"长内容难读"，三方案：

| | A. 显示层兜底 + 源头轻约束 | B. 源头硬约束为主 | C. 显示层丰富（全屏查看器） |
|---|---|---|---|
| 主防线 | 显示层（不信任模型自控） | 源头（prompt + 硬截流） | 显示层 + 全屏代码页 |
| 长内容能否读 | ✅ 无论 Claude 输出什么 | ⚠️ 模型常无视 prompt；硬截流切断代码中段更难读 | ✅✅ 最佳 |
| 工作量 | 中 | 中（但效果不稳） | 大（多一页面/组件） |

用户决策（2026-08-02）：**"两层都做"**——源头降频 + 显示层兜底。本设计选 **A**：显示层做可靠兜底，源头 prompt 降频，截断给清晰标识 + 续写。C 的全屏代码查看器列二期，等 A 上线后看是否真需要。

> 关键工程结论：`compose-markdown` 0.5.4 不支持单代码块自定义渲染（已提取 AAR 验证 API）。正确做法是**复用仓库已有的分段机制**（`segmentStreamingMarkdown` 已为表格分段），把代码块也切成独立段，用自定义 `CodeBlock` 组件渲染。与现有表格处理方式一致，不依赖库私有 API。

## 3. 设计

### 3.1 范围

只治 **claude-tunnel「AI 工程师」气泡**（`ChatMessageUi.claudeAgent != null` 的消息）。普通远程 chat 气泡（OpenAI 流式）不动。

### 3.2 显示层（核心，app）

**(a) 代码块分段折叠**

扩展分段器，新增 `CODE` 段类型（与现有 `TABLE`/`MARKDOWN` 并列）。新组件 `CodeBlock(text, lang)`：
- **默认折叠**：显示前 **12 行** + 「展开（共 N 行）」；点击展开全部，再点「收起」。
- **横向滚动**：长行不换行撑屏（`horizontalScroll`）。
- **复制**：右上角复制图标，长按亦可；复制后短暂显「已复制」。
- **样式**：等宽字体 + 浅底色块。MVP **不做语法高亮**（与库的 Markwon 高亮解耦，避免再依赖它；二期可加）。

**(b) 统一流式/最终渲染**

当前最终态直接 `MarkdownText(displayText)`（`ChatScreen.kt:1144`）不分段，流式态才走分段器。改为**两者都走分段器**：散文段 `MarkdownText`、代码段 `CodeBlock`、表格段维持现状。顺带消除"落库后表格才一次性定型"的流式/最终不一致。

**(c) 不做整气泡硬高度盖板**

代码块逐个折叠后，"过长"主因（大段代码）已被消除；散文极少爆量。整气泡高度盖板列 YAGNI，真出现再加。

### 3.3 源头层（网关，降频）

扩写 `APP_TOOL_SYSTEM_PROMPT`（`server.py:44`），追加输出约束：
- 结论先行；用要点 + 关键代码片段回答，**不要整段粘贴源文件或完整构建/日志**。
- 必须展示代码时，只贴**关键 ≤30 行片段**并注明文件位置。
- 日志只摘录关键行（报错行 + 上下文），不全量。
- 单次正文控制在手机可读量（约 ≤800 字）。

> 这是"降频"——模型不一定每次都听，所以 §3.2 显示层兜底仍是主防线。

### 3.4 截断治理（协议信号 + 标识 + 继续按钮）

**网关侧判定**（网关是 SSOT，持有 `MAX_TURNS`/timeout，app 不猜 env 值）：
- **phase_timeout**：`CT_PHASE_TIMEOUT` 触发的 `error`（`server.py:178`）→ 加 `truncated:true, reason:"phase_timeout"`。
- **max_turns**：claude_events.py 的 `result` 分支（`:42-46`）拿到 `num_turns`；server.py 的 pump 转发 `done` 时若 `turns >= MAX_TURNS(20)` → 加 `truncated:true, reason:"max_turns"`（pump 拥有 MAX_TURNS，判定放它这里）。

**app 侧**：
- `ClaudeAgentState` 加 `truncatedReason: String?`（null=未截断），进 `toJson`/`fromJson` 持久化。
- `ClaudeEvent.Done` 从空对象扩为 `Done(turns, truncated, reason)`；`ClaudeSseParser` 解析 `done`/`error` 的新可选字段。
- Renderer 折叠：截断类事件 → 置 `truncatedReason`（**不**再当红色 ⚠️ 错误灌进正文）；非截断 error → 维持现有 ⚠️ 追加（回归不破）。
- **截断原因粘滞**：`truncatedReason` 一旦置位，后续事件**只设不清**（`Done(truncated=false)` 不清空）。防 phase-timeout 后 pump 在 `server.py:183` 补发的兜底 `done:{}` 把刚置的原因擦掉（实现陷阱）。
- 气泡底部：`truncatedReason != null` 时显示「ⓘ 回答较长已截断（达最大轮数 / 超时）」+ **「继续」按钮**。
- 「继续」：用同一 `sid` 发"继续"二字（复用现有 `--resume` 多轮链路，零新通道）。max_turns 是干净截断，继续必可靠；phase_timeout 被 kill 的进程 resume 行为见 §9 待验证假设 1。

## 4. 改动点清单（文件级）

| 模块 | 文件 | 改动 |
|---|---|---|
| 网关 | `scripts/claude-tunnel/gateway/server.py` | `APP_TOOL_SYSTEM_PROMPT` 追加约束；pump 给 done/error 注入 `truncated`+`reason` |
| 网关 | `scripts/claude-tunnel/gateway/claude_events.py` | `result`→`done` 携带 `turns`（已现），无需改；注释说明 pump 负责 truncated 判定 |
| app | `data/remote/picme/ClaudeChatClient.kt` | `ClaudeEvent.Done` 扩字段；`ClaudeSseParser` 解析 done/error 的 `truncated`/`reason` |
| app | `features/chat/ClaudeAgentRenderer.kt` | `ClaudeAgentState.truncatedReason` + `toJson`/`fromJson`；fold 处理截断类 Done/Error |
| app | `features/chat/ChatScreen.kt` | 分段器加 `CODE` 段；新 `CodeBlock` 组件；最终态改走分段器；气泡底部截断标识 + 继续按钮回调 |
| app | `features/chat/ChatViewModel.kt` | `onClaudeContinue(msgId)` → 用同 sid 发"继续"；流式 collect 透传新字段 |
| app 资源 | `values/`、`values-zh-rCN/`、`values-zh-rTW/` `strings.xml` | 新增文案（见 §7） |

## 5. 事件协议改动（§6，向后兼容，纯加可选字段）

| 事件 | 现状 | 改动 |
|---|---|---|
| `done` | `{turns}` | 加可选 `truncated:bool` + `reason:"max_turns"\|"phase_timeout"` |
| `error` | `{message}` | 加可选 `truncated:bool` + `reason`（phase_timeout 走 error 路径） |

老 app（不识别新字段）行为不变：done 照常收尾，error 照常显 ⚠️。新 app 识别后改显截断标识。

## 6. 数据结构改动（app）

```kotlin
// ClaudeAgentState：加截断原因
data class ClaudeAgentState(
    val text: String = "",
    val steps: List<ClaudeStepUi> = emptyList(),
    val hasFileChange: Boolean = false,
    val truncatedReason: String? = null, // 新增：null=未截断
)

// ClaudeEvent：Done 扩字段，Error 加 truncated/reason
sealed interface ClaudeEvent {
    data class Done(val turns: Int, val truncated: Boolean, val reason: String?) : ClaudeEvent
    data class Error(val message: String, val truncated: Boolean, val reason: String?) : ClaudeEvent
    // 其余不变
}

// 分段器：新增 CODE 段
enum class StreamSegmentType { MARKDOWN, TABLE, CODE }
```

## 7. i18n（三语同步，强制）

新增文案（`values/` EN 默认 + `values-zh-rCN/` + `values-zh-rTW/`）：
- 代码块：展开 / 收起 / 复制 / 已复制 / 共 N 行
- 截断：回答较长已截断 /（达最大轮数）/（超时）/ 继续

## 8. 红线

- **ADR-008 不受影响**：本设计无媒体介入；源头 prompt 不外泄隐私（仅约束输出风格）；workdir 仍是公开源码仓。
- **[I18N]**：§7 文案三语同步。

## 9. 待验证假设（实现首日确认）

1. **被 phase-timeout kill 的 claude 进程能否 `--resume`**：可能从上一完成轮续（略重复）。影响"继续"体验，不阻塞 MVP（max_turns 干净截断，继续必可靠）。
2. **`--max-turns` 触顶时 result 的 `num_turns` 是否恰好 == 20**：判定依据。若不是，改用其他信号（如 claude 是否发专门事件）。
3. **分段后散文段单独 `MarkdownText` 与整段视觉一致性**：真机比对表格/链接/列表渲染是否一致。

## 10. 测试

沿用现有"胶水单测 + E2E 人工"诚实声明：
- **网关**（pytest，扩 `test_server.py`）：pump 对 `turns>=MAX_TURNS` 的 done 注入 `truncated`；phase_timeout error 注入 `truncated`；现有翻译用例不破。
- **app JVM**（扩 `ClaudeAgentRendererTest`/SSE 解析测试）：Done(truncated)→置 reason；Error(truncated=true)→置 reason 且不 ⚠️ 灌正文；Error(truncated=false)→维持 ⚠️（回归）；分段器把围栏代码块切成 CODE 段。
- **app androidTest**（按本仓惯例，Compose 测试放 androidTest）：CodeBlock 默认折叠显 ≤12 行 + 展开按钮；展开显全量；复制生效。
- **E2E**：人工跑真机→KimiClaw→GLM，触发长回答看折叠 + 截断标识 + 继续。

## 11. MVP 边界

**纳入**：代码块折叠 + 复制 + 横滚；流式/最终渲染统一走分段；源头 prompt；协议 `truncated` 信号；截断标识 + 继续按钮。
**二期（out）**：CodeBlock 语法高亮；全屏代码查看器（方案 C）；整气泡高度盖板（YAGNI）；GLM 成本额度池。

## 12. 验收标准

- [ ] 代码块默认折叠 ≤12 行 + 「展开/收起」；横向可滚；复制生效（androidTest）。
- [ ] 流式与最终渲染都走分段器；表格/代码块在两态下一致（真机比对）。
- [ ] 网关 prompt 追加简洁约束；pump 对 max_turns done / phase_timeout error 注入 `truncated`+`reason`（pytest）。
- [ ] app 解析 done/error 新字段；截断时显标识 + 继续按钮，**不**把截断当 ⚠️ 灌正文；非截断 error 维持 ⚠️（回归）（JVM 测试）。
- [ ] 「继续」用同 sid 发"继续"，走 `--resume` 多轮（E2E）。
- [ ] 三语文案同步；编译通过（`:app:assembleDebug` + 网关 pytest）无新增错误。
- [ ] E2E：真机触发长回答 → 代码折叠可展开 + 截断标识 + 继续续写，全链路。
