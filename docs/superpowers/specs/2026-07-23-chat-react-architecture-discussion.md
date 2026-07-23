# chat ReAct 架构讨论记录

**日期**：2026-07-23
**状态**：讨论中，部分已实现，待后续决策

---

## 背景

PoLang chat 页从旧文本协议（`LocalCommandParser`：LLM 输出 JSON method+params → 解析为 `AgentCommand` → Capability dispatch → UI 渲染）切到标准 ReAct（langchain4j AiServices tool_calls），以支持 `run_gallery_script`（LLM 生成 JS → 端侧 Rhino 执行 → 相册盘点报表）。

切换过程中发现一系列架构问题，引发深入讨论。本文记录讨论脉络 + 待决策项。

---

## 讨论脉络

### 1. B 方案：chat 切 ReAct（已实现）

chat 远程从 `streamChatRemote`（文本协议 + `LocalCommandParser`）切到 `processChatReAct`（标准 ReAct tool_calls）。实现：`ChatToolService`（~24 chat 能力 @Tool）+ `RemoteReActAgent` 注入 toolService + `AgentConfigurator.getChatAgent` + `AgentOrchestrator.processChatReAct` + `streamChatReAct`。

### 2. callTool 修复

langchain4j AiServices 的 `tryInvokeTool` 期望 tool 有 `callTool(toolName, argsJson)` 方法（`PoLangToolService` 有，`ChatToolService` 缺）→ fallback 到 `tryInvokeByMethodName`（不支持带参 → "Wrong number of arguments; expected N, got 0"）。修复：ChatToolService 加 `callTool`。

### 3. IIFE 修复

LLM 生成的 JS 含顶层 `return`（`var s=...; return {...}`），Rhino `evaluateString`（脚本模式）不允许顶层 return → "invalid return"。修复：`onRunScript` 包 IIFE `(function(){ <code> })()`。

### 4. 搜索卡片丢失（核心问题）

ReAct 的 `dispatchCommand` 把 `AgentAction.MediaResults`（搜索结果数据）转成 "OK" 字符串给 LLM，**丢失了 UI 渲染**（搜索横滑卡片不出现）。同理编辑跳转丢失。

### 5. 用户洞察：tool 结果应驱动 UI

- tool 是否正确执行，取决于 **UI 是否正确渲染**（UI 是 ground truth，而非 dispatchCommand 返回的 "OK"）。
- 旧文本协议"提前渲染"（命令解析阶段就渲染，未确认执行结果）不正确。
- 正确架构：**tool 执行结果驱动 UI 渲染**——执行 → 确认结果 → 渲染。

### 6. Agent-driven vs LLM-driven ReAct

- **langchain4j AiServices（LLM-driven）**：LLM 当大脑（决策者），每步远程调用看 observation → 决定下一步。手机端 loop 当手（执行者）。
- **用户主张（Agent-driven）**：Agent 做决策（Think），LLM 做工具。Agent 本地掌控 Observe→Think→Act 循环。LLM 降为 Act 步骤中按需调用的工具（生成 JS / 总结 / 意图理解）。
- Agent-driven 优势：快（少远程调用）、省（少 token）、可控（可测试）、UI 验证天然集成、隐私（observation 不外传）。
- LLM-driven 优势：灵活（LLM 自由推理）、实现简单（AiServices 现成）、错误恢复（LLM 自主修正）。
- **Agent 的 Think 方式**是关键决策（规则/LLM plan/每步 LLM），决定灵活性 vs 复杂度的平衡。

### 7. chat 定位讨论

- chat 适合：分析/盘点/统计/问答（LLM 推理 + JS 计算 + 文本报表）。
- chat 不适合（需沉浸式 UI）：搜索浏览（网格）、管理操作（批量选择）、编辑（全屏编辑器）。
- **用户决策**：chat 作为超级入口，具备搜索 + 编辑 + 分析能力。

### 8. 纯 tool_calls 达到 UI 效果（当前方案）

**tool 双通道输出**：同一个 tool 调用同时产出 UI 渲染信号（AgentAction → ChatViewModel → 卡片/跳转）+ LLM observation（字符串）。不依赖命令格式（纯 tool_calls）。

---

## 当前实现方案（tool 双通道）

`ChatToolService.dispatchCommand` 改为双通道：
- **UI 通道**：`AgentAction`（MediaResults / 编辑跳转）→ emit 到 `SharedFlow` → ChatViewModel collect → 渲染卡片/跳转。
- **LLM 通道**：基于 `AgentAction` 生成有意义的 observation（"找到 N 张，已展示" / "已跳转编辑器"）→ 返回给 LLM。

observation **来自 UI 渲染结果**（而非 "OK"）——满足"UI 是 ground truth"理念。

---

## 待决策项

### 1. Agent-driven ReAct（未来重构）

Agent 自己掌控 Observe→Think→Act 循环（本地 Think），替代 AiServices 的 LLM-driven 循环。需自己实现 ReAct 循环 + Think 逻辑 + LLM 工具调用 + UI 验证。

当前先用双通道（在 AiServices 框架内解决 UI），Agent-driven 作为后续重构。

### 2. Agent Think 方式

- **规则/意图分类**（关键词 → 固定 task 序列）：最快最省，新意图需加规则。
- **LLM 一次性 plan**（远程 LLM 生成 task plan → Agent 本地执行）：灵活 + Agent 掌控循环。
- **每步 LLM Think + UI 验证拦截**：保留 LLM 灵活性 + 增加 UI 验证层，但每步仍远程。

未定。

### 3. chat vs 相册原生 UI

chat 作为超级入口（搜索 + 编辑 + 分析），但搜索结果在 chat 卡片 vs 相册网格——哪个体验更好？待产品评估。

### 4. observation 内容策略

observation 给 LLM 多详细？
- 简略（"找到 N 张"）：省 token + 隐私（不回传完整数据）。
- 详细（完整数据 JSON）：LLM 推理更充分，但 token 消耗 + 隐私风险（相册数据回传远程）。

影响 token 消耗 + 隐私。未定。

### 5. 旧文本协议（LocalCommandParser）废弃

B 切了 ReAct（streamChatReAct 替代 streamChatRemote 调用），但 `streamChatRemote` + `LocalCommandParser` 代码仍在。双通道后旧路径是否保留（fallback）还是删除？未定。

---

## 已提交的代码（feat/js-engine-jsbridge 分支）

| commit | 内容 |
|---|---|
| `ef83cff6` | feat(chat): chat 远程切 ReAct tool_calls |
| `3c673635` | fix(chat): ChatToolService 加 callTool + 去 Kotlin 默认参数 |
| `9b67df5c` | fix(jsbridge): onRunScript IIFE 包裹（修 Rhino 顶层 return invalid） |

验证状态：盘点相册端到端跑通（run_gallery_script → JS 执行 → gallery.summary 往返 → Markdown 报表）。搜索卡片待双通道实现。
