#!/bin/bash
#
# Release Automation - PoLang 版本发布自动化脚本
# 用途: 自动化版本号更新、CHANGELOG 生成、签名构建、Git tag 打标
# 调用: ./scripts/release-automation.sh [options]
#
# Options:
#   --type <type>     版本类型: major|minor|patch (默认: patch)
#   --dry-run         预览变更，不实际执行
#   --skip-build      跳过构建（仅更新版本和 CHANGELOG）
#   --output <dir>    输出目录
#   --aab             同时构建 AAB（Google Play 上架格式）
#
# 示例:
#   ./scripts/release-automation.sh --type patch --dry-run    # 预览 patch 版本变更
#   ./scripts/release-automation.sh --type minor              # 执行 minor 版本发布
#   ./scripts/release-automation.sh --type patch --aab        # 发布 APK + AAB
#

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

RELEASE_TYPE="patch"
DRY_RUN=false
SKIP_BUILD=false
BUILD_AAB=false
OUTPUT_DIR="$PROJECT_ROOT/app/build/outputs/apk/release"
AAB_OUTPUT_DIR="$PROJECT_ROOT/app/build/outputs/bundle/release"

while [[ $# -gt 0 ]]; do
    case $1 in
        --type) RELEASE_TYPE="$2"; shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --aab) BUILD_AAB=true; shift ;;
        --output) OUTPUT_DIR="$2"; shift 2 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 获取当前版本
get_current_version() {
    grep -E "versionName\s*=" app/build.gradle.kts | grep -oE '"[^"]+"' | tr -d '"' || echo "1.0.0"
}

# 计算新版本
calculate_new_version() {
    local current="$1"
    local type="$2"
    
    # 解析版本号
    IFS='.' read -r major minor patch <<< "$current"
    
    # 处理带后缀的版本号（如 1.0.0-beta）
    patch_num=$(echo "$patch" | grep -oE '^[0-9]+')
    
    case "$type" in
        major)
            major=$((major + 1))
            minor=0
            patch_num=0
            ;;
        minor)
            minor=$((minor + 1))
            patch_num=0
            ;;
        patch)
            patch_num=$((patch_num + 1))
            ;;
    esac
    
    echo "${major}.${minor}.${patch_num}"
}

# 获取当前 versionCode
get_current_version_code() {
    grep -E "versionCode\s*=" app/build.gradle.kts | grep -oE '[0-9]+' | head -1 || echo "1"
}

# 生成 CHANGELOG
generate_changelog() {
    local version="$1"
    local since_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "HEAD~20")
    
    local changelog="## [$version] - $(date '+%Y-%m-%d')\n\n"
    
    # 按类型分组
    local feats=$(git log --oneline --format="- %s" "$since_tag"..HEAD 2>/dev/null | grep -E "^.*feat[(:]" || true)
    local fixes=$(git log --oneline --format="- %s" "$since_tag"..HEAD 2>/dev/null | grep -E "^.*fix[(:]" || true)
    local docs=$(git log --oneline --format="- %s" "$since_tag"..HEAD 2>/dev/null | grep -E "^.*docs[(:]" || true)
    local tests=$(git log --oneline --format="- %s" "$since_tag"..HEAD 2>/dev/null | grep -E "^.*test[(:]" || true)
    local refactors=$(git log --oneline --format="- %s" "$since_tag"..HEAD 2>/dev/null | grep -E "^.*refactor[(:]" || true)
    local others=$(git log --oneline --format="- %s" "$since_tag"..HEAD 2>/dev/null | grep -vE "feat[(:]|fix[(:]|docs[(:]|test[(:]|refactor[(:]" || true)
    
    if [ -n "$feats" ]; then
        changelog="${changelog}### ✨ Features\n${feats}\n\n"
    fi
    if [ -n "$fixes" ]; then
        changelog="${changelog}### 🐛 Bug Fixes\n${fixes}\n\n"
    fi
    if [ -n "$refactors" ]; then
        changelog="${changelog}### ♻️ Refactors\n${refactors}\n\n"
    fi
    if [ -n "$tests" ]; then
        changelog="${changelog}### 🧪 Tests\n${tests}\n\n"
    fi
    if [ -n "$docs" ]; then
        changelog="${changelog}### 📚 Documentation\n${docs}\n\n"
    fi
    if [ -n "$others" ]; then
        changelog="${changelog}### 🔧 Others\n${others}\n\n"
    fi
    
    echo -e "$changelog"
}

# 更新版本号
update_version() {
    local new_version="$1"
    local new_version_code="$2"
    
    if [ "$DRY_RUN" = true ]; then
        echo -e "${BLUE}[DRY-RUN] 将更新版本号:${NC}"
        echo "  versionName: $new_version"
        echo "  versionCode: $new_version_code"
        return
    fi
    
    # 更新 build.gradle.kts
    sed -i '' "s/versionCode = [0-9]*/versionCode = $new_version_code/" app/build.gradle.kts
    sed -i '' "s/versionName = \"[^\"]*\"/versionName = \"$new_version\"/" app/build.gradle.kts
    
    echo -e "${GREEN}✅ 版本号已更新:${NC}"
    echo "  versionName: $new_version"
    echo "  versionCode: $new_version_code"
}

# 更新 CHANGELOG
update_changelog() {
    local new_entry="$1"
    local changelog_file="$PROJECT_ROOT/CHANGELOG.md"
    
    if [ "$DRY_RUN" = true ]; then
        echo -e "${BLUE}[DRY-RUN] 将追加到 CHANGELOG:${NC}"
        echo "$new_entry"
        return
    fi
    
    # 如果 CHANGELOG 不存在，创建头部
    if [ ! -f "$changelog_file" ]; then
        cat > "$changelog_file" << EOF
# Changelog

All notable changes to this project will be documented in this file.

EOF
    fi
    
    # 在头部插入新内容
    local temp_file=$(mktemp)
    {
        echo "# Changelog"
        echo ""
        echo -e "$new_entry"
        # 保留旧内容（去掉头部）
        tail -n +3 "$changelog_file" 2>/dev/null || true
    } > "$temp_file"
    
    mv "$temp_file" "$changelog_file"
    echo -e "${GREEN}✅ CHANGELOG 已更新: $changelog_file${NC}"
}

# 构建发布 APK
build_release() {
    if [ "$SKIP_BUILD" = true ]; then
        echo -e "${YELLOW}⏭️ 跳过构建 (--skip-build)${NC}"
        return
    fi

    if [ "$DRY_RUN" = true ]; then
        echo -e "${BLUE}[DRY-RUN] 将执行: ./gradlew :app:assembleRelease${NC}"
        if [ "$BUILD_AAB" = true ]; then
            echo -e "${BLUE}[DRY-RUN] 将执行: ./gradlew :app:bundleRelease${NC}"
        fi
        return
    fi

    echo "🔨 构建 Release APK..."
    ./gradlew :app:assembleRelease

    # 查找 APK
    local apk=$(find "$OUTPUT_DIR" -name "*.apk" -type f | sort | tail -1)
    if [ -n "$apk" ]; then
        local size=$(du -h "$apk" | cut -f1)
        echo -e "${GREEN}✅ APK 构建成功:${NC}"
        echo "  路径: $apk"
        echo "  大小: $size"
    fi

    if [ "$BUILD_AAB" = true ]; then
        echo ""
        echo "🔨 构建 Release AAB（Google Play 上架格式）..."
        ./gradlew :app:bundleRelease

        local aab=$(find "$AAB_OUTPUT_DIR" -name "*.aab" -type f | sort | tail -1)
        if [ -n "$aab" ]; then
            local aab_size=$(du -h "$aab" | cut -f1)
            echo -e "${GREEN}✅ AAB 构建成功:${NC}"
            echo "  路径: $aab"
            echo "  大小: $aab_size"
        fi
    fi
}

# Git 操作
git_operations() {
    local version="$1"
    
    if [ "$DRY_RUN" = true ]; then
        echo -e "${BLUE}[DRY-RUN] 将执行 Git 操作:${NC}"
        echo "  git add app/build.gradle.kts CHANGELOG.md"
        echo "  git commit -m \"chore(release): $version\""
        echo "  git tag -a v$version -m \"Release $version\""
        return
    fi
    
    # 检查工作区是否干净
    if ! git diff-index --quiet HEAD -- 2>/dev/null; then
        echo -e "${YELLOW}⚠️ 工作区有未提交的变更，先提交...${NC}"
        git add app/build.gradle.kts CHANGELOG.md
        git commit -m "chore(release): $version" || true
    fi
    
    # 打 tag
    git tag -a "v$version" -m "Release $version"
    echo -e "${GREEN}✅ Git tag 已创建: v$version${NC}"
    
    echo ""
    echo -e "${CYAN}发布完成！${NC}"
    echo "  版本: $version"
    echo "  Tag: v$version"
    echo ""
    echo "后续步骤:"
    if [ "$BUILD_AAB" = true ]; then
        echo "  1. 测试 Release APK 与 AAB"
        echo "  2. 上传 AAB 到 Google Play Console"
    else
        echo "  1. 测试 Release APK"
    fi
    echo "  2. git push origin main --tags"
    echo "  3. 在 GitHub 创建 Release"
}

# 主流程
echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  🚀 Release Automation - 版本发布自动化                   ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

if [ "$DRY_RUN" = true ]; then
    echo -e "${YELLOW}💡 Dry-run 模式：预览变更，不实际执行${NC}"
    echo ""
fi

# 获取当前版本
CURRENT_VERSION=$(get_current_version)
CURRENT_VERSION_CODE=$(get_current_version_code)
NEW_VERSION=$(calculate_new_version "$CURRENT_VERSION" "$RELEASE_TYPE")
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))

echo -e "${BLUE}版本信息:${NC}"
echo "  当前版本: $CURRENT_VERSION (code: $CURRENT_VERSION_CODE)"
echo "  新版本:   $NEW_VERSION (code: $NEW_VERSION_CODE)"
echo "  升级类型: $RELEASE_TYPE"
echo ""

# 生成 CHANGELOG
CHANGELOG_ENTRY=$(generate_changelog "$NEW_VERSION")

echo -e "${BLUE}CHANGELOG 预览:${NC}"
echo "────────────────────────────────────────────────────────────"
echo -e "$CHANGELOG_ENTRY"
echo "────────────────────────────────────────────────────────────"
echo ""

if [ "$DRY_RUN" = true ]; then
    echo -e "${YELLOW}💡 使用 --dry-run 预览完成，去掉 --dry-run 执行实际发布${NC}"
    exit 0
fi

# 确认
if [ "$DRY_RUN" = false ]; then
    echo -n "确认执行发布? [y/N] "
    read -r confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        echo "已取消"
        exit 0
    fi
fi

# 执行更新
update_version "$NEW_VERSION" "$NEW_VERSION_CODE"
update_changelog "$CHANGELOG_ENTRY"
build_release
git_operations "$NEW_VERSION"

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  🎉 Release $NEW_VERSION 完成！${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
