# Claude Tunnel Phase 3 调试问题记录（incident log）

> **日期**：2026-08-01
> **范围**：Phase 3（app 接入 claude-tunnel）实现后，真机端到端验收「AI 工程师 chat → 改码 → 交付分支」时连续踩到的 9 个问题。
> **最终结果**：全链路打通——app → server `/v1/claude-chat`（SSE）→ gateway → Claude（GLM）→ 改码 → `/v1/claude-deliver` → gateway commit + `git push`（gh token）→ GitHub `claude-chat/<sid>` 分支。
> **方法论**：`superpowers:systematic-debugging`——取证驱动，不猜修。每个问题先取证（logcat / curl / SSH 探测）定位断点，再修根因。
> **关联**：spec `2026-07-31-claude-tunnel-chat-design.md`；plan `2026-08-01-claude-tunnel-phase3.md`；memory `claude-tunnel-phase1`。

---

## 问题速查表

| # | 症状 | 根因层 | 修复 commit |
|---|---|---|---|
| 1 | Claude 回复「写入操作未获得授权」 | gateway 权限白名单缺 Edit/Write | repo 化（见下） |
| 2 | 点交付 → app 收到 `HTTP 404`（空 body） | prod server 没部署 `/v1/claude-deliver` 路由 | 部署 prod |
| 3 | 点交付 → gateway healthz 也 `HTTP 000`，app deliver 永久阻塞 | gateway `/deliver` 同步 `subprocess.run(git push)` 卡死 aiohttp event loop | `f0b6dc1e` |
| 4 | （隐含）deliver 发错 sid → gateway 找不到 workdir | app 取了 claude session_id，而非网关 sid | `f0b6dc1e` |
| 5 | 交付按钮不出现 | gateway 只对 Edit/Write 发 `file_change`；Claude 用 Bash 改文件漏检 | `6cc73a5a`（放宽） |
| 6 | 交付按钮不出现（override 挂了但不渲染） | `persistClaudeBubble` insert 触发的 reload 早于 set override | `6cc73a5a`（时序） |
| 7 | 点交付失败后按钮永久消失，无法重试 | `confirmClaudeDeliver` 点时立即 `pending=false`，不看结果 | `6cc73a5a`（重试） |
| 8 | 重新发消息 → `503 ai_offline` | chisel 隧道断（0 个 client 连接） | 运维：重启 chisel client |
| 9 | `git push` → `HTTP 500 could not read Username` | repo remote HTTPS，git 无 credential | 运维：`gh auth setup-git` |

---

## 问题 1：Claude 写文件被拒——「写入操作未获得授权」

**症状**：app 发「在 README.md 末尾加 AI工程师」，Claude 回复文本说尝试写入但「未获得授权」，请授予写权限。

**取证**：commit `df354bec` 的 message 自述「chat 实测吐 assistant_text 通过」——**当时只测了文本流，没测改码**。该 commit 把 `--dangerously-skip-permissions` 换成 `~/.claude/settings.json` 的 `permissions.allow`，但 allow 列表缺 `Edit`/`Write`。且该 settings.json 只活在 KimiClaw 上（手动配、不可复现）。

**根因**：① 权限白名单漏写工具；② 配置在 KimiClaw `~/.claude/settings.json`，不进 repo，重装即丢。

**修复**：权限白名单 repo 化——新建 `scripts/claude-tunnel/gateway/claude-settings.json`（`permissions.allow`：Read/Glob/Grep/**Edit/Write/MultiEdit**/NotebookEdit/Bash/WebSearch/WebFetch/TodoWrite，非 bypass）；`server.py` 抽 `build_cmd()` 用 `--settings` 指定它（`CT_SETTINGS` 可覆盖），随 `gateway/` 落盘即生效。加 `test_server.py` 测 `build_cmd`。README 同步去掉过时的 `--dangerously-skip-permissions` 描述。

**commit**：`feat(claude-tunnel): 权限白名单 repo 化`（gateway/claude-settings.json + server.py build_cmd + test_server + README）。

---

## 问题 2：点交付 → app 收到 `HTTP 404`（空 body）

**症状**：app 点交付，气泡无 ✅ 也无 ❌（实际是 `confirmClaudeDeliver` onFailure，但首版无日志看不见）。

**取证（加诊断日志后）**：logcat `confirmClaudeDeliver: deliver returned in 75ms isSuccess=false` / `failure RuntimeException: HTTP 404:` —— 注意 **body 为空**。

**关键推理**：空 body 的 404 是 **Ktor 路由不存在**的典型特征（请求没进 handler）。但之前 `curl /v1/claude-deliver`（无 token）返回 `401`——看似路由存在。读 `Application.kt` 发现鉴权是 **application 级全局拦截器**（`intercept(ApplicationCallPipeline.Plugins)`），无 token 时在路由前就 401，**不能证明路由存在**。SSH prod `healthz` → `version: 0.9.2`（Phase 2 部署，含 `/v1/claude-chat` 但**不含本 session 才加的 `/v1/claude-deliver`**）。

**根因**：prod server 没部署 `/v1/claude-deliver` 路由（代码进了 repo 但没上线）。

**修复**：`./server/deploy.sh` 部署 prod（build → rsync → 蓝绿 → healthz ✅）。

**教训**：`curl 无 token → 401` 在「全局鉴权拦截器」架构下**不能**证明路由存在；带 token 才能区分「路由不存在（404 空 body）」vs「鉴权失败（401）」。

---

## 问题 3：点交付 → gateway 卡死，app deliver 永久阻塞

**症状**：部署 prod 后再点交付，logcat 只到 `calling deliver ...`，没有 `returned`（等 8s+ 仍无）。

**取证**：SSH prod 直接探 gateway（经隧道口 `127.0.0.1:3001`）：
```
gateway healthz → HTTP 000 time 5.0s   （5s 超时无响应）
gateway /deliver → HTTP 000 time 20s   （20s 超时无响应）
```
**gateway 连 healthz 都不响应了** = event loop 被卡死。

**根因**：gateway `/deliver` 用**同步** `subprocess.run(["git","push",...])`，而 aiohttp 是单线程 event loop。git push 一挂起，`subprocess.run` 阻塞 → 整个 event loop 卡死 → 所有请求（含 healthz）无响应 → app 的 deliver（`ClaudeChatClient` 复用 SSE 的 `readTimeout=0` client）跟着永久阻塞。

**修复**（`f0b6dc1e`）：
- gateway `/deliver` 全改异步（`asyncio.create_subprocess_exec`）+ `CT_PUSH_TIMEOUT`（默认 30s）+ `GIT_TERMINAL_PROMPT=0` + push 失败/超时**回传 stderr**（不再阻塞 event loop，且暴露 push 真实错误）。
- app `ClaudeChatClient.deliver` 用独立 `deliverClient`（`readTimeout=60s`），不复用 SSE 的 `readTimeout=0`。

---

## 问题 4：deliver 发错 sid（隐含，问题 2/3 修后暴露）

**取证**：logcat 事件流有**两个** `claude evt: Session`：
```
Session sid=98c43c71ab23          ← 网关 sid（workdir/deliver key，sm.create() 生成）
Session sid=8ab6a911-cc42-...     ← claude session_id（stream-json 的 system init，gateway 内部 --resume 用）
```
gateway 把两者都透传给 app。app 回调 `claudeSid = event.sid` 覆盖成**后者**（claude session_id）。但 gateway `/deliver` 用 `sm.exists(sid)` 查 workdir（key=网关 sid）→ 找不到 → `404 unknown sid`。

**根因**：gateway 发了两个语义不同的 session 事件，app 无法区分，取了错的那 个。

**修复**（`f0b6dc1e`）：app 只采纳**首个** session 事件（网关 sid），`if (claudeSid == null) claudeSid = event.sid`，忽略 claude init 的 session。验证：`confirmClaudeDeliver: ov=98c43c71ab23/true`（网关 sid）。

---

## 问题 5：交付按钮不出现——file_change 漏 Bash 改文件

**症状**：发「在 README.md 加 test」，收到 Claude 回复，但**没交付按钮**。

**取证**：读 `claude_events.py:31`：`file_change` 只在 `block.name in ("Edit","Write","MultiEdit","NotebookEdit")` 时发（tool_use 时刻）。Claude（GLM 后端）**有时用 `Bash`（`echo >> / sed`）改文件**而非 Edit 工具 → 不发 file_change → `hasFileChange=false` → 按钮不挂。另一次取证（Claude 用了 Edit）按钮正常出现，印证工具差异。

**根因**：交付按钮依赖 `file_change` 事件，而该事件漏检 Bash 改文件。

**修复**（`6cc73a5a`）：交付按钮触发条件放宽——**有网关 sid 就显示**，不依赖 `file_change`。spec §8：交付 = push workdir，是用户主动动作，由用户判断是否要交付（即使 Claude 只聊天没改文件，按钮在也无害；gateway `/deliver` 无条件 commit+push）。

---

## 问题 6：交付按钮不出现——override 时序（override 挂了但不渲染）

**症状**：问题 5 放宽后，发消息仍没按钮。但 logcat 显示 override **已挂上**：
```
10:07:33.453  persistClaudeBubble: hasFileChange=true claudeSid=0c9b4d50d459 ...
10:07:33.464  persistClaudeBubble: deliver override attached msgId=02ddb158...
```

**取证（时序分析）**：`insertMessage`（触发 `loadMessages` reload）发生在 `33.453` 之后、override set（`33.464`）**之前**。reload 执行时 `claudeDeliverOverrides[msgId]` 还是 null → 按钮不渲染。之后没有新 Room 写入再触发 reload → 按钮**永不出现**。diag 没此问题是因为它直接 `_messages.update`（不依赖 reload）。

**根因**：`persistClaudeBubble` 先 `insertMessage`（触发 reload）再 set override，set 晚于 reload。

**修复**（`6cc73a5a`）：预生成 `msgId`，**先 set override，再 `insertMessage`**——保证 reload 发生时 override 已在 map。loadMessages 重放时 `claudeDeliverOverrides[ui.id]` 命中 → 按钮渲染。

---

## 问题 7：点交付失败后按钮永久消失，无法重试

**症状**：点交付失败（如问题 9 的 credential 错），按钮消失，再点不了。

**取证**：读 `confirmClaudeDeliver`：点时立即 `claudeDeliverOverrides[messageId] = ov.copy(pending = false)`，按钮（`if (cd.pending)` 渲染）消失，不管成功失败。

**根因**：点交付立即 `pending=false`，失败时没恢复。

**修复**（`6cc73a5a`）：deliver 返回后判断 `delivered = isSuccess && json.ok && branch 非空`。**成功**才 `pending=false`（隐藏）；**失败**恢复 `pending=true`（按钮重现，可重试）。`claudeDeliverOverrides[messageId] = ov.copy(pending = !delivered)`。

---

## 问题 8：重新发消息 → `503 ai_offline`（chisel 隧道断）

**症状**：app 发消息收到 503（server `ai_offline`）。

**取证**：SSH prod：
```
隧道口 3001 healthz → HTTP 000 time 0.0001s   （立即拒，端口没人监听）
chisel-server 端口：只有 8090（chisel server 自己），没有 3001
chisel client 连接数：0
```
对比问题 3（gateway 卡死时是 `5s 超时`，隧道还在、gateway 不响应），这次是 `0.0001s 立即拒` = **隧道彻底断**（KimiClaw chisel client 没连）。

**根因**：重启 gateway 时连带影响了 chisel client，或 chisel client 自己断了（运维层，非代码 bug）。

**修复**：KimiClaw 重启 chisel client 重连隧道。

---

## 问题 9：`git push` → `HTTP 500 could not read Username`

**症状**：交付按钮出现 + 点了，gateway 异步版返回 500 + stderr。

**取证**：logcat `confirmClaudeDeliver: failure HTTP 500: {"ok": false, "error": "fatal: could not read Username for 'https://github.com': terminal prompts disabled"}`。

**根因**：gateway workdir 的 repo remote 是 **HTTPS**（`https://github.com/...`），`git push` 走 HTTPS 需要 credential；KimiClaw 上 git 没配 HTTPS credential（问题 3 修复时加的 `GIT_TERMINAL_PROMPT=0` 又禁止交互输入）→ 推不动。用户配过 `gh`（GitHub CLI）但没让 git 用它。

**修复**：KimiClaw 跑 `gh auth setup-git`（配 git credential helper 用 gh token）。之后 gateway 的 `git push` 读全局 `~/.gitconfig` 自动用 gh token（github.com:443，clone 已证通）。**不用重启 gateway**（git 每次读 config）。验证：再点交付 → `✅ 已交付分支：claude-chat/<sid>`，GitHub 上有该分支。

> 备选：把 workdir remote 改 SSH（`git@github.com:...`，用 SSH key）。但 SSH 走 github.com:22，不在 KimiClaw 出站白名单（白名单含 api.github.com，github.com:443 实测通），不如 gh（走 443）稳。

---

## 最终端到端验证

`gh auth setup-git` 后，手机：发「在 README.md 末尾加 test」→ Claude 用 Edit 改文件（`file_change` 发）→ agent 气泡（文本 + 步骤 ⏳→✓ + 文件徽标）+ 交付按钮 → 点交付 → 气泡 `✅ 已交付分支：claude-chat/<sid>` → GitHub 仓库出现 `claude-chat/<sid>` 分支，含 README 改动。**全链路通**。

---

## 调试方法论（`systematic-debugging`）

1. **Iron Law**：no fixes without root cause。每个问题先取证再修，不猜。
2. **多组件系统**：app → server → gateway → git → GitHub，每个边界加 instrumentation（logcat 日志 / curl 探测 / SSH 诊断），跑一次看断在哪层。
3. **取证手段**：
   - app 侧：`ChatViewModel` 加 `Logger.i` 到事件回调 + `confirmClaudeDeliver` 全链路（`adb logcat -d | grep "claude evt:|confirmClaudeDeliver:"`）。
   - server/gateway 侧：SSH prod `curl 127.0.0.1:3001/healthz`（隧道口）、`ss -tlnp`（端口监听）、`ss -tnp | grep chisel`（连接数）。
4. **区分症状**：`HTTP 000 time X` 的 X 区分「立即拒（端口没监听=隧道断）」vs「超时（连了但不响应=gateway 卡）」。空 body 的 404 = 路由不存在。

---

## 运维附录（KimiClaw 侧）

- **repo 位置**：`/root/polang`（注意：不是 `/root/.openclaw/workspace`）。
- **进程模型**：gateway + chisel 都是**手动进程**（非 systemd；systemd unit 在 repo `scripts/claude-tunnel/deploy/` 但未启用）。
- **gateway 重启**：`/tmp/restart-gw.sh`（source `tunnel.env` + exec python3，PID 随变）。关键：要 `git pull` 拿新版后再重启。
- **chisel client 重连**（隧道断了）：
  ```bash
  nohup chisel client --auth tunnel:d1a88674601fe6442c043acef68d96657515c3bbed4da57d \
    https://api.polang.net/tunnel R:3001:127.0.0.1:3000 >/tmp/ct-cc.log 2>&1 &
  ```
- **git push credential**：`gh auth setup-git`（一次性，git 用 gh token）。
- **验证隧道通**：prod 上 `curl 127.0.0.1:3001/healthz` 秒回 `ok`（0.0001s 立即拒 = 隧道断；5s 超时 = gateway 卡）。

---

## 涉及 commit（origin/main）

- `feat(app): ClaudeAgentRenderer（§6→气泡状态折叠）+ cost 事件`（Task 2）
- `feat(app): ChatViewModel claude-chat 模式`（Task 3）
- `feat(app): ChatScreen AI 工程师 toggle + agent 气泡渲染`（Task 4）
- `feat(server): POST /v1/claude-deliver 反代网关 /deliver`（Task 5）
- `feat(claude-tunnel): 权限白名单 repo 化`（问题 1）
- `fix(claude-tunnel): 交付链路——gateway /deliver 异步+超时 + app sid/超时`（问题 3/4，`f0b6dc1e`）
- `fix(app): claude 交付按钮——时序/触发/重试`（问题 5/6/7，`6cc73a5a`）
