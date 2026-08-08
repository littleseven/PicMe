#!/bin/bash
# sentencepiece iOS 构建归档（2.2 spike 验证产物，libsentencepiece-static.a 1.6MB arm64）
# 来源：tmp/mnn-ios-spike/spm-ios-build/CMakeLists.txt（Phase 5 基建期收编，Phase 6 才使用）
set -euo pipefail
cd "$(dirname "$0")/../.."
SRC=tmp/mnn-ios-spike/spm-ios-build/CMakeLists.txt
if [ -f "$SRC" ]; then
    cp "$SRC" engines/sentencepiece/ios/
    echo "OK: sentencepiece iOS CMakeLists 收编到 engines/sentencepiece/ios/"
else
    echo "WARN: $SRC not found (tmp/ may not exist in worktree); skipping"
fi
