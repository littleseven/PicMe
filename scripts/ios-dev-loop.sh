#!/bin/bash
# iOS 闭环验证：pod install → 编译 → 安装模拟器 → 启动 → 截图
# 用法：./scripts/ios-dev-loop.sh [截图名]
# 对标 scripts/auto-dev-loop.sh（Android 侧）
set -euo pipefail
cd "$(dirname "$0")/.."
SCHEME=PoLang
WORKSPACE=iosApp/PoLang.xcworkspace
DEST='platform=iOS Simulator,name=iPhone 16'
SHOT=${1:-ios-loop}
mkdir -p tmp/shots

echo "== pod install =="
cd iosApp && pod install --repo-update 2>&1 | tail -5; cd ..

echo "== build =="
xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" -destination "$DEST" build -quiet

echo "== install & launch =="
APP_PATH=$(xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" -destination "$DEST" -showBuildSettings -quiet \
    | awk -F' = ' '/TARGET_BUILD_DIR/{d=$2} /WRAPPER_NAME/{w=$2} END{print d"/"w}')
xcrun simctl boot "iPhone 16" 2>/dev/null || true
xcrun simctl install booted "$APP_PATH"
xcrun simctl launch booted com.mamba.picme
sleep 5

echo "== screenshot =="
xcrun simctl io booted screenshot "tmp/shots/${SHOT}.png"
echo "OK: tmp/shots/${SHOT}.png"
