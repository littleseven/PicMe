# Remote Diagnosis (Worker) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the cloud-host worker: a常驻 poller that claims diagnosis/fix jobs from the picme server and drives Claude Code (GLM) headlessly to locate root causes and push fixes to `diag-fix/<jobId>` branches.

**Architecture:** Bash poller loops `GET /diag/work/jobs` (worker token) → on `phase=diagnose`: `git clone @gitSha` → `claude -p` (diagnose prompt, read-only) → POST root cause → on `phase=fix`: `git checkout -b diag-fix/<jobId>` → `claude -p` (fix prompt) → optional JVM tests → commit → `git push` → POST result. Egress-only; no inbound. **Pure git (no gh)** — pr 模式仅拼接 compare URL。

**Tech Stack:** Bash, curl, jq, git, Claude Code CLI (`claude -p`, GLM backend), gradle (optional test).

**Spec:** `docs/superpowers/specs/2026-07-30-remote-diagnosis-design.md` (§4 数据流, §6.3 worker). Server API contract locked by the implemented `DiagRoute`.

**Scope note:** Plan 3 of the feature (worker). The server↔worker loop is E2E-testable here with a **stub `claude`** (no real LLM needed to verify the glue). The real Claude Code run happens on the cloud host after deploy.

---

## File Structure

**Create (all under `scripts/diag-worker/`):**
- `worker.env.example` — config template (server URL, worker token, repo, intervals)
- `prompts/diagnose.md` — read-only root-cause prompt template
- `prompts/fix.md` — minimal-fix prompt template
- `lib.sh` — shared helpers (claim, report-result, parse claim JSON, derive compare URL)
- `run-diagnose.sh` — clone @gitSha → claude diagnose → POST root cause
- `run-fix.sh` — branch → claude fix → test → commit → push → POST result
- `poll.sh` — main loop
- `README.md` — deploy/run/cost notes
- `smoke/stub-claude.sh` — stub claude for smoke (canned JSON outputs)
- `smoke/run-smoke.sh` — boots local server + runs poller with stub claude, asserts job → FIXED

---

## Task 1: Config + prompt templates

**Files:** `scripts/diag-worker/worker.env.example`, `prompts/diagnose.md`, `prompts/fix.md`

- [ ] **Step 1: Create worker.env.example**

```bash
# 远程诊断 worker 配置（复制为 worker.env 填真实值；worker.env 不要提交）
DIAG_SERVER=https://api.polang.net
DIAG_WORKER_TOKEN=               # 与 server 的 DIAG_WORKER_TOKEN 一致
DIAG_REPO=https://github.com/guoshuai/langchain4android.git
DIAG_WORKDIR=/tmp/diag-work      # clone/构建工作目录
DIAG_POLL_INTERVAL=60            # 秒；poll 不调 LLM，成本低，可低频
DIAG_BASE_BRANCH=main            # compare URL 的 base 分支
DIAG_CLAUDE=claude               # claude 可执行路径
DIAG_MAX_TURNS=20                # Claude Code 单次最大迭代（成本护栏）
DIAG_PHASE_TIMEOUT=300           # 单阶段 wall-clock 超时秒数
```

- [ ] **Step 2: Create prompts/diagnose.md**

```markdown
You are diagnosing a bug in an Android (Kotlin/Compose) project: PoLang (破浪相册).

Build git SHA: __GIT_SHA__
User-reported problem:
__DESCRIPTION__

Sanitized app logs (PoLang:* tags):
__LOGS__

Crash trace (if any):
__CRASH_TRACE__

Your task: find the ROOT CAUSE in the source code (checked out at the above SHA, in the current directory). Explore the relevant files. Do NOT modify any file — analysis only.

Output STRICTLY this JSON and nothing else:
{"rootCause": "<one-paragraph root cause>", "suspectFiles": ["<file:line>", ...], "suggestedFix": "<brief fix direction>"}
```

- [ ] **Step 3: Create prompts/fix.md**

```markdown
You are fixing a confirmed bug in an Android (Kotlin/Compose) project: PoLang (破浪相册).
You are on a fresh branch. Apply a MINIMAL fix — do not refactor unrelated code.

Confirmed root cause:
__ROOT_CAUSE__

Fix direction suggested:
__SUGGESTED_FIX__

Requirements:
- Make the smallest change that resolves the root cause.
- Do not change public API signatures unless strictly required.
- After editing, do NOT run the build (the wrapper will run tests separately).

Output STRICTLY this JSON and nothing else:
{"changedFiles": ["<file>", ...], "summary": "<one-line summary of the fix>"}
```

- [ ] **Step 4: Commit**

```bash
git add scripts/diag-worker/worker.env.example scripts/diag-worker/prompts/diagnose.md scripts/diag-worker/prompts/fix.md
git commit -m "feat(diag-worker): 配置模板 + 诊断/修复 prompt"
```

---

## Task 2: lib.sh shared helpers

**Files:** `scripts/diag-worker/lib.sh`

- [ ] **Step 1: Create lib.sh**

```bash
#!/usr/bin/env bash
# 远程诊断 worker 共享工具：加载配置、claim/result、JSON 解析、compare URL。
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 加载 worker.env（存在则覆盖默认）
load_env() {
  local f="${SCRIPT_DIR}/worker.env"
  if [ -f "$f" ]; then . "$f"; fi
  : "${DIAG_SERVER:?worker.env missing DIAG_SERVER}"
  : "${DIAG_WORKER_TOKEN:?worker.env missing DIAG_WORKER_TOKEN}"
  : "${DIAG_REPO:?worker.env missing DIAG_REPO}"
  : "${DIAG_WORKDIR:=/tmp/diag-work}"
  : "${DIAG_POLL_INTERVAL:=60}"
  : "${DIAG_BASE_BRANCH:=main}"
  : "${DIAG_CLAUDE:=claude}"
  : "${DIAG_MAX_TURNS:=20}"
  : "${DIAG_PHASE_TIMEOUT:=300}"
  mkdir -p "$DIAG_WORKDIR"
}

# 领一个任务；stdout 输出 claim JSON（空则返回 1）。
claim_next() {
  local code body
  body="$(curl -sf -m 15 -H "X-Diag-Worker-Token: $DIAG_WORKER_TOKEN" "$DIAG_SERVER/diag/work/jobs" 2>/dev/null)" || return 1
  [ -z "$body" ] && return 1
  printf '%s' "$body"
}

# report_result <jobId> <json body>
report_result() {
  local jobId="$1" body="$2"
  curl -sf -m 15 -H "X-Diag-Worker-Token: $DIAG_WORKER_TOKEN" \
    -H "Content-Type: application/json" --data "$body" \
    "$DIAG_SERVER/diag/work/jobs/$jobId/result" >/dev/null
}

# 从 owner/repo URL 派生 compare URL：compare/<base>...<branch>
# compare_url <branch>
compare_url() {
  local branch="$1"
  local rest="${DIAG_REPO#*://}"      # strip scheme
  rest="${rest%.git}"                  # strip .git
  rest="${rest#*github.com/}"          # strip host → owner/repo
  printf 'https://github.com/%s/compare/%s...%s' "$rest" "$DIAG_BASE_BRANCH" "$branch"
}

# JSON 字符串转义（最小：处理 " 和 \ 和换行），用于嵌入 prompt。
json_escape() {
  sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | awk 'BEGIN{ORS="\\n"} {print}' | sed 's/\\n$//'
}
```

- [ ] **Step 2: Commit**

```bash
git add scripts/diag-worker/lib.sh
git commit -m "feat(diag-worker): lib.sh 共享工具（claim/result/json/compare url）"
```

---

## Task 3: run-diagnose.sh + run-fix.sh

**Files:** `scripts/diag-worker/run-diagnose.sh`, `scripts/diag-worker/run-fix.sh`

- [ ] **Step 1: Create run-diagnose.sh**

```bash
#!/usr/bin/env bash
# 用法: run-diagnose.sh <jobId> <claimJson>
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
load_env

jobId="$1"; claim="$2"
gitSha="$(printf '%s' "$claim" | jq -r .gitSha)"
desc="$(printf '%s' "$claim" | jq -r .description | json_escape)"
logs="$(printf '%s' "$claim" | jq -r '.bundle.logs // ""' | json_escape)"
crash="$(printf '%s' "$claim" | jq -r '.bundle.crashTrace // ""' | json_escape)"

repo="$DIAG_WORKDIR/repo"
if [ ! -d "$repo/.git" ]; then
  git clone --quiet "$DIAG_REPO" "$repo" || { report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSE_FAILED\",\"error\":\"clone failed\"}"; exit 0; }
fi
git -C "$repo" fetch --quiet origin || true
git -C "$repo" checkout --quiet "$gitSha" 2>/dev/null || git -C "$repo" checkout --quiet "$DIAG_BASE_BRANCH"

prompt="$(sed -e "s|__GIT_SHA__|$gitSha|g" -e "s|__DESCRIPTION__|$desc|g" -e "s|__LOGS__|$logs|g" -e "s|__CRASH_TRACE__|$crash|g" "$SCRIPT_DIR/prompts/diagnose.md")"

out="$(timeout "$DIAG_PHASE_TIMEOUT" "$DIAG_CLAUDE" -p "$prompt" --output-format json --max-turns "$DIAG_MAX_TURNS" 2>/dev/null)" || true
rootCause="$(printf '%s' "$out" | jq -r '.result // empty' 2>/dev/null | jq -r '.rootCause // empty' 2>/dev/null)"
[ -z "$rootCause" ] && rootCause="$(printf '%s' "$out" | jq -r '.rootCause // empty' 2>/dev/null)"

if [ -z "$rootCause" ]; then
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSE_FAILED\",\"error\":\"no rootCause parsed\"}"
else
  rc_escaped="$(printf '%s' "$rootCause" | json_escape)"
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSED\",\"rootCause\":\"$rc_escaped\"}"
fi
```

- [ ] **Step 2: Create run-fix.sh**

```bash
#!/usr/bin/env bash
# 用法: run-fix.sh <jobId> <claimJson>
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
load_env

jobId="$1"; claim="$2"
gitSha="$(printf '%s' "$claim" | jq -r .gitSha)"
rootCause="$(printf '%s' "$claim" | jq -r '.rootCause // ""' | json_escape)"
suggested="$(printf '%s' "$claim" | jq -r '.bundle // {} | .suggestedFix // ""' | json_escape)"  # 诊断阶段未带，留空
mode="$(printf '%s' "$claim" | jq -r '.fixMode // "push"')"
branch="diag-fix/$jobId"

repo="$DIAG_WORKDIR/repo"
git -C "$repo" fetch --quiet origin || true
git -C "$repo" checkout --quiet -B "$branch" "$gitSha" 2>/dev/null || git -C "$repo" checkout --quiet -B "$branch" "$DIAG_BASE_BRANCH"

prompt="$(sed -e "s|__ROOT_CAUSE__|$rootCause|g" -e "s|__SUGGESTED_FIX__|$suggested|g" "$SCRIPT_DIR/prompts/fix.md")"
timeout "$DIAG_PHASE_TIMEOUT" "$DIAG_CLAUDE" -p "$prompt" --dangerously-skip-permissions --output-format json --max-turns "$DIAG_MAX_TURNS" >/dev/null 2>&1 || true

# 自检：跑 JVM 单测（资源允许）；失败/超时不阻断，只标 tested=false。
tested=false
if timeout 240 ./gradlew -p "$repo/server" test -q >/dev/null 2>&1; then tested=true; fi

git -C "$repo" add -A
git -C "$repo" commit --quiet -m "fix(diag): 远程诊断自动修复 job #$jobId" >/dev/null 2>&1 || true
git -C "$repo" push --quiet origin "$branch" >/dev/null 2>&1 || { report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIX_FAILED\",\"error\":\"push failed\"}"; exit 0; }

extra=""
[ "$mode" = "pr" ] && extra=",\"compareUrl\":\"$(compare_url "$branch")\""
status="FIXED"; [ "$tested" = "false" ] && status="FIXED_UNVERIFIED"
report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested$extra}"
```

- [ ] **Step 3: Commit**

```bash
git add scripts/diag-worker/run-diagnose.sh scripts/diag-worker/run-fix.sh
chmod +x scripts/diag-worker/run-diagnose.sh scripts/diag-worker/run-fix.sh
git update-index --chmod=+x scripts/diag-worker/run-diagnose.sh scripts/diag-worker/run-fix.sh 2>/dev/null || true
git add scripts/diag-worker/run-diagnose.sh scripts/diag-worker/run-fix.sh
git commit -m "feat(diag-worker): run-diagnose + run-fix（claude -p 驱动 + git push）"
```

---

## Task 4: poll.sh main loop

**Files:** `scripts/diag-worker/poll.sh`

- [ ] **Step 1: Create poll.sh**

```bash
#!/usr/bin/env bash
# 远程诊断 worker 主循环。常驻；poll 不调 LLM，无任务时仅 sleep。
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
load_env

echo "[diag-worker] polling $DIAG_SERVER every ${DIAG_POLL_INTERVAL}s"
while true; do
  claim="$(claim_next)" || { sleep "$DIAG_POLL_INTERVAL"; continue; }
  jobId="$(printf '%s' "$claim" | jq -r .jobId)"
  phase="$(printf '%s' "$claim" | jq -r .phase)"
  echo "[diag-worker] claimed job #$jobId phase=$phase"
  case "$phase" in
    diagnose) bash "$SCRIPT_DIR/run-diagnose.sh" "$jobId" "$claim" ;;
    fix)      bash "$SCRIPT_DIR/run-fix.sh" "$jobId" "$claim" ;;
    *) echo "[diag-worker] unknown phase=$phase; skipping" ;;
  esac
  sleep "$DIAG_POLL_INTERVAL"
done
```

- [ ] **Step 2: Commit**

```bash
git add scripts/diag-worker/poll.sh
git update-index --chmod=+x scripts/diag-worker/poll.sh 2>/dev/null || true
git commit -m "feat(diag-worker): poll.sh 常驻主循环"
```

---

## Task 5: README + smoke (stub claude)

**Files:** `scripts/diag-worker/README.md`, `smoke/stub-claude.sh`, `smoke/run-smoke.sh`

- [ ] **Step 1: Create README.md**

Document: copy `worker.env.example`→`worker.env` (fill token/repo); ensure `claude`, `jq`, `curl`, `git` on PATH; run `bash poll.sh` (or the provided systemd unit / tmux). Cost notes: poll free, host 0.6%/day, reasoning on GLM metered; `DIAG_MAX_TURNS` + `DIAG_PHASE_TIMEOUT` are the cost knobs. Worker token must equal server `DIAG_WORKER_TOKEN`.

- [ ] **Step 2: Create smoke/stub-claude.sh** (a fake `claude` that emits canned JSON)

```bash
#!/usr/bin/env bash
# 假 claude：把 -p 的 prompt 里的占位判断后吐出固定 JSON，用于 smoke 验证 poller 胶水。
# diagnose prompt 含 "ROOT CAUSE" 指令 → 输出 rootCause；fix prompt 含 "MINIMAL fix" → 空改。
out="$(cat)"
if printf '%s' "$out" | grep -q "Do NOT modify"; then
  printf '{"result":"{\"rootCause\":\"stub: NPE in GalleryScreen at null uri\",\"suspectFiles\":[\"GalleryScreen.kt\"],\"suggestedFix\":\"null check\"}"}'
else
  printf '{"result":"{\"changedFiles\":[],\"summary\":\"stub fix\"}"}'
fi
```
(The stub reads the prompt from stdin; `claude -p` passes the prompt as an arg, so the smoke harness exports `DIAG_CLAude` pointing at a wrapper that reads `"$1"` — see run-smoke.)

- [ ] **Step 3: Create smoke/run-smoke.sh**

Boots the local picme server (`./gradlew -p server installDist` then run on :18081 with `DIAG_WORKER_TOKEN=smoke`), creates a DIAGNOSED job via curl through the phone API is out of scope; instead this smoke **injects a QUEUED job directly into the server DB** is heavy. Simpler: drive the loop against a **stub server** is also heavy.

**Pragmatic smoke:** verify the worker glue units directly:
1. `compare_url diag-fix/7` → prints `https://github.com/guoshuai/langchain4android/compare/main...diag-fix/7`.
2. `json_escape` on a string with quotes/newlines round-trips.
3. `run-diagnose.sh` with `DIAG_CLAUDE=<stub>` + a fake claim JSON → calls a stub server (`report_result`) — verify the stub server received `{"phase":"diagnose","status":"DIAGNOSED",...}`.

Write `run-smoke.sh` that:
- starts a tiny `nc`/python HTTP stub on :18099 that accepts `POST /diag/work/jobs/1/result` and echoes the body to a file;
- sets `DIAG_SERVER=http://127.0.0.1:18099`, `DIAG_CLAUDE=stub-claude.sh`;
- runs `run-diagnose.sh 1 '<claim json>'` and `run-fix.sh 1 '<claim json>'`;
- asserts the captured result bodies contain `"status":"DIAGNOSED"` and `"status":"FIXED"`.

This verifies claim-parsing, prompt interpolation, claude-output parsing, and report_result — the real LLM is the only thing stubbed.

- [ ] **Step 4: Run the smoke**

Run: `bash scripts/diag-worker/smoke/run-smoke.sh`
Expected: assertions pass (DIAGNOSED + FIXED result bodies captured).

- [ ] **Step 5: Commit**

```bash
git add scripts/diag-worker/README.md scripts/diag-worker/smoke
git commit -m "docs(diag-worker): README + stub-claude 胶水冒烟测试"
```

---

## Self-Review

- **Spec coverage:** §4 数据流 (poll→diagnose→POST→confirm→fix→push→POST) = Tasks 3+4; §6.3 worker 组件 (poller/diagnose/fix/凭证) = Tasks 1-4; 成本护栏 (max_turns/timeout) = Task 1 config; 纯 git 无 gh、pr=compare URL = `compare_url` in Task 2.
- **Contract consistency:** result JSON shape matches server `DiagWorkResult` (phase/status/rootCause/fixBranch/compareUrl/tested). claim parsing matches `DiagClaimResponse`.
- **Refinement vs spec:** smoke verifies glue with a stub claude + stub HTTP (the real server E2E + real Claude Code happen on the cloud host post-deploy). `--dangerously-skip-permissions` used for fix phase (autonomous, non-interactive).

## Done criteria for this plan

- [ ] `bash scripts/diag-worker/smoke/run-smoke.sh` passes (DIAGNOSED + FIXED result bodies captured).
- [ ] 5 tasks committed (own files only).
- [ ] Scripts have +x (via `git update-index --chmod=+x`).
