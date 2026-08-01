import asyncio
import json
import os

# server.py 模块级读 CT_REPO_URL（构造 SessionManager）；测试默认置一个无害值。
os.environ.setdefault("CT_REPO_URL", "unused")

import pytest  # noqa: E402
from aiohttp import web  # noqa: E402

import server  # noqa: E402


def test_build_cmd_includes_settings_pointing_to_existing_template():
    cmd = server.build_cmd("hi", None)
    assert "--settings" in cmd
    idx = cmd.index("--settings")
    # 指向的权限白名单模板确实随 gateway/ 落盘
    assert os.path.exists(cmd[idx + 1])


def test_build_cmd_resume_when_sid():
    cmd = server.build_cmd("hi", "csid-1")
    assert "--resume" in cmd
    assert cmd[cmd.index("--resume") + 1] == "csid-1"


def test_build_cmd_no_resume_without_sid():
    cmd = server.build_cmd("hi", None)
    assert "--resume" not in cmd


def test_build_cmd_includes_mcp_and_system_prompt(monkeypatch, tmp_path):
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
    cfg = json.loads(mcp_cfg.read_text())
    args = cfg["mcpServers"]["app_tools"]["args"]
    assert args[0].endswith("app_tools_mcp.py") and args[0].startswith("/")


def test_build_env_injects_session_sid():
    """chat spawn 的 env 必须带 CT_SESSION_SID=<网关 sid>（MCP 子进程继承）。"""
    env = server.build_env("sidX")
    assert env["CT_SESSION_SID"] == "sidX"
    assert env["IS_SANDBOX"] == "1"


def test_build_cmd_core_flags():
    cmd = server.build_cmd("do something", None)
    assert cmd[0] == server.CLAUDE
    assert "-p" in cmd and "do something" in cmd
    assert "--output-format" in cmd
    assert "--max-turns" in cmd


class _FakeResp:
    """mock SSE writer：捕获写出的帧文本，测试从中解析下行事件。"""

    def __init__(self):
        self.frames = []

    async def write(self, data: bytes):
        self.frames.append(data.decode("utf-8"))

    def tool_request_data(self):
        assert self.frames, "no SSE frame pushed"
        frame = self.frames[0]
        assert frame.startswith("event: app_tool_request\n")
        return json.loads(frame.split("data: ", 1)[1])


async def _wait_tool_request_data(fake):
    """轮询等待下行 SSE 帧出现（避免固定 sleep 在慢机器上偶发失败）。"""
    for _ in range(200):
        if fake.frames:
            return fake.tool_request_data()
        await asyncio.sleep(0.01)
    raise AssertionError("no app_tool_request SSE frame pushed")


async def test_tool_result_roundtrip(aiohttp_client):
    """POST /app-tool-request 挂起 → 下行 SSE 帧 → POST /tool-result 解挂并返回 payload。"""
    app = web.Application()
    app.router.add_post("/app-tool-request", server.app_tool_request)
    app.router.add_post("/tool-result", server.tool_result)
    client = await aiohttp_client(app)

    fake = _FakeResp()
    server.SSE_HUB["sid1"] = fake

    async def post_result():
        request_id = (await _wait_tool_request_data(fake))["requestId"]
        return await client.post("/tool-result", json={
            "requestId": request_id,
            "payload": {"logs": "hello"},
        })

    try:
        result, result_resp = await asyncio.gather(
            client.post("/app-tool-request",
                        json={"sid": "sid1", "tool": "app_get_logs", "args": {"lines": 50}}),
            post_result(),
        )
        body = await result.json()
        assert body["ok"] is True
        assert body["payload"] == {"logs": "hello"}
        assert (await result_resp.json())["ok"] is True
        # 核心下行语义：SSE 帧携带 app_tool_request 事件、requestId、tool 名、args
        data = fake.tool_request_data()
        assert data["requestId"]
        assert data["tool"] == "app_get_logs"
        assert data["args"] == {"lines": 50}
    finally:
        server.SSE_HUB.pop("sid1", None)


async def test_app_tool_request_no_active_sse(aiohttp_client):
    """sid 无活跃 SSE → 立即返回 ok=false，不挂起。"""
    app = web.Application()
    app.router.add_post("/app-tool-request", server.app_tool_request)
    client = await aiohttp_client(app)
    resp = await client.post("/app-tool-request", json={"sid": "ghost", "tool": "app_get_logs", "args": {}})
    body = await resp.json()
    assert body["ok"] is False
    assert "offline" in body["error"]


async def test_app_tool_request_timeout(aiohttp_client, monkeypatch):
    """App 不回传 → 超时 ok=false、PENDING 清空，迟到的 /tool-result 得 404。"""
    monkeypatch.setattr(server, "APP_TOOL_TIMEOUT", 0.05)
    app = web.Application()
    app.router.add_post("/app-tool-request", server.app_tool_request)
    app.router.add_post("/tool-result", server.tool_result)
    client = await aiohttp_client(app)

    fake = _FakeResp()
    server.SSE_HUB["sid1"] = fake
    try:
        resp = await client.post("/app-tool-request", json={"sid": "sid1", "tool": "app_get_logs", "args": {}})
        body = await resp.json()
        assert body["ok"] is False
        assert "timeout" in body["error"]
        assert server.PENDING == {}
        request_id = fake.tool_request_data()["requestId"]
        late = await client.post("/tool-result", json={"requestId": request_id, "payload": {}})
        assert late.status == 404
    finally:
        server.SSE_HUB.pop("sid1", None)



class _FakeProc:
    def __init__(self, rc=0, stdout=b"", stderr=b""):
        self.returncode = rc
        self._stdout = stdout
        self._stderr = stderr

    async def communicate(self):
        return self._stdout, self._stderr

    async def wait(self):
        return self.returncode

    def kill(self):
        pass


class _SubprocMocker:
    def __init__(self):
        self.calls = []
        self._responses = {}

    def set(self, cmd_prefix, rc=0, stdout=b"", stderr=b""):
        """按命令前缀设置返回；前缀中 None 表示通配任一参数。"""
        self._responses[tuple(cmd_prefix)] = (rc, stdout, stderr)

    async def __call__(self, *cmd, **kwargs):
        self.calls.append(cmd)
        for prefix, (rc, out, err) in self._responses.items():
            if len(cmd) < len(prefix):
                continue
            match = True
            for i, p in enumerate(prefix):
                if p is not None and cmd[i] != p:
                    match = False
                    break
            if match:
                return _FakeProc(rc, out, err)
        return _FakeProc(0)


@pytest.fixture
def deliver_env(tmp_path, monkeypatch):
    """为 deliver 测试准备：临时 work_root + SessionManager + subprocess mock。"""
    work_root = tmp_path / "work"
    work_root.mkdir()
    sm = server.session.SessionManager(str(work_root), "unused")
    monkeypatch.setattr(server, "sm", sm)
    mocker = _SubprocMocker()
    monkeypatch.setattr(asyncio, "create_subprocess_exec", mocker)
    return {"sm": sm, "mocker": mocker}


def _make_repo(deliver_env):
    sid = "abc123"
    repo = deliver_env["sm"].repo_dir(sid)
    os.makedirs(os.path.join(repo, ".git"))
    return sid, repo


async def test_deliver_invalid_mode(aiohttp_client, deliver_env):
    app = web.Application()
    app.router.add_post("/deliver", server.deliver)
    client = await aiohttp_client(app)
    sid, _ = _make_repo(deliver_env)
    resp = await client.post("/deliver", json={"sid": sid, "mode": "bad"})
    assert resp.status == 400
    body = await resp.json()
    assert "invalid mode" in body["error"]


async def test_deliver_unknown_sid(aiohttp_client, deliver_env):
    app = web.Application()
    app.router.add_post("/deliver", server.deliver)
    client = await aiohttp_client(app)
    resp = await client.post("/deliver", json={"sid": "nosuch", "mode": "push"})
    assert resp.status == 404


async def test_deliver_push_ok(aiohttp_client, deliver_env):
    app = web.Application()
    app.router.add_post("/deliver", server.deliver)
    client = await aiohttp_client(app)
    sid, repo = _make_repo(deliver_env)
    resp = await client.post("/deliver", json={"sid": sid, "mode": "push"})
    assert resp.status == 200
    body = await resp.json()
    assert body["ok"] is True
    assert body["branch"] == "claude-chat/{}".format(sid)
    calls = deliver_env["mocker"].calls
    assert list(calls[0]) == ["git", "-C", repo, "add", "-A"]
    assert calls[1][:4] == ("git", "-C", repo, "commit")
    assert calls[2][:3] == ("git", "-C", repo)
    assert calls[2][-2:] == ("origin", "claude-chat/{}".format(sid))


async def test_deliver_commit_fails(aiohttp_client, deliver_env):
    # commit 命令形如 git -C <repo> commit -qm <msg>，commit 在索引 3
    deliver_env["mocker"].set(["git", None, None, "commit"], rc=1, stderr=b"nothing to commit")
    app = web.Application()
    app.router.add_post("/deliver", server.deliver)
    client = await aiohttp_client(app)
    sid, _ = _make_repo(deliver_env)
    resp = await client.post("/deliver", json={"sid": sid, "mode": "push"})
    assert resp.status == 500
    body = await resp.json()
    assert body["ok"] is False
    assert "commit failed" in body["error"]


async def test_deliver_pr_ok(aiohttp_client, deliver_env):
    pr_url = "https://github.com/org/repo/pull/7"
    deliver_env["mocker"].set(["gh", "pr", "create"], rc=0, stdout=(pr_url + "\n").encode())
    deliver_env["mocker"].set(["gh", "pr", "view"], rc=0, stdout=(pr_url + "\n").encode())
    app = web.Application()
    app.router.add_post("/deliver", server.deliver)
    client = await aiohttp_client(app)
    sid, _ = _make_repo(deliver_env)
    resp = await client.post("/deliver", json={"sid": sid, "mode": "pr"})
    assert resp.status == 200
    body = await resp.json()
    assert body["ok"] is True
    assert body["prUrl"] == pr_url
    assert body["branch"] == "claude-chat/{}".format(sid)


async def test_deliver_pr_gh_fails(aiohttp_client, deliver_env):
    deliver_env["mocker"].set(["gh", "pr", "create"], rc=1, stderr=b"gh not authenticated")
    app = web.Application()
    app.router.add_post("/deliver", server.deliver)
    client = await aiohttp_client(app)
    sid, _ = _make_repo(deliver_env)
    resp = await client.post("/deliver", json={"sid": sid, "mode": "pr"})
    assert resp.status == 500
    body = await resp.json()
    assert body["ok"] is False
    assert "gh pr create failed" in body["error"]


async def test_deliver_auto_merge_ok(aiohttp_client, deliver_env):
    deliver_env["mocker"].set(["./gradlew"], rc=0)
    app = web.Application()
    app.router.add_post("/deliver", server.deliver)
    client = await aiohttp_client(app)
    sid, _ = _make_repo(deliver_env)
    resp = await client.post("/deliver", json={"sid": sid, "mode": "auto"})
    assert resp.status == 200
    body = await resp.json()
    assert body["ok"] is True
    assert body["merged"] is True
    assert body["branch"] == "main"
    # 验证跑了 server 测试
    gradle_calls = [c for c in deliver_env["mocker"].calls if c and c[0] == "./gradlew"]
    assert len(gradle_calls) == 1


async def test_deliver_auto_tests_fail(aiohttp_client, deliver_env):
    deliver_env["mocker"].set(["./gradlew"], rc=1)
    app = web.Application()
    app.router.add_post("/deliver", server.deliver)
    client = await aiohttp_client(app)
    sid, _ = _make_repo(deliver_env)
    resp = await client.post("/deliver", json={"sid": sid, "mode": "auto"})
    assert resp.status == 200
    body = await resp.json()
    assert body["ok"] is True
    assert body["merged"] is False
    assert body["tested"] is False
    assert body["branch"] == "claude-chat/{}".format(sid)
