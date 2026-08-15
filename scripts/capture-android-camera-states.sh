#!/usr/bin/env bash
# scripts/capture-android-camera-states.sh <state...>
# 采集相机页各状态的真机截图 + uiautomator dump(地面真值)。
# 交互式:每个状态提示你在设备上进入该状态,回车后抓取。需在你的交互 shell 运行:
#   ! .worktrees/figma-camera-spec/scripts/capture-android-camera-states.sh [state...]
# 输出落到 worktree 的 specs/screens/refs/android/camera-<state>.{png,xml}(与脚本位置无关)。
# 瞬时态(capturing 闪屏 / focusing 对焦环):靠人肉时机抓;抓不到就在提交时标注「未锚定」。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/specs/screens/refs/android"
mkdir -p "$OUT"

STATES=("$@")
[ ${#STATES[@]} -eq 0 ] && STATES=(
  idle panel_beauty_face panel_beauty_makeup panel_filter
  panel_ratio panel_scene panel_grid panel_pro
  capturing focusing permission_denied
)

echo "设备: $(adb get-state 2>/dev/null || echo '未连接!')"
echo "输出目录: $OUT"
echo ""

capture_one() {
  local st="$1"
  echo "==> camera/$st : 在 Android 设备上进入该状态,准备好后回车抓取(回车跳过)..."
  read -r ans
  [ "$ans" = "skip" ] && { echo "   ⊘ 跳过 $st"; return; }
  adb exec-out screencap -p > "$OUT/camera-$st.png"
  adb shell uiautomator dump "/sdcard/camera-$st.xml" >/dev/null 2>&1 || true
  adb pull "/sdcard/camera-$st.xml" "$OUT/camera-$st.xml" >/dev/null 2>&1 || echo "   ⚠ uiautomator dump 拉取失败(可能该状态无标准视图层)"
  local sz; sz=$(wc -c < "$OUT/camera-$st.png" 2>/dev/null || echo 0)
  echo "   ✓ camera-$st.png (${sz} bytes) + camera-$st.xml"
}

for s in "${STATES[@]}"; do capture_one "$s"; done
echo ""
echo "完成。产物: $OUT/camera-*.{png,xml}"
