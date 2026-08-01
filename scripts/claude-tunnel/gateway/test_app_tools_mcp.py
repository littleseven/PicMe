"""app_tools_mcp 协议单测：initialize/tools.list/tools.call 路由与错误分支（不起真 gateway）。"""
import json
from unittest import mock

import app_tools_mcp


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
