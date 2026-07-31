#!/usr/bin/env bash
# 用 stub-claude 验证网关 /chat 的 SSE 翻译。本机可跑，不依赖真 claude/隧道。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GW="$HERE/../gateway"
VENV="${CT_SMOKE_VENV:-/tmp/ct-smoke-venv}"
[ -d "$VENV" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" install -q aiohttp

chmod +x "$HERE/stub-claude.py"
export CT_CLAUDE="$HERE/stub-claude.py"
export CT_WORK_ROOT="${CT_WORK_ROOT:-/tmp/ct-smoke-work}"
rm -rf "$CT_WORK_ROOT"
mkdir -p "$CT_WORK_ROOT"

# 造本地 fake repo 给 sm.create() clone（避免 CT_REPO_URL=. 失败）
FAKE="$CT_WORK_ROOT/fake-repo"
mkdir -p "$FAKE"
git init -q "$FAKE"
git -C "$FAKE" config user.email t@t
git -C "$FAKE" config user.name t
echo hi >"$FAKE/README"
git -C "$FAKE" add -A
git -C "$FAKE" commit -qm init
git -C "$FAKE" branch -M main
export CT_REPO_URL="$FAKE"

"$VENV/bin/python" "$GW/server.py" >/tmp/ct-smoke.log 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
for i in $(seq 1 30); do
  curl -sf http://127.0.0.1:3000/healthz 2>/dev/null && break
  sleep 0.3
done

OUT="$(curl -fs -m 10 -X POST http://127.0.0.1:3000/chat \
  -H 'Content-Type: application/json' -d '{"message":"hi"}')"
echo "$OUT"
echo "$OUT" | grep -q 'event: assistant_text' && echo "PASS: assistant_text"
echo "$OUT" | grep -q 'event: tool_use' && echo "PASS: tool_use"
echo "$OUT" | grep -q 'event: tool_result' && echo "PASS: tool_result"
echo "$OUT" | grep -q 'event: done' && echo "PASS: done"
