#!/usr/bin/env python3
"""
把 build/design-tokens/ardot-variables.json 同步到 Ardot 桌面端打开的文件的变量集合。

定位：Ardot 只是「token 活体预览层」，非 SSOT。SSOT 是 design-tokens.json。
流程：改 design-tokens.json → gen-design-tokens.py → 本脚本 → Ardot 画布实时反映。

前置：Ardot 桌面客户端已启动并打开目标 .ardot 文件（本地 MCP 在 127.0.0.1:50501）。
注意：MCP 工具的 apply_variables 只接受内联 JSON 对象；payload 有 300+ 变量，
经 agent 工具调用手抄易错，故本脚本直连本地 MCP HTTP 端点，文件内容原样上送。

用法：python3 scripts/sync-ardot-variables.py [--payload PATH] [--endpoint URL]
"""

import argparse
import json
import sys
import urllib.request

DEFAULT_ENDPOINT = "http://127.0.0.1:50501/api/v1/mcp"
DEFAULT_PAYLOAD = "build/design-tokens/ardot-variables.json"


def rpc(endpoint, method, params=None, rid=1, sid=None):
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    if sid:
        headers["Mcp-Session-Id"] = sid
    req = urllib.request.Request(endpoint, data=json.dumps(body).encode(), headers=headers)
    resp = urllib.request.urlopen(req, timeout=120)
    sid_out = resp.headers.get("Mcp-Session-Id", sid)
    raw = resp.read().decode()
    for line in raw.splitlines():
        if line.startswith("data:"):
            return json.loads(line[5:].strip()), sid_out
    return (json.loads(raw) if raw else {}), sid_out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--payload", default=DEFAULT_PAYLOAD)
    ap.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    args = ap.parse_args()

    payload = json.load(open(args.payload, encoding="utf-8"))
    _, sid = rpc(args.endpoint, "initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "sync-ardot-variables", "version": "1.0"},
    })
    notify = {"jsonrpc": "2.0", "method": "notifications/initialized"}
    req = urllib.request.Request(args.endpoint, data=json.dumps(notify).encode(), headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        **({"Mcp-Session-Id": sid} if sid else {}),
    })
    try:
        urllib.request.urlopen(req, timeout=30)
    except Exception:
        pass  # notification 无响应属正常

    result, _ = rpc(args.endpoint, "tools/call", {
        "name": "apply_variables",
        "arguments": {"variables": payload},
    }, rid=2, sid=sid)
    text = result["result"]["content"][0]["text"]
    data = json.loads(text)
    if not data.get("success"):
        print(f"❌ apply_variables 失败: {text}")
        sys.exit(1)
    d = data["data"]
    print(f"✅ Ardot 变量同步完成: created={d['created']} updated={d['updated']} deleted={d['deleted']}")
    print("提示：在 Ardot 画布确认预览后，可用 capture_screenshot 截图留档（PR 视觉 diff）。")


if __name__ == "__main__":
    main()
