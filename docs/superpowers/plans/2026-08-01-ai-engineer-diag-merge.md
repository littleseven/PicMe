# AI 工程师模式合并诊断能力 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将诊断模式完全合并进 AI 工程师模式：云主机 Claude Code 通过 MCP tool calls 按需拉取 App 日志/崩溃/聊天历史/运行时状态/相册摘要，旧 diag 链路（App 状态机 + Ktor DiagRoute + diag-worker）整体移除。

**Architecture:** 云主机 gateway 新增 stdio MCP server（`app_tools_mcp.py`），tool call → gateway `POST /app-tool-request` 长轮询 → 经当前活跃 SSE 流下行 `app_tool_request` 事件 → App `AppToolExecutor` 采集脱敏 → `POST /v1/claude-tool-result`（Ktor 反代 → gateway `/tool-result`）→ 解挂返回 Claude。关键前提：tool call 只发生在 claude 回合进行中，此时 SSE 流必然打开，**无需 idle 保活**（对 spec §3.2 的简化，已确认）。

**Tech Stack:** Kotlin/Android (OkHttp SSE, Room, JUnit4)、Ktor (Kotlin)、Python 3 aiohttp（gateway，无新增依赖，MCP 协议手写 NDJSON JSON-RPC）。

**Spec:** `docs/superpowers/specs/2026-08-01-ai-engineer-diag-merge-design.md`

**执行约定：**
- 每个 Task 完成后按其末尾的 commit 步骤提交
- App 测试：`./gradlew :app:testDebugUnitTest --tests "<class>"`；Ktor：`./gradlew -p server test`（server 是独立 Gradle 工程，不在 Android settings.gradle 内）；gateway：`cd scripts/claude-tunnel/gateway && python3 -m pytest <file> -v`
- gateway 代码部署在云主机，本 repo 只改 `scripts/claude-tunnel/gateway/`，部署本身不在本计划内

---

## 关键文件地图

**新增：**
- `scripts/claude-tunnel/gateway/app_tools_mcp.py` — stdio MCP server（手写协议，无第三方依赖）
- `scripts/claude-tunnel/gateway/test_app_tools_mcp.py` — MCP 协议单测
- `app/src/main/java/com/mamba/picme/core/agenttools/AppTool.kt` — 工具枚举
- `app/src/main/java/com/mamba/picme/core/agenttools/AppToolExecutor.kt` — 采集分发 + 脱敏 + 截断
- `app/src/main/java/com/mamba/picme/core/agenttools/RuntimeStateProvider.kt` — 运行时状态接口
- `app/src/main/java/com/mamba/picme/features/chat/ClaudeSidStore.kt` — claudeSid 持久化
- `app/src/test/java/com/mamba/picme/core/agenttools/AppToolExecutorTest.kt`
- `app/src/test/java/com/mamba/picme/data/remote/picme/ClaudeSseParserTest.kt`
- `server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeToolResultRoute.kt`
- `server/src/test/kotlin/com/mamba/picme/server/ClaudeToolResultRouteTest.kt`
- `server/migrations/009_drop_diag_jobs.sql`

**修改：**
- `scripts/claude-tunnel/gateway/server.py`（sse registry + 2 路由 + build_cmd 接线 + 启动时生成 mcp-config）
- `scripts/claude-tunnel/gateway/claude-settings.json`（允许 mcp__app_tools）
- `scripts/claude-tunnel/gateway/test_server.py`（新路由测试）
- `server/src/main/kotlin/com/mamba/picme/server/Application.kt`（注册新路由、移除 diagRoute）
- `app/src/main/java/com/mamba/picme/data/remote/picme/ClaudeEvent.kt`、`ClaudeChatClient.kt`（事件 + postToolResult）
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（接线 + diag 移除 + sid 持久化）
- `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（移除诊断 toggle）
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`（移除 diagClient）
- `app/src/main/res/values*/strings.xml`（移除 diag_*，新增 app tool 文案）

**删除：**
- `app/src/main/java/com/mamba/picme/features/chat/DiagChatSession.kt`、`DiagPrompts.kt`
- `app/src/main/java/com/mamba/picme/data/remote/picme/DiagClient.kt`
- `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelDiagTest.kt`、`DiagPromptsTest.kt`
- `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt` + 其测试
- `scripts/diag-worker/`（整个目录）

---

## Task 1: Gateway — app-tool 桥接路由（SSE 注册表 + /app-tool-request + /tool-result）

**Files:**
- Modify: `scripts/claude-tunnel/gateway/server.py`
- Test: `scripts/claude-tunnel/gateway/test_server.py`（追加，先读该文件匹配现有 pytest 风格）

**设计要点：** tool call 只发生在 claude 回合进行中，此时 `/chat` 的 SSE StreamResponse 必然打开。模块级注册表 `SSE_HUB: dict[sid, web.StreamResponse]` 在 chat() prepare 后注册、finally 注销。MCP server 是 claude 的子进程（独立进程），通过 localhost HTTP 长轮询与 gateway 通信。

- [ ] **Step 1: 写失败测试**

在 `test_server.py` 追加（风格对齐文件内既有测试）：

```python
async def test_tool_result_roundtrip(aiohttp_client):
    """POST /app-tool-request 挂起 → POST /tool-result 解挂并返回 payload。"""
    import server
    app = web.Application()
    app.router.add_post("/app-tool-request", server.app_tool_request)
    app.router.add_post("/tool-result", server.tool_result)
    client = await aiohttp_client(app)

    async def post_result():
        await asyncio.sleep(0.1)
        await client.post("/tool-result", json={
            "requestId": server._LAST_REQUEST_ID,  # 测试钩子：最近一次下发的 requestId
            "payload": {"logs": "hello"},
        })

    # 无活跃 SSE 时推送到 sid 应直接报错（走错误分支前先注册一个假 SSE 较复杂，
    # 故本用例用 server.SSE_HUB 直接塞一个 mock writer）
    class FakeResp:
        async def write(self, data: bytes):
            self.data = data
    server.SSE_HUB["sid1"] = FakeResp()
    try:
        result, _ = await asyncio.gather(
            client.post("/app-tool-request", json={"sid": "sid1", "tool": "app_get_logs", "args": {}}),
            post_result(),
        )
        body = await result.json()
        assert body["ok"] is True
        assert body["payload"] == {"logs": "hello"}
    finally:
        server.SSE_HUB.pop("sid1", None)


async def test_app_tool_request_no_active_sse(aiohttp_client):
    """sid 无活跃 SSE → 立即返回 ok=false，不挂起。"""
    import server
    app = web.Application()
    app.router.add_post("/app-tool-request", server.app_tool_request)
    client = await aiohttp_client(app)
    resp = await client.post("/app-tool-request", json={"sid": "ghost", "tool": "app_get_logs", "args": {}})
    body = await resp.json()
    assert body["ok"] is False
    assert "offline" in body["error"]
```

- [ ] **Step 2: 运行确认失败**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_server.py -v -k tool`
Expected: FAIL（`server.app_tool_request` 不存在 / ImportError 或 AttributeError）

- [ ] **Step 3: 实现**

`server.py` 顶部 import 区追加 `import json`、`import uuid`（若未 import）。在 `sm = ...` 之后追加：

```python
# ── app-tool 桥接（spec §4.1）：MCP server 经 localhost HTTP 与网关通信 ──
SSE_HUB = {}       # sid → 活跃 /chat 的 StreamResponse（tool call 只发生在回合进行中）
PENDING = {}       # requestId → asyncio.Future（tool-result 解挂）
_LAST_REQUEST_ID = None  # 测试钩子
APP_TOOL_TIMEOUT = int(os.environ.get("CT_APP_TOOL_TIMEOUT", "60"))
```

新增两个 handler（放在 `chat` 之前）：

```python
async def app_tool_request(request):
    """MCP server → 网关：经该 sid 的活跃 SSE 下行 app_tool_request 事件，长轮询等 App 回传。"""
    global _LAST_REQUEST_ID
    body = await request.json()
    sid = body.get("sid", "")
    resp = SSE_HUB.get(sid)
    if resp is None:
        return web.json_response({"ok": False, "error": "app offline (no active SSE)"})
    request_id = uuid.uuid4().hex[:12]
    _LAST_REQUEST_ID = request_id
    fut = asyncio.get_event_loop().create_future()
    PENDING[request_id] = fut
    try:
        await _send(resp, {"event": "app_tool_request", "data": {
            "requestId": request_id,
            "tool": body.get("tool", ""),
            "args": body.get("args") or {},
        }})
        payload = await asyncio.wait_for(fut, timeout=APP_TOOL_TIMEOUT)
        return web.json_response({"ok": True, "payload": payload})
    except asyncio.TimeoutError:
        return web.json_response({"ok": False, "error": "app tool timeout {}s".format(APP_TOOL_TIMEOUT)})
    except Exception as e:  # noqa: BLE001 — SSE 写失败（连接断开等）
        return web.json_response({"ok": False, "error": "sse push failed: {}".format(e)})
    finally:
        PENDING.pop(request_id, None)


async def tool_result(request):
    """App →（Ktor 反代）→ 网关：解挂 pending 的 app-tool 请求。"""
    body = await request.json()
    fut = PENDING.get(body.get("requestId", ""))
    if fut is None or fut.done():
        return web.json_response({"ok": False, "error": "unknown or expired requestId"}, status=404)
    fut.set_result(body.get("payload") or {})
    return web.json_response({"ok": True})
```

`chat()` 中注册/注销 SSE（`await resp.prepare(request)` 之后、return 之前的所有路径）：

```python
    await resp.prepare(request)
    # ...sid 确定之后（sm.create()/exists 分支之后）：
    SSE_HUB[sid] = resp
    try:
        # 原有 try 块整体内嵌一层（spawn claude、pump、error/done 发送）
        ...
    finally:
        SSE_HUB.pop(sid, None)
    return resp
```

`main()` 注册路由：

```python
    app.router.add_post("/app-tool-request", app_tool_request)
    app.router.add_post("/tool-result", tool_result)
```

- [ ] **Step 4: 运行确认通过**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_server.py -v`
Expected: PASS（含既有测试不回归）

- [ ] **Step 5: Commit**

```bash
git add scripts/claude-tunnel/gateway/server.py scripts/claude-tunnel/gateway/test_server.py
git commit -m "feat(claude-tunnel): app-tool 桥接路由（SSE_HUB + /app-tool-request + /tool-result）"
```

---

## Task 2: Gateway — stdio MCP server（app_tools_mcp.py）

**Files:**
- Create: `scripts/claude-tunnel/gateway/app_tools_mcp.py`
- Test: `scripts/claude-tunnel/gateway/test_app_tools_mcp.py`

**设计要点：** claude CLI `--mcp-config` 以 stdio 拉起本进程（每回合一个子进程）。MCP stdio = NDJSON JSON-RPC 2.0（无 Content-Length 头）。手写协议，零新增依赖（gateway requirements.txt 不变）。tool call 经 `urllib.request` POST 到 gateway `/app-tool-request` 长轮询（超时 65s > gateway 60s）。sid 来自环境变量 `CT_SESSION_SID`（Task 3 由 build_cmd 注入，子进程继承）。

- [ ] **Step 1: 写失败测试**

`test_app_tools_mcp.py`：

```python
"""app_tools_mcp 协议单测：initialize/tools.list/tools.call 路由与错误分支（不起真 gateway）。"""
import json
from unittest import mock

import app_tools_mcp


def _run_lines(responses):
    return [json.loads(line) for line in responses]


def test_initialize_and_tools_list():
    out = []
    app_tools_mcp.handle_message({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}, out.append)
    app_tools_mcp.handle_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}, out.append)
    init, tools = out
    assert init["result"]["serverInfo"]["name"] == "app_tools"
    names = [t["name"] for t in tools["result"]["tools"]]
    assert names == [
        "app_get_logs", "app_get_crash_trace", "app_get_chat_history",
        "app_get_runtime_state", "app_get_gallery_summary",
    ]


def test_tools_call_success():
    fake = {"ok": True, "payload": {"logs": "line1\nline2"}}
    with mock.patch.object(app_tools_mcp, "_post_to_gateway", return_value=fake):
        out = []
        app_tools_mcp.handle_message(
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "app_get_logs", "arguments": {"filter": "Tag"}}},
            out.append,
        )
    result = out[0]["result"]
    assert result["isError"] is False
    assert json.loads(result["content"][0]["text"]) == {"logs": "line1\nline2"}


def test_tools_call_gateway_error_becomes_is_error():
    with mock.patch.object(app_tools_mcp, "_post_to_gateway",
                           return_value={"ok": False, "error": "app offline (no active SSE)"}):
        out = []
        app_tools_mcp.handle_message(
            {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
             "params": {"name": "app_get_logs", "arguments": {}}},
            out.append,
        )
    result = out[0]["result"]
    assert result["isError"] is True
    assert "offline" in result["content"][0]["text"]


def test_unknown_tool():
    out = []
    app_tools_mcp.handle_message(
        {"jsonrpc": "2.0", "id": 5, "method": "tools/call",
         "params": {"name": "nope", "arguments": {}}},
        out.append,
    )
    assert out[0]["result"]["isError"] is True
```

- [ ] **Step 2: 运行确认失败**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_app_tools_mcp.py -v`
Expected: FAIL（ModuleNotFoundError: app_tools_mcp）

- [ ] **Step 3: 实现**

`app_tools_mcp.py` 完整内容：

```python
"""App 数据工具的 stdio MCP server（spec §4.1）。

claude CLI --mcp-config 以 stdio 拉起本进程（每回合一进程）。
协议：NDJSON JSON-RPC 2.0（initialize / notifications.initialized / tools.list / tools.call）。
tool call → POST http://127.0.0.1:$CT_GATEWAY_PORT/app-tool-request（长轮询，gateway 经 SSE 下行到 App）。
零第三方依赖（仅 stdlib），gateway requirements.txt 不变。
"""
import json
import os
import sys
import urllib.request

GATEWAY_PORT = os.environ.get("CT_GATEWAY_PORT", "3000")
SESSION_SID = os.environ.get("CT_SESSION_SID", "")
GATEWAY_TIMEOUT = 65  # > gateway APP_TOOL_TIMEOUT(60)，保证拿到的是 gateway 的错误而非本地超时

TOOLS = [
    {
        "name": "app_get_logs",
        "description": "读取 Android App 的 PoLang 日志环形缓冲（脱敏后）。用户报告异常行为/报错时使用。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filter": {"type": "string", "description": "按 tag 或关键字过滤，如 TagGeneration"},
                "lines": {"type": "integer", "description": "最多返回行数，上限 500，默认 200"},
            },
        },
    },
    {
        "name": "app_get_crash_trace",
        "description": "读取 App 最近一次未处理异常的崩溃栈（无则返回 empty）。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "app_get_chat_history",
        "description": "读取 App 聊天历史（脱敏后）。用户说「之前说过」「上次」等指代时使用。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "sessionId": {"type": "string", "description": "会话 id，缺省为当前会话"},
                "limit": {"type": "integer", "description": "最多返回条数，上限 50，默认 20"},
            },
        },
    },
    {
        "name": "app_get_runtime_state",
        "description": "读取 App 运行时状态快照：版本/gitSha/设备/推理模式/模型配置/登录态等。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "app_get_gallery_summary",
        "description": "读取相册统计摘要（数量、标签分布等元数据，不含图片本身）。",
        "inputSchema": {"type": "object", "properties": {}},
    },
]
_TOOL_NAMES = {t["name"] for t in TOOLS}


def _post_to_gateway(tool, args):
    """POST /app-tool-request 长轮询；返回 gateway 的 JSON dict。网络异常 → ok=False。"""
    body = json.dumps({"sid": SESSION_SID, "tool": tool, "args": args}).encode("utf-8")
    req = urllib.request.Request(
        "http://127.0.0.1:{}/app-tool-request".format(GATEWAY_PORT),
        data=body, headers={"Content-Type": "application/json"}, method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=GATEWAY_TIMEOUT) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:  # noqa: BLE001 — 网关不可达/超时，统一走 isError 分支
        return {"ok": False, "error": "gateway unreachable: {}".format(e)}


def _tool_result(req_id, text, is_error):
    return {
        "jsonrpc": "2.0", "id": req_id,
        "result": {"content": [{"type": "text", "text": text}], "isError": is_error},
    }


def handle_message(msg, emit):
    """处理一条 JSON-RPC 消息，响应经 emit(dict) 输出。notification 不响应。"""
    method = msg.get("method")
    req_id = msg.get("id")
    if method == "initialize":
        emit({"jsonrpc": "2.0", "id": req_id, "result": {
            "protocolVersion": msg.get("params", {}).get("protocolVersion", "2024-11-05"),
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "app_tools", "version": "1.0.0"},
        }})
    elif method == "notifications/initialized":
        pass
    elif method == "tools/list":
        emit({"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS}})
    elif method == "tools/call":
        params = msg.get("params", {})
        name = params.get("name", "")
        if name not in _TOOL_NAMES:
            emit(_tool_result(req_id, "unknown tool: {}".format(name), True))
            return
        resp = _post_to_gateway(name, params.get("arguments") or {})
        if resp.get("ok"):
            emit(_tool_result(req_id, json.dumps(resp.get("payload") or {}, ensure_ascii=False), False))
        else:
            emit(_tool_result(req_id, resp.get("error", "app tool failed"), True))
    elif req_id is not None:
        emit({"jsonrpc": "2.0", "id": req_id,
              "error": {"code": -32601, "message": "method not found: {}".format(method)}})


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        handle_message(msg, lambda resp: (
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n"), sys.stdout.flush()))


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 运行确认通过**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_app_tools_mcp.py -v`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
git add scripts/claude-tunnel/gateway/app_tools_mcp.py scripts/claude-tunnel/gateway/test_app_tools_mcp.py
git commit -m "feat(claude-tunnel): stdio MCP server 暴露 5 个 app_* 工具（手写协议零依赖）"
```

---

## Task 3: Gateway — build_cmd 接线（mcp-config + settings + system prompt + CT_SESSION_SID）

**Files:**
- Modify: `scripts/claude-tunnel/gateway/server.py`
- Modify: `scripts/claude-tunnel/gateway/claude-settings.json`
- Modify: `scripts/claude-tunnel/gateway/test_server.py`

**设计要点：** `--mcp-config` 需要绝对路径（claude 的 cwd 是 repo workdir），故 server.py 启动时自生成 `app-tools.mcp.json`（与 server.py 同目录）。`--append-system-prompt` 注入工具使用指引（spec §4.3，不污染仓库）。`CT_SESSION_SID`/`CT_GATEWAY_PORT` 经 env 传给 claude，MCP 子进程继承。

- [ ] **Step 1: 写失败测试**

`test_server.py` 追加：

```python
def test_build_cmd_includes_mcp_and_system_prompt(monkeypatch, tmp_path):
    import server
    mcp_cfg = tmp_path / "app-tools.mcp.json"
    monkeypatch.setattr(server, "MCP_CONFIG_PATH", str(mcp_cfg))
    server.write_mcp_config()  # 生成配置文件
    cmd = server.build_cmd("hello", "csid123")
    assert "--mcp-config" in cmd
    assert cmd[cmd.index("--mcp-config") + 1] == str(mcp_cfg)
    assert "--allowedTools" in cmd
    assert "mcp__app_tools" in cmd[cmd.index("--allowedTools") + 1]
    assert "--append-system-prompt" in cmd
    assert "--resume" in cmd
    cfg = __import__("json").loads(mcp_cfg.read_text())
    args = cfg["mcpServers"]["app_tools"]["args"]
    assert args[0].endswith("app_tools_mcp.py") and args[0].startswith("/")


def test_chat_env_injects_session_sid(monkeypatch):
    """chat() spawn 的 env 必须带 CT_SESSION_SID=<网关 sid>（MCP 子进程继承）。"""
    import server
    captured = {}

    class FakeProc:
        stdout = _aiter_empty()
        async def wait(self):
            return 0
        def kill(self):
            pass

    class _aiter_empty:
        def __aiter__(self):
            return self
        async def __anext__(self):
            raise StopAsyncIteration

    async def fake_spawn(*cmd, **kwargs):
        captured["env"] = kwargs["env"]
        return FakeProc()

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_spawn)
    # 直接调用 chat handler 需要 request 工厂；复用文件内既有 chat 测试的 request 构造方式，
    # 或退化为直接断言 server.build_env("sidX")["CT_SESSION_SID"] == "sidX"
    env = server.build_env("sidX")
    assert env["CT_SESSION_SID"] == "sidX"
    assert env["IS_SANDBOX"] == "1"
```

（若文件内已有 chat 端到端测试用 fake_spawn，优先把 `captured["env"]["CT_SESSION_SID"]` 断言并进去；`build_env` 为 Step 3 抽取的小函数。）

- [ ] **Step 2: 运行确认失败**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_server.py -v -k "mcp or session_sid"`
Expected: FAIL（`write_mcp_config`/`build_env` 不存在）

- [ ] **Step 3: 实现**

`server.py` 常量区追加：

```python
MCP_CONFIG_PATH = os.environ.get(
    "CT_MCP_CONFIG",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "app-tools.mcp.json"),
)
GATEWAY_PORT = os.environ.get("CT_PORT", "3000")

APP_TOOL_SYSTEM_PROMPT = """\
你同时是 PoLang 相册 App 的远程诊断工程师。你可以通过 app_* 工具按需感知用户手机上的 App 状态：
- app_get_logs：用户报告异常/报错时，先拉日志（用 filter 缩小范围）。
- app_get_crash_trace：怀疑崩溃时拉取最近崩溃栈。
- app_get_chat_history：用户说「之前/上次」等指代时拉聊天历史对齐事实。
- app_get_runtime_state：需要版本、推理模式、模型配置、登录态等环境信息时。
- app_get_gallery_summary：需要相册规模/标签分布等元数据时（图片本身永远拿不到，也不要索要）。
诊断方法论：先澄清事实（问用户 + 拉数据交叉验证）→ 在代码中定位根因 → 给出最小修复方案。
数据不足时明确告诉用户缺什么；工具返回 App 离线/超时时，引导用户保持 App 在前台重试。
"""
```

新增函数并改造 build_cmd / chat：

```python
def write_mcp_config():
    """启动时生成 --mcp-config（claude cwd 是 workdir，必须用绝对路径）。"""
    mcp_py = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app_tools_mcp.py")
    cfg = {"mcpServers": {"app_tools": {"command": "python3", "args": [mcp_py]}}}
    with open(MCP_CONFIG_PATH, "w") as fh:
        json.dump(cfg, fh)


def build_env(sid):
    """chat spawn 环境：CT_SESSION_SID 供 MCP 子进程把 tool call 路由回本会话。"""
    return dict(os.environ, IS_SANDBOX="1", GIT_TERMINAL_PROMPT="0",
                CT_SESSION_SID=sid, CT_GATEWAY_PORT=GATEWAY_PORT)


def build_cmd(message, claude_sid):
    """构造 claude CLI 调用：settings 白名单 + --resume 多轮 + MCP app 工具 + 诊断指引。"""
    cmd = [CLAUDE, "-p", message, "--output-format", "stream-json",
           "--max-turns", MAX_TURNS, "--verbose",
           "--append-system-prompt", APP_TOOL_SYSTEM_PROMPT]
    if os.path.exists(SETTINGS_PATH):
        cmd += ["--settings", SETTINGS_PATH]
    if os.path.exists(MCP_CONFIG_PATH):
        cmd += ["--mcp-config", MCP_CONFIG_PATH,
                "--allowedTools", "mcp__app_tools"]
    if claude_sid:
        cmd += ["--resume", claude_sid]
    return cmd
```

`chat()` 中 `env = dict(os.environ, IS_SANDBOX="1", GIT_TERMINAL_PROMPT="0")` 改为 `env = build_env(sid)`。`main()` 开头调 `write_mcp_config()`。

`claude-settings.json` 的 `permissions.allow` 数组追加一项 `"mcp__app_tools"`。

- [ ] **Step 4: 运行确认通过**

Run: `cd scripts/claude-tunnel/gateway && python3 -m pytest test_server.py test_app_tools_mcp.py -v`
Expected: 全 PASS

- [ ] **Step 5: Commit**

```bash
git add scripts/claude-tunnel/gateway/
git commit -m "feat(claude-tunnel): claude 回合接入 MCP app 工具与诊断 system prompt"
```

---

## Task 4: Ktor — POST /v1/claude-tool-result 反代路由

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeToolResultRoute.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/ClaudeToolResultRouteTest.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`（注册路由；先读 170-195 行附近的路由注册块）

**设计要点：** 镜像 `claudeDeliverRoute` 的纯透传模式（ClaudeChatRoute.kt:73-100），upstream 为 `http://127.0.0.1:3001/tool-result`（同一 chisel 隧道口）。

- [ ] **Step 1: 写失败测试**

先读 `server/src/test/kotlin/com/mamba/picme/server/ClaudeDeliverRouteTest.kt` 对齐测试基建（mock upstream / 鉴权 token 构造）。新建 `ClaudeToolResultRouteTest.kt`：无 token → 401；合法 token → 透传 body 到 upstream 并回传 upstream 响应（用 Ktor 的 MockEngine 或文件内既有 fake HttpClient 模式）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew -p server test --tests "*ClaudeToolResultRouteTest*"`
Expected: FAIL（路由 404）

- [ ] **Step 3: 实现**

`ClaudeToolResultRoute.kt`：

```kotlin
package com.mamba.picme.server.routes

import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** App tool 结果回传反代到网关 /tool-result（spec §5，与 /v1/claude-chat 同一 chisel 隧道口）。 */
private const val CLAUDE_TOOL_RESULT_UPSTREAM = "http://127.0.0.1:3001/tool-result"

fun Route.claudeToolResultRoute(httpClient: HttpClient, rateLimiter: RateLimiter?) {
    post("/v1/claude-tool-result") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        if (rateLimiter != null && !rateLimiter.allow(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded")); return@post
        }
        val body = call.receiveText()
        val upstream = try {
            httpClient.post(CLAUDE_TOOL_RESULT_UPSTREAM) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Throwable) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "ai_offline", "message" to "tunnel unavailable"),
            )
            return@post
        }
        call.respondText(
            text = upstream.bodyAsText(),
            contentType = ContentType.Application.Json,
            status = upstream.status,
        )
    }
}
```

`ClaudeChatRoute.kt` 的 `ownerTokenHash()` 是 private——把它改为 internal（同文件顶层，去 private 修饰）供新路由复用。`Application.kt` 在 `claudeDeliverRoute(...)` 后加一行 `claudeToolResultRoute(claudeClient, rateLimiter)` 并补 import。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew -p server test --tests "*ClaudeToolResultRouteTest*" --tests "*ClaudeDeliverRouteTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeToolResultRoute.kt \
        server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeChatRoute.kt \
        server/src/main/kotlin/com/mamba/picme/server/Application.kt \
        server/src/test/kotlin/com/mamba/picme/server/ClaudeToolResultRouteTest.kt
git commit -m "feat(server): /v1/claude-tool-result 反代路由（App tool 结果回传通道）"
```

---

## Task 5: App — ClaudeEvent.AppToolRequest + 解析 + postToolResult

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/remote/picme/ClaudeEvent.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/remote/picme/ClaudeChatClient.kt`
- Test: `app/src/test/java/com/mamba/picme/data/remote/picme/ClaudeSseParserTest.kt`（新建；先 Glob 确认该目录无既有 parser 测试）

- [ ] **Step 1: 写失败测试**

`ClaudeSseParserTest.kt`：

```kotlin
package com.mamba.picme.data.remote.picme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSseParserTest {

    @Test
    fun `parse app_tool_request event`() {
        val sse = """
            event: app_tool_request
            data: {"requestId":"abc123","tool":"app_get_logs","args":{"filter":"Tag","lines":100}}

        """.trimIndent()
        val events = ClaudeSseParser.parse(sse)
        assertEquals(1, events.size)
        val ev = events[0] as ClaudeEvent.AppToolRequest
        assertEquals("abc123", ev.requestId)
        assertEquals("app_get_logs", ev.tool)
        assertEquals("Tag", ev.args.optString("filter"))
        assertEquals(100, ev.args.optInt("lines"))
    }

    @Test
    fun `parse app_tool_request with missing args defaults to empty json`() {
        val sse = "event: app_tool_request\ndata: {\"requestId\":\"r1\",\"tool\":\"app_get_crash_trace\"}\n\n"
        val ev = ClaudeSseParser.parse(sse).single() as ClaudeEvent.AppToolRequest
        assertEquals(0, ev.args.length())
    }

    @Test
    fun `parse existing events not broken`() {
        val sse = "event: assistant_text\ndata: {\"delta\":\"hi\"}\n\n"
        val events = ClaudeSseParser.parse(sse)
        assertTrue(events.single() is ClaudeEvent.AssistantText)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.ClaudeSseParserTest"`
Expected: 编译 FAIL（`ClaudeEvent.AppToolRequest` 不存在）

- [ ] **Step 3: 实现**

`ClaudeEvent.kt` 在 `data object Done` 前加：

```kotlin
    /** spec §4.4：网关下行的 App 数据请求（MCP tool call → App 采集回传）。 */
    data class AppToolRequest(val requestId: String, val tool: String, val args: JSONObject) : ClaudeEvent()
```

`ClaudeSseParser.parse` 的 `when (t)` 加分支：

```kotlin
                "app_tool_request" -> ClaudeEvent.AppToolRequest(
                    json.optString("requestId"),
                    json.optString("tool"),
                    json.optJSONObject("args") ?: JSONObject(),
                )
```

`ClaudeChatClient` 加方法（复用 `deliverClient`，60s read timeout 足够）：

```kotlin
    /**
     * App tool 结果回传（spec §5）：POST /v1/claude-tool-result → server 反代网关 /tool-result。
     * [payload] 为 AppToolExecutor 采集+脱敏后的 JSON。
     */
    suspend fun postToolResult(token: String, requestId: String, payload: JSONObject): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("requestId", requestId)
                    .put("payload", payload)
                    .toString()
                val req = Request.Builder()
                    .url("$baseUrl/v1/claude-tool-result")
                    .header("X-App-Token", token)
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                val resp = deliverClient.newCall(req).execute()
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
                }
                Unit
            }
        }
```

`ClaudeEvent` 是 sealed class，新增的 `AppToolRequest` 会破坏既有穷尽 `when`：`ClaudeAgentRenderer.fold`（ClaudeAgentRenderer.kt:104-141）和 `ChatViewModel.sendClaudeMessage` 的两个 `when`（ChatViewModel.kt:311-336）。本 Task 先让编译通过：`ClaudeAgentRenderer.fold` 的第一分支改为 `is ClaudeEvent.Session, ClaudeEvent.Done, is ClaudeEvent.Cost, is ClaudeEvent.AppToolRequest -> cur`（AppToolRequest 的 UI 渲染由 Task 7 在 ViewModel 合成 ToolUse/ToolResult 事件，renderer 本身无视觉）；ChatViewModel 的两个 when 各加 `is ClaudeEvent.AppToolRequest -> Unit`（Task 7 替换为真实处理）。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.*" --tests "com.mamba.picme.features.chat.ClaudeAgentRendererTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/remote/picme/ \
        app/src/main/java/com/mamba/picme/features/chat/ClaudeAgentRenderer.kt \
        app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt \
        app/src/test/java/com/mamba/picme/data/remote/picme/ClaudeSseParserTest.kt
git commit -m "feat(app): ClaudeEvent.AppToolRequest 解析与 postToolResult 回传通道"
```

---

## Task 6: App — AppTool 枚举 + AppToolExecutor + RuntimeStateProvider

**Files:**
- Create: `app/src/main/java/com/mamba/picme/core/agenttools/AppTool.kt`
- Create: `app/src/main/java/com/mamba/picme/core/agenttools/RuntimeStateProvider.kt`
- Create: `app/src/main/java/com/mamba/picme/core/agenttools/AppToolExecutor.kt`
- Test: `app/src/test/java/com/mamba/picme/core/agenttools/AppToolExecutorTest.kt`

**设计要点：** 纯 JVM 可测（BuildConfig/Context 一律注入，镜像 DiagBundleCollector 的约束）。payload 上限 32KB，截断加 `truncated: true`。所有文本经 `DiagSanitizer.sanitize`。

- [ ] **Step 1: 写失败测试**

`AppToolExecutorTest.kt`：

```kotlin
package com.mamba.picme.core.agenttools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppToolExecutorTest {

    private fun executor(
        logs: String = "2026-08-01 I PoLang:Tag: hello",
        crash: String? = null,
        history: List<Pair<String, String>> = listOf("user_text" to "之前的问题"),
        state: JSONObject = JSONObject().put("appVersion", "1.0"),
        gallery: JSONObject = JSONObject().put("total", 42),
    ) = AppToolExecutor(
        logProvider = { logs },
        crashTraceReader = { crash },
        chatHistoryLoader = { _, limit -> history.take(limit) },
        runtimeStateProvider = RuntimeStateProvider { state },
        gallerySummaryLoader = { gallery },
    )

    @Test
    fun `get_logs returns sanitized lines with default limit`() {
        val out = executor(logs = "mail me a@b.com\nline2").execute(AppTool.GET_LOGS, JSONObject())
        assertFalse(out.getBoolean("empty"))
        val text = out.getString("logs")
        assertTrue(text.contains("<email>"))
        assertTrue(text.contains("line2"))
    }

    @Test
    fun `get_logs respects filter and lines cap`() {
        val many = (1..600).joinToString("\n") { "line$it" }
        val out = executor(logs = many).execute(AppTool.GET_LOGS, JSONObject().put("lines", 10))
        assertEquals(9, out.getString("logs").count { it == '\n' } + 1 - 0 + 0.coerceAtLeast(0).let { 0 } + 0) // 10 行 = 9 个换行
    }

    @Test
    fun `crash trace empty when none`() {
        val out = executor(crash = null).execute(AppTool.GET_CRASH_TRACE, JSONObject())
        assertTrue(out.getBoolean("empty"))
        assertEquals("no_crash_trace", out.getString("reason"))
    }

    @Test
    fun `chat history sanitized and limited`() {
        val history = (1..60).map { "user_text" to "msg$it" }
        val out = executor(history = history).execute(
            AppTool.GET_CHAT_HISTORY,
            JSONObject().put("limit", 100),
        )
        assertEquals(50, out.getJSONArray("messages").length()) // 上限 50
    }

    @Test
    fun `runtime state and gallery summary pass through`() {
        val out = executor().execute(AppTool.GET_RUNTIME_STATE, JSONObject())
        assertEquals("1.0", out.getString("appVersion"))
        val g = executor().execute(AppTool.GET_GALLERY_SUMMARY, JSONObject())
        assertEquals(42, g.getInt("total"))
    }

    @Test
    fun `payload truncated at 32KB with flag`() {
        val big = "x".repeat(40 * 1024)
        val out = executor(logs = big).execute(AppTool.GET_LOGS, JSONObject())
        val wire = executor().let { out } // out 即截断后 payload
        assertTrue(out.toString().length <= AppToolExecutor.MAX_PAYLOAD_BYTES + 256)
        assertTrue(out.getBoolean("truncated"))
    }

    @Test
    fun `unknown args tolerated`() {
        val out = executor().execute(AppTool.GET_LOGS, JSONObject().put("nope", 1))
        assertFalse(out.getBoolean("empty"))
    }
}
```

（`get_logs respects filter and lines cap` 断言写法别扭——实现后按实际行为简化为 `assertEquals(10, out.getString("logs").split("\n").size)`，并把 filter 用例补上：`logs = "TagA x\nOther y"` + filter=TagA → 只含 TagA 行。）

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.agenttools.*"`
Expected: 编译 FAIL（类不存在）

- [ ] **Step 3: 实现**

`AppTool.kt`：

```kotlin
package com.mamba.picme.core.agenttools

/** 云主机 MCP server 暴露给 Claude 的 App 数据工具（spec §2.2）。穷举分发，新增工具编译期可检查。 */
enum class AppTool(val toolName: String) {
    GET_LOGS("app_get_logs"),
    GET_CRASH_TRACE("app_get_crash_trace"),
    GET_CHAT_HISTORY("app_get_chat_history"),
    GET_RUNTIME_STATE("app_get_runtime_state"),
    GET_GALLERY_SUMMARY("app_get_gallery_summary"),
    ;

    companion object {
        fun fromName(name: String): AppTool? = entries.firstOrNull { it.toolName == name }
    }
}
```

`RuntimeStateProvider.kt`：

```kotlin
package com.mamba.picme.core.agenttools

import org.json.JSONObject

/** 运行时状态快照来源（spec §3.1）。实现方负责从设置/仓库采集，纯接口便于 JVM 单测。 */
fun interface RuntimeStateProvider {
    fun snapshot(): JSONObject
}
```

`AppToolExecutor.kt`：

```kotlin
package com.mamba.picme.core.agenttools

import com.mamba.picme.core.diag.DiagSanitizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * app_tool_request 的采集分发器（spec §3.1）：按工具采集 → 脱敏 → 截断（≤32KB）→ 返回 payload。
 *
 * 全部依赖以函数/接口注入，纯 JVM 可测；Android 接线（Logger 环缓冲、CrashTraceStore、
 * Room DAO、UserSettingsRepository、GetGallerySummaryUseCase）在 Task 7 的工厂函数完成。
 */
class AppToolExecutor(
    private val logProvider: () -> String,
    private val crashTraceReader: () -> String?,
    private val chatHistoryLoader: (sessionId: String?, limit: Int) -> List<Pair<String, String>>,
    private val runtimeStateProvider: RuntimeStateProvider,
    private val gallerySummaryLoader: () -> JSONObject,
) {
    /** 执行一次采集，返回可直接放入 postToolResult payload 的 JSON。 */
    fun execute(tool: AppTool, args: JSONObject): JSONObject {
        val payload = when (tool) {
            AppTool.GET_LOGS -> collectLogs(args)
            AppTool.GET_CRASH_TRACE -> collectCrash()
            AppTool.GET_CHAT_HISTORY -> collectChatHistory(args)
            AppTool.GET_RUNTIME_STATE -> runtimeStateProvider.snapshot()
            AppTool.GET_GALLERY_SUMMARY -> gallerySummaryLoader()
        }
        return truncate(payload)
    }

    private fun collectLogs(args: JSONObject): JSONObject {
        val filter = args.optString("filter").takeIf { it.isNotBlank() }
        val lines = args.optInt("lines", DEFAULT_LOG_LINES).coerceIn(1, MAX_LOG_LINES)
        val all = logProvider().lines()
            .let { l -> if (filter != null) l.filter { it.contains(filter) } else l }
        if (all.isEmpty()) return emptyPayload("no_matching_logs")
        return JSONObject().put("empty", false)
            .put("logs", DiagSanitizer.sanitize(all.take(lines).joinToString("\n")))
    }

    private fun collectCrash(): JSONObject {
        val trace = crashTraceReader()?.takeIf { it.isNotBlank() }
            ?: return emptyPayload("no_crash_trace")
        return JSONObject().put("empty", false).put("crashTrace", DiagSanitizer.sanitize(trace))
    }

    private fun collectChatHistory(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", DEFAULT_HISTORY_LIMIT).coerceIn(1, MAX_HISTORY_LIMIT)
        val sessionId = args.optString("sessionId").takeIf { it.isNotBlank() }
        val history = chatHistoryLoader(sessionId, limit)
        if (history.isEmpty()) return emptyPayload("no_chat_history")
        val arr = JSONArray()
        history.forEach { (type, content) ->
            arr.put(JSONObject().put("type", type).put("content", DiagSanitizer.sanitize(content)))
        }
        return JSONObject().put("empty", false).put("messages", arr)
    }

    private fun emptyPayload(reason: String) =
        JSONObject().put("empty", true).put("reason", reason)

    /** 超 32KB：对最大的字符串字段做截断并打 truncated 标记（宁可截断也不撑爆 MCP tool result）。 */
    private fun truncate(payload: JSONObject): JSONObject {
        if (payload.toString().length <= MAX_PAYLOAD_BYTES) return payload.put("truncated", false)
        val keys = payload.keys().asSequence().toList()
        val biggest = keys.maxByOrNull { payload.optString(it).length } ?: return payload.put("truncated", true)
        val budget = (MAX_PAYLOAD_BYTES - 1024).coerceAtLeast(1024)
        payload.put(biggest, payload.optString(biggest).take(budget) + "…[truncated]")
        return payload.put("truncated", true)
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 32 * 1024
        private const val MAX_LOG_LINES = 500
        private const val DEFAULT_LOG_LINES = 200
        private const val MAX_HISTORY_LIMIT = 50
        private const val DEFAULT_HISTORY_LIMIT = 20
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.agenttools.*"`
Expected: PASS（按 Step 1 注记修正断言行）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/core/agenttools/ app/src/test/java/com/mamba/picme/core/agenttools/
git commit -m "feat(app): AppToolExecutor 采集分发（日志/崩溃/历史/状态/相册摘要 + 脱敏 + 32KB 截断）"
```

---

## Task 7: App — ChatViewModel 接线（AppToolRequest → 执行 → 回传 + 过程气泡）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelAppToolTest.kt`（新建，风格对齐 ChatViewModelGuestModeTest）

**设计要点：** onEvent 收到 `AppToolRequest` 时：① 给 renderer 合成 `ToolUse(tool, args)` 事件（复用现有步骤气泡折叠，不改 renderer）；② `viewModelScope.launch(Dispatchers.IO)` 执行采集并 `postToolResult`；③ 完成后合成 `ToolResult(ok, summary)` 事件给 renderer。依赖全部走 `ChatViewModelDependencies` 注入（Agent First 显式原则）。

- [ ] **Step 1: 写失败测试**

`ChatViewModelAppToolTest.kt`：用 fake `AppToolExecutor`（构造注入）+ fake `ClaudeChatClient`（记录 postToolResult 调用），触发 ViewModel 内部的 app tool 处理入口，断言：executor 被调用、结果回传、异常时回传 `{error}` payload。测试入口建议暴露为 `@VisibleForTesting internal fun handleAppToolRequest(requestId: String, tool: String, args: JSONObject, renderer: ClaudeAgentRenderer)`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatViewModelAppToolTest"`
Expected: 编译 FAIL

- [ ] **Step 3: 实现**

`ChatViewModelDependencies.kt`：删 `diagClient` 字段（Task 9 做完整 diag 清理，此处如编译冲突可留到 Task 9），加：

```kotlin
    val appToolExecutor: AppToolExecutor? = null, // null = 未接线（单测默认）；生产由工厂构建
```

`ChatViewModel.kt`：

新增工厂（文件底部 companion 或顶层），把 Android 数据源接进 `AppToolExecutor`：

```kotlin
internal fun buildAppToolExecutor(deps: ChatViewModelDependencies): AppToolExecutor = AppToolExecutor(
    logProvider = {
        Logger.logs.value.joinToString("\n") { e -> "${e.timestamp} ${e.level} PoLang:${e.tag}: ${e.message}" }
    },
    crashTraceReader = { CrashTraceStore.read(deps.context.filesDir) },
    chatHistoryLoader = { sessionId, limit ->
        runBlocking {
            deps.chatMessageDao.getRecentMessages(sessionId ?: "default", limit)
                .map { it.type to it.content }
        }
    },
    runtimeStateProvider = RuntimeStateProvider {
        // 先读 UserSettingsRepository 确认可用 flow（serverAuthTokenFlow 已存在），
        // 采集：appVersion/gitSha/deviceModel/androidVersion（BuildConfig 注入处取）、
        // selectedModelId、hasUserKey、serverConnected
        JSONObject()
    },
    gallerySummaryLoader = {
        // 复用 deps.getGallerySummaryUseCase；先读该类签名，把结果映射为 JSON
        // （total/标签分布等元数据，绝不含路径/图片）
        JSONObject()
    },
)
```

注意：`chatHistoryLoader` 是同步签名但 DAO 是 suspend——把 `AppToolExecutor` 的函数类型改为 suspend（`(sessionId: String?, limit: Int) -> List<...>` 加 suspend 修饰，`execute` 改 suspend fun），测试同步 lambda 仍兼容（suspend 上下文调用）。`runBlocking` 仅作无法改签名时的退路，优先改 suspend。

`sendClaudeMessage` 的 event when（ChatViewModel.kt:324-336）把 `is ClaudeEvent.AppToolRequest -> Unit` 改为：

```kotlin
                        is ClaudeEvent.AppToolRequest -> {
                            renderer.apply(ClaudeEvent.ToolUse(event.tool, event.args))
                            _streamingMessage.update { cur ->
                                cur?.copy(claudeAgent = renderer.state, isThinking = false)
                            }
                            handleAppToolRequest(event.requestId, event.tool, event.args, renderer)
                        }
```

新增：

```kotlin
    /** spec §3.1/§3.3：执行 App 数据采集并回传；过程经合成 ToolUse/ToolResult 事件入气泡。 */
    @VisibleForTesting
    internal fun handleAppToolRequest(
        requestId: String,
        tool: String,
        args: JSONObject,
        renderer: ClaudeAgentRenderer,
    ) {
        val executor = dependencies.appToolExecutor ?: return
        val token = _serverAuthToken.value
        viewModelScope.launch(Dispatchers.IO) {
            val summary = try {
                val appTool = AppTool.fromName(tool)
                    ?: throw IllegalArgumentException("unknown app tool: $tool")
                val payload = executor.execute(appTool, args)
                if (token.isNotBlank()) {
                    dependencies.claudeChatClient.postToolResult(token, requestId, payload)
                }
                if (payload.optBoolean("empty")) "无数据（${payload.optString("reason")}）"
                else "已回传（${payload.toString().length}B${if (payload.optBoolean("truncated")) "，已截断" else ""}）"
            } catch (e: Exception) {
                Logger.e(TAG, "handleAppToolRequest failed", e)
                if (token.isNotBlank()) {
                    runCatching {
                        dependencies.claudeChatClient.postToolResult(
                            token, requestId, JSONObject().put("error", e.message ?: "collect failed"),
                        )
                    }
                }
                "采集失败：${e.message}"
            }
            renderer.apply(ClaudeEvent.ToolResult(ok = true, summary = summary))
            _streamingMessage.update { cur -> cur?.copy(claudeAgent = renderer.state) }
        }
    }
```

imports 按需补（AppTool、AppToolExecutor、RuntimeStateProvider、CrashTraceStore、Logger、Dispatchers、VisibleForTesting 等）。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ app/src/test/java/com/mamba/picme/features/chat/ChatViewModelAppToolTest.kt
git commit -m "feat(app): AI 工程师模式接线 app_tool_request（采集回传 + 过程气泡）"
```

---

## Task 8: App — claudeSid 持久化（修复"没有记忆"）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/ClaudeSidStore.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（enterClaudeMode / Session 事件处理）
- Test: `app/src/test/java/com/mamba/picme/features/chat/ClaudeSidStoreTest.kt`

**设计要点：** ChatSessionEntity 无 metadata 列（加列要 Room 迁移，过重），改用 SharedPreferences（`claude_sid_<sessionId>` key）。接口注入便于 JVM 单测。

- [ ] **Step 1: 写失败测试**

`ClaudeSidStoreTest.kt`：内存 fake（`mutableMapOf` 实现接口）断言 save/load/clear 与按 sessionId 隔离。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*ClaudeSidStoreTest*"`
Expected: 编译 FAIL

- [ ] **Step 3: 实现**

`ClaudeSidStore.kt`：

```kotlin
package com.mamba.picme.features.chat

import android.content.Context

/** claude-tunnel 网关 sid 持久化（spec §3.3）：进程重建后 --resume 续上下文，修复失忆。 */
interface ClaudeSidStore {
    fun load(sessionId: String): String?
    fun save(sessionId: String, sid: String)
    fun clear(sessionId: String)
}

/** SharedPreferences 实现：key = claude_sid_<sessionId>。 */
class PrefsClaudeSidStore(context: Context) : ClaudeSidStore {
    private val prefs = context.getSharedPreferences("claude_tunnel", Context.MODE_PRIVATE)

    override fun load(sessionId: String): String? =
        prefs.getString(key(sessionId), null)

    override fun save(sessionId: String, sid: String) {
        prefs.edit().putString(key(sessionId), sid).apply()
    }

    override fun clear(sessionId: String) {
        prefs.edit().remove(key(sessionId)).apply()
    }

    private fun key(sessionId: String) = "claude_sid_$sessionId"
}
```

`ChatViewModelDependencies` 加 `val claudeSidStore: ClaudeSidStore? = null`。

`ChatViewModel`：

- `enterClaudeMode()`（ChatViewModel.kt:256-263）：`claudeSid = null` 改为先 `claudeSidStore?.clear(_currentSessionId.value)` 再置 null（新建会话 = 明确重置上下文），其余不变
- Session 事件回填处（:328）`if (claudeSid == null) claudeSid = event.sid` 后加 `claudeSidStore?.save(sessionId, event.sid)`
- `sendClaudeMessage` 开头（:310 前）加恢复逻辑：`if (claudeSid == null) claudeSid = claudeSidStore?.load(sessionId)`
- `claudeSidStore = dependencies.claudeSidStore`

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*ClaudeSidStore*" --tests "com.mamba.picme.features.chat.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ClaudeSidStore.kt \
        app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt \
        app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt \
        app/src/test/java/com/mamba/picme/features/chat/ClaudeSidStoreTest.kt
git commit -m "fix(app): claudeSid 持久化到 SharedPreferences，进程重建后保留工程师模式记忆"
```

---

## Task 9: App — 移除诊断模式（ViewModel / Screen / 文案 / 文件 / 测试）

**Files:**
- Delete: `app/src/main/java/com/mamba/picme/features/chat/DiagChatSession.kt`、`DiagPrompts.kt`
- Delete: `app/src/main/java/com/mamba/picme/data/remote/picme/DiagClient.kt`
- Delete: `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelDiagTest.kt`、`DiagPromptsTest.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（:209-239 diag 状态机、:446-687 sendDiagMessage/submitDiagnosis/pollDiagnose/confirmDiagnosis/pollFix/upsertDiagMessage/ActiveDiag/trackDiagForTesting/diagPollTimeoutMs）
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（:1205-1206 diagMode 收集、:1278-1279 onToggleDiag、:1305 路由、:1538-1545 诊断 toggle、以及 DiagSubmit/DiagConfirm 相关渲染——先 grep `diagSubmit|diagConfirm|DiagSubmitUi|DiagConfirmUi|diagSheet` 找全）
- Modify: `app/src/main/res/values/strings.xml` 及所有 `values-*/strings.xml`（删 :1005-1026 的 `diag_*`，三语同步红线）
- Modify: ChatMessageUi 定义处（删 `diagSubmit`/`diagConfirm` 字段及全部引用）
- Modify: `ChatViewModelDependencies.kt`（删 `diagClient` 字段与 import）
- 保留：`core/diag/DiagBundleCollector.kt`、`DiagSanitizer.kt`、`CrashTraceStore.kt`（被 AppToolExecutor 复用）

**说明：** 本 Task 是纯删除，不写新测试；以编译 + 全量单测回归为验证。

- [ ] **Step 1: 删除文件与代码块**

```bash
git rm app/src/main/java/com/mamba/picme/features/chat/DiagChatSession.kt \
       app/src/main/java/com/mamba/picme/features/chat/DiagPrompts.kt \
       app/src/main/java/com/mamba/picme/data/remote/picme/DiagClient.kt \
       app/src/test/java/com/mamba/picme/features/chat/ChatViewModelDiagTest.kt \
       app/src/test/java/com/mamba/picme/features/chat/DiagPromptsTest.kt
```

然后 grep 清除残留引用（每处都要处理）：

```bash
rg -l 'diagClient|DiagChatSession|DiagPrompts|diagMode|sendDiagMessage|submitDiagnosis|confirmDiagnosis|pollDiagnose|pollFix|DiagSubmitUi|DiagConfirmUi|diagSubmit|diagConfirm|R\.string\.diag_' app/src
```

`ChatViewModel.kt` 删：diag 区段全部成员（见 Files 行号）、`exitDiagMode()` 调用（`enterClaudeMode` 内 :258）、相关 import（DiagClient/DiagChatSession/DiagPrompts/CrashTraceStore 若仅 diag 用——CrashTraceStore 仍被 Task 7 工厂用则保留 import）。

`ChatScreen.kt` 删：diag toggle CapsuleButton、diagMode 状态与 onToggleDiag 参数、`when` 里的 diagMode 分支、诊断确认 sheet/按钮渲染；`ChatTextInputMode` 的 `diagMode/onToggleDiag` 形参同步删除（含所有调用点）。

ChatMessageUi 删字段后，grep `diagSubmit|diagConfirm` 清理所有构造/拷贝点。

strings.xml：删 `diag_icon_desc` 到 `diag_job_timed_out` 全部条目（values + values-zh-rCN + 其他 values-* 目录同步删，I18N 红线）。

- [ ] **Step 2: 编译 + 全量单测**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全测试 PASS（其他 ViewModel 测试若引用 diag 成员，一并删干净）

- [ ] **Step 3: Commit**

```bash
git add -A app/
git commit -m "refactor(app): 移除诊断模式（合并入 AI 工程师模式），保留可复用的 diag 采集/脱敏组件"
```

---

## Task 10: Server — 移除 DiagRoute + diag_jobs 迁移 + diag-worker 目录

**Files:**
- Delete: `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt`
- Delete: DiagRoute 的测试（`rg -l "DiagRoute|diag" server/src/test` 定位）
- Delete: `scripts/diag-worker/`（整个目录）
- Create: `server/migrations/009_drop_diag_jobs.sql`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`（删 `diagRoute(config.diagWorkerToken, diagReportLimiter)` :184、import :26、`diagReportLimiter` 定义、`config.diagWorkerToken` 引用）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/routes/AdminRoutes.kt`（删 `/admin/diag` 页及引用；先 grep 确认位置）
- Modify: server config 定义处（删 `diagWorkerToken`，grep `diagWorkerToken|X-Diag-Worker-Token` 找全）

- [ ] **Step 1: 创建迁移**

`server/migrations/009_drop_diag_jobs.sql`：

```sql
DROP TABLE IF EXISTS diag_jobs;
```

先读 `server/migrations/001_init.sql` 确认 diag_jobs 表名拼写；若表名不同按实际改。

- [ ] **Step 2: 删除与清理**

```bash
git rm -r scripts/diag-worker/
git rm server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt
# DiagRoute 测试按 rg 结果逐个 git rm
```

清理 `Application.kt`、`AdminRoutes.kt`、config 中的所有 diag 引用（rg `diag|Diag` 在 `server/src` 复核，注意别误伤无关词）。

- [ ] **Step 3: 编译 + 测试**

Run: `./gradlew -p server build`
Expected: BUILD SUCCESSFUL（含全部既有测试）

- [ ] **Step 4: Commit**

```bash
git add -A server/ scripts/
git commit -m "refactor(server): 移除诊断工单链路（DiagRoute/diag_jobs/diag-worker），诊断能力并入 claude-tunnel"
```

---

## Task 11: 文档同步（DOC-SYNC 红线）

**Files:**
- Modify: `docs/superpowers/specs/2026-07-30-remote-diagnosis-design.md`、`2026-07-31-diag-multiturn-and-hardening-design.md`、`2026-07-31-diag-three-fix-means-design.md`（实际文件名以 ls 为准）
- Modify: `docs/superpowers/specs/2026-07-31-claude-tunnel-chat-design.md`
- Modify: `AGENTS.md`（根，第 8 节架构说明）
- Modify: `scripts/claude-tunnel/README.md`

- [ ] **Step 1: 三篇 diag spec 头部加 superseded 标记**

每篇在标题下加：

```markdown
> **状态**：⛔ SUPERSEDED（2026-08-01）——诊断模式已合并入 AI 工程师模式，见
> `2026-08-01-ai-engineer-diag-merge-design.md`。本文仅作历史存档。
```

（`diag-three-fix-modes` 的交付三档设计仍被 claude-deliver 复用，标记中注明「交付三档部分仍然有效」。）

- [ ] **Step 2: claude-tunnel spec 追加演进说明**

`2026-07-31-claude-tunnel-chat-design.md` 头部加一行：「**演进（2026-08-01）**：新增 MCP app 工具通道（`app_tool_request`/`/v1/claude-tool-result`）并吸收诊断模式，见 `2026-08-01-ai-engineer-diag-merge-design.md`。」

- [ ] **Step 3: 根 AGENTS.md 第 8 节架构说明**

在「JS Engine」条目后追加一条：

```markdown
> - **AI 工程师模式（原诊断模式已并入）**：Chat「AI Engineer」toggle → `POST /v1/claude-chat` → chisel 隧道 → KimiClaw 云主机 Claude Code（GLM）；云主机 MCP server 暴露 5 个 `app_*` 工具（日志/崩溃/聊天历史/运行时状态/相册摘要），tool call 经 SSE 下行 `app_tool_request` 到 App，`AppToolExecutor`（`app/core/agenttools/`）采集脱敏后经 `POST /v1/claude-tool-result` 回传。诊断工单链路（DiagRoute/diag-worker）已于 2026-08-01 移除。详见 `docs/superpowers/specs/2026-08-01-ai-engineer-diag-merge-design.md`
```

- [ ] **Step 4: claude-tunnel README 更新**

`scripts/claude-tunnel/README.md` 补部署说明：`app_tools_mcp.py` 随 gateway 部署、gateway 启动自生成 `app-tools.mcp.json`、systemd 需 restart gateway、新环境变量 `CT_APP_TOOL_TIMEOUT`（默认 60）。

- [ ] **Step 5: Commit**

```bash
git add docs/ AGENTS.md scripts/claude-tunnel/README.md
git commit -m "docs: 诊断合并演进同步（spec superseded 标记 + AGENTS.md 架构说明 + 部署文档）"
```

---

## Task 12: 端到端验证 + 交付报告

- [ ] **Step 1: 全量构建与测试**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew -p server build
cd scripts/claude-tunnel/gateway && python3 -m pytest -v
```

Expected: 全部 PASS

- [ ] **Step 2: 静态红线检查**

```bash
rg -n "diagMode|DiagRoute|diag-worker|DiagClient" app/src server/src scripts/ --glob '!**/build/**'
rg -n "app_tool_request|claude-tool-result" app/src server/src scripts/claude-tunnel | wc -l
```

Expected: 第一组无残留（spec 文档除外，限 `docs/`）；第二组三层均有命中。

- [ ] **Step 3: 交付报告**

汇总：变更清单（按 commit）、测试结果、部署待办（gateway 重新部署 + systemd restart + chisel 不动）、遗留风险（见下）。

---

## 自审记录（写计划时已完成）

- **Spec 覆盖**：§2.1 生命周期 → Task 1/2/4/5/6/7；§2.2 五工具 → Task 2（MCP 定义）+ Task 6（App 采集）；§3.1 执行器 → Task 6/7；§3.2 事件/回传 → Task 5（SSE 保活经分析后简化为「tool call 必在回合内，无需保活」）；§3.3 sid 持久化 → Task 8；§3.4 UI → Task 7（复用步骤气泡）+ Task 9；§4 网关 → Task 1/2/3；§5 Ktor → Task 4；§6 移除清单 → Task 9/10/11；§7 错误处理 → 各 Task 内联；§8 测试 → 各 Task TDD + Task 12。
- **类型一致性**：`AppTool.toolName`（Task 6）↔ MCP TOOLS name（Task 2）↔ `AppTool.fromName`（Task 7）一致；`requestId/payload` 字段名四层一致（gateway → Ktor → App）；`ClaudeEvent.AppToolRequest(requestId, tool, args)` 与 parser/ViewModel 用法一致。
- **已知留待执行时确认的点**（已在对应 Task 注明）：`GetGallerySummaryUseCase` 返回类型（Task 7 工厂）、`UserSettingsRepository` 可用 flow（Task 7）、server 测试基建风格（Task 4）、diag_jobs 表名（Task 10）、`ChatTextInputMode` 形参（Task 9）。
- **Task 9 偏差记录**：spec 保留清单假设 DiagBundleCollector 被 AppToolExecutor 复用，实际未复用（工厂直接用 Logger.logs），已随诊断链路一并删除（commit 893801c3）。
