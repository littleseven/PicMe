#!/usr/bin/env bash
# Detect hardcoded .dp / Color(0x...) in a single .kt file outside designsystem/.
# Observational: prints ⚠️ lines, always exit 0.
# usage: parity-hardcode.sh <file>
set -uo pipefail
[ -n "${1:-}" ] || exit 0
FILE="$1"
[ -f "$FILE" ] || exit 0

# Only check .kt files
case "$FILE" in
  *.kt) ;;
  *) exit 0 ;;
esac

# Skip design system directory (token definitions live here)
case "$FILE" in
  */designsystem/*) exit 0 ;;
  *) ;;
esac

OUT="$(FILE="$FILE" python3 - <<'PY'
import os, re
p = os.environ["FILE"]
try:
    lines = open(p, encoding="utf-8").read().splitlines()
except Exception:
    raise SystemExit

dp_re = re.compile(r'\b\d+\.dp\b')
color_re = re.compile(r'Color\(0x[0-9A-Fa-f]{8}\)')
hits = []
for i, ln in enumerate(lines, 1):
    s = ln.strip()
    # Skip comments
    if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
        continue
    found = []
    for m in dp_re.finditer(ln):
        found.append(m.group())
    for m in color_re.finditer(ln):
        found.append(m.group())
    if found:
        hits.append(f'  ⚠️  L{i}: {", ".join(found)}')
for h in hits[:15]:
    print(h)
PY
)"

if [ -n "$OUT" ]; then
  echo "📐 [PARITY] 疑似硬编码 dp/color ($FILE) — 建议引用 MaterialTheme.spacing / AppColors:"
  echo "$OUT"
fi
exit 0
