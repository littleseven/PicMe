#!/usr/bin/env bash
# Detect hardcoded user-visible strings in a single .kt/.java file (high-signal subset).
# usage: i18n-hardcode.sh <file>   → prints ⚠️ lines; always exit 0.
set -uo pipefail
[ -n "${1:-}" ] || exit 0
FILE="$1"
[ -f "$FILE" ] || exit 0

OUT="$(FILE="$FILE" python3 - <<'PY'
import os, re
p = os.environ["FILE"]
try:
    lines = open(p, encoding="utf-8").read().splitlines()
except Exception:
    raise SystemExit
str_re = re.compile(r'"([^"\\]{3,})"')
NOISE = ("log.", " tag", "tag =", "http", "://", "buildconfig", ".packageinfo", "contentresolver")
hits = []
for i, ln in enumerate(lines, 1):
    s = ln.strip()
    if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
        continue
    low = ln.lower()
    if any(k in low for k in NOISE):
        continue
    for m in str_re.finditer(ln):
        val = m.group(1)
        has_letter = bool(re.search(r"[A-Za-z]", val))
        has_cjk = bool(re.search(r"[一-鿿]", val))
        if not (has_letter or has_cjk):
            continue
        if re.fullmatch(r"[A-Z0-9_]+", val):        # CONST_LIKE_KEY
            continue
        if re.fullmatch(r"[a-z][a-z0-9_]*", val) and len(val) <= 12:
            continue                                # likely an identifier/key (user_id, api_key, db_name)
        hits.append(f'  ⚠️  L{i}: "{val}"')
        break
for h in hits[:20]:
    print(h)
PY
)"
if [ -n "$OUT" ]; then
  echo "🌐 i18n 疑似硬编码 ($FILE):"
  echo "$OUT"
fi
exit 0
