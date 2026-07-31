#!/usr/bin/env bash
# 诊断 worker 胶水冒烟：验证 compare_url、claim 解析、claude 输出解析、report_result。
# 真 Claude Code / 真 git push 不在此覆盖（在云主机上验）。
set -euo pipefail
WD="$(cd "$(dirname "$0")/.." && pwd)"     # scripts/diag-worker
SMOKE="$(mktemp -d)"
CAPTURE="$SMOKE/captured.txt"
PORT="${DIAG_SMOKE_PORT:-18099}"
cleanup() { kill "${SRV_PID:-}" 2>/dev/null || true; rm -rf "$SMOKE"; }
trap cleanup EXIT

export DIAG_SERVER="http://127.0.0.1:$PORT"
export DIAG_WORKER_TOKEN=smoke
export DIAG_REPO="https://github.com/guoshuai/langchain4android.git"
export DIAG_BASE_BRANCH=main
export DIAG_WORKDIR="$SMOKE/w"
export DIAG_CLAUDE="$SMOKE/stub-claude.sh"
export DIAG_POLL_INTERVAL=1
export DIAG_MAX_TURNS=5
export DIAG_PHASE_TIMEOUT=30

# --- stub claude ---
cp "$WD/smoke/stub-claude.sh" "$SMOKE/stub-claude.sh"
chmod +x "$SMOKE/stub-claude.sh"

# --- stub HTTP capture server ---
python3 - "$CAPTURE" "$PORT" <<'PYEOF' &
import http.server, sys
cap, port = sys.argv[1], int(sys.argv[2])
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers()
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0)); body = self.rfile.read(n).decode()
        open(cap, 'a').write(body + "\n")
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.end_headers()
        self.wfile.write(b'{"ok":true}')
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', port), H).serve_forever()
PYEOF
SRV_PID=$!
curl -sf --retry 30 --retry-delay 0 --retry-connrefused -o /dev/null "http://127.0.0.1:$PORT/hz"

# --- 1) compare_url ---
. "$WD/lib.sh"; load_env
cu="$(compare_url diag-fix/7)"
expected="https://github.com/guoshuai/langchain4android/compare/main...diag-fix/7"
[ "$cu" = "$expected" ] || { echo "FAIL compare_url: '$cu'"; exit 1; }
echo "ok compare_url"

# --- 1b) gh_auth：无 GITHUB_TOKEN 应返回 1（push 模式不需要 gh）---
unset GITHUB_TOKEN
if gh_auth; then echo "FAIL gh_auth: should fail without GITHUB_TOKEN"; exit 1; fi
echo "ok gh_auth no-token fails"

# --- 2) diagnose 胶水：本地仓 + stub claude → 解析 → report_result ---
mkdir -p "$DIAG_WORKDIR/repo"
git -C "$DIAG_WORKDIR/repo" init -q
git -C "$DIAG_WORKDIR/repo" config user.email s@x.com
git -C "$DIAG_WORKDIR/repo" config user.name smoke
printf 'hi\n' > "$DIAG_WORKDIR/repo/README.md"
git -C "$DIAG_WORKDIR/repo" add -A
git -C "$DIAG_WORKDIR/repo" commit -qm init
SHA="$(git -C "$DIAG_WORKDIR/repo" rev-parse --short HEAD)"

CLAIM='{"jobId":1,"phase":"diagnose","description":"crash on open gallery","conversationSummary":"现象: 打开相册崩溃","bundle":{"logs":"PoLang:Gallery boom | sed & break \\ path \"q\"","gitSha":"'"$SHA"'","appVersion":"1.0.29","deviceModel":"X","androidVersion":"14"},"gitSha":"'"$SHA"'"}'
# 注：JSON 内 \\ 解码为单个 \（jq -r 输出 boom | sed & break \ path "q"），勿写成 \ （非法 JSON escape）。
bash "$WD/run-diagnose.sh" 1 "$CLAIM"

grep -q '"status":"DIAGNOSED"' "$CAPTURE" && grep -q 'stub: NPE' "$CAPTURE" \
  || { echo "FAIL diagnose glue; captured:"; cat "$CAPTURE"; exit 1; }
echo "ok diagnose glue -> $(cat "$CAPTURE")"

# --- 2d) W1：三字段（rootCause/suspectFiles/suggestedFix）全部回传 ---
grep -q '"suspectFiles":"GalleryScreen.kt"' "$CAPTURE" \
  || { echo "FAIL suspectFiles missing; captured:"; cat "$CAPTURE"; exit 1; }
grep -q '"suggestedFix":"null check"' "$CAPTURE" \
  || { echo "FAIL suggestedFix missing; captured:"; cat "$CAPTURE"; exit 1; }
echo "ok diagnose three-field report"

# --- 2b) 模板注入安全（W3）：含 | & \ " 的日志原样进入 prompt，不被替换语法破坏 ---
grep -qF 'boom | sed & break \ path "q"' "$DIAG_WORKDIR/last-prompt.txt" \
  || { echo "FAIL template injection; prompt:"; cat "$DIAG_WORKDIR/last-prompt.txt"; exit 1; }
echo "ok template injection safe"

# --- 2c) conversationSummary 进入 diagnose prompt ---
grep -qF '现象: 打开相册崩溃' "$DIAG_WORKDIR/last-prompt.txt" \
  || { echo "FAIL conversationSummary missing in prompt"; cat "$DIAG_WORKDIR/last-prompt.txt"; exit 1; }
echo "ok conversationSummary in diagnose prompt"

# --- 3) W1：fix 阶段 claim 带 suggestedFix → fix prompt 拿到真实值 ---
CLAIM_FIX='{"jobId":2,"phase":"fix","gitSha":"'"$SHA"'","rootCause":"stub: NPE","suggestedFix":"null check","fixMode":"push"}'
: > "$CAPTURE"
bash "$WD/run-fix.sh" 2 "$CLAIM_FIX" || true
grep -qF 'null check' "$DIAG_WORKDIR/last-prompt.txt" \
  || { echo "FAIL suggestedFix not in fix prompt"; cat "$DIAG_WORKDIR/last-prompt.txt"; exit 1; }
echo "ok suggestedFix in fix prompt"

# --- 3b) W2：模型未产生改动 → FIX_FAILED（不产生空 commit/空分支）---
grep -q '"status":"FIX_FAILED"' "$CAPTURE" && grep -q '模型未产生修改' "$CAPTURE" \
  || { echo "FAIL empty-change should be FIX_FAILED; captured:"; cat "$CAPTURE"; exit 1; }
echo "ok fix empty-change -> FIX_FAILED"

echo "SMOKE PASS"
