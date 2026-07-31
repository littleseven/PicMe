#!/usr/bin/env python3
"""假 claude：忽略入参，吐固定的 stream-json 事件序列。供 smoke 验证网关翻译。"""
import json

events = [
    {"type": "system", "subtype": "init", "session_id": "stub-session-1"},
    {"type": "assistant", "message": {"role": "assistant",
      "content": [{"type": "text", "text": "hello from stub"}]}},
    {"type": "assistant", "message": {"role": "assistant", "content": [
        {"type": "tool_use", "name": "Bash", "input": {"command": "echo hi"}}]}},
    {"type": "user", "message": {"content": [
        {"type": "tool_result", "content": "hi", "is_error": False}]}},
    {"type": "result", "subtype": "success", "num_turns": 2},
]
for e in events:
    print(json.dumps(e), flush=True)
