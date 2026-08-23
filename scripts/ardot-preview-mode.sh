#!/usr/bin/env bash
# Ardot 帧级主题预览切换：把指定帧（及其子树）切到 Light/Dark/Auto 模式预览。
#
# 原理：画布颜色几乎全部绑 PoLang Tokens 变量（scheme/* 双 mode），
# 帧根挂 variableModes override 即整树换主题——零复制、零改色、可随时还原。
# ⚠️ 仅影响该帧的变量解析（预览用）；refs 快照/双端代码不受影响。
#
# 用法：
#   scripts/ardot-preview-mode.sh <frameId> <light|dark|auto> [--lang en|zh|auto] [--shot out.png]
#   scripts/ardot-preview-mode.sh 105:45 dark --lang zh --shot /tmp/x.png  # 深色+中文预览
#   scripts/ardot-preview-mode.sh <frameId> light   # 切浅色预览
#   scripts/ardot-preview-mode.sh <frameId> dark    # 切深色预览
#   scripts/ardot-preview-mode.sh <frameId> auto    # 还原主题（写回默认 Dark）；--lang auto 同理写回 English
#   scripts/ardot-preview-mode.sh <frameId> light --shot /tmp/x.png  # 切换并截图
#
# 常用帧 id（docs/08-UI-SPECS/screens/refs/ardot/manifest.json 可查全量）：
#   settings/main_list=108:94  gallery/grid=105:45  chat/empty=111:321
#   editor/concept_a_hypic=118:243  camera/idle=118:1146
set -euo pipefail

# --lang 可出现在任意位置：先摘出，剩余参数再按 frame mode --shot 顺序解析
LANG_MODE=""
args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --lang) LANG_MODE="${2:?--lang 需 en|zh|auto}"; shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
# bash 3.2 下空数组 + set -u 会报 unbound variable，用 ${a[@]+...} 惯用法兜底
set -- ${args[@]+"${args[@]}"}

FRAME="${1:?用法: ardot-preview-mode.sh <frameId> <light|dark|auto> [--lang en|zh|auto] [--shot out.png]}"
MODE="${2:?light|dark|auto}"
shift 2 || true
SHOT=""
if [ "${1:-}" = "--shot" ]; then SHOT="${2:?--shot 需要输出路径}"; fi

SET_ID="2:2"; DARK_ID="2:0"; LIGHT_ID="79:1"; ENDPOINT="http://127.0.0.1:50501/api/v1/mcp"
# UI Language 变量集（探针实测 id；--lang 未传时不触碰该 override）
LANG_SET_ID="182:133"
LANG_EN_ID="182:132"
LANG_ZH_ID="182:134"

entries=""
case "$MODE" in
  light) entries="{\"variableSetId\":\"$SET_ID\",\"modeId\":\"$LIGHT_ID\"}" ;;
  dark)  entries="{\"variableSetId\":\"$SET_ID\",\"modeId\":\"$DARK_ID\"}" ;;
  # null/[] 是静默 no-op（probe-record §5 实证），还原=显式写回默认 Dark
  auto)  entries="{\"variableSetId\":\"$SET_ID\",\"modeId\":\"$DARK_ID\"}" ;;
  *) echo "模式须为 light|dark|auto"; exit 2 ;;
esac
case "${LANG_MODE:-}" in
  en)  entries="${entries:+$entries,}{\"variableSetId\":\"$LANG_SET_ID\",\"modeId\":\"$LANG_EN_ID\"}" ;;
  zh)  entries="${entries:+$entries,}{\"variableSetId\":\"$LANG_SET_ID\",\"modeId\":\"$LANG_ZH_ID\"}" ;;
  # --lang auto 还原=写回 English 默认 mode（同上，null 无效）
  auto) entries="${entries:+$entries,}{\"variableSetId\":\"$LANG_SET_ID\",\"modeId\":\"$LANG_EN_ID\"}" ;;
  "")  : ;;  # 未传 --lang：不触碰 lang override
  *) echo "--lang 须为 en|zh|auto"; exit 2 ;;
esac
MODE_JSON="[$entries]"

/usr/bin/python3 - "$FRAME" "$MODE_JSON" "$ENDPOINT" "$SHOT" <<'PYEOF'
import json, sys, urllib.request
frame, mode_json, endpoint, shot = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

def rpc(method, params):
    body = {"jsonrpc":"2.0","id":1,"method":method,**({"params":params} if params else {})}
    req = urllib.request.Request(endpoint, data=json.dumps(body).encode(),
        headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream'})
    resp = urllib.request.urlopen(req, timeout=120)
    for line in resp.read().decode().splitlines():
        if line.startswith('data:'): return json.loads(line[5:])
    return {}

rpc("initialize", {"protocolVersion":"2024-11-05","capabilities":{},
    "clientInfo":{"name":"ardot-preview-mode","version":"1.0"}})
r = rpc("tools/call", {"name":"batch_edit","arguments":{
    "operations": f'U("{frame}", {{variableModes: {mode_json}}})'}})
txt = json.dumps(r)[:120]
ok = '"success":true' in json.dumps(r) or 'updated' in json.dumps(r)
print(("OK " if ok else "FAIL ") + txt)

if shot:
    s = rpc("tools/call", {"name":"capture_screenshot","arguments":{
        "nodeIds":[frame], "screenShotDir":"/tmp/ardot-mode-shot"}})
    import re, glob, os, time
    # 截图落在 /tmp/ardot-mode-shot/<frame>.png（带时间戳），取最新的复制到目标
    files = sorted(glob.glob(f"/tmp/ardot-mode-shot/screenshot-{frame.replace(':','_')}-*.png"), key=os.path.getmtime)
    if files:
        os.replace(files[-1], shot); print("SHOT " + shot)
    else:
        print("SHOT-MISSING 检查 /tmp/ardot-mode-shot/")
PYEOF
