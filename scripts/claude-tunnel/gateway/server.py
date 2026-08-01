"""Claude 流式网关。POST /chat → SSE；POST /deliver → push 分支；GET /healthz。"""
import asyncio
import os

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
    return resp


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
    app.router.add_get("/healthz", healthz)
    web.run_app(app, host="127.0.0.1", port=int(os.environ.get("CT_PORT", "3000")))


if __name__ == "__main__":
    main()
