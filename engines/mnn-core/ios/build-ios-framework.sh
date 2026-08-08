#!/bin/bash
# MNN iOS framework 构建（真机 arm64）并同步到 iosApp/Frameworks/
# 源码：MNN 官方 build_lib.sh（2.1 spike 已验证全配置覆盖项目需求）
# Phase 5 不集成 MNN 推理（相机美颜走 MediaPipe）；本脚本仅为 Phase 6.1 TAG 铺路 + tmp/ 产物转正。
set -euo pipefail
cd "$(dirname "$0")/../.."
MNN_SRC=${MNN_SRC:-engines/mnn-core/src/main/cpp/third_party/MNN}
if [ -d "$MNN_SRC" ]; then
    (cd "$MNN_SRC" && ./build_lib.sh --ios)
    cp -R "$MNN_SRC/build_ios/MNN.framework" iosApp/Frameworks/
else
    # 无 MNN 源码树时，用 2.1 spike 预编译产物兜底
    cp -R tmp/mnn-ios-spike/MNN.framework iosApp/Frameworks/
fi
ls -lh iosApp/Frameworks/MNN.framework/MNN
