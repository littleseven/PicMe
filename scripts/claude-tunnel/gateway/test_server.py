import asyncio
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


def test_build_cmd_core_flags():
    cmd = server.build_cmd("do something", None)
    assert cmd[0] == server.CLAUDE
    assert "-p" in cmd and "do something" in cmd
    assert "--output-format" in cmd
    assert "--max-turns" in cmd


async def test_tool_result_roundtrip(aiohttp_client):
    """POST /app-tool-request 挂起 → POST /tool-result 解挂并返回 payload。"""
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
    app = web.Application()
    app.router.add_post("/app-tool-request", server.app_tool_request)
    client = await aiohttp_client(app)
    resp = await client.post("/app-tool-request", json={"sid": "ghost", "tool": "app_get_logs", "args": {}})
    body = await resp.json()
    assert body["ok"] is False
    assert "offline" in body["error"]
