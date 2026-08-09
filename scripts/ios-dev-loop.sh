#!/bin/bash
#
# iOS Dev Loop — 兼容入口（已合并到 ios-auto-dev-loop.sh）
#
# 历史：本脚本原为模拟器（simctl）轻量闭环。Phase 6 起真机（devicectl +
# pymobiledevice3）成为主路径，统一收敛到 ios-auto-dev-loop.sh（5 阶段、阶段
# 隔离、自动设备检测、无人值守）。本文件保留为转发垫片，所有参数透传。
#
# 若需纯模拟器快速验证，直接用 simctl（见 /ios-build-debug），不在本闭环范围。
#
# 用法（不变）：./scripts/ios-dev-loop.sh [任意 ios-auto-dev-loop.sh 选项]
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
echo "[ios-dev-loop] → 转发到 ios-auto-dev-loop.sh（已合并）" >&2
exec "$DIR/ios-auto-dev-loop.sh" "$@"
