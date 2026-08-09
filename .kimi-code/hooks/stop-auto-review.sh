#!/usr/bin/env bash
# Hook B: async GLM cross-review on turn end. Non-blocking; always exit 0.
# Guards: (1) recursion sentinel, (2) code-change guard, (3) 10-min cooldown.
set -uo pipefail
DEBUG="${KIMI_POLANG_HOOK_DEBUG:-0}"
log() { [ "$DEBUG" = "1" ] && echo "[stop-review] $*" >&2 || true; }

# drain stdin (Stop payload) — unused fields, but must consume
cat >/dev/null 2>&1 || true

# (1) recursion: don't re-trigger from the spawned review process
[ "${KIMI_POLANG_HOOK_REVIEW:-0}" = "1" ] && { log "review process; skip"; exit 0; }

# (2) only when code actually changed this turn
CHANGED="$(git status --porcelain 2>/dev/null | grep -E '\.(kt|xml)$' || true)"
[ -n "$CHANGED" ] || { log "no .kt/.xml changes; skip"; exit 0; }

# (3) cooldown 600s
STATE_DIR="$(cd "$(dirname "$0")/.." && pwd)"   # .kimi-code/
LAST="$STATE_DIR/.last-review"
COOLDOWN=600
now="$(date +%s)"
if [ -f "$LAST" ]; then
  prev="$(tr -dc '0-9' < "$LAST" 2>/dev/null)"
  if [ -n "$prev" ] && [ "$((now - prev))" -lt "$COOLDOWN" ]; then
    log "cooldown $((now - prev))s < ${COOLDOWN}s; skip"; exit 0
  fi
fi

# (4) async spawn (detached so the Stop hook returns immediately)
KIMI_REVIEW="${KIMI_REVIEW:-$HOME/.kimi-code/bin/kimi-review}"
[ -x "$KIMI_REVIEW" ] || { log "kimi-review missing ($KIMI_REVIEW); skip"; exit 0; }
echo "$now" > "$LAST"
KIMI_POLANG_HOOK_REVIEW=1 nohup "$KIMI_REVIEW" "$PWD" > "$STATE_DIR/.last-review.log" 2>&1 &
disown 2>/dev/null || true
log "spawned async review of $PWD"
exit 0
