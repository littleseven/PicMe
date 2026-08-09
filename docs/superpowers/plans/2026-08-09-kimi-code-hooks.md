# Kimi Code Hooks (P0-1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three native kimi-code hooks (post-edit lightweight check, turn-end async GLM cross-review, completion desktop notification) to polang, scoped to the polang repo via cwd self-isolation, with the `[[hooks]]` config managed-appended into the user `~/.kimi-code/config.toml`.

**Architecture:** kimi-code has no project-level `config.toml` (verified by binary reverse-engineering — see spec §0), so the `[[hooks]]` block must live in user-level `config.toml`. Project isolation is achieved by making each hook `command` a relative path guarded by `[ -x .kimi-code/hooks/X.sh ] && … || true`: the scripts only exist under polang, so other repos no-op silently. All logic + the config SSOT live in the repo under `.kimi-code/`; `install-hooks.sh` idempotently appends/removes a marker-wrapped block in user config. Hooks are observational/async and fail-open.

**Tech Stack:** bash (`set -uo pipefail`), python3 (JSON parse + CJK-aware i18n detection — BSD `grep` lacks `-P`), macOS-native `osascript` (terminal-notifier not installed), existing `scripts/check_doc_sync.py` and `~/.kimi-code/bin/kimi-review`. No `jq` dependency.

**Spec:** `docs/superpowers/specs/2026-08-09-kimi-code-hooks-design.md` (v2, Option A).

**Verified facts baked into this plan:**
- `HookDefSchema$1` (strict): `event`, `matcher` (optional **RegExp** on tool name; empty = all), `command` (required), `timeout` (optional, 1–600s, default 30).
- Events: `PreToolUse PostToolUse PostToolUseFailure PermissionRequest PermissionResult UserPromptSubmit Stop StopFailure Interrupt SessionStart SessionEnd SubagentStart SubagentStop PreCompact PostCompact Notification`.
- stdin JSON is snake_case. PostToolUse: `hook_event_name session_id cwd matcher_value tool_name tool_input{…} tool_call_id`. **File path = `tool_input.path`** (Edit args = `old_string/new_string/path`; Write args = `content/path`).
- `Stop` is blockable; `Notification` is fire-and-forget, `matcherValue = notification.type`, stdin carries `title/body/severity`.
- Tool names (wire.jsonl): `Edit`, `Write` (no MultiEdit).

---

## File Structure

**Create (repo — committed):**
- `.kimi-code/hooks.toml` — `[[hooks]]` SSOT config fragment (the 4 hook entries).
- `.kimi-code/install-hooks.sh` — idempotent managed append/remove of the block into user `config.toml`.
- `.kimi-code/hooks/post-edit-check.sh` — Hook A entry (PostToolUse on Edit|Write).
- `.kimi-code/hooks/stop-auto-review.sh` — Hook B entry (Stop, async).
- `.kimi-code/hooks/notify.sh` — Hook C entry (SubagentStop / Notification).
- `.kimi-code/hooks/lib/i18n-hardcode.sh` — single-file hardcoded-string detector (python3 core).

**Modify (repo — committed):**
- `.gitignore` — ignore `.kimi-code/` run-state (`.last-review`, `.last-review.log`).

**Out-of-repo (managed, NOT committed):**
- `~/.kimi-code/config.toml` — receives the marker-wrapped `[[hooks]]` block via `install-hooks.sh`.

---

## Conventions for all hook scripts

- Shebang `#!/usr/bin/env bash`; `set -uo pipefail`; **always `exit 0`** (observational/fail-open).
- JSON from stdin is read once (`INPUT="$(cat)"`) and passed to python3 via the **`HOOK_JSON` env var** (avoids the heredoc-vs-stdin conflict).
- Respect `KIMI_POLANG_HOOK_DEBUG=1` to log decisions to stderr (used by tests + live debugging).

---

### Task 1: Scaffold + gitignore + managed installer

**Files:**
- Create: `.kimi-code/hooks/lib/.gitkeep`, `.kimi-code/install-hooks.sh`
- Modify: `.gitignore`

- [ ] **Step 1: Create directory scaffold**

Run:
```bash
mkdir -p .kimi-code/hooks/lib
touch .kimi-code/hooks/lib/.gitkeep
```

- [ ] **Step 2: Add run-state ignores**

Append to `.gitignore` (create the block; do not duplicate existing lines):
```gitignore

# kimi-code hooks run-state (managed; not the scripts/config, which ARE committed)
.kimi-code/.last-review
.kimi-code/.last-review.log
```

- [ ] **Step 3: Write `.kimi-code/install-hooks.sh`**

```bash
#!/usr/bin/env bash
# Managed append/remove of polang kimi-code [[hooks]] block in USER config.toml.
# SSOT: .kimi-code/hooks.toml. Idempotent. Marker-guarded so other config is untouched.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
HOOKS_TOML="$HERE/hooks.toml"
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
```

- [ ] **Step 4: Make it executable**

Run: `chmod +x .kimi-code/install-hooks.sh`

- [ ] **Step 5: Verify installer is idempotent & removable (without a hooks.toml yet — create a throwaway)**

`hooks.toml` is created in Task 2; for now verify the remove path works on a clean config. Back up the live user config first:
```bash
cp ~/.kimi-code/config.toml /tmp/user-config.bak
printf '# >>> polang kimi-code hooks (managed; SSOT=polang/.kimi-code/hooks.toml) >>>\n# placeholder\n# <<< polang kimi-code hooks <<<\n' > /tmp/fake-hooks.toml
# point installer at a temp config to avoid touching the real one during this unit test
KIMI_CODE_HOME=/tmp/kimi-test-home mkdir -p /tmp/kimi-test-home && cp ~/.kimi-code/config.toml /tmp/kimi-test-home/config.toml
HOOKS_TOML=/tmp/fake-hooks.toml KIMI_HOME=/tmp/kimi-test-home USER_CONFIG=/tmp/kimi-test-home/config.toml \
  bash -c 'BEGIN="# >>> polang kimi-code hooks (managed; SSOT=polang/.kimi-code/hooks.toml) >>>"; END="# <<< polang kimi-code hooks <<<"; python3 - /tmp/kimi-test-home/config.toml "$BEGIN" "$END" <<'"'"'PY'"'"'
import sys,pathlib
cfg,begin,end=sys.argv[1:4]
t=pathlib.Path(cfg).read_text(); lines=t.splitlines(); out=[]; i=0; n=len(lines)
while i<n:
  if lines[i].strip()==begin:
    while i<n and lines[i].strip()!=end: i+=1
    i+=1
    if i<n and lines[i].strip()=="": i+=1
    continue
  out.append(lines[i]); i+=1
pathlib.Path(cfg).write_text("\n".join(out).rstrip("\n")+"\n")
PY'
echo "--- after remove, marker must be gone ---"
grep -c "polang kimi-code hooks" /tmp/kimi-test-home/config.toml || echo "0 markers (expected)"
```
Expected: `0 markers (expected)` (the throwaway block is removed; real user config untouched — still at `/tmp/user-config.bak` identical to live). Clean up: `rm -rf /tmp/kimi-test-home /tmp/fake-hooks.toml`.

- [ ] **Step 6: Commit**

```bash
git add .gitignore .kimi-code/install-hooks.sh .kimi-code/hooks/lib/.gitkeep
git commit -m "feat(kimi-hooks): scaffold .kimi-code/ + managed config installer (P0-1)"
```

---

### Task 2: hooks.toml SSOT + wire into user config

**Files:**
- Create: `.kimi-code/hooks.toml`

- [ ] **Step 1: Write `.kimi-code/hooks.toml`**

```toml
# >>> polang kimi-code hooks (managed; SSOT=polang/.kimi-code/hooks.toml) >>>
# Installed into ~/.kimi-code/config.toml by .kimi-code/install-hooks.sh.
# Self-isolating: each command is a relative path guarded by [ -x ... ], so the
# scripts only run inside the polang repo (cwd); other repos no-op via `|| true`.

[[hooks]]
event = "PostToolUse"
matcher = "Edit|Write"
command = "[ -x .kimi-code/hooks/post-edit-check.sh ] && .kimi-code/hooks/post-edit-check.sh || true"
timeout = 30

[[hooks]]
event = "Stop"
command = "[ -x .kimi-code/hooks/stop-auto-review.sh ] && .kimi-code/hooks/stop-auto-review.sh || true"
timeout = 10

[[hooks]]
event = "SubagentStop"
command = "[ -x .kimi-code/hooks/notify.sh ] && .kimi-code/hooks/notify.sh subagent || true"
timeout = 5

[[hooks]]
event = "Notification"
command = "[ -x .kimi-code/hooks/notify.sh ] && .kimi-code/hooks/notify.sh notification || true"
timeout = 5
# <<< polang kimi-code hooks <<<
```

- [ ] **Step 2: Install into user config + validate**

Run:
```bash
.kimi-code/install-hooks.sh install
```
Expected output ends with `installed polang hooks block -> /Users/.../.kimi-code/config.toml` and `kimi doctor` reports `All checked config files are valid.`

- [ ] **Step 3: Verify the block landed and is parseable**

Run:
```bash
grep -n "polang kimi-code hooks" ~/.kimi-code/config.toml
grep -n "event = " ~/.kimi-code/config.toml | tail -4
```
Expected: 2 marker lines (`>>>` / `<<<`) and the 4 `event =` entries appended.

- [ ] **Step 4: Commit (repo file only; user config is out-of-repo)**

```bash
git add .kimi-code/hooks.toml
git commit -m "feat(kimi-hooks): hooks.toml SSOT (PostToolUse/Stop/SubagentStop/Notification)"
```

---

### Task 3: Hook C — notify.sh (establishes stdin→osascript pattern)

**Files:**
- Create: `.kimi-code/hooks/notify.sh`

- [ ] **Step 1: Write `.kimi-code/hooks/notify.sh`**

```bash
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
/usr/bin/osascript -e "display notification \"${MSG:-完成}\" with title \"Kimi\" sound name \"Glass\"" >/dev/null 2>&1 || true
exit 0
```

- [ ] **Step 2: Make executable**

Run: `chmod +x .kimi-code/hooks/notify.sh`

- [ ] **Step 3: Offline test — SubagentStop payload**

Run:
```bash
echo '{"hook_event_name":"SubagentStop","session_id":"s1","cwd":"/Users/guoshuai/AndroidStudioProjects/polang"}' \
  | KIMI_POLANG_HOOK_DEBUG=1 .kimi-code/hooks/notify.sh subagent
```
Expected: stderr line `[notify] kind=subagent msg=子agent 完成` and a macOS notification toast "Kimi — 子agent 完成".

- [ ] **Step 4: Offline test — Notification payload with title/body**

Run:
```bash
echo '{"hook_event_name":"Notification","notification_type":"task.completed","title":"后台任务","body":"done","severity":"info"}' \
  | KIMI_POLANG_HOOK_DEBUG=1 .kimi-code/hooks/notify.sh notification
```
Expected: `[notify] kind=notification msg=后台任务｜done` and a toast "Kimi — 后台任务｜done".

- [ ] **Step 5: Offline test — malformed JSON still exits 0 (fail-open)**

Run:
```bash
echo 'not-json' | .kimi-code/hooks/notify.sh subagent; echo "exit=$?"
```
Expected: `exit=0` (no crash; falls back to default message).

- [ ] **Step 6: Commit**

```bash
git add .kimi-code/hooks/notify.sh
git commit -m "feat(kimi-hooks): notify.sh — osascript completion notifications (Hook C)"
```

---

### Task 4: Hook A — lib/i18n-hardcode.sh + post-edit-check.sh

**Files:**
- Create: `.kimi-code/hooks/lib/i18n-hardcode.sh`, `.kimi-code/hooks/post-edit-check.sh`

- [ ] **Step 1: Write `.kimi-code/hooks/lib/i18n-hardcode.sh`**

```bash
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
        if val.isascii() and val.islower() and "_" not in val and len(val) <= 12 and val.isalpha():
            continue                                # likely an identifier/key word
        hits.append(f"  ⚠️  L{i}: \"{val}\"")
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
```

- [ ] **Step 2: Make executable**

Run: `chmod +x .kimi-code/hooks/lib/i18n-hardcode.sh`

- [ ] **Step 3: Offline test — dirty file hits**

Run:
```bash
cat > /tmp/dirty.kt <<'EOF'
val greeting = "Hello World"
val toastMsg = "保存成功"
private const val TAG = "PoLang:Test"
val url = "https://example.com"
val KEY_USER = "user_id"
EOF
.kimi-code/hooks/lib/i18n-hardcode.sh /tmp/dirty.kt
```
Expected: two `⚠️` lines for `Hello World` and `保存成功`; **no** hits for TAG / url / KEY_USER (filtered).

- [ ] **Step 4: Offline test — clean file is silent**

Run:
```bash
cat > /tmp/clean.kt <<'EOF'
val msg = stringResource(R.string.save_success)
private const val TAG = "PoLang:Test"
EOF
.kimi-code/hooks/lib/i18n-hardcode.sh /tmp/clean.kt; echo "exit=$?"
```
Expected: no output, `exit=0`.

- [ ] **Step 5: Write `.kimi-code/hooks/post-edit-check.sh`**

```bash
#!/usr/bin/env bash
# Hook A: post Edit|Write lightweight check. Observational; always exit 0.
set -uo pipefail
DEBUG="${KIMI_POLANG_HOOK_DEBUG:-0}"
LIB="$(cd "$(dirname "$0")" && pwd)/lib"
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

INPUT="$(cat)"
PATH_="$(HOOK_JSON="$INPUT" python3 -c '
import os, json
try:
    d = json.loads(os.environ.get("HOOK_JSON", "") or "{}")
except Exception:
    d = {}
print((d.get("tool_input") or {}).get("path") or "")
')"

[ -n "$PATH_" ] || exit 0
[ "$DEBUG" = "1" ] && echo "[post-edit] path=$PATH_" >&2

case "$(basename "$PATH_")" in
  *.kt|*.java)
    "$LIB/i18n-hardcode.sh" "$PATH_" || true
    ;;
  *.md)
    case "$PATH_" in
      */docs/*|*/PRODUCT.md|*/FEATURES.md)
        [ "$DEBUG" = "1" ] && echo "[post-edit] doc-sync for $PATH_" >&2
        ( cd "$ROOT" && python3 scripts/check_doc_sync.py 2>&1 | tail -8 ) || true
        ;;
    esac
    ;;
esac
exit 0
```

> Note: `case "$(basename "$PATH_")"` — basename keeps the extension so `*.kt` matches; the inner `case "$PATH_"` on the full path gates doc-sync to project docs only.

- [ ] **Step 6: Make executable**

Run: `chmod +x .kimi-code/hooks/post-edit-check.sh`

- [ ] **Step 7: Offline test — PostToolUse JSON on the dirty .kt**

Run:
```bash
echo '{"hook_event_name":"PostToolUse","session_id":"s1","cwd":"'"$PWD"'","matcher_value":"Edit","tool_name":"Edit","tool_input":{"path":"/tmp/dirty.kt","old_string":"a","new_string":"b"},"tool_call_id":"c1"}' \
  | KIMI_POLANG_HOOK_DEBUG=1 .kimi-code/hooks/post-edit-check.sh
echo "exit=$?"
```
Expected: the `🌐 i18n 疑似硬编码 (/tmp/dirty.kt):` block with the two `⚠️` lines, then `exit=0`.

- [ ] **Step 8: Offline test — non-source path is a no-op (exit 0, no output)**

Run:
```bash
echo '{"hook_event_name":"PostToolUse","tool_input":{"path":"/tmp/build.gradle.kts"}}' \
  | .kimi-code/hooks/post-edit-check.sh; echo "exit=$?"
```
Expected: no output (`.kts`/non-`*.kt`/non-`*.md`), `exit=0`.

- [ ] **Step 9: Offline test — missing path field fail-opens**

Run:
```bash
echo '{"hook_event_name":"PostToolUse","tool_input":{}}' | .kimi-code/hooks/post-edit-check.sh; echo "exit=$?"
```
Expected: no output, `exit=0`.

- [ ] **Step 10: Commit**

```bash
git add .kimi-code/hooks/lib/i18n-hardcode.sh .kimi-code/hooks/post-edit-check.sh
git commit -m "feat(kimi-hooks): post-edit check (i18n hardcode + doc-sync) (Hook A)"
```

---

### Task 5: Hook B — stop-auto-review.sh (async GLM cross-review)

**Files:**
- Create: `.kimi-code/hooks/stop-auto-review.sh`

- [ ] **Step 1: Write `.kimi-code/hooks/stop-auto-review.sh`**

```bash
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
```

- [ ] **Step 2: Make executable**

Run: `chmod +x .kimi-code/hooks/stop-auto-review.sh`

- [ ] **Step 3: Offline test — recursion sentinel short-circuits**

Run:
```bash
echo '{"hook_event_name":"Stop","stop_hook_active":false}' \
  | KIMI_POLANG_HOOK_REVIEW=1 KIMI_POLANG_HOOK_DEBUG=1 .kimi-code/hooks/stop-auto-review.sh
```
Expected: stderr `[stop-review] review process; skip`, exit 0, **no** `.last-review` write.

- [ ] **Step 4: Offline test — no code changes short-circuits (clean git scratch repo)**

Run:
```bash
T=$(mktemp -d); cd "$T"; git init -q; git commit -q --allow-empty -m init
echo '{"hook_event_name":"Stop","stop_hook_active":false}' \
  | KIMI_POLANG_HOOK_DEBUG=1 "$OLDPWD/.kimi-code/hooks/stop-auto-review.sh"
cd "$OLDPWD"; rm -rf "$T"
```
Expected: stderr `[stop-review] no .kt/.xml changes; skip`, exit 0.

- [ ] **Step 5: Offline test — changes present spawns a MOCK review (no real GLM call)**

Create a mock so the test does not burn GLM tokens:
```bash
cat > /tmp/mock-kimi-review <<'EOF'
#!/usr/bin/env bash
echo "MOCK review ran on: $1" >> /tmp/mock-review.log
EOF
chmod +x /tmp/mock-kimi-review
rm -f .kimi-code/.last-review /tmp/mock-review.log
# stage a fake code change in a scratch repo that still has the hook script reachable
T=$(mktemp -d); cd "$T"; git init -q; git commit -q --allow-empty -m init
echo "val x = 1" > Foo.kt                        # untracked .kt → shows in git status
echo '{"hook_event_name":"Stop","stop_hook_active":false}' \
  | KIMI_REVIEW=/tmp/mock-kimi-review KIMI_POLANG_HOOK_DEBUG=1 "$OLDPWD/.kimi-code/hooks/stop-auto-review.sh"
sleep 1
cd "$OLDPWD"; rm -rf "$T"
echo "--- mock log ---"; cat /tmp/mock-review.log 2>/dev/null || echo "(no log)"
```
Expected: stderr `[stop-review] spawned async review of <tmpdir>`, then `MOCK review ran on: <tmpdir>`. (`.last-review` written under polang's `.kimi-code/`.)

- [ ] **Step 6: Offline test — cooldown blocks a second spawn**

Run immediately after Step 5 (within 600s), reusing the mock in a fresh scratch repo:
```bash
T=$(mktemp -d); cd "$T"; git init -q; git commit -q --allow-empty -m init; echo "val y=2" > Bar.kt
echo '{"hook_event_name":"Stop"}' \
  | KIMI_REVIEW=/tmp/mock-kimi-review KIMI_POLANG_HOOK_DEBUG=1 "$OLDPWD/.kimi-code/hooks/stop-auto-review.sh"
cd "$OLDPWD"; rm -rf "$T"; rm -f .kimi-code/.last-review /tmp/mock-kimi-review /tmp/mock-review.log
```
Expected: stderr `[stop-review] cooldown … skip`, and the mock log gains **no** new line.

- [ ] **Step 7: Commit**

```bash
git add .kimi-code/hooks/stop-auto-review.sh
git commit -m "feat(kimi-hooks): async GLM cross-review on turn end (Hook B)"
```

---

### Task 6: Live smoke test + finalize

**No new files.** Verify end-to-end behavior in a real kimi session, then confirm isolation + reversibility.

- [ ] **Step 1: Confirm hooks are active in this polang repo**

Run (kimi should report the 4 hooks; if it has no `/hooks` command, skip to behavior checks):
```bash
~/.kimi-code/bin/kimi doctor 2>&1 | tail -4
grep -c "polang kimi-code hooks" ~/.kimi-code/config.toml   # expect 2 (markers)
```

- [ ] **Step 2: Live — Hook A fires on a .kt edit**

In a real `kimi` session inside polang, ask the agent to create a scratch file with a hardcoded string, e.g. "create `/tmp/live-dirty.kt` containing `val m = \"Saved\"`". Expected: after the Edit/Write, the agent's tool output includes the `🌐 i18n 疑似硬编码` notice (not blocking).

- [ ] **Step 3: Live — Hook A doc-sync on a docs edit**

In the session, ask it to append a trivial line to `docs/01-PRODUCT/FEATURES.md`. Expected: the `📖 doc-sync`/check_doc_sync summary appears (non-blocking).

- [ ] **Step 4: Live — Hook B async review**

In the session, make a real `.kt` change and end the turn. Expected: turn returns immediately (not blocked); within the review duration, `.kimi-code/.last-review.log` is created. Verify afterward:
```bash
ls -la .kimi-code/.last-review .kimi-code/.last-review.log 2>&1
tail -15 .kimi-code/.last-review.log 2>/dev/null || echo "(review still running or kimi-review missing)"
```

- [ ] **Step 5: Live — Hook C notification**

In the session, trigger a subagent (e.g. "use explore to find …"). Expected: a macOS toast "Kimi — 子agent 完成".

- [ ] **Step 6: Isolation — other repo is unaffected**

Run:
```bash
T=$(mktemp -d); cd "$T"; git init -q
echo '{"hook_event_name":"PostToolUse","tool_input":{"path":"/tmp/x.kt"}}' \
  | bash -c 'command="[ -x .kimi-code/hooks/post-edit-check.sh ] && .kimi-code/hooks/post-edit-check.sh || true"; eval "$command"'; echo "exit=$?"
cd -; rm -rf "$T"
```
Expected: `exit=0`, **no** `🌐` output (the relative-path script doesn't exist outside polang → `|| true`).

- [ ] **Step 7: Reversibility — remove + reinstall**

Run:
```bash
.kimi-code/install-hooks.sh remove
grep -c "polang kimi-code hooks" ~/.kimi-code/config.toml || echo "0 markers (clean)"
.kimi-code/install-hooks.sh install
grep -c "event = " ~/.kimi-code/config.toml | tail -1
```
Expected: after remove, `0 markers (clean)` and `kimi doctor` still valid; after reinstall, the 4 `event =` entries are back.

- [ ] **Step 8: Final commit (docs only, if any hand-notes added)**

If any notes were added under `.kimi-code/` (e.g. a short README), commit:
```bash
git add .kimi-code/
git commit -m "docs(kimi-hooks): finalize P0-1 native hooks" || echo "nothing to commit"
```

---

## Self-Review (completed during authoring)

**Spec coverage:** Spec §4 Hook A → Task 4; Hook B → Task 5; Hook C → Task 3; `.kimi-code/hooks.toml` SSOT → Task 2; `install-hooks.sh` managed append/remove → Task 1; isolation (other-repo no-op) → Task 6 Step 6; reversibility → Task 6 Step 7; recursion guard → Task 5 Steps 3/6. All spec sections mapped.

**Placeholder scan:** No TBD/TODO. Every code step contains complete script source; every test step contains the exact command and expected output.

**Type/field consistency:** stdin file path consistently `tool_input.path` (Task 4 Step 5, Task 3 uses title/body). `HOOK_JSON` env pattern used identically in notify.sh, post-edit-check.sh. Marker strings (`# >>> polang kimi-code hooks (managed; SSOT=polang/.kimi-code/hooks.toml) >>>` / `# <<< polang kimi-code hooks <<<`) are byte-identical between `hooks.toml` and `install-hooks.sh`. `event` names match the verified 16-event enum.
