# AI 工程师模式合并诊断能力 · 设计文档

> **日期**：2026-08-01
> **状态**：已批准（用户逐节确认）
> **前置文档**：
> - `2026-07-31-claude-tunnel-chat-design.md`（AI 工程师模式基座）
> - `2026-07-30-remote-diagnosis-design.md`（诊断模式，本方案落地后 superseded）
> - `2026-07-31-diag-multiturn-and-hardening-design.md`（superseded）
> - `2026-07-31-diag-three-fix-modes-design.md`（交付三档，保留并被 claude-deliver 复用）

---

## 1. 背景与目标

### 1.1 背景

Chat 输入栏现有三个互斥通道：普通聊天、诊断模式、AI 工程师模式。诊断模式（异步工单：澄清 → 提交诊断包 → worker 轮询 → 根因 → 确认修复）与 AI 工程师模式（实时流式远程 coding agent）功能重叠——两者都为排查/修复 App 问题。原设计决策「互补不替代」在实践中带来两条并行链路（diag-worker 批处理 + claude-tunnel 实时流）的维护成本。

### 1.2 目标

将诊断模式**完全合并**进 AI 工程师模式，使其具备：

1. 多轮对话诊断问题时，Agent 具备感知代码与 App 状态的能力
2. Agent 可通过真正的 tool calls（MCP）按需获取 App 运行时状态、日志、聊天历史，澄清事实、定位问题，并结合代码提出方案
3. 过程高度自动化：App 侧数据拉取全自动执行（脱敏后回传），无需用户逐次确认

### 1.3 非目标

- 不改动代码交付管线（`/v1/claude-deliver` 的 push/pr/auto 三档维持现状）
- 不改变普通聊天模式
- 不为 tool call 增加用户确认环节（已决策：全自动执行）

### 1.4 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| 旧诊断模式处置 | **完全移除**（App 入口、状态机、server 队列、diag-worker；可复用组件保留） |
| 数据拉取确认 | **全自动执行**，DiagSanitizer 脱敏后直接回传，过程透明渲染 |
| 可拉取数据范围 | 日志与崩溃、聊天历史、运行时状态、相册摘要（四类全选） |
| 反向通道方案 | **方案 A：MCP 工具服务器 + SSE 下行推送 + 结果回传端点** |

---

## 2. 总体架构

核心思想：诊断能力从「异步工单」重构为「AI 工程师模式下 Agent 的按需感知能力」。诊断不再是独立模式，而是工程师模式的一种用法——用户描述问题，Agent 自主决定拉什么数据、结合代码给方案。

```
App（工程师模式，SSE 流保持打开）
  ↑↓ POST /v1/claude-chat（上行消息 / 下行 SSE 事件流）
  →  POST /v1/claude-tool-result（tool 结果回传）
Ktor Server（api.polang.net）
  ↑↓ 反代 127.0.0.1:3001（chisel wss 反向隧道）
KimiClaw 云主机 gateway（server.py，新增 app_tools_mcp.py）
  ↑↓ stdio MCP
Claude Code（--resume 多轮，GLM 后端，workdir=仓库 clone）
```

### 2.1 一次 tool call 的生命周期

1. Claude 调用 MCP 工具，如 `app_get_logs(filter: "TagGeneration", lines: 200)`
2. 云主机 MCP server 生成 `requestId`，请求挂起到 pending map，经网关 → 隧道 → server → SSE 流下行 `app_tool_request` 事件给 App
3. App 的 `ClaudeSseParser` 识别事件 → `AppToolExecutor` 采集数据 → `DiagSanitizer` 脱敏 → POST `/v1/claude-tool-result {requestId, payload}`（X-App-Token 鉴权，走同一隧道反代回网关）
4. MCP server 解挂，payload 作为 tool result 返回 Claude，Agent 继续推理
5. 超时治理：单次 tool call 60s 超时，MCP server 返回「App 未响应」错误，Agent 可决定重试或降级

### 2.2 MCP 工具清单

| 工具 | 参数 | 数据来源 |
|---|---|---|
| `app_get_logs` | filter?, lines≤500 | `DiagBundleCollector` 日志环形缓冲 |
| `app_get_crash_trace` | — | `CrashTraceStore` |
| `app_get_chat_history` | sessionId?, limit≤50 | Room 会话表（脱敏） |
| `app_get_runtime_state` | — | agentMode / 模型配置 / Tag 生成进度 / OpenCL 降级状态等快照 |
| `app_get_gallery_summary` | — | 相册统计元数据（数量、标签分布等，不含图片，遵守 PRIVACY 红线） |

---

## 3. App 侧设计

### 3.1 新增 `AppToolExecutor`

位置：`app/src/main/java/com/mamba/picme/core/agenttools/`（新包）。单一职责：接收 `app_tool_request` → 按工具名分发 → 脱敏 → 回传。显式依赖注入（Agent First 原则）：

```kotlin
class AppToolExecutor(
    private val diagBundleCollector: DiagBundleCollector,   // 复用：日志环缓冲
    private val crashTraceStore: CrashTraceStore,           // 复用：崩溃栈
    private val chatHistoryDao: ChatHistoryDao,             // 复用：Room 会话表
    private val runtimeStateProvider: RuntimeStateProvider, // 新增接口：状态快照
    private val gallerySummaryProvider: GallerySummaryProvider, // 新增接口：相册摘要
    private val sanitizer: DiagSanitizer,                   // 复用：脱敏
    private val claudeChatClient: ClaudeChatClient          // 扩展：postToolResult()
)
```

- 工具分发用 `when (tool)` 穷举枚举（`AppTool` enum），新增工具编译期可检查
- 每个工具输出 JSON payload，大小上限 32KB，超出截断并标记 `truncated: true`
- 采集在 IO 调度器执行，不阻塞 UI
- 执行过程在 chat 渲染为透明气泡：「正在读取日志（filter=TagGeneration）… → 已返回 200 行」

### 3.2 `ClaudeChatClient` / `ClaudeSseParser` 扩展

- `ClaudeEvent.kt` 新增事件类型 `app_tool_request {requestId, tool, args}`
- 新增 `postToolResult(requestId, payload)` → `POST /v1/claude-tool-result`
- SSE 连接保活：回合结束（`done` 事件）后不立即断开，进入 idle 保活（心跳 30s），Agent 可在后续回合随时发起 tool call；用户退出工程师模式或 5 分钟无活动才断开

### 3.3 `ChatViewModel` 改造

- 删除 `diagMode` 全部状态机及 `enterDiagMode/exitDiagMode/sendDiagMessage/submitDiagnosis/pollDiagnose/confirmDiagnosis`
- `claudeMode` 状态机新增子状态 `AwaitingAppTool(requestId, toolName)`，驱动 tool 过程气泡渲染
- **记忆修复**：`claudeSid` 持久化到 Room 会话表 metadata，进程重建后恢复；`enterClaudeMode()` 仅在新建会话时置 null，切出/切回不再丢 sid（解决当前"没有记忆"体验：此前 sid 仅存内存，进程被杀/进出模式即失忆）

### 3.4 UI

- 删除「诊断」toggle（`ChatScreen.kt:1541-1545`）及三语文案
- tool call 过程气泡复用 `ClaudeAgentRenderer` 的折叠渲染风格

---

## 4. 云主机网关设计

### 4.1 新增 `app_tools_mcp.py`

位置：`scripts/claude-tunnel/gateway/`。stdio MCP server，向 Claude Code 暴露 5 个 `app_*` 工具。核心是 pending request map：

```python
class AppToolBridge:
    pending: dict[str, asyncio.Future]  # requestId → Future

    async def call_tool(self, tool: str, args: dict) -> dict:
        request_id = uuid4().hex[:12]
        fut = asyncio.get_event_loop().create_future()
        self.pending[request_id] = fut
        await sse_hub.push(session_sid, {           # 下行到 App
            "event": "app_tool_request",
            "requestId": request_id, "tool": tool, "args": args,
        })
        return await asyncio.wait_for(fut, timeout=60)  # 超时 → MCP 错误
```

- **解挂入口**：网关新增 HTTP 路由 `POST /tool-result`（与 `/chat` 并列），接收经 server 反代回来的 App 结果，`pending.pop(requestId).set_result(payload)`
- **会话绑定**：`session.py` 扩展 `sse_hub`：sid → 活跃 SSE response writer。无活跃连接时 MCP 立即返回错误「App 不在线」，由 Agent 告知用户
- **超时与清理**：60s `wait_for` 超时后从 pending 移除并返回 MCP 错误；网关重启时 pending 清空（进程内存即可，无需持久化）

### 4.2 `server.py` 的 `build_cmd` 改造

```bash
claude -p <msg> --output-format stream-json --resume <sid> \
  --mcp-config app-tools.mcp.json \      # 新增：注册 app_tools_mcp.py
  --allowedTools "mcp__app__*,..." \
  --settings claude-settings.json --max-turns 20
```

- `app-tools.mcp.json` 静态配置文件，随 gateway 部署
- `claude-settings.json` 权限白名单加入 `mcp__app__*` 工具

### 4.3 工具使用指引（prompt 沉淀）

通过 `--append-system-prompt` 注入（不污染仓库 CLAUDE.md）：何时该拉日志（用户报告异常行为时）、何时拉聊天历史（用户说"之前说过"时）、数据不全时如何向用户追问。**诊断的「澄清 → 定位 → 方案」方法论沉淀为 prompt，而非代码状态机**——这是合并的本质。

### 4.4 SSE 协议扩展

`claude_events.py` 新增事件类型 `app_tool_request {requestId, tool, args}`，原样透传。App 的 tool result 不走 SSE，走独立 POST 回传通道。

---

## 5. Ktor Server 改动

`server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeChatRoute.kt`：

- 新增 `POST /v1/claude-tool-result`：AppToken 鉴权 + RateLimiter → 反代到网关 `POST /tool-result`（与 `/v1/claude-chat` 同一 chisel 隧道口）。无状态纯透传
- `/v1/claude-chat` SSE 反代支持 idle 保活（心跳帧透传），配合 App 侧连接保活

---

## 6. AI 工程师模式账号白名单

因 AI 工程师模式可调用 MCP 工具读取 App 数据，并可通过 `/v1/claude-deliver` 推送代码分支，属于高权限功能，故增加**账号级白名单**。

### 6.1 设计语义

- `ai_engineer_whitelist` 表为空时，**所有账号均不可使用** AI 工程师模式。
- 管理员在后台把用户邮箱加入白名单后，该账号才可调用相关端点。
- 白名单匹配**大小写不敏感**（入库时统一小写）。

### 6.2 受保护端点

| 端点 | 说明 |
|---|---|
| `POST /v1/claude-chat` | 进入 AI 工程师聊天 |
| `POST /v1/claude-deliver` | 代码交付（push/pr/auto） |
| `POST /v1/claude-tool-result` | App tool 结果回传 |
| `GET /v1/claude-engineer/available` | 客户端查询入口是否可用 |

未在白名单的请求返回：

```json
{"error":"ai_engineer_not_allowed","message":"AI engineer mode not enabled for this account"}
```

HTTP 状态码 `403 Forbidden`。

### 6.3 管理后台

路径：`/admin/ai-engineer-whitelist`

- 列表展示所有白名单邮箱。
- 输入邮箱点击「添加」加入白名单。
- 每行提供「移除」按钮，从白名单删除。

### 6.4 实现要点

- `AiEngineerWhitelistService` 提供 `isAllowed(email)`、`allow(email)`、`revoke(email)`、`list()`。
- `AccountService.validateToken` 返回结果新增 `email` 字段，由全局 auth interceptor 写入 `EmailKey` 属性。
- `ClaudeChatRoute`、`ClaudeDeliverRoute`、`ClaudeToolResultRoute` 在鉴权后调用 `requireAiEngineerWhitelist()`。
- 新增 `GET /v1/claude-engineer/available` 供 App 在展示入口前查询，避免用户进入后才收到 403。

---

## 7. 旧诊断移除清单

| 层 | 删除 | 保留（复用） |
|---|---|---|
| App | 诊断 toggle + 三语文案、`diagMode` 状态机、`DiagChatSession`、`DiagPrompts`、`DiagClient` | `DiagSanitizer`、`CrashTraceStore`（注：`DiagBundleCollector` 原计划保留复用，实际未被 AppToolExecutor 引用，已随诊断链路一并删除，见 commit `893801c3`） |
| Server | `DiagRoute.kt` 全部 5 端点、`diag_jobs` 表与状态机、`/admin/diag` 页 | AppToken 鉴权、RateLimiter |
| 云主机 | `scripts/diag-worker/` 整个目录 | 交付管线（run-fix push/pr/auto，已被 claude-deliver 复用） |
| 文档 | 3 篇 diag spec 标记为 superseded（见文首） | — |

DB 迁移：`server/migrations/` 新增一版，`DROP TABLE diag_jobs`。

---

## 8. 错误处理

| 场景 | 行为 |
|---|---|
| App 离线 / tool 超时（60s） | MCP 返回错误，Agent 引导用户"请保持 App 在前台" |
| 采集为空（如无崩溃记录） | 返回 `{empty: true, reason}`，不算错误 |
| 脱敏异常 | 拒绝回传并返回错误——宁可拿不到数据也不泄露（PRIVACY 红线） |
| payload 超 32KB | 截断 + `truncated: true` 标记 |
| 代码交付 | 沿用现有 `/v1/claude-deliver` 三档，本次不动 |
| 账号未在白名单 | `403 ai_engineer_not_allowed` |

---

## 9. 测试

- **App**：`AppToolExecutor` 单元测试（分发、截断、脱敏调用）；`ClaudeSseParser` 新事件解析测试
- **Gateway**：`app_tools_mcp.py` pytest（pending map 挂起 / 解挂 / 超时）；参照 `scripts/diag-worker/smoke/` 思路补一个 claude-tunnel smoke 测试
- **Server**：
  - `ClaudeChatRouteTest` / `ClaudeDeliverRouteTest` / `ClaudeToolResultRouteTest`：鉴权、白名单、限流、反代
  - `ClaudeEngineerAvailabilityRouteTest`：可用性查询
  - `AiEngineerWhitelistServiceTest`：白名单增删查
  - `AdminAiEngineerWhitelistRoutesTest`：管理后台增删查
- **端到端手测清单**：描述问题 → Agent 拉日志 → 结合代码给方案 → 确认交付

---

## 10. 红线合规

- **[PRIVACY]**：所有回传数据经 `DiagSanitizer` 脱敏；相册摘要仅元数据，不含图片/视频；脱敏异常时拒绝回传
- **[AGENT-FIRST]**：`AppToolExecutor` 显式构造注入；工具分发穷举枚举；tool 过程结构化事件渲染
- **[DOC-SYNC]**：落地后更新 `docs/03-TECHNICAL-SPECS/` 相关文档与根 `AGENTS.md` 架构说明
