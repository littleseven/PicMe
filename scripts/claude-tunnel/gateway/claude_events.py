"""Claude Code stream-json (NDJSON) → claude-tunnel SSE 事件翻译。spec §6 事件协议。"""
import json

# 文件改动类工具 → 额外发 file_change 事件
_FILE_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")


def translate_stream_line(line):
    """一行 stream-json → 事件 dict 列表（空列表=无事件/无法识别）。"""
    line = line.strip()
    if not line:
        return []
    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        return []
    mtype = msg.get("type")
    events = []
    if mtype == "system" and msg.get("subtype") == "init":
        sid = msg.get("session_id")
        if sid:
            events.append({"event": "session", "data": {"sid": sid}})
    elif mtype == "assistant":
        for block in msg.get("message", {}).get("content", []):
            btype = block.get("type")
            if btype == "text" and block.get("text"):
                events.append({"event": "assistant_text", "data": {"delta": block["text"]}})
            elif btype == "tool_use":
                inp = block.get("input") or {}
                events.append({"event": "tool_use", "data": {"tool": block.get("name"), "input": inp}})
                if block.get("name") in _FILE_TOOLS:
                    path = inp.get("file_path") or inp.get("notebook_path")
                    if path:
                        events.append({"event": "file_change", "data": {"path": path, "action": "modified"}})
    elif mtype == "user":
        for block in msg.get("message", {}).get("content", []):
            if block.get("type") == "tool_result":
                events.append({"event": "tool_result", "data": {
                    "ok": not block.get("is_error", False),
                    "summary": _summarize(block.get("content")),
                }})
    elif mtype == "result":
        events.append({"event": "done", "data": {"turns": msg.get("num_turns")}})
    return events


def _summarize(content, limit=300):
    """tool_result content（str 或 list[{type:text,text}]）→ 压空白 + 截断。"""
    if isinstance(content, str):
        s = content
    elif isinstance(content, list):
        s = " ".join(b.get("text", "") for b in content
                     if isinstance(b, dict) and b.get("type") == "text")
    else:
        s = str(content)
    return " ".join(s.split())[:limit]


def format_sse(event):
    return "event: {ev}\ndata: {d}\n\n".format(ev=event["event"], d=json.dumps(event["data"]))
