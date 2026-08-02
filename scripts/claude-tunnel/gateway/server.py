"""Claude 流式网关。POST /chat → SSE；POST /deliver → push 分支；
POST /app-tool-request → 经活跃 SSE 下行 app tool 调用并长轮询等回传；
POST /tool-result → App 回传 tool 结果解挂；GET /healthz。"""
import asyncio
import json
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
APP_TOOL_TIMEOUT = int(os.environ.get("CT_APP_TOOL_TIMEOUT", "60"))
# pump() 与 app_tool_request handler 可能并发写同一 StreamResponse，
# 串行化 _send 防止 SSE 帧交错损坏（量小，一把全局锁即可）。
_SEND_LOCK = asyncio.Lock()

# claude --mcp-config 指向的 stdio MCP server 配置（启动时自生成）。
# claude 的 cwd 是 workdir repo，args 必须用绝对路径，故不能手写静态文件。
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

输出风格（用户在手机上读你的回复，必须简洁可读）：
- 结论先行；用要点 + 关键代码片段回答，不要整段粘贴源文件或完整构建/命令日志。
- 必须展示代码时，只贴关键 ≤30 行片段并注明文件位置，省略部分用注释代替。
- 日志/构建输出只摘录关键行（报错行 + 上下文），不要全量回灌。
- 单次正文控制在约 ≤800 字；长内容分多条消息，每条聚焦一个要点。
"""


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


def annotate_truncated(ev, max_turns=None):
    """pump 转发 done 前调用：达 max_turns 则标注截断。返回（可能改写的）ev。"""
    if ev["event"] != "done":
        return ev
    mt = int(MAX_TURNS) if max_turns is None else max_turns
    turns = ev.get("data", {}).get("turns")
    if isinstance(turns, int) and turns >= mt:
        data = dict(ev["data"])
        data["truncated"] = True
        data["reason"] = "max_turns"
        return {"event": "done", "data": data}
    return ev


def phase_timeout_event(seconds):
    """CT_PHASE_TIMEOUT 触发的截断 error 事件。"""
    return {"event": "error", "data": {
        "message": "phase timeout {}s".format(seconds),
        "truncated": True,
        "reason": "phase_timeout",
    }}


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


async def _send(resp, event):
    async with _SEND_LOCK:
        await resp.write(claude_events.format_sse(event).encode("utf-8"))


async def app_tool_request(request):
    """MCP server → 网关：经该 sid 的活跃 SSE 下行 app_tool_request 事件，长轮询等 App 回传。"""
    body = await request.json()
    sid = body.get("sid", "")
    resp = SSE_HUB.get(sid)
    if resp is None:
        return web.json_response({"ok": False, "error": "app offline (no active SSE)"})
    request_id = uuid.uuid4().hex[:12]
    fut = asyncio.get_running_loop().create_future()
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
        # 带身份判断：旧回合的 finally 不得误删新回合对同一 sid 的注册
        if SSE_HUB.get(sid) is resp:
            SSE_HUB.pop(sid, None)
    return resp


async def _run_claude_turn(resp, sid, message, claude_sid):
    """原 chat() 主体：spawn claude → pump SSE → error/done 收尾。"""
    repo = sm.repo_dir(sid)
    env = build_env(sid)
    cmd = build_cmd(message, claude_sid)

    timeout = int(os.environ.get("CT_PHASE_TIMEOUT", "300"))
    done_sent = False

    async def pump():
        nonlocal done_sent
        async for raw in proc.stdout:
            for ev in claude_events.translate_stream_line(raw.decode("utf-8", "replace")):
                if ev["event"] == "session":
                    sm.set_claude_session(sid, ev["data"]["sid"])
                ev = annotate_truncated(ev)
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
            await _send(resp, phase_timeout_event(timeout))
        await proc.wait()
    except Exception as e:  # noqa: BLE001
        await _send(resp, {"event": "error", "data": {"message": str(e)}})
    if not done_sent:
        await _send(resp, {"event": "done", "data": {}})


async def _run_cmd(cmd, env, timeout, cwd=None, capture=False):
    """异步运行单条命令，超时处理。返回 (rc, stdout, stderr)。"""
    if capture:
        p = await asyncio.create_subprocess_exec(
            *cmd, cwd=cwd, env=env,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    else:
        p = await asyncio.create_subprocess_exec(
            *cmd, cwd=cwd, env=env,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
    try:
        out, err = await asyncio.wait_for(p.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        p.kill()
        await p.wait()
        return -1, b"", "command timed out after {}s".format(timeout).encode()
    return p.returncode, out or b"", err or b""


async def _git_push_branch(repo, branch, env, timeout):
    rc, _, err = await _run_cmd(
        ["git", "-C", repo, "push", "--quiet", "origin", branch],
        env, timeout, capture=True)
    if rc == 0:
        return 0, ""
    return rc, err.decode("utf-8", "replace")[:500] or "git push rc={}".format(rc)


async def _gh_pr_create(repo, base, head, sid, env, timeout):
    title = "fix(claude-tunnel): session {}".format(sid)
    body = "Auto-created by AI engineer mode"
    rc, out, err = await _run_cmd(
        ["gh", "pr", "create", "--base", base, "--head", head, "--title", title, "--body", body],
        env, timeout, cwd=repo, capture=True)
    if rc != 0:
        return rc, "", err.decode("utf-8", "replace")[:500] or out.decode("utf-8", "replace")[:500]
    rc2, out2, err2 = await _run_cmd(
        ["gh", "pr", "view", head, "--json", "url", "-q", ".url"],
        env, timeout, cwd=repo, capture=True)
    if rc2 == 0:
        return 0, out2.decode("utf-8", "replace").strip(), ""
    return 0, out.decode("utf-8", "replace").strip() or "", ""


async def _run_server_tests(repo, env, timeout):
    rc, _, _ = await _run_cmd(
        ["./gradlew", "-p", "server", "test"],
        env, timeout, cwd=repo, capture=False)
    return rc == 0


async def _ff_merge_and_push(repo, base, branch, env, timeout):
    for cmd in (
        ["git", "-C", repo, "fetch", "origin", base],
        ["git", "-C", repo, "checkout", "-B", base, "origin/{}".format(base)],
        ["git", "-C", repo, "merge", "--ff-only", branch],
        ["git", "-C", repo, "push", "origin", base],
    ):
        rc, _, err = await _run_cmd(cmd, env, timeout, capture=True)
        if rc != 0:
            return rc, err.decode("utf-8", "replace")[:500]
    return 0, ""


async def deliver(request):
    """交付当前 session 的改动。支持 push / pr / auto 三档。

    - push：在 workdir commit + push claude-chat/<sid>。
    - pr：push 分支后，用 gh 创建 GitHub PR。
    - auto：push 分支后，跑 server 单测；通过则 ff-merge 进 main 并 push。

    全程异步 + 超时，避免阻塞 gateway event loop。
    """
    body = await request.json()
    sid = body["sid"]
    mode = body.get("mode", "push")
    if mode not in ("push", "pr", "auto"):
        return web.json_response({"ok": False, "error": "invalid mode '{}'".format(mode)}, status=400)
    repo = sm.repo_dir(sid)
    if not sm.exists(sid):
        return web.json_response({"ok": False, "error": "unknown sid"}, status=404)
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    timeout = int(os.environ.get("CT_DELIVER_TIMEOUT", os.environ.get("CT_PUSH_TIMEOUT", "120")))
    base_branch = os.environ.get("CT_BASE_BRANCH", "main")
    branch = "claude-chat/{}".format(sid)
    try:
        # 公共步骤：add / commit
        # --allow-empty：重试交付（首次 push 失败后 workdir 已有提交、无新改动）时
        # "nothing to commit" 会让 commit 退出非零、阻断后续 push。允许空提交即可放过。
        for cmd in (
            ["git", "-C", repo, "add", "-A"],
            ["git", "-C", repo, "commit", "--allow-empty", "-qm", "fix(claude-tunnel): session {}".format(sid)],
        ):
            rc, _, err = await _run_cmd(cmd, env, timeout, capture=True)
            if rc != 0:
                return web.json_response(
                    {"ok": False, "error": "git commit failed: {}".format(err.decode("utf-8", "replace")[:500])},
                    status=500)
        # 公共步骤：push branch
        rc, err = await _git_push_branch(repo, branch, env, timeout)
        if rc != 0:
            return web.json_response({"ok": False, "error": err}, status=500)
        if mode == "push":
            return web.json_response({"ok": True, "branch": branch})
        if mode == "pr":
            rc, pr_url, err = await _gh_pr_create(repo, base_branch, branch, sid, env, timeout)
            if rc != 0:
                return web.json_response({"ok": False, "error": "gh pr create failed: {}".format(err)}, status=500)
            return web.json_response({"ok": True, "branch": branch, "prUrl": pr_url})
        # mode == "auto"
        tested = await _run_server_tests(repo, env, timeout)
        if tested:
            rc, err = await _ff_merge_and_push(repo, base_branch, branch, env, timeout)
            if rc == 0:
                return web.json_response({"ok": True, "branch": base_branch, "merged": True, "tested": True})
        return web.json_response({
            "ok": True,
            "branch": branch,
            "merged": False,
            "tested": tested,
            "note": "tests failed or merge conflict; branch pushed only",
        })
    except Exception as e:  # noqa: BLE001
        return web.json_response({"ok": False, "error": "deliver failed: {}".format(e)}, status=500)


async def healthz(request):
    return web.Response(text="ok")


def main():
    write_mcp_config()
    app = web.Application()
    app.router.add_post("/chat", chat)
    app.router.add_post("/deliver", deliver)
    app.router.add_post("/app-tool-request", app_tool_request)
    app.router.add_post("/tool-result", tool_result)
    app.router.add_get("/healthz", healthz)
    web.run_app(app, host="127.0.0.1", port=int(os.environ.get("CT_PORT", "3000")))


if __name__ == "__main__":
    main()
