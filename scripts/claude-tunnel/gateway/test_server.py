import asyncio
import json
import os

# server.py 模块级读 CT_REPO_URL（构造 SessionManager）；测试默认置一个无害值。
os.environ.setdefault("CT_REPO_URL", "unused")

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
