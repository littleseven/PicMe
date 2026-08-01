"""Claude 流式网关。POST /chat → SSE；POST /deliver → push 分支；GET /healthz。"""
import asyncio
import os
import uuid

from aiohttp import web

import claude_events
import session

CLAUDE = os.environ.get("CT_CLAUDE", "claude")
MAX_TURNS = os.environ.get("CT_MAX_TURNS", "20")
WORK_ROOT = os.environ.get("CT_WORK_ROOT", "/tmp/claude-tunnel-work")
REPO_URL = os.environ["CT_REPO_URL"]
BASE_BRANCH = os.environ.get("CT_BASE_BRANCH", "main")
# claude 权限白名单（permissions.allow，非 bypass；root + IS_SANDBOX 下可用）。
# 默认随 gateway/ 目录落盘，CT_SETTINGS 可覆盖路径。df354bec：bypass 被 claude 硬禁。
SETTINGS_PATH = os.environ.get(
    "CT_SETTINGS",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "claude-settings.json"),
)

sm = session.SessionManager(WORK_ROOT, REPO_URL, BASE_BRANCH)

# ── app-tool 桥接（spec §4.1）：MCP server 经 localhost HTTP 与网关通信 ──
SSE_HUB = {}       # sid → 活跃 /chat 的 StreamResponse（tool call 只发生在回合进行中）
PENDING = {}       # requestId → asyncio.Future（tool-result 解挂）
_LAST_REQUEST_ID = None  # 测试钩子
APP_TOOL_TIMEOUT = int(os.environ.get("CT_APP_TOOL_TIMEOUT", "60"))


def build_cmd(message, claude_sid):
    """构造 claude CLI 调用：--settings 指向权限白名单（模板存在时）+ --resume（多轮续上下文）。"""
    cmd = [CLAUDE, "-p", message, "--output-format", "stream-json",
           "--max-turns", MAX_TURNS, "--verbose"]
    if os.path.exists(SETTINGS_PATH):
        cmd += ["--settings", SETTINGS_PATH]
    if claude_sid:
        cmd += ["--resume", claude_sid]
    return cmd


async def _send(resp, event):
    await resp.write(claude_events.format_sse(event).encode("utf-8"))


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

    SSE_HUB[sid] = resp
    try:
        await _run_claude_turn(resp, sid, message, claude_sid)
    finally:
        SSE_HUB.pop(sid, None)
    return resp


async def _run_claude_turn(resp, sid, message, claude_sid):
    """原 chat() 主体：spawn claude → pump SSE → error/done 收尾。"""
    repo = sm.repo_dir(sid)
    env = dict(os.environ, IS_SANDBOX="1", GIT_TERMINAL_PROMPT="0")
    cmd = build_cmd(message, claude_sid)

    timeout = int(os.environ.get("CT_PHASE_TIMEOUT", "300"))
    done_sent = False

    async def pump():
        nonlocal done_sent
        async for raw in proc.stdout:
            for ev in claude_events.translate_stream_line(raw.decode("utf-8", "replace")):
                if ev["event"] == "session":
                    sm.set_claude_session(sid, ev["data"]["sid"])
                if ev["event"] == "done":
                    done_sent = True
                await _send(resp, ev)

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


async def deliver(request):
    """MVP：仅 push 模式——在 workdir commit + push claude-chat/<sid>。pr/auto 二期。

    全程异步（asyncio.create_subprocess_exec）+ 超时：避免同步 subprocess.run 阻塞 event loop
    （git push 若挂会卡死整个 gateway，连 healthz 都不响应）。push 失败/超时回传 stderr 便于诊断。"""
    body = await request.json()
    sid = body["sid"]
    repo = sm.repo_dir(sid)
    if not sm.exists(sid):
        return web.json_response({"ok": False, "error": "unknown sid"}, status=404)
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    timeout = int(os.environ.get("CT_PUSH_TIMEOUT", "30"))
    try:
        for cmd in (
            ["git", "-C", repo, "add", "-A"],
            ["git", "-C", repo, "commit", "-qm", "fix(claude-tunnel): session {}".format(sid)],
        ):
            p = await asyncio.create_subprocess_exec(
                *cmd, env=env,
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
            await asyncio.wait_for(p.wait(), timeout=timeout)
        push = await asyncio.create_subprocess_exec(
            "git", "-C", repo, "push", "--quiet", "origin", "claude-chat/{}".format(sid),
            env=env, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            _out, err = await asyncio.wait_for(push.communicate(), timeout=timeout)
        except asyncio.TimeoutError:
            push.kill()
            await push.wait()
            return web.json_response(
                {"ok": False,
                 "error": "git push timeout ({}s) — KimiClaw 无法连 remote，查网络/credential".format(timeout)},
                status=504)
        if push.returncode == 0:
            return web.json_response({"ok": True, "branch": "claude-chat/{}".format(sid)})
        return web.json_response(
            {"ok": False,
             "error": (err.decode("utf-8", "replace")[:500] or "git push rc={}".format(push.returncode))},
            status=500)
    except Exception as e:  # noqa: BLE001
        return web.json_response({"ok": False, "error": "deliver failed: {}".format(e)}, status=500)


async def healthz(request):
    return web.Response(text="ok")


def main():
    app = web.Application()
    app.router.add_post("/chat", chat)
    app.router.add_post("/deliver", deliver)
    app.router.add_post("/app-tool-request", app_tool_request)
    app.router.add_post("/tool-result", tool_result)
    app.router.add_get("/healthz", healthz)
    web.run_app(app, host="127.0.0.1", port=int(os.environ.get("CT_PORT", "3000")))


if __name__ == "__main__":
    main()
