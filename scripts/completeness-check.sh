#!/usr/bin/env bash
# completeness-check.sh <screen> <state>
# 完整性闸门:核对 iOS a11y 树 vs Figma 帧 node 树(从 <screen>-frames-nodes.json 取该 state)。
# 前置:Figma 节点已 dump 到 refs/figma/<screen>-frames-nodes.json;
#       iOS 该状态 a11y 已 dump 到 refs/ios/<screen>-<state>.json(由 XCUITest 产出)。
# 退出码:0=无 missing(通过), 1=有 missing(完整性缺口), 2=缺输入文件。
set -uo pipefail
SCREEN="${1:?usage: completeness-check.sh <screen> <state>}"
STATE="${2:?state required}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

FIGMA_NODES="$ROOT/specs/screens/refs/figma/${SCREEN}-frames-nodes.json"
IOS_NODES="$ROOT/specs/screens/refs/ios/${SCREEN}-${STATE}.json"
[ -f "$FIGMA_NODES" ] || { echo "缺 Figma nodes: $FIGMA_NODES"; exit 2; }
[ -f "$IOS_NODES" ] || { echo "缺 iOS dump: $IOS_NODES(先跑 XCUITest dump 该状态)"; exit 2; }

TMP="$(mktemp)"
python3 -c "import json; d=json.load(open('$FIGMA_NODES'))['frames']['$STATE']; print(json.dumps({'nodes':d}))" > "$TMP"
python3 "$ROOT/scripts/completeness/match.py" "$TMP" "$IOS_NODES" "$STATE"
RC=$?
rm -f "$TMP"
exit $RC
