import json

from claude_events import translate_stream_line, format_sse


def test_init_emits_session():
    line = json.dumps({"type": "system", "subtype": "init", "session_id": "abc123"})
    assert translate_stream_line(line) == [{"event": "session", "data": {"sid": "abc123"}}]


def test_assistant_text():
    line = json.dumps({"type": "assistant", "message": {"role": "assistant",
                     "content": [{"type": "text", "text": "hello"}]}})
    assert translate_stream_line(line) == [{"event": "assistant_text", "data": {"delta": "hello"}}]


def test_tool_use_and_file_change():
    line = json.dumps({"type": "assistant", "message": {"role": "assistant", "content": [
        {"type": "tool_use", "name": "Edit", "input": {"file_path": "src/Foo.kt"}}]}})
    evs = translate_stream_line(line)
    assert {"event": "tool_use", "data": {"tool": "Edit", "input": {"file_path": "src/Foo.kt"}}} in evs
    assert {"event": "file_change", "data": {"path": "src/Foo.kt", "action": "modified"}} in evs


def test_tool_result_ok():
    line = json.dumps({"type": "user", "message": {"content": [
        {"type": "tool_result", "content": "tests passed", "is_error": False}]}})
    assert translate_stream_line(line) == [{"event": "tool_result", "data": {"ok": True, "summary": "tests passed"}}]


def test_tool_result_error_and_long_summary_truncated():
    long = "x" * 500
    line = json.dumps({"type": "user", "message": {"content": [
        {"type": "tool_result", "content": long, "is_error": True}]}})
    evs = translate_stream_line(line)
    assert evs[0]["data"]["ok"] is False
    assert len(evs[0]["data"]["summary"]) == 300


def test_result_done():
    line = json.dumps({"type": "result", "subtype": "success", "num_turns": 3})
    assert translate_stream_line(line) == [{"event": "done", "data": {"turns": 3}}]


def test_empty_and_garbage_return_empty_list():
    assert translate_stream_line("") == []
    assert translate_stream_line("not json") == []


def test_format_sse():
    s = format_sse({"event": "done", "data": {"turns": 3}})
    assert s == 'event: done\ndata: {"turns": 3}\n\n'
