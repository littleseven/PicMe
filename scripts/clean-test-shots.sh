#!/usr/bin/env bash
# clean-test-shots.sh — 清理 /sdcard 根目录的测试截屏垃圾，并同步删除 MediaStore 记录。
# 仅删根目录 .png（用户真照片在 DCIM/、Pictures/ 等子目录，不受影响）。
# rm 文件不会自动清 MediaStore 索引，PoLang 相册仍会显示，故需 content delete 同步清记录。
#
# 用法:
#   scripts/clean-test-shots.sh            # dry-run 预览（默认，只列出不删）
#   scripts/clean-test-shots.sh --rm       # 实际删除 + 清 MediaStore 记录
set -euo pipefail

ACT=0
[[ "${1:-}" == "--rm" ]] && ACT=1

echo "扫描 /sdcard 根目录 .png ..."
FILES=()
while IFS= read -r line; do
  [[ -n "$line" ]] && FILES+=("$line")
done < <(adb shell 'ls /sdcard/*.png 2>/dev/null' | tr -d '\r' || true)
count=${#FILES[@]}
echo "发现 ${count} 个测试 .png 残留"

if (( count == 0 )); then
  echo "✅ 无残留，相册干净"
  exit 0
fi

deleted=0
for f in "${FILES[@]}"; do
  if (( ACT == 0 )); then
    echo "  [dry] $f"
    continue
  fi
  adb shell "rm -f '$f'"
  # 同步删 MediaStore 记录（让 PoLang 相册立即移除）
  # 注意：adb shell 会把多个参数用空格拼成命令串、丢掉主机层引号，故整条命令包成单串、
  # 内部双引号转义，确保设备 sh 把含 SQL 单引号的 where 原样交给 content 工具。
  adb shell "content delete --uri content://media/external/images/media --where \"_data='$f'\"" >/dev/null 2>&1 || true
  echo "  [rm]  $f"
  deleted=$((deleted + 1))
done

if (( ACT == 0 )); then
  echo "→ 以上均为待删；确认无误后执行: scripts/clean-test-shots.sh --rm"
else
  echo "✅ 已删除 ${deleted} 个 + 同步清 MediaStore（PoLang 相册应已移除）"
fi
