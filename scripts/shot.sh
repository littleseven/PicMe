#!/usr/bin/env bash
# shot.sh — 研发截屏封装：直传电脑，设备零残留，不污染相册。
# PoLang 是相册 app，读 MediaStore；裸 `adb shell screencap -p /sdcard/xxx.png` 会落设备
# 根目录、被 MediaStore 扫描、混进相册。本脚本用 exec-out 直传，绕开设备存储。
#
# 用法:
#   scripts/shot.sh              # → tmp/shots/shot-HHMMSS.png
#   scripts/shot.sh cursor       # → tmp/shots/cursor-HHMMSS.png
#   scripts/shot.sh -d /sdcard/PoLang_test_shots/plt_x.png   # 必须落设备时进 .nomedia 隔离目录
set -euo pipefail

DEV_OUT=""
if [[ "${1:-}" == "-d" ]]; then
  DEV_OUT="${2:?用法: shot.sh -d <设备路径> [名字]}"
  shift 2
fi
name="${1:-shot}"

if [[ -n "$DEV_OUT" ]]; then
  # 落设备模式：写入指定路径（调用方应确保其在 .nomedia 隔离目录下）
  adb shell screencap -p "$DEV_OUT"
  echo "[+] 设备: $DEV_OUT"
else
  # 默认：直传电脑，设备零残留
  out_dir="tmp/shots"
  mkdir -p "$out_dir"
  out="${out_dir}/${name}-$(date +%H%M%S).png"
  adb exec-out screencap -p > "$out"
  echo "[+] $out"
fi
