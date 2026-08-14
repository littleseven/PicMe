#!/usr/bin/env bash
# setup-ios-worktree.sh — 在从 main 新建的 worktree 里准备 iOS 构建(gitignored 产物在主 checkout)。
# 用法:在 worktree 内运行。MAIN(主 checkout)默认为 worktree 上两级(REPO/.worktrees/<wt> → REPO)。
# 关键坑(实证):SharedKit.xcframework 不能直接 symlink 主 checkout 的——若主 checkout 在别的分支
#   (如 feat/ios-chat-rich-features)会 API 签名不匹配。故删 symlink+hash,让 build-shared-kit.sh
#   从本 worktree 的 shared/ 重建(见 Phase 0)。
set -euo pipefail
WT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="${MAIN:-$(cd "$WT_ROOT/../.." && pwd)}"
echo "worktree: $WT_ROOT"
echo "主 checkout: $MAIN"

# 可直接 symlink 的产物(MNN/Pods/face 模型与分支无关)
ln -sfn "$MAIN/iosApp/Frameworks/MNN.framework" "$WT_ROOT/iosApp/Frameworks/MNN.framework"
mkdir -p "$WT_ROOT/iosApp/PoLang/Features/Camera/Beauty/Assets"
ln -sfn "$MAIN/iosApp/PoLang/Features/Camera/Beauty/Assets/face_landmarker.task" "$WT_ROOT/iosApp/PoLang/Features/Camera/Beauty/Assets/face_landmarker.task"
ln -sfn "$MAIN/iosApp/Pods" "$WT_ROOT/iosApp/Pods"
echo "✓ symlink: MNN.framework / face_landmarker.task / Pods"

# SharedKit:不 symlink,强制从本 worktree main 源码重建(避免分支签名污染)
rm -f "$WT_ROOT/shared/build/XCFrameworks" "$WT_ROOT/iosApp/build/.shared-kit-hash"
echo "✓ 清除 SharedKit symlink+hash → 首次 xcodebuild 会触发 build-shared-kit.sh 重建(K/N,慢)"
echo "完成。下一步:xcodebuild build-for-testing(generic/platform=iOS)。"
