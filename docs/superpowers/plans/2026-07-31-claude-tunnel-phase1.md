# Claude Tunnel Phase 1（隧道 + Claude 流式网关）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:executing-plans 逐任务实现（本环境 subagent 不可用，走 inline 执行）。Steps 用 `- [ ]` 复选框跟踪。

**Goal:** 让 `curl` 能经 `api.polang.net` 的 chisel 反向隧道打到 KimiClaw 上的 Claude Code，并按 spec §6 拿到流式 SSE 事件——不依赖 app/server 一行代码。

**Architecture:** KimiClaw 上跑一个 Python（aiohttp）「Claude 流式网关」，spawn `claude -p ... --output-format stream-json`（CLI 路径，已验证吃 GLM，避开 SDK 假设风险），把 stream-json 翻译成 §6 的 SSE 事件。chisel client（KimiClaw）主动连 `wss://api.polang.net/tunnel`（SNI 已验证可达），reverse tunnel 把网关 `127.0.0.1:3000` 暴露到 PoLang 服务器的 `127.0.0.1:3001`。验收：在 PoLang 服务器 `curl -N 127.0.0.1:3001/chat` 看到 SSE 事件流。

**Tech Stack:** Python 3 + aiohttp（网关）、chisel（Go 二进制隧道）、claude CLI（GLM 后端）、nginx wss 反代、systemd。

**关联 spec：** `docs/superpowers/specs/2026-07-31-claude-tunnel-chat-design.md`（§1.4 隧道可行性、§6 事件协议、§7.1 网关、§7.2 隧道、§9 三层鉴权、§12 待验证假设）。

**实现选择（对 spec 的细化）：** 网关用 **spawn `claude` CLI + 解析 stream-json**，**不用 CC SDK**。理由：CLI 已在 KimiClaw 验证可吃 GLM 后端（job #5），SDK 是否继承 GLM 配置未知（spec §12 风险）——选确定性高的路径。spec §7.1 允许此 fallback。

---

## File Structure

**新建（repo 内，纳入提交）—— `scripts/claude-tunnel/`：**

| 文件 | 职责 |
|---|---|
| `gateway/claude_events.py` | stream-json (NDJSON) 行 → §6 SSE 事件 dict 翻译；`format_sse()`。纯函数 |
| `gateway/test_claude_events.py` | 翻译单测 |
| `gateway/session.py` | `SessionManager`：sid→workdir/分支/claude session_id |
| `gateway/test_session.py` | session 单测（fake local repo） |
| `gateway/server.py` | aiohttp 服务：`POST /chat`（SSE）、`POST /deliver`、`GET /healthz` |
| `gateway/requirements.txt` | `aiohttp` |
| `tunnel.env.example` | 配置模板（CT_REPO_URL / 端口 / PSK） |
| `deploy/install-chisel.sh` | 下载 chisel 二进制（server/client 通用） |
| `deploy/chisel-server.service` | PoLang 服务器 systemd unit |
| `deploy/chisel-client.service` | KimiClaw systemd unit |
| `deploy/nginx-tunnel.conf` | nginx `/tunnel` wss 反代片段 |
| `deploy/gateway.service` | KimiClaw 网关 systemd unit |
| `smoke/run-smoke.sh` | mock claude 的端到端 smoke + chisel 本地 reverse tunnel 验证 |
| `smoke/stub-claude.py` | 吐固定 stream-json 行的假 claude |
| `README.md` | 部署/运维说明 |

**新建（repo 外，部署时生成，不提交）：** PoLang 服务器 `/usr/local/bin/chisel`、`/etc/systemd/system/chisel-server.service`、nginx 配置、`/etc/picme/tunnel.env`；KimiClaw `/usr/local/bin/chisel`、`/root/claude-tunnel/`（代码+venv+env）、两个 systemd unit。

**修改（repo 内）：** 无（Phase 1 全新）。

---

## Task 1: stream-json → SSE 事件翻译（纯函数，TDD）

**Files:**
- Create: `scripts/claude-tunnel/gateway/claude_events.py`
- Test: `scripts/claude-tunnel/gateway/test_claude_events.py`

- [ ] **Step 1: 先实测 stream-json 真实结构（在 KimiClaw 跑一次，确认字段名）**

Run（KimiClaw web 终端）:
```bash
IS_SANDBOX=1 claude -p "say hi in one word" --output-format stream-json --dangerously-skip-permissions 2>/dev/null | head -20
```
Expected: 每行一个 JSON。记录事件类型与字段：`system/init`（含 `session_id`）、`assistant`（`message.content[]`，block `type` 为 `text`/`tool_use`）、`user`（`message.content[]` 的 `tool_result`）、`result`（含 `num_turns`）。**若字段名与下述代码出入，调整 `claude_events.py` 的字段映射即可（仅一处）。**

- [ ] **Step 2: 写失败测试**

`scripts/claude-tunnel/gateway/test_claude_events.py`:
```python
import json
from claude_events import translate_stream_line, format_sse


def test_init_emits_session():
    line = json.dumps({"type": "system", "subtype": "init", "session_id": "abc123"})
    assert translate_stream_line(line) == [{"event": "session", "data": {"sid": "abc123"}}]


def test_assistant_text():
    line = json.dumps({"type": "assistant", "message": {"role": "assistant",
                     "content": [{"type": "text", "text": "hello"}]}})
    assert translate_stream_line(line) == [{"event": "assistant_text", "data": {"delta": "hello"}}]


def test_tool_use_and_file_change():
    line = json.dumps({"type": "assistant", "message": {"role": "assistant", "content": [
        {"type": "tool_use", "name": "Edit", "input": {"file_path": "src/Foo.kt"}}]}})
    evs = translate_stream_line(line)
    assert {"event": "tool_use", "data": {"tool": "Edit", "input": {"file_path": "src/Foo.kt"}}} in evs
    assert {"event": "file_change", "data": {"path": "src/Foo.kt", "action": "modified"}} in evs


def test_tool_result_ok():
    line = json.dumps({"type": "user", "message": {"content": [
        {"type": "tool_result", "content": "tests passed", "is_error": False}]}})
    assert translate_stream_line(line) == [{"event": "tool_result", "data": {"ok": True, "summary": "tests passed"}}]


def test_tool_result_error_and_long_summary_truncated():
    long = "x" * 500
    line = json.dumps({"type": "user", "message": {"content": [
        {"type": "tool_result", "content": long, "is_error": True}]}})
    evs = translate_stream_line(line)
    assert evs[0]["data"]["ok"] is False
    assert len(evs[0]["data"]["summary"]) == 300


def test_result_done():
    line = json.dumps({"type": "result", "subtype": "success", "num_turns": 3})
    assert translate_stream_line(line) == [{"event": "done", "data": {"turns": 3}}]


def test_empty_and_garbage_return_empty_list():
    assert translate_stream_line("") == []
    assert translate_stream_line("not json") == []


def test_format_sse():
    s = format_sse({"event": "done", "data": {"turns": 3}})
    assert s == 'event: done\ndata: {"turns": 3}\n\n'
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_claude_events.py -v`
Expected: FAIL（`ModuleNotFoundError: claude_events`）

- [ ] **Step 4: 实现 `claude_events.py`**

`scripts/claude-tunnel/gateway/claude_events.py`:
```python
"""Claude Code stream-json (NDJSON) → claude-tunnel SSE 事件翻译。spec §6 事件协议。"""
import json

# 文件改动类工具 → 额外发 file_change 事件
_FILE_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")


def translate_stream_line(line):
    """一行 stream-json → 事件 dict 列表（空列表=无事件/无法识别）。"""
    line = line.strip()
    if not line:
        return []
    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        return []
    mtype = msg.get("type")
    events = []
    if mtype == "system" and msg.get("subtype") == "init":
        sid = msg.get("session_id")
        if sid:
            events.append({"event": "session", "data": {"sid": sid}})
    elif mtype == "assistant":
        for block in msg.get("message", {}).get("content", []):
            btype = block.get("type")
            if btype == "text" and block.get("text"):
                events.append({"event": "assistant_text", "data": {"delta": block["text"]}})
            elif btype == "tool_use":
                inp = block.get("input") or {}
                events.append({"event": "tool_use", "data": {"tool": block.get("name"), "input": inp}})
                if block.get("name") in _FILE_TOOLS:
                    path = inp.get("file_path") or inp.get("notebook_path")
                    if path:
                        events.append({"event": "file_change", "data": {"path": path, "action": "modified"}})
    elif mtype == "user":
        for block in msg.get("message", {}).get("content", []):
            if block.get("type") == "tool_result":
                events.append({"event": "tool_result", "data": {
                    "ok": not block.get("is_error", False),
                    "summary": _summarize(block.get("content")),
                }})
    elif mtype == "result":
        events.append({"event": "done", "data": {"turns": msg.get("num_turns")}})
    return events


def _summarize(content, limit=300):
    """tool_result content（str 或 list[{type:text,text}]）→ 压空白 + 截断。"""
    if isinstance(content, str):
        s = content
    elif isinstance(content, list):
        s = " ".join(b.get("text", "") for b in content
                     if isinstance(b, dict) and b.get("type") == "text")
    else:
        s = str(content)
    return " ".join(s.split())[:limit]


def format_sse(event):
    return "event: {ev}\ndata: {d}\n\n".format(ev=event["event"], d=json.dumps(event["data"]))
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_claude_events.py -v`
Expected: PASS（8 passed）

- [ ] **Step 6: Commit**

```bash
git add scripts/claude-tunnel/gateway/claude_events.py scripts/claude-tunnel/gateway/test_claude_events.py
git commit -m "feat(claude-tunnel): stream-json → SSE 事件翻译纯函数 + 单测"
```

---

## Task 2: session/workdir 管理（TDD）

**Files:**
- Create: `scripts/claude-tunnel/gateway/session.py`
- Test: `scripts/claude-tunnel/gateway/test_session.py`

- [ ] **Step 1: 写失败测试**

`scripts/claude-tunnel/gateway/test_session.py`:
```python
import os
import shutil
import subprocess
import tempfile

from session import SessionManager


def _make_fake_repo(path):
    os.makedirs(path)
    subprocess.run(["git", "init", "-q"], cwd=path, check=True)
    subprocess.run(["git", "config", "user.email", "t@t"], cwd=path, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=path, check=True)
    with open(os.path.join(path, "README"), "w") as f:
        f.write("hi")
    subprocess.run(["git", "add", "-A"], cwd=path, check=True)
    subprocess.run(["git", "commit", "-qm", "init"], cwd=path, check=True)
    subprocess.run(["git", "branch", "-M", "main"], cwd=path, check=True)


def test_create_clones_and_branches():
    root = tempfile.mkdtemp()
    src = tempfile.mkdtemp()
    _make_fake_repo(src)
    try:
        sm = SessionManager(work_root=root, repo_url=src, base_branch="main")
        sid = sm.create()
        assert sid  # 非空
        assert sm.exists(sid)
        out = subprocess.run(["git", "-C", sm.repo_dir(sid), "rev-parse", "--abbrev-ref", "HEAD"],
                             capture_output=True, text=True, check=True)
        assert out.stdout.strip() == "claude-chat/{}".format(sid)
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(src, ignore_errors=True)


def test_claude_session_roundtrip():
    root = tempfile.mkdtemp()
    try:
        sm = SessionManager(work_root=root, repo_url="unused", base_branch="main")
        sid = "deadbeef"
        os.makedirs(sm.session_dir(sid))
        assert sm.get_claude_session(sid) is None
        sm.set_claude_session(sid, "csid-1")
        assert sm.get_claude_session(sid) == "csid-1"
    finally:
        shutil.rmtree(root, ignore_errors=True)
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_session.py -v`
Expected: FAIL（`ModuleNotFoundError: session`）

- [ ] **Step 3: 实现 `session.py`**

`scripts/claude-tunnel/gateway/session.py`:
```python
"""Session/workdir 管理：一个 app 会话 → 一个 workdir + git 分支 + claude session_id。"""
import os
import subprocess
import uuid


class SessionManager:
    def __init__(self, work_root, repo_url, base_branch="main"):
        self.work_root = work_root
        self.repo_url = repo_url
        self.base_branch = base_branch

    def session_dir(self, sid):
        return os.path.join(self.work_root, sid)

    def repo_dir(self, sid):
        return os.path.join(self.session_dir(sid), "repo")

    def exists(self, sid):
        return os.path.isdir(os.path.join(self.repo_dir(sid), ".git"))

    def create(self):
        """新会话：生成 sid，clone 仓 + checkout claude-chat/<sid>。返回 sid。"""
        sid = uuid.uuid4().hex[:12]
        os.makedirs(self.session_dir(sid), exist_ok=True)
        repo = self.repo_dir(sid)
        subprocess.run(["git", "clone", "--quiet", self.repo_url, repo], check=True)
        subprocess.run(["git", "-C", repo, "checkout", "--quiet",
                        "-B", "claude-chat/{}".format(sid), self.base_branch], check=True)
        subprocess.run(["git", "-C", repo, "config", "user.email", "claude-tunnel@polang"], check=False)
        subprocess.run(["git", "-C", repo, "config", "user.name", "claude-tunnel"], check=False)
        return sid

    def _claude_session_file(self, sid):
        return os.path.join(self.session_dir(sid), ".claude_session")

    def get_claude_session(self, sid):
        f = self._claude_session_file(sid)
        if os.path.exists(f):
            with open(f) as fh:
                return fh.read().strip()
        return None

    def set_claude_session(self, sid, csid):
        with open(self._claude_session_file(sid), "w") as fh:
            fh.write(csid)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_session.py -v`
Expected: PASS（2 passed）

- [ ] **Step 5: Commit**

```bash
git add scripts/claude-tunnel/gateway/session.py scripts/claude-tunnel/gateway/test_session.py
git commit -m "feat(claude-tunnel): session/workdir 管理器 + 单测"
```

---

## Task 3: aiohttp 网关 `/chat`（SSE 流式）

**Files:**
- Create: `scripts/claude-tunnel/gateway/server.py`
- Create: `scripts/claude-tunnel/gateway/requirements.txt`

- [ ] **Step 1: 写 `requirements.txt`**

`scripts/claude-tunnel/gateway/requirements.txt`:
```
aiohttp>=3.9
```

- [ ] **Step 2: 实现 `server.py`**

`scripts/claude-tunnel/gateway/server.py`:
```python
"""Claude 流式网关。POST /chat → SSE；POST /deliver → push 分支；GET /healthz。"""
import asyncio
import os
import subprocess

from aiohttp import web

import claude_events
import session

CLAUDE = os.environ.get("CT_CLAUDE", "claude")
MAX_TURNS = os.environ.get("CT_MAX_TURNS", "20")
WORK_ROOT = os.environ.get("CT_WORK_ROOT", "/tmp/claude-tunnel-work")
REPO_URL = os.environ["CT_REPO_URL"]
BASE_BRANCH = os.environ.get("CT_BASE_BRANCH", "main")

sm = session.SessionManager(WORK_ROOT, REPO_URL, BASE_BRANCH)


async def _send(resp, event):
    await resp.write(claude_events.format_sse(event).encode("utf-8"))


async def chat(request):
    body = await request.json()
    message = body.get("message", "")
    sid = body.get("sid")
    resp = web.StreamResponse(status=200, headers={
        "Content-Type": "text/event-stream", "Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
    await resp.prepare(request)

    if not sid or not sm.exists(sid):
        sid = sm.create()
        await _send(resp, {"event": "session", "data": {"sid": sid}})
        claude_sid = None
    else:
        claude_sid = sm.get_claude_session(sid)

    repo = sm.repo_dir(sid)
    env = dict(os.environ, IS_SANDBOX="1", GIT_TERMINAL_PROMPT="0")
    cmd = [CLAUDE, "-p", message, "--output-format", "stream-json",
           "--max-turns", MAX_TURNS, "--dangerously-skip-permissions"]
    if claude_sid:
        cmd += ["--resume", claude_sid]

    async def pump():
        nonlocal done_sent
        async for raw in proc.stdout:
            for ev in claude_events.translate_stream_line(raw.decode("utf-8", "replace")):
                if ev["event"] == "session":
                    sm.set_claude_session(sid, ev["data"]["sid"])
                if ev["event"] == "done":
                    done_sent = True
                await _send(resp, ev)

    timeout = int(os.environ.get("CT_PHASE_TIMEOUT", "300"))
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
            cwd=repo, env=env)
        try:
            await asyncio.wait_for(pump(), timeout=timeout)
        except asyncio.TimeoutError:
            proc.kill()
            await _send(resp, {"event": "error", "data": {"message": "phase timeout {}s".format(timeout)}})
        await proc.wait()
    except Exception as e:  # noqa: BLE001
        await _send(resp, {"event": "error", "data": {"message": str(e)}})
    if not done_sent:
        await _send(resp, {"event": "done", "data": {}})
    return resp


async def deliver(request):
    """MVP：仅 push 模式——在 workdir commit + push claude-chat/<sid>。pr/auto 二期。"""
    body = await request.json()
    sid = body["sid"]
    repo = sm.repo_dir(sid)
    if not sm.exists(sid):
        return web.json_response({"ok": False, "error": "unknown sid"}, status=404)
    subprocess.run(["git", "-C", repo, "add", "-A"], check=False)
    subprocess.run(["git", "-C", repo, "commit", "-qm", "fix(claude-tunnel): session {}".format(sid)], check=False)
    pushed = subprocess.run(["git", "-C", repo, "push", "--quiet", "origin",
                             "claude-chat/{}".format(sid)], capture_output=True)
    if pushed.returncode == 0:
        return web.json_response({"ok": True, "branch": "claude-chat/{}".format(sid)})
    return web.json_response({"ok": False, "error": pushed.stderr.decode("utf-8", "replace")[:500]}, status=500)


async def healthz(request):
    return web.Response(text="ok")


def main():
    app = web.Application()
    app.router.add_post("/chat", chat)
    app.router.add_post("/deliver", deliver)
    app.router.add_get("/healthz", healthz)
    web.run_app(app, host="127.0.0.1", port=int(os.environ.get("CT_PORT", "3000")))


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: 装依赖 + 启动验证 healthz**

Run:
```bash
cd scripts/claude-tunnel/gateway
python3 -m venv /tmp/ct-venv && /tmp/ct-venv/bin/pip install -q aiohttp
CT_REPO_URL=https://github.com/guoshuai/langchain4android.git /tmp/ct-venv/bin/python server.py &
sleep 2
curl -sf http://127.0.0.1:3000/healthz
kill %1
```
Expected: `ok`（服务起来，healthz 通）

- [ ] **Step 4: Commit**

```bash
git add scripts/claude-tunnel/gateway/server.py scripts/claude-tunnel/gateway/requirements.txt
git commit -m "feat(claude-tunnel): aiohttp 网关 /chat(SSE)/deliver/healthz"
```

> `/chat` 的流式正确性依赖真实 claude，由 Task 8 的 mock-claude smoke + 真机 E2E 覆盖，此处不写易碎的 async-subprocess 单测。

---

## Task 4: 配置模板 + 部署清单文件

**Files:**
- Create: `scripts/claude-tunnel/tunnel.env.example`
- Create: `scripts/claude-tunnel/deploy/install-chisel.sh`
- Create: `scripts/claude-tunnel/deploy/chisel-server.service`
- Create: `scripts/claude-tunnel/deploy/chisel-client.service`
- Create: `scripts/claude-tunnel/deploy/gateway.service`
- Create: `scripts/claude-tunnel/deploy/nginx-tunnel.conf`

- [ ] **Step 1: 写配置模板**

`scripts/claude-tunnel/tunnel.env.example`:
```bash
# 网关（KimiClaw）
CT_REPO_URL=https://github.com/guoshuai/langchain4android.git
CT_BASE_BRANCH=main
CT_CLAUDE=claude
CT_MAX_TURNS=20
CT_PHASE_TIMEOUT=300
CT_PORT=3000
CT_WORK_ROOT=/root/claude-tunnel/work

# chisel 隧道（server 与 client 共用同一 PSK）
CHISEL_PSK=                  # 双端一致，强随机串，如 openssl rand -hex 32
CT_TUNNEL_HOST=api.polang.net
CT_REVERSE_PORT=3001         # server 端暴露的隧道口（只绑 127.0.0.1）
CT_GATEWAY_PORT=3000         # client(KimiClaw) 端网关端口
```

- [ ] **Step 2: 写 chisel 安装脚本**

`scripts/claude-tunnel/deploy/install-chisel.sh`:
```bash
#!/usr/bin/env bash
# 下载 chisel 到 /usr/local/bin/chisel。用法: bash install-chisel.sh [version]
set -euo pipefail
VER="${1:-1.10.3}"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) GOARCH="amd64" ;;
  aarch64|arm64) GOARCH="arm64" ;;
  *) echo "unsupported arch: $ARCH"; exit 1 ;;
esac
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
URL="https://github.com/jpilloura/chisel/releases/download/v${VER}/chisel_${VER}_${OS}_${GOARCH}.gz"
curl -fSL "$URL" | gunzip > /tmp/chisel
chmod +x /tmp/chisel
sudo mv /tmp/chisel /usr/local/bin/chisel
chisel version
```

> ⚠️ 执行时确认 chisel 最新 release tag 与下载 URL（项目用 `jpilloura/chisel` 还是 `cnrad/chisel`——以 GitHub 实际为准），调整 `VER` 与 `URL` 的 owner/repo。

- [ ] **Step 3: 写三个 systemd unit**

`scripts/claude-tunnel/deploy/chisel-server.service`（PoLang 服务器）:
```ini
[Unit]
Description=chisel server (claude-tunnel)
After=network.target

[Service]
ExecStart=/usr/local/bin/chisel server --host 127.0.0.1 --port 8090 --auth ${CHISEL_PSK} --reverse
EnvironmentFile=/etc/picme/tunnel.env
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`scripts/claude-tunnel/deploy/chisel-client.service`（KimiClaw）:
```ini
[Unit]
Description=chisel client (claude-tunnel, egress to api.polang.net)
After=network.target gateway.service

[Service]
ExecStart=/usr/local/bin/chisel client --auth ${CHISEL_PSK} https://${CT_TUNNEL_HOST}/tunnel R:${CT_REVERSE_PORT}:127.0.0.1:${CT_GATEWAY_PORT}
EnvironmentFile=/root/claude-tunnel/tunnel.env
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`scripts/claude-tunnel/deploy/gateway.service`（KimiClaw）:
```ini
[Unit]
Description=claude-tunnel gateway (aiohttp)
After=network.target

[Service]
WorkingDirectory=/root/claude-tunnel/gateway
ExecStart=/root/claude-tunnel/venv/bin/python server.py
EnvironmentFile=/root/claude-tunnel/tunnel.env
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 4: 写 nginx 片段**

`scripts/claude-tunnel/deploy/nginx-tunnel.conf`（PoLang 服务器，并入现有 443 server block）:
```nginx
    location /tunnel {
        proxy_pass http://127.0.0.1:8090;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 86400;
        proxy_send_timeout 86400;
    }
```

- [ ] **Step 5: Commit**

```bash
git add scripts/claude-tunnel/tunnel.env.example scripts/claude-tunnel/deploy/
git commit -m "feat(claude-tunnel): 配置模板 + chisel/gateway 部署清单(systemd/nginx)"
```

---

## Task 5: 部署 chisel server 到 PoLang 服务器 + nginx /tunnel

> 在 PoLang 服务器（`ubuntu@43.161.201.142`，ubuntu sudo 免密）执行。

- [ ] **Step 1: 装 chisel + 配 PSK**

Run（本机 ssh 上去）:
```bash
ssh ubuntu@43.161.201.142 'sudo bash -s' < scripts/claude-tunnel/deploy/install-chisel.sh
PSK=$(openssl rand -hex 32)
ssh ubuntu@43.161.201.142 "echo CHISEL_PSK=$PSK | sudo tee /etc/picme/tunnel.env >/dev/null"
echo "PSK=$PSK"  # 记下，KimiClaw 侧 Task 7 要用同一值
```
Expected: chisel 版本打印；`/etc/picme/tunnel.env` 含 `CHISEL_PSK=...`

- [ ] **Step 2: 装 chisel-server systemd unit + 启动**

Run:
```bash
scp scripts/claude-tunnel/deploy/chisel-server.service ubuntu@43.161.201.142:/tmp/
ssh ubuntu@43.161.201.142 'sudo mv /tmp/chisel-server.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now chisel-server && sleep 1 && sudo systemctl is-active chisel-server'
```
Expected: `active`

- [ ] **Step 3: nginx 加 /tunnel + reload**

Run:
```bash
ssh ubuntu@43.161.201.142 'ls /etc/nginx/sites-enabled/ /etc/nginx/conf.d/ 2>/dev/null'  # 找到 443 server block 所在文件
```
把 `deploy/nginx-tunnel.conf` 的 `location /tunnel {...}` 并入 443 server block（执行者用 `sudo nginx -t` 校验后 reload）:
```bash
ssh ubuntu@43.161.201.142 'sudo nginx -t && sudo systemctl reload nginx'
```
Expected: `nginx -t` 语法 OK

- [ ] **Step 4: 从本机验 /tunnel 可达（应拒绝无 PSK 的连入）**

Run: `curl -sI https://api.polang.net/tunnel`
Expected: 非 200（chisel 协议握手失败，证明 nginx 反代到 chisel server 了，不是 404）

> 无需 commit（部署产物在服务器）。

---

## Task 6: 部署网关 + chisel client 到 KimiClaw

> 在 KimiClaw web 终端（root）执行。需 Task 5 的 `PSK`。

- [ ] **Step 1: 落地网关代码 + venv**

Run（KimiClaw）:
```bash
mkdir -p /root/claude-tunnel
# 把 scripts/claude-tunnel/gateway 拷到 /root/claude-tunnel/gateway（从仓库 scp 或 git clone）
cd /root/claude-tunnel
python3 -m venv venv
venv/bin/pip install -q aiohttp
```

- [ ] **Step 2: 配 tunnel.env**

Run（KimiClaw，填入 Task 5 的 PSK）:
```bash
cat > /root/claude-tunnel/tunnel.env <<'EOF'
CT_REPO_URL=https://github.com/guoshuai/langchain4android.git
CT_BASE_BRANCH=main
CT_CLAUDE=claude
CT_MAX_TURNS=20
CT_PHASE_TIMEOUT=300
CT_PORT=3000
CT_WORK_ROOT=/root/claude-tunnel/work
CHISEL_PSK=<Task5的PSK>
CT_TUNNEL_HOST=api.polang.net
CT_REVERSE_PORT=3001
CT_GATEWAY_PORT=3000
EOF
```

- [ ] **Step 3: 装 chisel + 起 gateway + chisel-client systemd unit**

Run（KimiClaw）:
```bash
bash <仓库>/scripts/claude-tunnel/deploy/install-chisel.sh
cp <仓库>/scripts/claude-tunnel/deploy/gateway.service /etc/systemd/system/
cp <仓库>/scripts/claude-tunnel/deploy/chisel-client.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now gateway
systemctl enable --now chisel-client
sleep 2
systemctl is-active gateway chisel-client
curl -sf http://127.0.0.1:3000/healthz
```
Expected: 两个 `active`；网关 healthz 返回 `ok`

- [ ] **Step 4: 验证隧道已建（PoLang 服务器侧 3001 口出现）**

Run（本机 ssh PoLang 服务器）:
```bash
ssh ubuntu@43.161.201.142 'curl -sf -m 5 http://127.0.0.1:3001/healthz'
```
Expected: `ok`（证明 reverse tunnel 通：server 3001 → KimiClaw 3000 网关）

> 无需 commit。

---

## Task 7: 端到端验证（真 Claude + 真隧道）

- [ ] **Step 1: 从 PoLang 服务器经隧道发真 chat**

Run（本机 ssh PoLang 服务器）:
```bash
ssh ubuntu@43.161.201.142 'curl -N -m 120 -X POST http://127.0.0.1:3001/chat -H "Content-Type: application/json" -d "{\"message\":\"用一句话自我介绍\"}"'
```
Expected: 流式 SSE 事件，至少看到 `event: session`、`event: assistant_text`、`event: done`。GLM 推理慢，耐心等到 done。

- [ ] **Step 2: 多轮验证（带 sid resume）**

从 Step 1 的 `session` 事件取 `sid`，Run:
```bash
ssh ubuntu@43.161.201.142 'curl -N -m 120 -X POST http://127.0.0.1:3001/chat -H "Content-Type: application/json" -d "{\"sid\":\"<Step1的sid>\",\"message\":\"我刚说了啥？\"}"'
```
Expected: Claude 能引用上一轮内容（证明 `--resume` 多轮上下文生效）。

- [ ] **Step 3: 交付验证（push 分支）**

Run:
```bash
ssh ubuntu@43.161.201.142 'curl -X POST http://127.0.0.1:3001/deliver -H "Content-Type: application/json" -d "{\"sid\":\"<Step1的sid>\"}"'
```
Expected: `{"ok":true,"branch":"claude-chat/<sid>"}`（或 `ok:false` + git push 权限错误，需配 KimiClaw 的 git/SSH push 凭证——见 README）。

> 无需 commit。此步若 push 失败，确认 KimiClaw 的 `git remote set-url origin <ssh-url>` + SSH key 已配（diag-worker 已配，复用）。

---

## Task 8: smoke 脚本（mock claude，本地可跑，验证胶水）

**Files:**
- Create: `scripts/claude-tunnel/smoke/stub-claude.py`
- Create: `scripts/claude-tunnel/smoke/run-smoke.sh`

- [ ] **Step 1: 写 stub-claude（吐固定 stream-json 的假 claude）**

`scripts/claude-tunnel/smoke/stub-claude.py`:
```python
#!/usr/bin/env python3
"""假 claude：忽略入参，吐固定的 stream-json 事件序列。供 smoke 验证网关翻译。"""
import sys
events = [
    {"type": "system", "subtype": "init", "session_id": "stub-session-1"},
    {"type": "assistant", "message": {"role": "assistant",
      "content": [{"type": "text", "text": "hello from stub"}]}},
    {"type": "assistant", "message": {"role": "assistant", "content": [
        {"type": "tool_use", "name": "Bash", "input": {"command": "echo hi"}}]}},
    {"type": "user", "message": {"content": [
        {"type": "tool_result", "content": "hi", "is_error": False}]}},
    {"type": "result", "subtype": "success", "num_turns": 2},
]
import json
for e in events:
    print(json.dumps(e), flush=True)
```

- [ ] **Step 2: 写 smoke 脚本**

`scripts/claude-tunnel/smoke/run-smoke.sh`:
```bash
#!/usr/bin/env bash
# 用 stub-claude 验证网关 /chat 的 SSE 翻译。本机可跑，不依赖真 claude/隧道。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GW="$HERE/../gateway"
VENV="${CT_SMOKE_VENV:-/tmp/ct-smoke-venv}"
[ -d "$VENV" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" install -q aiohttp

# 让 server.py 的 CT_CLAUDE 指向 stub
export CT_CLAUDE="$HERE/stub-claude.py"
export CT_REPO_URL="."   # session.create 会 git clone .（空仓会失败，下面用已存在 sid 绕过）
export CT_WORK_ROOT="${CT_WORK_ROOT:-/tmp/ct-smoke-work}"
mkdir -p "$CT_WORK_ROOT"

"$VENV/bin/python" "$GW/server.py" &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
sleep 2

# 预置一个 fake session workdir，绕过 clone（让 sm.exists 返回 true，走 resume 分支也测到）
SID="smokestub"
mkdir -p "$CT_WORK_ROOT/$SID/repo/.git"

OUT="$(curl -fs -m 10 -X POST http://127.0.0.1:3000/chat \
  -H 'Content-Type: application/json' -d "{\"message\":\"hi\"}")"
echo "$OUT"
echo "$OUT" | grep -q 'event: assistant_text' && echo "PASS: assistant_text"
echo "$OUT" | grep -q 'event: tool_use' && echo "PASS: tool_use"
echo "$OUT" | grep -q 'event: tool_result' && echo "PASS: tool_result"
echo "$OUT" | grep -q 'event: done' && echo "PASS: done"
```

- [ ] **Step 3: 跑 smoke**

Run: `bash scripts/claude-tunnel/smoke/run-smoke.sh`
Expected: 输出含 `PASS: assistant_text`、`PASS: tool_use`、`PASS: tool_result`、`PASS: done`

> 注：smoke 走的是「首次 chat → create() clone」分支，因 `CT_REPO_URL=.` 会 clone 失败。执行时若 smoke 因此挂，把 stub 测试改为先 `git init` 一个本地 fake repo 指给 `CT_REPO_URL`（仿 `test_session.py` 的 `_make_fake_repo`）。

- [ ] **Step 4: Commit**

```bash
git add scripts/claude-tunnel/smoke/
git commit -m "test(claude-tunnel): mock-claude smoke 验证网关 SSE 翻译"
```

---

## Task 9: README + 收尾

**Files:**
- Create: `scripts/claude-tunnel/README.md`

- [ ] **Step 1: 写 README**

`scripts/claude-tunnel/README.md`:
```markdown
# claude-tunnel（Phase 1）

chisel wss 反向隧道 + Claude 流式网关。让外部经 api.polang.net 实时、流式、多轮驱动 KimiClaw 上的 Claude Code（GLM 后端）。spec：`docs/superpowers/specs/2026-07-31-claude-tunnel-chat-design.md`。

## 拓扑
app → api.polang.net(nginx /v1/claude-chat，Phase 2) → 127.0.0.1:3001(chisel 隧道口) → KimiClaw 127.0.0.1:3000(网关) → claude --resume(GLM)。
Phase 1 验收不含 app/server：直接 `curl 127.0.0.1:3001/chat`。

## 部署
1. PoLang 服务器：`deploy/install-chisel.sh` → 配 `/etc/picme/tunnel.env`(CHISEL_PSK) → 装 `chisel-server.service` → nginx 加 `/tunnel`。见 plan Task 5。
2. KimiClaw：落 `gateway/` + venv → 配 `tunnel.env`(同 PSK) → 装 `gateway.service` + `chisel-client.service`。见 plan Task 6。
3. 验证：`curl http://127.0.0.1:3001/healthz`(服务器上) → `ok`。

## 三层鉴权（spec §9）
- chisel PSK（client→server 建隧道）
- 端口只绑 127.0.0.1（3001/3000/8090）
- （Phase 2 补 Ktor X-App-Token）

## 网关本地开发
`cd gateway && python3 -m pytest -v`（翻译 + session 单测）；`bash smoke/run-smoke.sh`（mock claude）。

## 已知限制（Phase 1）
- 网关以 root + `--dangerously-skip-permissions` 跑（spec §10 root 风险，接受）。
- deliver 仅 push 模式（pr/auto 二期）。
- 无并发隔离（多 session 共享 KimiClaw 资源，Phase 1 单用户够用）。
```

- [ ] **Step 2: Commit**

```bash
git add scripts/claude-tunnel/README.md
git commit -m "docs(claude-tunnel): Phase 1 README（部署/鉴权/限制）"
```

---

## Phase 1 完成标准

- [ ] 翻译 + session 单测全绿（Task 1/2）。
- [ ] 网关 healthz 本地起来（Task 3）。
- [ ] PoLang 服务器 chisel-server active + nginx /tunnel 反代生效（Task 5）。
- [ ] KimiClaw gateway + chisel-client active，服务器 `curl 127.0.0.1:3001/healthz` → `ok`（Task 6）。
- [ ] 真实 chat 经隧道流式返回 `session`/`assistant_text`/`done`，多轮 resume 生效，deliver 推分支（Task 7）。
- [ ] mock-claude smoke 本地 PASS（Task 8）。
- [ ] **§12 待验证假设落地**：spawn CLI 路径实测吃 GLM（Task 7 Step 1 见到真实 GLM 回复即确认）。

Phase 1 通过后，再写 Phase 2（server Ktor 反代 + 三层鉴权补齐）与 Phase 3（app）plan。
