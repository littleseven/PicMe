# 飞书 ReAct UI 操作后自动观察设计

> **任务来源**：用端上 UI 结构化描述辅助大模型确认飞书指令是否生效。
> **方案选择**：工具层自动追加观察（ReAct 工具执行后自动 dump 新 UI 状态并返回给 LLM）。
> **状态**：待实现。

---

## 1. 背景与问题

当前飞书远程控制链路已经通过 `RemoteReActAgent` + `PicMeToolService` 实现多轮 ReAct 交互。`PicMeToolService` 提供了 `get_screen_info` 工具，理论上 LLM 可以在操作后调用它来验证屏幕状态变化。

但实际运行中发现：LLM 不会主动、稳定地在每次 UI 操作后调用 `get_screen_info` 做验证。这导致：
- Agent 误以为指令已执行成功，实际上 UI 没变化。
- 对需要多步操作的复杂任务，错误会在后续步骤中放大。
- 用户收到 "✅ 已完成"，但设备端没有任何变化。

本设计通过在**工具层强制追加观察结果**，把“操作后的 UI 状态”自动注入 ReAct 上下文，消除对 LLM 自觉性的依赖。

---

## 2. 设计目标

1. **确定性验证**：每次 UI 改变操作后，LLM 必须看到新的屏幕状态。
2. **最小侵入**：尽量复用现有 `ViewHierarchyExtractor` 和 `PicMeToolService`，不引入新 Capability 或改 ReAct 循环。
3. **性能可控**：单次 dump 控制在 10–50ms，返回文本控制在 500–2000 tokens。
4. **向后兼容**：非 UI 类工具（相机控制、finish 等）行为不变。

---

## 3. 架构

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│  RemoteReActAgent│────→│   PicMeToolService   │────→│ LLM (next turn) │
│  (AiServices)    │     │  UI action tools     │     │                 │
└─────────────────┘     └──────────────────────┘     └─────────────────┘
                                │
                                ▼
                    ┌─────────────────────┐
                    │ ViewHierarchyExtractor│
                    │  extractSemanticSummary│
                    └─────────────────────┘
```

**核心改动**：在 `PicMeToolService` 内部，每个 UI 操作工具执行完成后，自动调用 `ViewHierarchyExtractor.extractSemanticSummary()` 生成当前屏幕摘要，并拼接到 tool result 中返回给 LLM。

---

## 4. 需要追加观察的工具

| 工具 | 是否追加 | 观察目的 |
|------|----------|----------|
| `click` | ✅ | 点击后页面/弹窗/高亮是否变化 |
| `scroll` | ✅ | 滚动后目标元素是否出现 |
| `input_text` | ✅ | 输入框内容是否更新 |
| `navigate_to` | ✅ | 目标页面是否加载 |
| `go_back` | ✅ | 是否成功返回上一页 |
| `get_screen_info` | ❌ | 本身就是观察 |
| `capture` / `flip_camera` / ... | ❌ | 相机 Capability 直接返回 OK/Error，UI 变化有限 |
| `finish` | ❌ | 终止工具 |

---

## 5. 返回格式

每个 UI 操作工具的返回字符串统一为两段式：

```text
Action: {操作结果描述}
Post-action screen state:
{ViewHierarchyExtractor.extractSemanticSummary() 输出}
```

示例（仅包含语义摘要，不含完整层级树）：

```text
Action: Clicked element with text: '搜索照片'
Post-action screen state:
=== 页面结构摘要 ===
页面标题: 相册
可交互元素 (5个):
  - EditText id=search_input hint="搜索照片" editable bounds=(325,166 810x156 ~17.6%,7.7%)
  - ImageView content_desc="清除搜索" clickable bounds=(1080,166 96x96 ~58.6%,7.7%)
  - RecyclerView scrollable bounds=(0,322 1440x2418 ~0.0%,15.0%)
  - ImageView text="照片 2026-07-02" clickable bounds=(..., ...)
关键状态:
  - 无
```

LLM 在收到该结果后，可以直接基于新旧状态对比判断操作是否生效，并决定下一步动作。

---

## 6. UI 稳定等待策略

操作后必须等待 UI 渲染完成再 dump，否则可能看到旧状态。

| 操作类型 | 等待策略 | 备注 |
|----------|----------|------|
| `click` | 在 UI 线程执行 click 后，通过 `Choreographer.postFrameCallback` 等待下一帧，或固定延迟 200–300ms | 优先用 frame callback，避免盲目等待 |
| `input_text` | 同 click，等待文本设置完成后再 dump | 可检查 EditText text 是否包含目标字符串 |
| `scroll` | `smoothScrollBy` 完成后等待 300ms，或检测滚动位置变化 | 若未提供目标元素，可只做固定延迟 |
| `navigate_to` | 操作完成后固定延迟 400–600ms 再 dump | 后续可扩展：通过 `NavigationCapability` 暴露导航完成回调 |
| `go_back` | 同 navigate_to | 固定延迟最简单可靠 |

所有等待逻辑都在工具内部完成，对 `RemoteReActAgent` 透明。

---

## 7. 性能与 Token 控制

### 7.1 性能

- `ViewHierarchyExtractor.extractSemanticSummary()` 遍历当前 Activity 的 decorView，端上耗时约 **10–50ms**。
- 每个 UI 操作触发一次，正常任务 3–5 步操作，总额外开销约 **30–250ms**。
- 所有 dump 操作都在当前线程同步完成，避免跨线程竞争。

### 7.2 Token

- `extractSemanticSummary` 当前输出包含“摘要 + 完整层级树”。
- 为控制 Token，给 `ViewHierarchyExtractor` 增加 `includeFullTree: Boolean = true` 参数（或新增 `extractCompactSummary` 方法）。工具层调用时传 `includeFullTree=false`，只取语义摘要部分。
- 摘要本身也做截断：
  - 文本长度限制 30 字符（已有 `MAX_TEXT_LENGTH=80`，可临时覆盖）。
  - 可交互元素最多返回 30 个。
  - 完整树仅在显式调用 `get_screen_info` 时返回。

预计单次追加观察返回文本 **500–1500 tokens**，对远程模型上下文压力可控。

---

## 8. 错误处理

| 场景 | 行为 |
|------|------|
| UI 操作成功，dump 成功 | 返回 Action 结果 + Post-action screen state |
| UI 操作成功，dump 失败 | 返回 Action 结果 + `Warning: failed to capture post-action screen state: ${reason}` |
| UI 操作失败 | 直接返回错误，不再 dump |
| `currentRootView == null` | 返回 `Error: No activity root view available`，不执行操作 |
| 等待超时 | 仍尝试 dump 当前状态，并在结果中标注 `Warning: UI settle timeout` |

---

## 9. Prompt 调整（可选但建议）

在 `RemoteReActAgentConfig.DEFAULT_SYSTEM_PROMPT` 中增加一句话：

```text
注意：当你调用 click/scroll/input_text/navigate_to/go_back 等 UI 操作工具后，工具返回中会包含操作后的屏幕状态摘要。请基于该摘要判断操作是否生效，再决定下一步行动或调用 finish。
```

这样 LLM 能更好地理解返回结构，避免重复调用 `get_screen_info`。

---

## 10. 实现范围

### 10.1 修改文件

| 文件 | 改动 |
|------|------|
| `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/PicMeToolService.kt` | UI 工具返回值追加 post-action screen state；新增内部辅助方法 |
| `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/RemoteReActAgentConfig.kt` | System prompt 增加对返回格式的说明 |
| `runtime-core/src/main/java/com/mamba/picme/agent/core/tool/perception/ViewHierarchyExtractor.kt` | 可选：增加更紧凑的摘要模式参数 |

### 10.2 新增内容

- `PicMeToolService.capturePostActionState(actionDescription: String): String`
- 工具内部等待 UI settle 的辅助方法
- 针对 `navigate_to`/`go_back` 的导航等待逻辑

### 10.3 不改动

- `RemoteReActAgent` 的 AiServices ReAct 循环
- `FeishuChannelHandler` 和 `RemoteCommandDispatcher`
- `CapabilityRegistry` 和 Capability 执行层

---

## 11. 验收标准 (AC)

| ID | 验收项 | 优先级 |
|----|--------|--------|
| AC-1 | `click` 工具返回包含 `Post-action screen state` 段落 | P0 |
| AC-2 | `navigate_to("camera")` 返回后，摘要中能识别相机页面关键元素 | P0 |
| AC-3 | 相机控制工具（如 `capture`）返回不包含额外 screen state | P1 |
| AC-4 | 单次追加观察耗时 < 100ms | P0 |
| AC-5 | 追加观察返回文本 < 2000 tokens（典型页面） | P0 |
| AC-6 | 飞书发送 "打开相册" → Agent 返回结果中包含相册页面已加载的证据 | P0 |

---

## 12. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 频繁 dump 导致 UI 线程短暂卡顿 | 中 | dump 只做 view 遍历，不做复杂计算；摘要模式减少输出 |
| 导航/动画期间 dump 拿到旧状态 | 高 | 使用 frame callback 或导航监听器等待稳定 |
| Token 增长导致远程模型成本上升 | 中 | 只返回语义摘要，不返回完整层级树 |
| LLM 仍然忽略返回的 screen state | 低 | Prompt 明确告知 LLM 返回格式和判断方式 |

---

## 13. 后续可扩展

如果本方案验证效果良好，未来可以进一步：
1. 增加**程序化断言层**：对常见操作（如导航成功）先用规则快速判断，失败再交给 LLM。
2. 增加**操作前后 diff**：只把变化的 UI 元素返回给 LLM，进一步降低 Token。
3. 把观察能力抽象为独立 `UiVerificationTool`，供 LLM 显式调用做复杂校验。

---

> **设计者**：CO + RD Agent
> **日期**：2026-07-02
> **关联文档**：`docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`
