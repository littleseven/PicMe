#!/usr/bin/env bash
# 假 claude，供 smoke 验证 poller 胶水。真实 claude 以 `claude -p "<prompt>" ...` 调用，
# 故 $1=-p、$2=prompt。按 prompt 内容吐出固定 JSON；同时把 prompt 落盘供 smoke 断言。
prompt="$2"
printf '%s' "$prompt" > "${DIAG_WORKDIR:-/tmp}/last-prompt.txt"
if printf '%s' "$prompt" | grep -q "Do NOT modify"; then
  # diagnose 分支：输出 .result 内嵌一段含 rootCause 的 JSON
  printf '%s' '{"result":"{\"rootCause\":\"stub: NPE GalleryScreen null uri\",\"suspectFiles\":[\"GalleryScreen.kt\"],\"suggestedFix\":\"null check\"}"}'
else
  # fix 分支
  printf '%s' '{"result":"{\"changedFiles\":[],\"summary\":\"stub fix\"}"}'
fi
