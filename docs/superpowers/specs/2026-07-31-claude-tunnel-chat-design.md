# 反向隧道接入 KimiClaw Claude Code：app 内实时 agent chat

> **日期**：2026-07-31
> **状态**：已确认，待实施
> **范围**：跨端 —— `app/`（claude-chat 通道 + agent 步骤渲染 + 交付闭环）、`server/`（反代端点 + 三层鉴权 + 健康推断 + 限流）、`scripts/claude-tunnel/`（chisel 部署 + Claude 流式网关，KimiClaw 侧）
> **关联**：`2026-07-30-remote-diagnosis-design.md`（现有诊断 feature，本设计复用其交付/脱敏/鉴权基建）；`2026-07-31-diag-multiturn-and-hardening-design.md` §1 的「worker↔server 不上 WebSocket」决策针对的是 **job 通道**，本设计是新增的**实时 chat 通道**，二者不冲突、互补（见 §1.3）

## 1. 背景与问题

### 1.1 现有诊断 feature 的局限

「远程诊断」feature 已实现 app 上报 → KimiClaw Claude Code（GLM 后端）headless 诊断/修复 → 推 `diag-fix/*` 分支，并端到端跑通（job #5）。但它是**异步批处理工单**形态：上报 → worker 每 60s 轮询 → GLM 推理十几分钟 → 回根因报告 → 用户确认 → 再修复。用户体感「时延太久，失去了 chat 的优势」——要的是**真 chat 体验**：实时流式、多轮、能在 Claude 思考时追问纠正。

### 1.2 用户诉求

在手机 app 里，和一个**有执行力**的 agent 实时对话——它能读完整代码库、跑 gradle/测试、改码、推分支，边做边把过程流式吐回，用户可中途追问/纠正。不只是「能读不能做」的 LLM。

### 1.3 与现有诊断 feature 的关系

二者互补，不替代：

| | 现有远程诊断 | 本设计（claude-tunnel chat） |
|---|---|---|
| 形态 | 异步工单（上报→等→报告） | 实时流式 chat |
| Claude 调用 | headless `claude -p` 一次性 | 交互式 `--resume` 多轮 + 流式 |
| 适合场景 | 无人值守、自动上报、明确问题 | 人在回路、疑难需追问、要边看边纠 |
| 共享 | 交付（run-fix push/pr/auto）、脱敏（DiagSanitizer）、AppToken、限流、workdir 思路 |

### 1.4 隧道可行性（2026-07-31 探针实测）

KimiClaw 网络画像（入站不可达 / 出口 IP 池化 / 出站 SNI 级白名单）决定了必须用反向隧道，且会合点必须是 KimiClaw 够得着的 `api.polang.net`：

- **P4**：带 WebSocket Upgrade 头的请求到 `api.polang.net` → 拿到正常 HTTP 200（非 RST）；负对照 `api.ipify.org` exit 7 秒断。→ 出站为 **SNI 级过滤**，ws Upgrade 握手能穿透。
- **P5 修正**：有周期心跳（~5s 一次）的长连接稳定维持 35s+。→ NAT 不杀有流量的连接，chisel 心跳可保活。
- 结论：**chisel over `wss://api.polang.net` 可建**。

## 2. 选型依据（为何方案 A）

三方案均满足「app 里实时聊」，差别在「聊的对象有没有执行力」与「要不要建隧道」：

| | A. 隧道接 KimiClaw Claude | B. 远程 DeepSeek + 代码上下文 | C. 混合（B chat + 现有改码） |
|---|---|---|---|
| 聊的对象 | 有执行力 agent（跑/改/推） | 无执行力 LLM | chat=B，改码=KimiClaw job |
| 要隧道 | 是 | 否 | 否 |
| 当场交付 | ✅ 边聊边改边推 | ❌ 只给建议 | ⚠️ 异步 job |
| 工作量 | 大 | 中 | 中小 |

用户明确要「全实时执行力」（看 Claude 跑测试、边改边验证、实时纠正改法），选 **A**。B/C 不能在对话中执行，舍弃。

> 延迟须拆两半：网络/架构延迟（轮询/批处理）隧道能解决；**GLM 推理延迟（分钟级）隧道解决不了**，靠流式输出缓解体感。本设计不承诺把单轮 agent loop 压到秒级。

## 3. 设计目标

- **真 chat 体验**：app 内流式、多轮、人在回路驱动 KimiClaw 的 Claude Code。
- **有执行力**：Claude 在 workdir 能读码/跑 gradle/改码，聊完当场交付修复。
- **agent 过程可见**：app 渲染工具调用步骤（非纯文本）。
- **复用现有**：交付（run-fix）、脱敏（DiagSanitizer）、鉴权（AppToken）、限流（RateLimiter）。
- **隐私不破**：Claude 只碰源码 workdir，不经手媒体；chat 禁图片（ADR-008）。
- **生产可控**：三层鉴权 + localhost 绑定，`api.polang.net` 不变开放代理。

## 4. 架构

```
┌─ 手机 app ──────┐   ┌─ api.polang.net (PoLang 服务器) ─┐   ┌─ chisel 隧道 ─┐   ┌─ KimiClaw 云主机 ──────────┐
│ chat UI         │   │ nginx 443                        │   │ wss           │   │ chisel client (egress)     │
│ + agent 步骤渲染│   │ POST /v1/claude-chat             │   │ SNI=polang.net│   │   │ reverse tunnel            │
│                 │──►│ X-App-Token 鉴权 + 限流/额度     │──►│ chisel server │──►│   ↓                         │
│ session_id 多轮 │◄──│ 反代到隧道本地端口, SSE 透传     │◄──│ (PSK 鉴权)    │◄──│ Claude 流式网关 (新, Python)│
└─────────────────┘   └──────────────────────────────────┘   └───────────────┘   │   ↓ claude --resume <sid>  │
                                                                                            │ (GLM 后端 / root / workdir │
                                                                                            │  能读码·跑 gradle·改码)    │
                                                                                            └────────────────────────────┘
```

复用现有基建：`PoLangAuthClient`（X-App-Token + `https://api.polang.net`）、Ktor 认证拦截器、`RateLimiter`、`DiagSanitizer`、`scripts/diag-worker/run-fix.sh`（交付）。

## 5. 数据流（端到端）

1. app chat 输入栏切「AI 工程师」toggle，发消息（带 `session_id`，首条可空）→ `POST /v1/claude-chat`（X-App-Token）。
2. server 鉴权（AppToken）+ 限流/额度 → 探测 `127.0.0.1:3001`（隧道口）健康 → 反代请求到隧道口，SSE 透传。
3. 经 chisel wss 隧道（PSK）→ KimiClaw chisel client → Claude 流式网关（`127.0.0.1:3000`）。
4. 网关调底层 Claude：`--resume <sid>`（首次新建）+ `--output-format stream-json`（GLM 后端），在 session workdir 跑（root + `IS_SANDBOX=1` + 工具权限 + `--max-turns` + wall-clock 超时）。
5. Claude stream 事件 → 网关翻译归并为粗粒度 SSE 事件 → 原路流式回 app。
6. app 渲染：文本气泡流式 + 可折叠步骤列表（tool_use↔tool_result 配对）+ 文件改动徽标。
7. 多轮：app 后续消息带同一 `session_id` → `--resume` 续上下文，复用同一 workdir（累积改动）。
8. 交付：气泡「交付」按钮 → 选 push/pr/auto → 网关在 workdir commit + 调 `run-fix.sh` 交付 → 结果回气泡。

## 6. 事件协议（app ↔ server ↔ 网关，SSE 透传）

D2（agent 步骤渲染）的落地契约。网关发出、server 透传、app 消费：

| 事件 | 载荷 | app 渲染 |
|---|---|---|
| `session` | `{sid}` | 存 sid，后续多轮带上 |
| `assistant_text` | `{delta}` | 文本气泡流式吐字 |
| `tool_use` | `{tool:"Bash"\|"Edit"\|…, input}` | 步骤行「▶ 跑 ./gradlew…」「✎ Foo.kt」 |
| `tool_result` | `{ok:bool, summary}` | 步骤配对 ✓/✗ + 摘要（gradle 日志裁成摘要，不灌全量） |
| `file_change` | `{path, action}` | 改动徽标（可折叠列表） |
| `cost` | `{turns, cents}` | 进度/额度（可选） |
| `error` | `{message}` | 出错提示 |
| `done` | `{}` | 本轮结束 |

> 协议面向 agent 语义（非 OpenAI Chat Completions 兼容），因为 Claude 的多步工具调用/文件改动无法用单轮 OpenAI 格式自然表达。app 端是新通道，不复用现有 OpenAI 流式客户端的事件解析。

## 7. 组件拆分

### 7.1 KimiClaw：Claude 流式网关（新，Python + aiohttp）

| 子组件 | 职责 |
|---|---|
| HTTP 入口（aiohttp） | 接 `{sid?, message, delivery?}` 请求；SSE 流式响应 |
| session/workdir 管理 | 首次 clone 仓到 `work/<sid>` + checkout `diag-fix/<sid>`；后续 `--resume <sid>` + 复用同 workdir |
| Claude 调用 | CC SDK `query()`（GLM 后端，resume，限 max-turns + 超时），root + `IS_SANDBOX=1` |
| 事件翻译 | SDK/stream-json 细粒度事件 → §6 粗粒度 SSE 事件 |
| 交付动作 | 在 workdir commit + 调 `run-fix.sh` 的 push/pr/auto（不让 Claude 自由 push） |

> **封装底层差异**：事件翻译层对 app 协议不变。若 CC SDK 不继承 CLI 的 GLM 配置（见 §12 待验证假设），底层改 spawn `claude` CLI + 解析 stream-json 即可，上层协议与 app 不受影响。

路径：`scripts/claude-tunnel/gateway/`（与 `scripts/diag-worker/` 并列，同属 KimiClaw 侧脚本/服务）。

### 7.2 隧道：chisel

| 部件 | 位置 | 说明 |
|---|---|---|
| chisel server | PoLang 服务器 | 监听本地 HTTP 端口（如 `127.0.0.1:8090`），nginx 反代 `/tunnel`（wss 升级）到它；systemd unit，与 `picme-api` 并列，独立崩溃互不影响 |
| chisel client | KimiClaw | 主动连 `wss://api.polang.net/tunnel`（SNI=api.polang.net），reverse tunnel `R:3001:127.0.0.1:3000`；systemd 保活，~25s 心跳 + 自动重连 |
| PSK | 双端 env | `--auth <psk>`，env 注入，不进 repo |

### 7.3 server：反代 + 鉴权 + 健康推断

| 组件 | 路径 | 职责 |
|---|---|---|
| `ClaudeChatRoute` | `server/.../routes/ClaudeChatRoute.kt`（新） | `POST /v1/claude-chat`；AppToken 鉴权；反代到 `127.0.0.1:3001`；SSE 透传；挂 RateLimiter |
| 健康推断 | `ClaudeChatRoute` 内 | 反代前探测 `127.0.0.1:3001` 是否通；不通 → 「AI 离线，稍后重试」（复用 `inferDiagWorkerHealth` 思路） |
| `Application` | `server/.../Application.kt`（改） | `/v1/claude-chat` 走现有 AppToken 拦截器；nginx 无需新公网端口 |
| nginx | 服务器 nginx（改） | 加 `location /tunnel`（wss 反代到 chisel server）；`/v1/claude-chat` 仍走 Ktor |

### 7.4 app：claude-chat 通道 + 步骤渲染

| 组件 | 路径 | 改动 |
|---|---|---|
| 入口 toggle | `app/.../features/chat/ChatScreen.kt`（改） | chat 输入栏加「AI 工程师」toggle；激活后消息走 claude-chat 通道 |
| `ClaudeChatClient` | `app/.../data/remote/picme/ClaudeChatClient.kt`（新） | 消费 §6 自定义 SSE 事件；复用 `PoLangAuthClient` 的 baseUrl/OkHttp/X-App-Token |
| ViewModel | `app/.../features/chat/ChatViewModel.kt`（改） | 持有 `session_id`；多轮带上；toggle 路由到 claude-chat |
| agent 气泡 UI | `app/.../features/chat/components/`（新） | 文本流式 + 可折叠步骤列表 + 文件改动徽标 |
| 交付按钮 | 根因/改完气泡内嵌 | 检测 `file_change` 后出现「交付」→ 选 push/pr/auto（复用现有三模式 UI）→ POST 交付 |
| 图片禁用 | 输入栏（改） | claude-chat 模式禁用图片输入（ADR-008，见 §11） |

## 8. session / workdir 生命周期 + 交付闭环

- **一 session 一 workdir 一分支**：首次消息 → 网关 clone 仓到 `work/<sid>` + checkout `diag-fix/<sid>`；Claude 在此改码。多轮消息复用同一 workdir，累积改动。
- **session 持久化**：用 Claude Code 原生 session 文件（`--resume`），网关只生成/透传 sid。网关重启不影响（可 resume）。
- **交付**：app 点「交付」→ 选 mode → 网关在 workdir `git add -A && commit` + 调 `run-fix.sh` 的 push/pr/auto 分支 → 结果（分支名/PR URL/是否合 main）回气泡。**不让 Claude 在对话里自由 push**（可控 + 复用）。
- **清理**：session 结束后清理 `work/<sid>`（或保留供 resume，二期策略）。

## 9. 鉴权链（三层）+ 端口绑定

| 层 | 谁鉴谁 | 凭证 | 复用 |
|---|---|---|---|
| Ktor | app → server | **X-App-Token**（每用户） | ✅ 现有 AppToken 拦截器 |
| chisel | KimiClaw client → server 建隧道 | **PSK**（静态共享密钥，env 注入） | 新增 |
| 绑定 | localhost-only | `3001`/`3000`/chisel-server 端口**只绑 127.0.0.1** | — |

> **关键**：chisel 在 server 暴露的隧道口（`3001`）只绑 localhost，外部无法绕过 Ktor 直连。`api.polang.net` 不会变成开放代理。第四层「网关内部 token」判为 YAGNI（localhost 绑定下只有 Ktor 能到），不加。

## 10. 护栏与成本

- **限流**：复用 `RateLimiter`（每账号 N 次/小时，具体值实现时定）。
- **额度**：MVP 用限频，**不精细化 GLM 成本池**（Claude agent 多轮比 DeepSeek 贵；精细化二期）。
- **超时**：单轮 agent wall-clock ~5 分钟 + `--max-turns`（防 Claude 跑飞烧 GLM）。
- **脱敏**：用户消息/日志经 `DiagSanitizer`（复用）。
- **带宽**：网关把 `tool_result` 的 gradle/命令日志**裁成摘要**再回传，避免大日志灌隧道。
- **🔴 root 权限风险**：MVP 阶段 Claude 以 root + `IS_SANDBOX=1` + 全工具权限运行（沿用 job #5 经验，`--dangerously-skip-permissions`），存在误操作系统文件的风险。**接受此风险作为 MVP trade-off**；二期通过工具权限精细化（白名单可跑命令）收紧。

## 11. 红线（ADR-008）

- Claude 在 KimiClaw 操作**源码仓库**（公开），不经手用户媒体——✅。
- chat 文本/日志脱敏后走远程（KimiClaw 算远程推理），文本/元数据可远程——✅。
- **🔴 图片禁用**：claude-chat 模式**禁用图片输入**（只接受文本/脱敏日志）。现有 chat 支持图像理解输入——若 claude-chat 允许图片，图片会经隧道发到 KimiClaw，违反「媒体不上传远程」。此条必须，不可妥协。
- **[I18N]**：新增文案（toggle / 步骤标签 / 交付 / 离线提示等）三语同步（values / values-zh-rCN / values-zh-rTW）。
- **[AGENT-FIRST]**：PSK、git/SSH 凭证只存 KimiClaw/PoLang 服务器 env，不进 repo。

## 12. 待验证假设（实现首日需确认）

**Claude Code SDK 是否继承 CLI 的 GLM 后端配置**：KimiClaw 上 Claude Code 目前以 CLI + GLM 后端跑通（job #5）。若 CC SDK 不吃该配置，网关底层改 spawn `claude` CLI + 解析 `--output-format stream-json`。**不影响 app 协议与整体架构**（§7.1 翻译层封装了差异），仅影响网关内部实现。

## 13. 测试

- **网关**（`scripts/claude-tunnel/gateway/`）：事件协议翻译单测（mock Claude stream 事件 → 断言 §6 SSE 事件）+ session/workdir 生命周期。
- **server**（`server/src/test`，in-memory）：反代路由 + AppToken 鉴权正反例 + 健康推断（隧道口通/不通）+ 限流。
- **隧道**（`scripts/claude-tunnel/smoke/`）：本地起 chisel server/client，验证 reverse tunnel + PSK + 断线重连。
- **app**（纯 JVM/Compose）：§6 事件解析 + agent 步骤渲染 + 多轮 session 携带 + 图片禁用。
- **E2E**：真机 chat 一个简单问题 → 流式 + 步骤 → 改码 → 交付分支，全链路。
- *诚实声明*：核心依赖真实 KimiClaw + GLM，单测覆盖胶水与协议，E2E 人工跑。

## 14. MVP 范围

**纳入**：chisel 隧道（server+client）；Claude 流式网关（事件协议+session/workdir+翻译）；server 反代端点 + 三层鉴权 + 健康推断 + 限流；app claude-chat 通道 + agent 步骤渲染 + 多轮；交付闭环（复用 run-fix）；脱敏 + 图片禁用。

**二期**：GLM 成本精细化额度池；session 跨设备恢复；多 KimiClaw 容灾（多 chisel client）；Claude 工具权限精细化（限制能跑的命令）；`/admin` 观测页（类似 `/admin/diag`）；workdir 清理策略。

## 15. 验收标准

- [ ] chisel wss 隧道建通：KimiClaw client 主动连 `wss://api.polang.net/tunnel`，reverse tunnel 把网关 `3000` 暴露到 server `127.0.0.1:3001`；PSK 鉴权、断线自动重连（smoke 验证）。
- [ ] `3001`/`3000` 只绑 localhost；外部无法绕过 Ktor 直连隧道口（server 单测 + 部署后 netstat 验证）。
- [ ] `POST /v1/claude-chat`：无 AppToken → 401；隧道口不通 → 「AI 离线」；正常 → SSE 流式（server 单测）。
- [ ] 网关事件翻译：mock Claude stream → 正确产出 §6 事件序列；session/workdir 首建 + resume（单测）。
- [ ] app「AI 工程师」toggle：激活后消息走 claude-chat；agent 气泡渲染文本 + 步骤 + 文件改动；多轮带 session_id；图片输入被禁用（UI 测试）。
- [ ] 交付：气泡「交付」→ push/pr/auto → 网关 commit + run-fix 交付 → 结果回气泡（E2E）。
- [ ] 脱敏：用户消息/日志经 DiagSanitizer（单测）；claude-chat 模式图片禁用（红线）。
- [ ] 三语文案同步；编译通过（`:app:assembleDebug` + `gradlew -p server build` + 网关/隧道 smoke）无新增错误。
- [ ] E2E：真机 chat 一个简单问题 → 流式 + 步骤 → 改码 → 交付分支，全链路跑通。
