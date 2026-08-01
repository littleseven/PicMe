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
