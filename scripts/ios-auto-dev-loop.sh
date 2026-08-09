#!/bin/bash
#
# iOS Auto Dev Loop — 真机自动闭环验证（对标 auto-dev-loop.sh Android 侧）
#
# 流程：编译 → 单元测试 → 安装 → 启动 → 截图 → 报告
# 用法：./scripts/ios-auto-dev-loop.sh [选项]
#
# Options:
#   --no-test         跳过单元测试
#   --no-install      跳过安装（仅编译+测试）
#   --screenshot NAME 截图并保存（默认 polang-loop）
#   --test-only       仅运行单元测试（不编译/安装）
#   --help            显示帮助
#
# 工作流:
#   1. pod install + xcodegen（确保工程最新）
#   2. 编译 Debug（真机）
#   3. 单元测试（jvmTest + PoLangTests）
#   4. 安装到真机
#   5. 启动 App + 截图
#   6. 生成报告（PASS/FAIL 汇总）

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS_APP="$PROJECT_ROOT/iosApp"
WORKSPACE="$IOS_APP/PoLang.xcworkspace"
SCHEME=PoLang
DEVICE_ID="00008120-000105443AD2201E"
BUNDLE_ID="com.mamba.picme"
VENV_PYMD3="$PROJECT_ROOT/tmp/venv_pmd3/bin"

cd "$PROJECT_ROOT"

# 参数
NO_TEST=false
NO_INSTALL=false
TEST_ONLY=false
SHOT_NAME="polang-loop"
SHOW_HELP=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --no-test) NO_TEST=true; shift ;;
        --no-install) NO_INSTALL=true; shift ;;
        --test-only) TEST_ONLY=true; shift ;;
        --screenshot) SHOT_NAME="$2"; shift 2 ;;
        --help) SHOW_HELP=true; shift ;;
        *) echo "未知参数：$1"; exit 1 ;;
    esac
done

if $SHOW_HELP; then
    echo "iOS Auto Dev Loop — 真机自动闭环验证"
    echo ""
    echo "用法：$0 [选项]"
    echo ""
    echo "选项:"
    echo "  --no-test         跳过单元测试"
    echo "  --no-install      跳过安装（仅编译+测试）"
    echo "  --screenshot NAME 截图名（默认 polang-loop）"
    echo "  --test-only       仅运行单元测试"
    echo "  --help            显示帮助"
    exit 0
fi

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0;33m'

PASS_COUNT=0
FAIL_COUNT=0
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
OUTPUT_DIR="$PROJECT_ROOT/scripts/auto_test_output/ios_$TIMESTAMP"
mkdir -p "$OUTPUT_DIR"

info()  { echo -e "${BLUE}[INFO]${NC} $1"; }
pass()  { echo -e "${GREEN}[PASS]${NC} $1"; PASS_COUNT=$((PASS_COUNT+1)); }
fail()  { echo -e "${RED}[FAIL]${NC} $1"; FAIL_COUNT=$((FAIL_COUNT+1)); }
step()  { echo -e "\n${YELLOW}━━━ $1 ━━━${NC}"; }

# ── Step 1: 工程准备 ──────────────────────────────────────────
step "1/6 工程准备（xcodegen + pod install）"

cd "$IOS_APP"
if xcodegen generate > "$OUTPUT_DIR/xcodegen.log" 2>&1; then
    pass "xcodegen"
else
    fail "xcodegen 失败（见 $OUTPUT_DIR/xcodegen.log）"
    cd "$PROJECT_ROOT"
    exit 1
fi

if pod install >> "$OUTPUT_DIR/pod_install.log" 2>&1; then
    pass "pod install"
else
    fail "pod install（可能有警告，继续）"
fi

mkdir -p build && touch build/.shared-kit-hash
cd "$PROJECT_ROOT"

# ── Step 2: 编译 ──────────────────────────────────────────────
step "2/6 编译 Debug（真机）"

if $TEST_ONLY; then
    info "跳过编译（--test-only）"
else
    if xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" \
        -destination "id=$DEVICE_ID" -configuration Debug \
        -derivedDataPath "$IOS_APP/build/derivedData" \
        build > "$OUTPUT_DIR/build.log" 2>&1; then
        pass "编译 Debug"
    else
        fail "编译失败（见 $OUTPUT_DIR/build.log）"
        tail -5 "$OUTPUT_DIR/build.log"
        exit 1
    fi
fi

# ── Step 3: 单元测试 ──────────────────────────────────────────
if ! $NO_TEST; then
    step "3/6 单元测试"

    # shared JVM 测试
    if ./gradlew :shared:jvmTest > "$OUTPUT_DIR/jvm_test.log" 2>&1; then
        pass "shared:jvmTest"
    else
        fail "shared:jvmTest（见 $OUTPUT_DIR/jvm_test.log）"
    fi

    # iOS 真机测试
    if xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" \
        -destination "id=$DEVICE_ID" -configuration Debug \
        -only-testing:PoLangTests \
        -derivedDataPath "$IOS_APP/build/derivedData" \
        test > "$OUTPUT_DIR/ios_test.log" 2>&1; then
        pass "PoLangTests（iOS 真机）"
    else
        fail "PoLangTests（见 $OUTPUT_DIR/ios_test.log）"
        # 提取失败用例
        grep "failed\|XCTAssert" "$OUTPUT_DIR/ios_test.log" | head -5
    fi
else
    step "3/6 单元测试（跳过）"
fi

# ── Step 4: 安装 ──────────────────────────────────────────────
if ! $NO_INSTALL && ! $TEST_ONLY; then
    step "4/6 安装到真机"

    APP_PATH="$IOS_APP/build/derivedData/Build/Products/Debug-iphoneos/PoLang.app"
    if xcrun devicectl device install app --device "$DEVICE_ID" "$APP_PATH" \
        > "$OUTPUT_DIR/install.log" 2>&1; then
        pass "安装"
    else
        fail "安装失败（见 $OUTPUT_DIR/install.log）"
    fi
else
    step "4/6 安装（跳过）"
fi

# ── Step 5: 启动 + 截图 ───────────────────────────────────────
if ! $NO_INSTALL && ! $TEST_ONLY; then
    step "5/6 启动 + 截图"

    # 启动
    if xcrun devicectl device process launch --device "$DEVICE_ID" "$BUNDLE_ID" \
        > "$OUTPUT_DIR/launch.log" 2>&1; then
        pass "启动 App"
    else
        fail "启动失败"
    fi

    sleep 3

    # 截图（pymobiledevice3 dvt screenshot）
    SHOT_PATH="$OUTPUT_DIR/${SHOT_NAME}.png"
    if "$VENV_PYMD3/pymobiledevice3" developer dvt screenshot "$SHOT_PATH" \
        --udid "$DEVICE_ID" > "$OUTPUT_DIR/screenshot.log" 2>&1; then
        if [ -f "$SHOT_PATH" ]; then
            pass "截图 → $SHOT_PATH"
        else
            fail "截图文件未生成"
        fi
    else
        fail "截图失败（pymobiledevice3）"
    fi
else
    step "5/6 启动+截图（跳过）"
fi

# ── Step 6: 报告 ──────────────────────────────────────────────
step "6/6 报告"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  ${GREEN}PASS: $PASS_COUNT${NC}  ${RED}FAIL: $FAIL_COUNT${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  输出目录: $OUTPUT_DIR"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
    echo -e "  ${GREEN}✅ 全部通过${NC}"
    exit 0
else
    echo -e "  ${RED}❌ 有 $FAIL_COUNT 项失败${NC}"
    exit 1
fi
