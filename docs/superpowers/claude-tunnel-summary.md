# Claude Tunnel — 项目总结

> **一句话**：让 app 内实时驱动 KimiClaw 云主机上的 Claude Code（GLM 后端）——有执行力（读码 / 跑 gradle / 改码 / 推分支），流式、多轮、人在回路。app「AI 工程师」toggle → agent 气泡（文本 + 步骤 + 文件徽标）→ 交付分支。**端到端已通**（2026-08-01 真机验证）。
>
> 本篇是 claude-tunnel 的收尾总结，收编了架构 / 协议 / 实现 / 运维 / 踩坑 / 现状。设计细节见保留的 spec `specs/2026-07-31-claude-tunnel-chat-design.md`。

---

## 1. 背景与动机

现有「远程诊断」是异步工单（上报 → worker 轮询 → GLM 推理十几分钟 → 根因 → 确认 → 修复），用户体感「时延太久，失去 chat 优势」。claude-tunnel 要的是**真 chat 体验**：实时流式、多轮、能在 Claude 思考时追问纠正，且 Claude **有执行力**（不只「能读不能做」）。与诊断互补（诊断适合无人值守，claude-tunnel 适合人在回路疑难）。

复用诊断基建：AppToken 鉴权、`DiagSanitizer` 脱敏、`RateLimiter` 限流、交付（`run-fix` 思路）。

## 2. 架构

```
┌─ 手机 app ──────┐   ┌─ api.polang.net (PoLang 服务器) ─┐   ┌─ chisel 隧道 ─┐   ┌─ KimiClaw 云主机 ────────────┐
│ chat UI         │   │ nginx 443                        │   │ wss           │   │ chisel client (egress)       │
│ + agent 步骤渲染│──►│ POST /v1/claude-chat             │──►│ SNI=polang.net│──►│   │ reverse tunnel             │
│ + 交付按钮      │◄──│ X-App-Token 鉴权 + 限流          │◄──│ chisel server │◄──│   ↓ R:3001→gateway R:3022→ssh│
│ session_id 多轮 │   │ 反代 127.0.0.1:3001, SSE 透传    │   │ (PSK 鉴权)    │   │ Claude 流式网关 (aiohttp)    │
│ /v1/claude-     │──►│ POST /v1/claude-deliver (JSON)   │──►│               │──►│   ↓ claude --resume <sid>    │
│   deliver       │◄──│ 反代 127.0.0.1:3001/deliver      │◄──│               │◄──│ (GLM 后端 / root / workdir)  │
└─────────────────┘   └──────────────────────────────────┘   └───────────────┘   └──────────────────────────────┘
```

- **KimiClaw 约束**：腾讯云 CVM，**egress-only**（无入站、出口 SNI 级白名单：`polang.net` / `api.github.com` / `baidu` 可达）→ 必须反向隧道，会合点 = `api.polang.net`。chisel `wss://api.polang.net/tunnel` 可建（SNI 穿透 + 心跳保活）。
- **反向 SSH**：chisel reverse tunnel `R:3022:127.0.0.1:22` 把 KimiClaw sshd 反向暴露到 prod:3022 → prod `ssh kimi-worker`（别名）直达 KimiClaw root，**运维不用登 console**。

## 3. 事件协议（§6，gateway 发 → server 透传 → app 消费）

| 事件 | 载荷 | app 渲染 |
|---|---|---|
| `session` | `{sid}` | 网关 sid（app 持有，多轮 + 交付 key）|
| `assistant_text` | `{delta}` | 文本气泡流式吐字 |
| `tool_use` | `{tool, input}` | 步骤行（▶ Bash/Edit…）|
| `tool_result` | `{ok, summary}` | 步骤配对 ✓/✗ + 摘要 |
| `file_change` | `{path, action}` | 改动徽标（注：漏 Bash 改文件，交付按钮不依赖它）|
| `cost` | `{turns, cents}` | 额度（可选）|
| `error` | `{message}` | 出错提示 |
| `app_tool_request` | `{requestId, tool, args}` | 静默执行（AppToolExecutor 采集后经 `POST /v1/claude-tool-result` 回传，见 §9 演进摘要）|
| `done` | `{}` | 本轮结束 |

面向 agent 语义（非 OpenAI Chat Completions 兼容）——Claude 多步工具调用 / 文件改动无法用单轮 OpenAI 格式表达。

## 4. 鉴权（三层，spec §9）

| 层 | 谁 鉴 谁 | 凭证 |
|---|---|---|
| Ktor | app → server | **X-App-Token**（每用户，复用现有拦截器）|
| chisel | KimiClaw client → server 建隧道 | **PSK**（env 注入，不进 repo）|
| 绑定 | localhost-only | `3001`/`3000`/chisel-server 端口只绑 127.0.0.1 |

> `api.polang.net` 不变开放代理：隧道口只绑 localhost，外部无法绕过 Ktor 直连。root bypass 被 claude CLI 硬禁（含 `IS_SANDBOX=1`），改用 `permissions.allow` 白名单。

## 5. 实现分层

**app**（`app/.../features/chat/` + `data/remote/picme/`）：
- `ClaudeChatClient` — OkHttp SSE 流式读 `/v1/claude-chat` + `deliver()`（独立 60s 超时 client，不复用 SSE 的 `readTimeout=0`）。
- `ClaudeEvent` + `ClaudeSseParser` — §6 事件 sealed + 纯逻辑解析（单测）。
- `ClaudeAgentRenderer` — 事件有状态折叠成 `ClaudeAgentState`（text + steps + hasFileChange，纯逻辑 + 单测）。
- `ChatViewModel` — claude 模式（toggle/流式/多轮/交付，镜像 diag），`claudeSid` 只取首个网关 sid，`persistClaudeBubble` 时序（先 set override 再 insert），`confirmClaudeDeliver` 失败可重试。
- `ChatScreen` — 「AI 工程师」toggle + agent 气泡（`ChatMessageUi.claudeAgent` inline：文本流式 + `ClaudeAgentSteps` 步骤列表 ⏳/✓/✗ + 文件徽标）+ 交付按钮 + 图片禁用（§11 红线）。

**server**（`server/.../routes/ClaudeChatRoute.kt`）：
- `POST /v1/claude-chat` — AppToken + 限流，反代 `127.0.0.1:3001/chat`，SSE 透传（`respondBytesWriter`，`requestTimeout=0` 防长连接被断）。
- `POST /v1/claude-deliver` — 同构，JSON 透传到 `/deliver`。

**gateway**（`scripts/claude-tunnel/gateway/`）：
- `server.py` — aiohttp：`/chat`（spawn `claude -p ... --output-format stream-json --settings ... --resume`，SSE）/ `/deliver`（**全异步** `asyncio.create_subprocess_exec` + `CT_PUSH_TIMEOUT` + `GIT_TERMINAL_PROMPT=0` + stderr 回传）/ `/healthz`。`build_cmd()` 用 `--settings` 指定权限白名单。
- `claude_events.py` — stream-json → §6 事件翻译（含单测）。
- `session.py` — 一 session 一 workdir 一分支（clone + checkout `claude-chat/<sid>`）。
- `claude-settings.json` — `permissions.allow` 白名单（Edit/Write/MultiEdit/Bash/Read…，非 bypass）。

**隧道 + 运维**（`scripts/claude-tunnel/deploy/`）：
- `chisel-server.service`（prod）/ `gateway.service` + `chisel-client.service`（KimiClaw，systemd 开机自启 + 崩溃重启，chisel 含 `R:3022` 反向 SSH）。
- `install-systemd.sh` — 装 unit + enable + 接管 nohup（自我 setsid 脱离，因 pkill chisel 断反向 SSH）。
- `bootstrap-kimiclaw.sh` — 重置/重装兜底：git pull + gh credential + sshd/prod key + 起 chisel + 起 gateway + 验证。

## 6. 交付闭环（spec §8）

app 点「交付」→ `POST /v1/claude-deliver {sid}` → server 反代 → gateway `/deliver` → workdir `git add -A && commit && push origin claude-chat/<sid>`（**gh token** via `gh auth setup-git`）→ `{ok, branch}` 回气泡。**不让 Claude 自由 push**（可控 + 复用）。MVP 仅 push（pr/auto 二期）。

## 7. 运维

- **systemd**（KimiClaw）：`gateway` + `chisel-client` 开机自启 + `Restart=always`。KimiClaw 重启自动恢复，不用手动。
- **反向 SSH**：prod `ssh kimi-worker`（别名，经 `R:3022`）直达 KimiClaw root。日常运维远程跑，不登腾讯云 console。
- **日常命令**（经 prod）：
  ```bash
  ssh kimi-worker                                                         # 直达
  ssh kimi-worker 'cd /root/polang && git pull && systemctl restart gateway'   # 发版
  ssh kimi-worker 'systemctl status gateway chisel-client'                # 状态
  ssh kimi-worker 'journalctl -u gateway -n 30 --no-pager'                # 日志
  ```
- **兜底**：systemd 起不来 / 重装系统 → console 跑 `bootstrap-kimiclaw.sh`。

## 8. 踩过的坑（9 个，真机调试）

1. **写文件被拒** → 权限白名单缺 Edit/Write，且配置在 KimiClaw 手配不可复现 → repo 化 `claude-settings.json` + `--settings`。
2. **交付 404（空 body）** → prod server 没部署 `/v1/claude-deliver`（全局鉴权下「无 token 401」不能证明路由存在）→ 部署 prod。
3. **交付卡死 gateway** → `/deliver` 同步 `subprocess.run(git push)` 阻塞 aiohttp event loop（healthz 都 HTTP 000）→ 全异步 + 超时。
4. **deliver sid 错** → gateway 发两个 session 事件（网关 sid + claude session_id），app 取了后者 → 只取首个网关 sid。
5. **按钮不出现（file_change 漏检）** → gateway 只对 Edit/Write 发 file_change，Claude 用 Bash 改文件漏 → 按钮改为有 sid 就显示。
6. **按钮不出现（时序）** → `insertMessage` 触发的 reload 早于 set override → 预生成 msgId 先 set 再 insert。
7. **失败无重试** → 点交付立即 `pending=false` → 失败恢复 `pending=true`。
8. **503 ai_offline** → chisel 隧道断（0 client 连接）→ 重启 chisel（现已 systemd 保活）。
9. **push 500 could not read Username** → repo remote HTTPS 缺 credential → `gh auth setup-git`（git 用 gh token，走 github.com:443）。

> 取证方法论（`systematic-debugging`）：多组件系统在每个边界加 instrumentation（logcat / curl / SSH 探测），先定位断在哪层再修；`HTTP 000 time X` 的 X 区分「立即拒（端口没监听=隧道断）」vs「超时（连了不响应=gateway 卡）」。

## 9. 现状与待办

**已完成**：隧道（chisel）+ 流式网关（事件协议 + session/workdir + 异步 deliver）+ server 反代（chat SSE + deliver JSON + 三层鉴权 + 健康推断 + 限流）+ app claude-chat 通道（toggle + agent 气泡 + 多轮 + 交付）+ 脱敏 + 图片禁用 + 权限 repo 化 + systemd/反向 SSH 运维自动化。**端到端真机验证通过**（chat 流式 → 改码 → 推 `claude-chat/<sid>` 分支）。

**二期**：GLM 成本精细化额度池；session 跨设备恢复；多 KimiClaw 容灾（多 chisel client）；Claude 工具权限精细化（白名单可跑命令）；`/admin` 观测页（类似 `/admin/diag`）；workdir 清理策略；deliver pr/auto 模式。

**演进（2026-08-01，AI 工程师模式）**：

- **MCP app 工具链**：gateway 新增 `app_tools_mcp.py`（MCP stdio server），向 Claude Code 暴露 5 个 `app_*` 工具（日志/崩溃/聊天历史/运行时状态/相册摘要）；tool call 经 SSE 下行 `app_tool_request` 到 App，`AppToolExecutor`（`app/core/agenttools/`）采集脱敏后经 `POST /v1/claude-tool-result`（Ktor 新增路由）回传。超时经 `CT_APP_TOOL_TIMEOUT` 配置（默认 60s）。
- **诊断模式移除**：原诊断工单链路（DiagRoute / diag_jobs / diag-worker）已整体删除，诊断能力以「AI Engineer」toggle 并入本实时 chat 通道；三篇 diag spec 已标记 SUPERSEDED。
- **sid 持久化**：`claudeSid` 经 `ClaudeSidStore`（SharedPreferences）持久化，进程重建后可 `--resume` 续上下文。
- 设计 SSOT：`docs/superpowers/specs/2026-08-01-ai-engineer-diag-merge-design.md`。

## 10. 关键文件索引

- 代码：`app/src/main/java/com/mamba/picme/`（`features/chat/ChatViewModel.kt`、`ChatScreen.kt`、`ClaudeAgentRenderer.kt`、`data/remote/picme/ClaudeChatClient.kt` + `ClaudeEvent.kt`）；`server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeChatRoute.kt`；`scripts/claude-tunnel/gateway/`（`server.py`、`claude_events.py`、`session.py`、`claude-settings.json`）。
- 部署/运维：`scripts/claude-tunnel/deploy/`（`bootstrap-kimiclaw.sh`、`install-systemd.sh`、`gateway.service`、`chisel-client.service`、`install-chisel.sh`）。
- 测试：`app/src/test/.../ClaudeSseParserTest` + `ClaudeAgentRendererTest`；`server/src/test/.../ClaudeChatRouteTest` + `ClaudeDeliverRouteTest`；`scripts/claude-tunnel/gateway/test_*.py`。
- 设计 SSOT：`docs/superpowers/specs/2026-07-31-claude-tunnel-chat-design.md`（保留）。
- memory：`claude-tunnel-phase1`（部署状态 + PSK + 踩坑 + 运维现状）。
