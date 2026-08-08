#!/bin/bash
# Xcode Run Script 阶段调用：SharedKit debug XCFramework 增量构建
# 只在 shared 源码比 hash 文件新时触发 Gradle，避免每次 Xcode 编译都跑 Kotlin
# （kmp-ios-interop skill「embed hash 重拷」坑：产物重建后 touch framework 顶层，
#  强制 Xcode embed 阶段重新拷贝，防止旧 framework 残留）
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HASH_FILE="$REPO_ROOT/iosApp/build/.shared-kit-hash"
XCFW="$REPO_ROOT/shared/build/XCFrameworks/debug/SharedKit.xcframework"

# 注意：find 的 -newer 引用文件缺失时 BSD find 退出码非零，
# pipefail + set -e 会静默中止脚本（本脚本首个 bug），故分支处理 + || true 兜底。
STALE=""
if [ -f "$HASH_FILE" ]; then
    STALE="$(find "$REPO_ROOT/shared/src" -name '*.kt' -newer "$HASH_FILE" 2>/dev/null | head -1 || true)"
else
    STALE=force  # hash 文件缺失视为过期（防首次/清理后误判）
fi
if [ -z "$STALE" ] && [ -d "$XCFW" ]; then
    echo "SharedKit up-to-date, skip gradle"
    exit 0
fi

# Xcode 构建环境 PATH 无 java，兜底经 /usr/libexec/java_home 注入
if ! command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [ -n "$JAVA_HOME" ]; then
        export JAVA_HOME
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi

# Gradle 配置阶段会解析 Android 模块（engines/*），Xcode 环境无 ANDROID_HOME，兜底默认路径
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

cd "$REPO_ROOT"
JITPACK=true ./gradlew :shared:assembleSharedKitDebugXCFramework
mkdir -p "$(dirname "$HASH_FILE")" && touch "$HASH_FILE"
touch "$XCFW"  # 强制 embed 重拷
