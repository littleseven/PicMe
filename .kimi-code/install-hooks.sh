#!/usr/bin/env bash
# Managed append/remove of polang kimi-code [[hooks]] block in USER config.toml.
# SSOT: .kimi-code/hooks.toml. Idempotent. Marker-guarded so other config is untouched.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
HOOKS_TOML="${HOOKS_TOML:-$HERE/hooks.toml}"
KIMI_HOME="${KIMI_CODE_HOME:-$HOME/.kimi-code}"
USER_CONFIG="$KIMI_HOME/config.toml"
BEGIN='# >>> polang kimi-code hooks (managed; SSOT=polang/.kimi-code/hooks.toml) >>>'
END='# <<< polang kimi-code hooks <<<'

apply_remove() {  # args: cfg begin end
  python3 - "$1" "$2" "$3" <<'PY'
import sys, pathlib
cfg, begin, end = sys.argv[1:4]
text = pathlib.Path(cfg).read_text(encoding="utf-8") if pathlib.Path(cfg).exists() else ""
lines = text.splitlines()
out, i, n = [], 0, len(lines)
while i < n:
    if lines[i].strip() == begin:
        while i < n and lines[i].strip() != end:
            i += 1
        i += 1                       # skip end marker
        if i < n and lines[i].strip() == "":   # drop one trailing blank
            i += 1
        continue
    out.append(lines[i]); i += 1
pathlib.Path(cfg).write_text(("\n".join(out).rstrip("\n") + "\n"), encoding="utf-8")
PY
}

case "${1:-install}" in
  install|"")
    [ -f "$HOOKS_TOML" ] || { echo "missing $HOOKS_TOML" >&2; exit 1; }
    mkdir -p "$KIMI_HOME"; touch "$USER_CONFIG"
    apply_remove "$USER_CONFIG" "$BEGIN" "$END"          # idempotent: drop old block first
    block="$(cat "$HOOKS_TOML")"
    printf '\n%s\n' "$block" >> "$USER_CONFIG"
    echo "installed polang hooks block -> $USER_CONFIG"
    ~/.kimi-code/bin/kimi doctor 2>&1 | tail -4 || true
    ;;
  remove|--remove|uninstall)
    apply_remove "$USER_CONFIG" "$BEGIN" "$END"
    echo "removed polang hooks block -> $USER_CONFIG"
    ~/.kimi-code/bin/kimi doctor 2>&1 | tail -4 || true
    ;;
  *) echo "usage: $0 [install|remove]" >&2; exit 2 ;;
esac
