# Chat 与 LLM 链路架构 Review 报告

- **Review 日期**：2026-07-27
- **Review 范围**：chat 主入口 + 本地/远程 LLM 推理链路（`:runtime-core` Agent Runtime、`:agent-core` langchain4j 适配层、`:app` chat 层）
- **代码基线**：`main` @ `eb11897f`（app v1.0.26）
- **Review 方法**：静态精读核心源码 + 调用链追踪（无运行时动态分析）
- **结论一句话**：功能可用，但链路存在 **一组可用性/并发硬伤**（P0-2 超时冻结 / P0-3 配置污染）、**大量重复与死代码**、以及 **结构性可维护性债务**。隐私方面，远程**文本**推理在决策1 下已合规，需把红线收紧为「**不上传图片/视频文件**」并加防回归守卫。治理总纲见 **§0（五条决策 + 治理建议）**，核心动作是「**链路隔离 + 本地模型收缩至相机 + 统一记忆 + 清理测试资产**」。

---

## 0. 架构决策（2026-07-28）与治理建议

用户基于本 review 的初步结论，已拍板 5 条架构决策。本节是当前文档的**权威行动基准**——下方 §4–§7 的发现仍作为事实观察保留，但其定级与处置以本节为准。

### 0.1 五条决策（原文转述）

| # | 决策 |
|---|---|
| **决策1** | PRIVACY 红线放宽为：**不上传用户图片、视频文件到远程大模型/推理服务器**（文本/元数据/相册摘要等可上远程推理）。飞书、Telegram 等用户自配置 IM 通道**回传图片/视频给用户本人不属红线**。 |
| **决策2** | 本地 LLM 能力与远程差距大，**本地模型收缩至相机场景**；其余场景一律远程 LLM，本地模型不再为这些场景适配，以释放远程 LLM 能力。 |
| **决策3** | **远程链路与本地链路严格隔离**，不做交叉，减少耦合。 |
| **决策4** | 除 `ui-driver` 外，其他测试方法不再规模化运行，**代码及文档一律清理**。 |
| **决策5** | **双套记忆问题应解决**。 |

### 0.2 发现重评级与影响矩阵

| 发现 | 原级别 | 决策影响 | 新定级 / 动作 |
|---|---|---|---|
| P0-1 chat 绕过隐私分级 | 🔴 | **决策1**：远程文本推理合规；新红线=「不上传媒体文件」。已核实远程链路（`inference/remote/**`）零 `ImageContent`/multipart，`executeTask` 只收 text prompt → **现状合规** | 降为 🟠；动作=更新红线文案 + 加防回归守卫 + 审计媒体处理留端侧 |
| P0-2 全局串行+假取消+超时冻结 | 🔴 | 无直接影响 | 不变 🔴 |
| P0-3 全局配置污染/override 泄漏 | 🔴 | **决策3** | ✅ 止血已做（`updateRemoteRuntimeConfig`，commit b915626d，消除 override 回写持久 mode）；完整链路拆类隔离 ⏳ 后续（ADR-010） |
| P1-1 chat 无 token 流式 | 🟠 | 无直接影响（决策2 确认 chat=远程，流式只能向远程 SSE 要） | 不变 🟠 |
| P1-2 三入口+多段复制 | 🟠 | **决策2+决策3**：强制收敛入口、本地限定相机 | 决策强制修复 🟠 |
| P1-3 双记忆割裂/死写 | 🟠 | **决策5**：强制解决 | 决策强制修复 🟠 |
| P1-4 工具三处同步+日期冻结 | 🟠 | 决策3 隔离后 chat 工具集更聚焦，但三处同步本身仍存 | 不变 🟠 |
| P1-5 工具阻塞+超时错配 | 🟠 | 无直接影响 | 不变 🟠 |
| P1-6 本地引擎反模式 | 🟠 | **决策2**：本地引擎仅相机，影响面收敛到相机路径 | 范围缩小；相机内 runBlocking/scope 泄漏仍需修 |
| P2-1..P2-10 | 🟡 | 决策4 触发测试资产清理（新增条目，见 0.3-D4） | 多数不变；随隔离/收缩顺带清理 |

### 0.3 治理建议（按决策落到具体改动）

#### D1 · 决策1（隐私红线重定义 + 防回归）

- **改红线文案**：`CLAUDE.md` 的 `[PRIVACY]`、各 `AGENTS.md` 把「Cloud inference is strictly prohibited / 敏感处理 100% 端侧」改为「**禁止向远程大模型/推理服务器上传用户图片/视频文件；文本、元数据、相册聚合摘要可走远程推理**」。补一条 ADR 记录让步边界与可出境数据白名单。
- **⚠️ 飞书/Telegram 通道豁免（2026-07-28 用户澄清）**：红线约束的是**远程 LLM 推理链路**（chat → DeepSeek/PoLang server 等第三方模型）。飞书、Telegram 是**用户自配置的 IM 通道**，agent 经这些通道**回传图片/视频给用户本人**不属红线（用户自有通道，非模型推理上传）。即：媒体字节可经用户自配通道回传，但**不可作为多模态输入送进远程 LLM**。
- **加防回归守卫**（范围=远程 LLM 链路）：新增单测/静态规则，断言 `runtime-core/.../inference/remote/**`（RemoteReActAgent/AiServices/ChatModel）不引用 `ImageContent`/`generateWithImage`/multipart 上传；`RemoteReActAgent.executeTask` 入参仅为 text。**不限制** `domain/agent/remote/` 下的飞书/Telegram channel handler 传输媒体（那是回传通道）。
- **审计媒体处理留端侧**：确认 `ai_optimize`/`edit_image`/`adjust_image`/打标/人脸 全走本地 renderer/本地模型（当前已满足），在 `ChatToolService`/相关 capability 顶部注释标注「媒体字节不送远程 LLM」。
- **保留并强化** `PrivacyGuard`：用途从「拦截远程推理」转为「拦截媒体文件进入远程 LLM + 标注敏感字段」，删除已死的 `assertLocalOnly/isRemoteAllowed`（见 P2-1）。

#### D2 · 决策2（本地模型收缩至相机）

- **删 chat 本地分支**：`ChatViewModel` 的 `ChatModelOption.Local` 枚举值、`currentModelLabel()` 的 local 分支、`processAgentInput`（已是死代码）、chat 对 `getLastLocalGenerationMetrics` 的引用。
- **清非相机本地适配**：`features/gallery/components/AiChatPanel.kt`、`features/common/chat/AgentChatComponents.kt` 等非相机文件中对本地模型的分支与文案。
- **本地链路相机化**：`LocalLlmEngine`/`LocalInferencePipeline`/`IntentCache`/`LocalCommandParser`/`LocalPromptBuilder` 在包结构与文档上标注「相机专用」；理想下沉到相机相关模块或 `runtime-core/local/` 子包，编译期禁止 chat/gallery 引用。
- **AiAgentUseCase 收敛**：变为纯相机 Facade，删 `processInput` 的 REMOTE/FEISHU 重复分支、legacy 映射中所有非相机命令、`mapAgentActionToLegacyCommand` 等死代码。

#### D3 · 决策3（远程/本地严格隔离）

- **拆 `AgentOrchestrator`**：远程 chat 引擎（如 `RemoteChatEngine`）与本地相机 Agent（`LocalCameraAgent`）各自独立实例、**无共享可变单例配置**——这直接消除 P0-3（`configure(mode=getAgentMode())` 跨域回写）与 P1-2（三入口分叉）的根因。
- **依赖单向切断**：远程链路不再依赖 `LocalLlmEngine`/`IntentCache`/本地 JSON 协议；本地链路不再依赖远程 `RemoteModelConfig`/`ChatToolService`。
- **chat 不再每条消息重配 orchestrator**：模型选择以请求级参数传递，而非改全局单例。
- **modeOverride 栈退役或限定飞书域**：隔离后 chat 与飞书不再共用同一个 orchestrator，override 栈的泄漏面自然消失。

#### D4 · 决策4（清理非 ui-driver 测试）

- **删测试框架代码**：`app/src/main/java/com/mamba/picme/testing/**`（15 文件 / 4138 行：`AgentTestFramework`、`DataDrivenTestRunner`、`cases/`、`bridge/` 等）。
- **删 commands**：`.claude/commands/agent-test.md`、`qa-acceptance.md`（**保留** `ui-driver.md`）。
- **删/归档 scripts**：`scripts/agent-tester`、`scripts/regression-test.sh`、`scripts/tests/`。
- **删 docs**：`docs/06-QA/QA_EXECUTION_CHECKLIST.md`；同步更新 `.claude/CLAUDE.md` 命令索引（25 → 23）与 `CLAUDE.md`「Quality Toolchain / Useful Scripts」段、`testing/agent` 的 manifest/广播注册（`AndroidManifest.xml` 里若有 AgentTestActivity/Receiver 一并清）。
- ✅ **已确认：暂不清理**：`scripts/test_*.py` / `test_*.sh`（deepseek/mnn/florence 等模型评测脚本）属**模型实验**而非 QA 测试方法，不在决策4「测试方法」语义内 → **本轮保留不动**（2026-07-28 用户确认）。

#### D5 · 决策5（解决双套记忆）

- **chat 记忆单一来源**：统一用 `DataStoreChatMemory`（langchain4j ChatMemory）作为 chat 唯一对话记忆；**删除 `streamChat` 对 `MemoryManager.appendConversation` 的死写回**（`AgentOrchestrator.kt:463-468`，该写入 chat 从不读取）。
- **`MemoryManager` 限定相机**：与 D2 一致，成为相机本地路径专用；或抽象出统一 `ConversationMemory` 接口、两实现各服务一条隔离链路。
- **修两个数据正确性 bug**：`MemoryManager.appendConversation` 的 load→改→save 改为 DataStore `edit{}` 内原子 read-modify-write（解决并发丢更新）；`RemoteReActAgent.resetSession` 同步清内存 `cache`（`RemoteReActAgent.kt:313-317,413`）。
- **划清边界并文档化**：`MemoryContextProvider`（事实/人物关系快照）≠ 对话记忆；三者（对话记忆 / 事实记忆 / 人物关系）职责在 ADR 中写清，避免再次混淆。

### 0.4 治理执行顺序（替代原 §8）

治理分四波，**前两波对应决策落地、可立即开工**，后两波是可用性与技术债：

1. **波次 0 · 决策落地（立即，1 周内）**：D1 红线文案 + 防回归守卫；D4 清理测试资产；补 5 条决策的 ADR。
2. **波次 1 · 隔离与收缩（2–4 周）**：D3 拆分链路（解 P0-3 / P1-2 根因）+ D2 本地模型相机化（删 chat/gallery 本地残余）+ D5 统一记忆（解 P1-3、修两个 bug）。这一波完成后，远程链路独立、本地链路相机专用，耦合面大幅下降。
3. **波次 2 · 可用性止血（与波次 1 并行）**：P0-2（超时/取消/串行对齐）、P1-5（工具执行去 `GlobalScope`/`runBlocking`）、P1-1（真流式或阶段性反馈）、P1-4（工具规格单一来源 + 日期每轮动态拼）。
4. **波次 3 · 技术债（持续）**：P2-2 i18n 结构性整改（runtime-core 只产结构化结果）、P2-3 拆 God Object、P2-6 legacy 命令层退役、P2-7 本地 JSON 容错、P2-4 伪事务等。

> 原则：**波次 1 是枢纽**——决策3 的链路隔离会自然消解 P0-3 / P1-2 / P1-3 / P1-6 的一半问题，应优先投入；其余 P0/P1 在隔离后的新结构上再做，避免在旧耦合上反复打补丁。

---

## 1. 方法论与评级

| 级别 | 含义 |
|---|---|
| 🔴 **P0** | 触碰项目红线、或会导致功能不可用/数据错误的硬伤。优先修。 |
| 🟠 **P1** | 显著影响体验/可维护性/正确性概率较高，应在下个迭代内处理。 |
| 🟡 **P2** | 改进项/技术债，可排期渐进治理。 |

每条发现给出：**位置**（`file:line`）、**失败场景**、**建议**。

---

## 2. 架构总览（先对齐事实）

当前 chat 与 LLM 实际存在 **两条近乎独立的链路**，文档（CLAUDE.md / AGENT_ARCHITECTURE.md）把它们描述得比实现更统一。

### 2.1 Chat 链路（远程 ReAct，主入口）

```
ChatViewModel.sendMessage()
  └─ orchestrator.configure(...)            # 每次发消息都重配全局单例 ⚠
  └─ orchestrator.streamChat(onToken={_})   # 流式回调被丢弃
       └─ streamChatReAct()
            └─ processChatReAct()            # withTimeout(120s)
                 └─ configurator.getChatAgent()   # 单例缓存
                      └─ RemoteReActAgent         # 单线程 executor
                           └─ AiServices PoLangAssistant.chat()  # 同步阻塞，无流式
                                └─ tool_calls → ChatToolService.callTool()
                                     └─ dispatchCommand()  # GlobalScope.future{}.get(5s) 阻塞
                                          └─ CapabilityRegistry.dispatch(scene=CHAT)
```

**特征**：远程 OpenAI tool_calls 协议、AiServices 自动 ReAct 循环、`DataStoreChatMemory` 维护工具历史、同步一次性返回（无 token 流）。

### 2.2 相机 / Agent 控制链路（本地 JSON 协议）

```
GlobalAgentPanel / RemoteCommandDispatcher(飞书)
  └─ orchestrator.processUserInput()        # 旧入口（含重复分支）
  或 AiAgentUseCase.processInput()
       └─ orchestrator.processInputWithRouter()
            └─ LocalInferencePipeline.processInput()
                 ├─ IntentCache (L1)         # 仅相机用
                 ├─ PrivacyGuard.classify    # 仅此链路接隐私
                 └─ L2/L3/Full 三段本地推理   # 大量复制
                      └─ LocalLlmEngine.chat()  # runBlocking, maxTokens 128/256
                           └─ LocalCommandParser  # 手写 JSON 扫描
                                └─ CapabilityRegistry.dispatch()
```

**特征**：自定义 `method/params` JSON 数组协议、本地 Qwen3.5-2B(MNN)、`MemoryManager` 维护对话历史、L1 意图缓存。

> **关键观察**：两条链路各自有独立的记忆系统、独立的入口、独立的错误处理，几乎不共享代码。CLAUDE.md 把它们描述为"统一 AgentOrchestrator 编排"，实际 orchestrator 内部按入口分叉成三套（`processInputWithRouter` / `processUserInput` / `streamChat`）。

---

## 3. 组件清单（关键文件）

| 文件 | 行数 | 职责 | 备注 |
|---|---|---|---|
| `AgentOrchestrator.kt` | 1100 | 统一入口/三套分发/模型加载/记忆回写 | God Object 倾向 |
| `AgentConfigurator.kt` | 419 | 组件工厂 + 模式栈 + chat/飞书 agent 单例缓存 + chat system prompt | 含 67 行硬编码 prompt |
| `LocalInferencePipeline.kt` | 327 | 本地 L1/L2/L3/Full 路由 | 三段复制 |
| `LocalLlmEngine.kt` | 680 | MNN-LLM 封装/加载/推理/资源管理 | runBlocking 反模式 |
| `RemoteReActAgent.kt` | 432 | AiServices ReAct + DataStoreChatMemory | 单线程、假取消 |
| `ChatToolService.kt` | 494 | chat 场景 @Tool 面 + callTool 派发 | 三处同步 |
| `CapabilityRegistry.kt` | 511 | 能力注册/场景过滤/命令分发/批量子事务 | 回滚为 no-op |
| `MemoryManager.kt` | 332 | 本地路径对话记忆（Preferences JSON） | 非原子追加 |
| `IntentCache.kt` | 282 | L1 意图缓存（相机专用） | 中文模糊匹配 |
| `PrivacyGuard.kt` | 112 | 隐私分级/策略 | chat 链路未接入 |
| `RemoteModelFactory.kt` | 96 | 远程模型参数工厂 | maxTokens/重试不对称 |
| `AiAgentUseCase.kt` | 438 | 相机 Facade + legacy 命令映射 | 大量死代码 |
| `ChatViewModel.kt` | 2239 | chat UI 编排 + LLM 调用 + 状态机 | God Object |

---

## 4. P0 发现（红线 / 硬伤）

### 🟠 P0-1　Chat 远程链路绕过隐私分级（决策1 已放宽红线 → 降级，转为「防媒体文件出境」）

> **📌 决策1 更新（2026-07-28）**：PRIVACY 红线已放宽为「**不上传用户图片/视频文件**到服务器/大模型」。远程**文本**推理（含对话文本、相册摘要、搜索词）现属**合规**。已核实 `runtime-core/.../inference/remote/**` 不引用 `ImageContent`/`generateWithImage`/multipart，`RemoteReActAgent.executeTask` 入参仅为 text prompt → **当前代码对新红线合规**。本条由 🔴 降为 🟠；处置见 §0.3-D1：更新红线文案 + 加「远程只发文本」防回归守卫 + 审计媒体处理留端侧。下方原文保留作为历史观察。

**位置**
- `AgentOrchestrator.kt:445`（`streamChat`）→ `:477`（`streamChatReAct`）→ `:949`（`processChatReAct`）→ `RemoteReActAgent`，全程未调用 `PrivacyGuard.classify`。
- `PrivacyGuard.classify` 仅被 `LocalInferencePipeline.kt:74` 调用（即只有相机本地路径做分级）。
- `PrivacyGuard.assertLocalOnly()` / `isRemoteAllowed()`（`PrivacyGuard.kt:73,84`）**全项目零调用**——隐私策略守卫形同摆设。
- chat 的 `AgentContext` 还携带 `gallerySummary`、`recentSearchResults`、`lastUserImageUri`（`ChatViewModel.kt:707-714`）一并送入远程 LLM。

**失败场景**
- 用户在 chat 说"把这张照片里的人脸坐标发我"、"我家里地址是……记一下"、"这张截图里的身份证号是…"，内容**原样发往远程 DeepSeek/PoLang Server**，无任何分级拦截。CLAUDE.md 的 `[PRIVACY]` 红线（"All AI processing (face, OCR, classification) must be 100% on-device. Cloud inference is strictly prohibited."）在主入口上被静默突破。

**建议**
- 在 `streamChat` 入口对 `input` + `AgentContext` 中将随 prompt 上行的字段做整体隐私分级；`RESTRICTED` 级必须降级到本地模型或拒绝并提示。
- 把隐私检查做成 chat 与本地管线**共用的强制前置**，而非仅挂在本地管线。
- 删除或真正接线 `assertLocalOnly/isRemoteAllowed`；至少补单测固化分级用例。
- 即便产品决策已把 chat 默认改远程，也应**显式记录该红线让步**（ADR），并明确哪些字段允许出境。

---

### 🔴 P0-2　Chat 全局串行 + 假取消 + 超时冻结，单次故障可锁死聊天数分钟

**位置**
- `RemoteReActAgent.kt:91` 单线程 `executor`；`AgentConfigurator.kt:233` `cachedChatAgent` 单例缓存；`AgentOrchestrator.kt:968` `agent.isRunning()` 互斥 → 拒绝并发，第二个请求直接报"Agent 正在执行其他任务"。
- `RemoteReActAgent.cancel()`（`:215-217`）只置 `cancelled` flag，**不中断**阻塞中的 `assistant.chat()`；`cancelled` 仅在 catch 里用于区分上报（`:274`）。
- 远程 `maxRetries(2)` × `timeout(60s)` 最坏 **180s**（`RemoteModelFactory.kt:79-80`），远超外层 `processChatReAct` 的 `withTimeout(120_000L)`（`AgentOrchestrator.kt:952,976`）。

**失败场景**
- 网络抖动 → 外层 120s 超时 → 协程恢复并向用户报错，但底层 `executor` 线程仍在做 HTTP 重试（最长再 60s+），`isRunning` 持续为 true。**期间用户再发任何消息都被 `isRunning()` 拒绝**，聊天疑似"卡死"最长约 3 分钟。叠加 P0-1 的无流式，用户只看到"正在思考…"不动。

**建议**
- 短期：外层超时与底层重试/超时对齐（如外层 ≥ 内层×(retries+1)），并在超时分支 `agent.shutdown()` 重建（或换用可中断的 HTTP 客户端）。
- 中期：`RemoteReActAgent` 改用协程 `CoroutineScope` 取代 `Executors.newSingleThreadExecutor`，让 `cancel` 真正传播取消；或为 chat agent 做"每请求一个 agent / 有限并发池"而非全局单例 + 互斥。

---

### 🔴 P0-3　全局单例配置被反复重写，飞书模式覆盖会泄漏为持久模式

**位置**
- `ChatViewModel.sendMessage` **每次发消息**都 `orchestrator.configure(mode = orchestrator.getAgentMode(), …)`（`ChatViewModel.kt:725-731`）。
- `configure()` 把传入的 `mode` 写入**持久字段** `agentMode`（`AgentConfigurator.kt:119`），而 `getAgentMode()` 返回的是"栈顶覆盖 ?? 持久值"（`:143`）。
- `PoLangApplication` 多处同样以 `mode = orchestrator.getAgentMode()` 重配（`PoLangApplication.kt:392,441,455`）。

**失败场景**
- 飞书远程控制通过 `pushModeOverride(REMOTE)` 压栈（`RemoteCommandDispatcher`）。此时若 chat 发送一条消息（或任一 configure 触发），`getAgentMode()` 返回栈顶 REMOTE，被 `configure` 写进持久 `agentMode`。随后飞书 `popModeOverride()` 弹栈——但持久 `agentMode` 已被污染为 REMOTE，用户原本的 LOCAL 设置丢失且不会自愈。

**建议**
- `configure` 增加只更新"想要持久化的字段"的入口，**绝不**从 `getAgentMode()`（含临时覆盖）回写持久 mode。
- 调用方如需仅改 remoteConfig/preference，提供细粒度 setter，避免整包重配。
- chat 不应每条消息都重配全局 orchestrator；模型选择应在请求级参数传递。

---

## 5. P1 发现（重要）

### 🟠 P1-1　Chat 没有 token 流式（"假流式"）

**位置**：`streamChatReAct` 一次性 `onToken(summary)`（`AgentOrchestrator.kt:486`）；`PoLangAssistant.chat(): String` 同步整串返回（`RemoteReActAgent.kt:146,249`）；`ChatViewModel` 把 `onToken` 显式丢弃（`ChatViewModel.kt:746`），占位"正在思考…"全程不变（`:717-722`，注释自认远程"同步一次性返回"）；远程用同步模型因网关不支持 SSE（`AgentConfigurator.kt:198-202`）。

**影响**：多工具 ReAct 一轮可能数秒~数十秒，用户全程面对静态 spinner，体感"卡"。`streamChat` 的 `onToken: (String)->Unit` API 形同虚设，误导调用方。

**建议**：若网关最终支持 SSE，接入 `StreamingChatModel` 并真正逐 token 回调；短期至少把 ReAct 的 `onContent/onToolCall/onToolResult` 回调透传到 UI（"正在搜索相册…"→"正在画图…"），给用户阶段性反馈。同时把假流式 API 改名或标注，避免误用。

---

### 🟠 P1-2　三套入口 + 多段复制，语义分叉

**位置**
- 三个公开推理入口：`processInputWithRouter`（`:370`）、`processUserInput`（`:577`）、`streamChat`（`:445`），各自带模型加载、L1 缓存、记忆回写，逻辑重叠又细节不一。
- `processUserInput` 内 `LOCAL`（`:617-656`）与 `else`（`:667-705`，注释自认"REMOTE/FEISHU 统一走本地推理"）**整段复制**；`when` 每个分支都 `return`，导致 `:709` `handleInferenceResult(...)` 与其私有函数**不可达（死代码）**。
- `LocalInferencePipeline` 的 `routeToLocalL2`/`routeToLocalL3`/`routeToLocal`（`:139/:209/:289`）三段近乎相同，且 L2 与 L3 用的是**同一个** `buildL2SystemPrompt`（`:144,:214`），L3 只是换了响应解析。
- `AiAgentUseCase.processInput` 的 `LOCAL` 与 `REMOTE/FEISHU` 两个分支**完全相同**（`AiAgentUseCase.kt:193-215`）。

**影响**：维护时极易只改一处漏改另一处（例如记忆回写语义在 `streamChat` 是 await、在 `processInputWithRouter` 是 fire-and-forget——`AgentOrchestrator.kt:467 vs :780`），行为分叉且难测。

**建议**：收敛为单一内部 `infer(input, context)` 核心，入口仅做"本地/远程"策略选择；删除 `processUserInput` 的复制分支与不可达 `handleInferenceResult`；`LocalInferencePipeline` 三段抽公共 `runLocal(promptBuilder, parser)`。

---

### 🟠 P1-3　两套记忆系统割裂，且 chat 存在死写

**位置**
- 两套互不同步：`MemoryManager`（Preferences JSON，本地路径用）与 `DataStoreChatMemory`（langchain4j ChatMemory，远程 ReAct 用，`RemoteReActAgent.kt:331`）。
- `streamChat` 回写 `MemoryManager.appendConversation`（`AgentOrchestrator.kt:463-468`），但 chat ReAct 只读 `DataStoreChatMemory`，**不读 `MemoryManager`** → 该回写对 chat 是死写（`:458-462` 注释声称"ReAct 经 buildContextMessages 读历史"与实现不符）。
- `MemoryManager.appendConversation` 是 load→改→save 非原子（`MemoryManager.kt:129-138`），配合 `AgentOrchestrator` 的 fire-and-forget `backgroundScope.launch`（`:780`）→ 并发追加**丢更新**。
- `RemoteReActAgent.resetSession()` 只 `store.deleteMessages`，**不清内存 `cache`**（`:313-317` + `:413`）→ 重置后 `messages()` 仍返回旧缓存。

**建议**：统一记忆抽象（至少 chat 路径只用一套）；若保留双写，明确各自消费者并加一致性测试；`appendConversation` 改为 DataStore 原生 `edit{}` 内 read-modify-write；修复 `resetSession` 同步清 cache。

---

### 🟠 P1-4　工具定义三处同步，系统 prompt 硬编码且日期会冻结

**位置**
- 工具语义散落在三处需手动同步：`@Tool`/`@P` 注解（仅生成给 LLM 的规格）、`ChatToolService.callTool` 手写 when-switch（真正派发，`ChatToolService.kt:374-443`）、`chatSystemPrompt` 自然语言说明（`AgentConfigurator.kt:237-304`，67 行硬编码）。
- 系统日期在 agent **构建时**拼进 prompt（`AgentConfigurator.kt:389`），而 chat agent 是单例缓存 → 日期冻结在首次构建日；app 跨天/长期不杀进程，"去年""上个月"等相对时间计算会基于过期日期。

**影响**：新增/改名工具易漏 callTool 分支（落到 `else -> "Unknown tool"`）；prompt 与工具规格漂移会误导模型；跨天后时间相关查询出错。

**建议**：工具规格单一来源（注解生成 spec + 派发表 + prompt 段落均由同一元数据驱动）；日期改为每轮 `systemMessageProvider` 内动态拼接（已具备 provider 钩子，`:173`）。

---

### 🟠 P1-5　工具执行阻塞单线程，超时与真实操作错配

**位置**
- `dispatchCommand` 用 `GlobalScope.future{}.get(5, SECONDS)`（`ChatToolService.kt:450-454`）在 ReAct 单线程 executor 上**阻塞**；超时返回 `"Error: ..."` 但底层 `dispatch` 在 GlobalScope 继续跑（结构化并发泄漏）。
- 5s 与耗时操作不匹配：`delete_media` 走系统 MediaStore 授权框（注释 `:92-94`）、`ai_optimize`/`edit_image` 走渲染，常 >5s → 工具对 LLM 报 Error，而操作随后成功 → 用户看到"失败"却实际发生。
- `adjustImage` 用 `runBlocking { handler(...) }` **无超时**阻塞 ReAct 线程（`:179`），handler 挂起即冻结整个 chat。

**建议**：工具执行改为可取消的协程，超时语义与操作类型匹配（删除/渲染给更长时间或改为异步+回调上报）；移除 `GlobalScope` 与 `runBlocking`。

---

### 🟠 P1-6　本地引擎若干反模式与资源隐患

**位置**
- `LocalLlmEngine.chat()` 用 `runBlocking(modelDispatcher)`（`:213`）——suspend 体系内的阻塞反模式。
- 流式 `chat(request, handler)` 用 `CoroutineScope(modelDispatcher).launch{}`（`:315`）无主、不可外部取消、持有 `engineMutex` 整段生成 → 结构化并发违规。
- `maxTokens` 本地 `128/256`（`:227`）vs 远程 `4096`（`RemoteModelFactory.kt:78`）：同一"命令生成"任务预算差 32 倍，本地 128 token 易截断 → JSON 不完整 → 解析失败 → 回退全量 prompt 二次调用（`LocalInferencePipeline` L2→Full）。
- 内存压力下 `enqueueTrimMemory`/`enqueueUnload`/`onSafeUnload` 均用 `engineMutex.tryLock()`，失败即"跳过，等下次操作"（`:553,576,625`）→ 若持续繁忙，**释放被无限推迟**，OOM 风险。

**建议**：本地推理统一 `withContext(modelDispatcher)` + 可取消；流式改为接收外部 `CoroutineScope`/`Job`；本地 maxTokens 可配置并按任务（单命令/批量）区分；内存释放跳过时进入退避重试队列而非纯靠"下次操作"。

---

## 6. P2 发现（技术债 / 改进项）

### 🟡 P2-1　大量死代码
已确认无外部调用方：`ChatViewModel.processAgentInput`（`:898`）、`AgentOrchestrator.resolveRemoteConfig`（`:517`）、`AiAgentUseCase.mapAgentActionToLegacyCommand`（仅自递归，`:345`）、`CapabilityRegistry.findCapabilityForCommandName`（`:279`）、`processUserInput` 末尾不可达的 `handleInferenceResult`（`AgentOrchestrator.kt:709,1043`）、`AiAgentUseCase.forceRemoteMode`（`:93`）、`CODING_DEFAULT_BASE_URL`（`:436`）、`PrivacyGuard.assertLocalOnly/isRemoteAllowed`（`:73,84`）。建议清理。

### 🟡 P2-2　I18N 红线结构性违反
runtime-core（纯 Kotlin，**无法访问 `R.string`**）多处硬编码中文用户可见字符串：`CapabilityRegistry.kt:161,209,233`、`RemoteReActAgent.kt:298,301,304`、`ChatToolService.kt:462-470`；`ChatViewModel` 亦大量硬编码（`:784,855,930,984,989,1035,1038,1048-1065`）。与 CLAUDE.md `[I18N]` 红线冲突。**根因是分层**：用户可见文案产生在不该产生它的层。建议：runtime-core 只回传结构化结果/错误码，文案由 app 层本地化渲染。

### 🟡 P2-3　God Object
`ChatViewModel` 2239 行（含会话/消息/模型/反馈/编辑/JS/图片渲染多职责）、`AgentOrchestrator` 1100 行、`LocalCommandParser` 1002 行。建议按职责拆分（如 ChatViewModel 拆出 `ChatOrchestrator`/`ChatMemoryController`/`ChatActionRenderer`）。

### 🟡 P2-4　批量 atomic 是 no-op 空实现（伪事务）
`CapabilityRegistry.rollbackExecuted` 仅打日志（`:437-448`），但 `BatchExecute(atomic=true)` 对外承诺回滚。要么实现真实 undo 接口，要么去掉 `atomic` 语义避免误导。

### 🟡 P2-5　IntentCache 中文模糊匹配 + 非线程安全 + 范围有限
`levenshteinDistance` 按字符、阈值 ≤1，对短中文极易误匹配（如"去相机/去相册"仅差 1 字，`IntentCache.kt:98-115`）；`LinkedHashMap`+计数器非线程安全；且仅相机路径使用（chat 的 `streamChat` 不查不学），文档将其描述为通用 L1 偏夸大。建议：模糊匹配仅对英文/拼音生效或提高阈值；加并发保护；文档校正适用范围。

### 🟡 P2-6　双命令模型 + 95% 重复映射
存在 `AgentCommand`（新）与 `AiAgentCommand`（legacy）两套并行的命令类型，`AiAgentUseCase` 用两个几乎相同的 when（`mapAgentCommandToLegacy` `:268`、`mapAgentActionToLegacyCommand` `:345`）互转，且每加一个命令要两处改。建议完成迁移、消除 legacy 层。

### 🟡 P2-7　本地协议手写 JSON 扫描，脆弱
`LocalCommandParser` 自行做大括号深度匹配（`:79-114`）而非用 JSON 解析器；小模型 + 128 token 截断 → 不完整 JSON → 解析失败 → 回退全量。建议引入容错 JSON 提取（或限定模型输出为严格 JSON Schema），并评测回退率。

### 🟡 P2-8　用脆弱关键词匹配补偿 LLM 行为
`isRefusedSearchRequest`（`ChatViewModel.kt:873`）用"照片/不能/无法…"中文子串判定"LLM 安全误拒"并回退本地搜索，易误触发（如"找一下不能用的照片"）。应在 prompt/模型层治理拒答，而非 UI 层关键词打补丁。

### 🟡 P2-9　"已自动重置会话"提示与实现不符
`RemoteReActAgent.buildFriendlyErrorMessage` 对 tool_sequence 异常返回"已自动重置会话"文案（`:300-302`），但该分支**并未调用 `resetSession()`**，误导用户。建议真正重置或改文案。

### 🟡 P2-10　traceId 经单例 mutable var side-channel 注入
`RemoteReActAgent.init` 把 `traceIdHolder` 赋给 `ChatToolService` 单例的 public var（`RemoteReActAgent.kt:103`、`ChatToolService.kt:61`）。可变公开状态 + 单例，易在多 agent/多会话下互相覆盖。建议改为请求级上下文传递。

---

## 7. 横切问题

- **并发模型不一致**：同一仓库里同时存在 `Executors.newSingleThreadExecutor`（ReAct）、`runBlocking`（本地 chat）、`GlobalScope.future`（工具派发）、`backgroundScope.launch`（fire-and-forget）、`withContext(orchestratorDispatcher)`。取消、超时、生命周期语义各不相同，是 P0-2/P1-5 等问题的共同根因。建议统一到"协程 + 结构化并发 + 可取消"单一模型。
- **错误处理**：普遍 `catch(Exception)` 吞错返回空串/默认值（`LocalLlmEngine` 多处、`MemoryManager` 多处），掩盖故障；远程错误靠字符串匹配 `"quota_exceeded"`（`ChatViewModel.kt:822`）、`"upstream_error"`/`"tool_calls"`（`RemoteReActAgent.kt:293-294`）判定类型——层泄漏、脆弱。建议引入错误码/类型化异常。
- **可观测性**：已有 `LlmCallRecorder`/`CapturingChatModelListener`/`TraceIdHolder`（亮点，release 不落内容符合隐私），但 ReAct 的工具执行耗时、超时/取消事件、并发拒绝事件无统一上报；`IntentCache.stats()` 未见消费。建议补齐工具执行指标与降级事件埋点。
- **可测试性**：`LocalCommandParser` 抽成 object 便于 JVM 单测（好）；但 `AgentOrchestrator`/`RemoteReActAgent` 强耦合单例 + Android Context + JNI，难写隔离测试。配合 P1-2 收敛入口后可显著改善可测性。

---

## 8. 建议路线图（已被 §0.4 取代）

> **📌 2026-07-28 更新**：用户已下达 5 条架构决策，路线图已重写为**决策驱动的治理计划**，见 **§0.3（治理建议）+ §0.4（执行顺序）**。下方早期路线图仅作历史记录保留，不再作为行动基准（其 P0-1 条目「chat 入口接隐私分级…RESTRICTED 降级本地」与决策1 冲突，已作废）。

### 短期（止血，1–2 周）—— *历史记录*
1. ~~**P0-1**：chat 入口接隐私分级…RESTRICTED 降级本地或拒绝~~（决策1 已放宽，作废；新动作见 §0.3-D1）
2. **P0-2**：外层超时与底层重试/超时对齐；超时分支 `shutdown()` 重建 agent，避免 isRunning 长期占用。
3. ~~**P0-3**：`configure` 不再从 `getAgentMode()` 回写持久 mode~~（根因由决策3 消除，见 §0.3-D3）
4. **P1-5**：工具执行去 `GlobalScope`/`runBlocking`，超时按操作类型分级。

### 中期（收敛，3–6 周）—— *历史记录*
5. ~~**P1-2**：合并三入口…~~（决策2+3 强制；见 §0.3-D2/D3）
6. ~~**P1-3**：统一 chat 记忆…~~（决策5 强制；见 §0.3-D5）
7. **P1-4**：工具规格单一来源；系统 prompt 日期改为每轮动态拼接。
8. **P2-1/P2-6**：清理死代码；推进 `AiAgentCommand` legacy 层退役。

### 长期（治本，持续）—— *历史记录*
9. **P1-1**：真流式（SSE 就绪后接入）或阶段性反馈。
10. **P2-2**：runtime-core 仅产结构化结果，文案本地化上移 app 层。
11. **P2-3**：ChatViewModel / AgentOrchestrator 拆分。
12. **横切**：统一并发模型与类型化错误；补齐可观测性与隔离测试。

---

## 附录 A：发现速查表

| ID | 级别 | 主题 | 关键位置 |
|---|---|---|---|
| P0-1 | 🟠 | chat 远程文本合规；转为防媒体出境（决策1） | `AgentOrchestrator.kt:445`; `PrivacyGuard.kt:73,84`; `LocalInferencePipeline.kt:74` |
| P0-2 | 🔴 | 全局串行+假取消+超时冻结 | `RemoteReActAgent.kt:91,215`; `RemoteModelFactory.kt:79`; `AgentOrchestrator.kt:952,968` |
| P0-3 | 🔴 | 全局配置污染/override 泄漏 | `ChatViewModel.kt:725`; `AgentConfigurator.kt:119,143`; `PoLangApplication.kt:392,441,455` |
| P1-1 | 🟠 | chat 无 token 流式 | `AgentOrchestrator.kt:486`; `RemoteReActAgent.kt:146,249`; `ChatViewModel.kt:746` |
| P1-2 | 🟠 | 三入口+多段复制 | `AgentOrchestrator.kt:370,577,709`; `LocalInferencePipeline.kt:139,209,289`; `AiAgentUseCase.kt:193` |
| P1-3 | 🟠 | 双记忆系统割裂/死写 | `AgentOrchestrator.kt:463,780`; `MemoryManager.kt:129`; `RemoteReActAgent.kt:313,413` |
| P1-4 | 🟠 | 工具三处同步+日期冻结 | `ChatToolService.kt:374`; `AgentConfigurator.kt:237,389` |
| P1-5 | 🟠 | 工具阻塞+超时错配 | `ChatToolService.kt:179,450` |
| P1-6 | 🟠 | 本地引擎反模式 | `LocalLlmEngine.kt:213,315,227,553,576,625` |
| P2-1 | 🟡 | 大量死代码 | 见 §6 |
| P2-2 | 🟡 | i18n 结构性违反 | `CapabilityRegistry.kt:161,209,233`; `RemoteReActAgent.kt:298-304`; `ChatViewModel.kt` 多处 |
| P2-3 | 🟡 | God Object | `ChatViewModel.kt`(2239); `AgentOrchestrator.kt`(1100) |
| P2-4 | 🟡 | 伪事务回滚 | `CapabilityRegistry.kt:437` |
| P2-5 | 🟡 | IntentCache 模糊匹配 | `IntentCache.kt:98-115` |
| P2-6 | 🟡 | 双命令模型+重复映射 | `AiAgentUseCase.kt:268,345` |
| P2-7 | 🟡 | 本地 JSON 手写扫描 | `LocalCommandParser.kt:79-114` |
| P2-8 | 🟡 | 关键词补偿 LLM | `ChatViewModel.kt:873` |
| P2-9 | 🟡 | 重置提示与实现不符 | `RemoteReActAgent.kt:300` |
| P2-10 | 🟡 | traceId side-channel | `RemoteReActAgent.kt:103`; `ChatToolService.kt:61` |

---

## 附录 B：本次未覆盖（建议后续 review）

- `PoLangToolService`（飞书 RPA 工具，865 行）未精读，可能存在与 `ChatToolService` 同类的三处同步/阻塞问题。
- JS 沙箱（`JsRuntime`/`QuickJsEngine`）与 `capability.dispatch` 写操作确认链路未深入（涉及写操作安全）。
- `MnnLlmClient` JNI 层（`llm_jni_bridge.cpp`）的取消/错误语义未核对。
- 远程服务端（api.polang.net）的配额/鉴权边界仅在客户端侧观察，未结合服务端实现复核。
- 未做运行时压测（并发消息、慢网络、跨天）以定量验证 P0-2/P1-1。
