#!/usr/bin/env bash
# Hook C: desktop notification via macOS osascript (terminal-notifier not installed).
# usage (from kimi hook command): notify.sh subagent|notification   (event JSON on stdin)
set -uo pipefail
DEBUG="${KIMI_POLANG_HOOK_DEBUG:-0}"
kind="${1:-subagent}"

INPUT="$(cat)"
MSG="$(HOOK_JSON="$INPUT" python3 -c '
import os, json, sys
kind = sys.argv[1]
try:
    d = json.loads(os.environ.get("HOOK_JSON", "") or "{}")
except Exception:
    d = {}
if kind == "notification":
    t = d.get("title") or "Kimi 通知"
    b = d.get("body") or ""
    print((t + "｜" + b) if b else t)
else:
    print("子agent 完成")
' "$kind")"

[ "$DEBUG" = "1" ] && echo "[notify] kind=$kind msg=$MSG" >&2
# escape backslash + double-quote so a title/body containing them can't break AppleScript compilation
MSG_ESC="${MSG//\\/\\\\}"
MSG_ESC="${MSG_ESC//\"/\\\"}"
/usr/bin/osascript -e "display notification \"${MSG_ESC:-完成}\" with title \"Kimi\" sound name \"Glass\"" >/dev/null 2>&1 || true
exit 0
