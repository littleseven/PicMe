#!/bin/bash
#
# Doc Sync Guardian - PoLang 文档变更自动识别与同步提醒
# 用途: 根据 git diff 自动识别代码变更对应的文档，生成同步提醒报告
# 调用: ./scripts/doc-sync-guardian.sh [options]
#
# Options:
#   --staged          检查已暂存的变更（默认）
#   --unstaged        检查未暂存的变更
#   --since <commit>  检查从某次 commit 以来的变更
#   --auto-suggest    自动生成文档变更草案
#   --output <file>   输出报告文件
#
# 示例:
#   ./scripts/doc-sync-guardian.sh                    # 检查暂存区变更
#   ./scripts/doc-sync-guardian.sh --unstaged         # 检查工作区变更
#   ./scripts/doc-sync-guardian.sh --auto-suggest     # 生成变更草案
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

MODE="staged"
SINCE_COMMIT=""
AUTO_SUGGEST=false
OUTPUT_FILE=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --staged) MODE="staged"; shift ;;
        --unstaged) MODE="unstaged"; shift ;;
        --since) MODE="since"; SINCE_COMMIT="$2"; shift 2 ;;
        --auto-suggest) AUTO_SUGGEST=true; shift ;;
        --output) OUTPUT_FILE="$2"; shift 2 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 文档映射规则：代码路径前缀 → 需同步的文档（用 | 分隔）
# 格式: "前缀|文档1|文档2|..."
declare -a DOC_MAP_PAIRS=(
    "androidApp/src/main/java/com/picme/features/camera/|docs/01-PRODUCT/FEATURES.md|androidApp/src/main/java/com/picme/features/camera/AGENTS.md|docs/03-TECHNICAL-SPECS/CAMERA_PREVIEW_TECH_SPEC.md"
    "androidApp/src/main/java/com/picme/features/gallery/|docs/01-PRODUCT/FEATURES.md|androidApp/src/main/java/com/picme/features/gallery/AGENTS.md"
    "androidApp/src/main/java/com/picme/features/editor/|docs/01-PRODUCT/FEATURES.md|androidApp/src/main/java/com/picme/features/editor/AGENTS.md"
    "androidApp/src/main/java/com/picme/features/settings/|docs/01-PRODUCT/FEATURES.md|androidApp/src/main/java/com/picme/features/settings/AGENTS.md"
    "androidApp/src/main/java/com/picme/features/debug/|docs/01-PRODUCT/FEATURES.md|androidApp/src/main/java/com/picme/features/debug/AGENTS.md"
    "androidApp/src/main/java/com/picme/data/|androidApp/src/main/java/com/picme/data/AGENTS.md"
    "androidApp/src/main/java/com/picme/di/|androidApp/src/main/java/com/picme/di/AGENTS.md"
    "engines/beauty-engine/|engines/beauty-engine/AGENTS.md|docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md|docs/08-FALLBACK/BEAUTY_ENGINE_FALLBACK.md"
    "buildSrc/|DEVELOPMENT.md"
    "androidApp/src/main/res/values/strings.xml|docs/01-PRODUCT/FEATURES.md|androidApp/src/main/res/values-zh-rCN/strings.xml|androidApp/src/main/res/values-zh-rTW/strings.xml"
    "androidApp/src/main/res/values-zh-rCN/strings.xml|androidApp/src/main/res/values/strings.xml|androidApp/src/main/res/values-zh-rTW/strings.xml"
    "androidApp/src/main/res/values-zh-rTW/strings.xml|androidApp/src/main/res/values/strings.xml|androidApp/src/main/res/values-zh-rCN/strings.xml"
    "AGENTS.md|AGENTS.md"
    "PRODUCT.md|docs/01-PRODUCT/FEATURES.md"
    "docs/01-PRODUCT/FEATURES.md|PRODUCT.md"
    "docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md|engines/beauty-engine/AGENTS.md"
    "docs/03-TECHNICAL-SPECS/CAMERA_PREVIEW_TECH_SPEC.md|androidApp/src/main/java/com/picme/features/camera/AGENTS.md"
)

# 查找文档映射（兼容 bash 3.2）
find_doc_mapping() {
    local file="$1"
    local result=""
    for pair in "${DOC_MAP_PAIRS[@]}"; do
        local prefix=$(echo "$pair" | cut -d'|' -f1)
        if echo "$file" | grep -q "^$prefix"; then
            local docs=$(echo "$pair" | cut -d'|' -f2-)
            result="${result}${result:+|}$docs"
        fi
    done
    echo "$result"
}

# 获取变更文件
get_changed_files() {
    case $MODE in
        staged)
            git diff --cached --name-only 2>/dev/null || true
            ;;
        unstaged)
            git diff --name-only 2>/dev/null || true
            ;;
        since)
            if [ -n "$SINCE_COMMIT" ]; then
                git diff --name-only "$SINCE_COMMIT" HEAD 2>/dev/null || true
            else
                git diff --name-only HEAD~1 HEAD 2>/dev/null || true
            fi
            ;;
    esac
}

# 分析文件变更类型
analyze_change_type() {
    local file="$1"
    
    if echo "$file" | grep -qE "Test\.kt$|test/|androidTest/"; then
        echo "test"
    elif echo "$file" | grep -qE "\.md$"; then
        echo "doc"
    elif echo "$file" | grep -qE "build\.gradle|libs\.versions\.toml"; then
        echo "build"
    elif echo "$file" | grep -qE "\.sh$|\.py$"; then
        echo "script"
    elif echo "$file" | grep -qE "\.xml$"; then
        echo "resource"
    elif echo "$file" | grep -qE "\.kt$|\.java$"; then
        echo "code"
    else
        echo "other"
    fi
}

# 查找相关文档
find_related_docs() {
    local file="$1"
    local related=""
    
    # 直接映射匹配
    related=$(find_doc_mapping "$file")
    
    # 全局规则：修改代码文件时提醒更新对应模块 AGENTS.md
    if echo "$file" | grep -qE "\.kt$|\.java$"; then
        local module_dir=$(echo "$file" | sed 's|/src/.*||')
        if [ -d "$module_dir" ] && [ -f "$module_dir/AGENTS.md" ]; then
            related="${related}${related:+|}$module_dir/AGENTS.md"
        fi
    fi
    
    # 去重
    echo "$related" | tr '|' '\n' | sort -u | tr '\n' '|' | sed 's/|$//'
}

# 生成文档同步提醒
generate_sync_report() {
    local files="$1"
    local doc_updates_needed=""
    local i18n_needed=false
    local arch_doc_needed=false
    
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        
        local change_type=$(analyze_change_type "$file")
        local related_docs=$(find_related_docs "$file")
        
        # 检查 I18N
        if echo "$file" | grep -q "strings\.xml"; then
            i18n_needed=true
        fi
        
        # 检查架构文档
        if echo "$file" | grep -qE "engines/beauty-engine/|camera/|gallery/"; then
            arch_doc_needed=true
        fi
        
        if [ -n "$related_docs" ]; then
            doc_updates_needed="${doc_updates_needed}${file}|${related_docs}\n"
        fi
    done <<< "$files"
    
    # 输出报告
    {
        echo "# Doc Sync Guardian Report"
        echo ""
        echo "**检查模式**: $MODE"
        echo "**变更文件数**: $(echo "$files" | grep -c '^' || echo 0)"
        echo "**生成时间**: $(date '+%Y-%m-%d %H:%M:%S')"
        echo ""
        
        # I18N 提醒
        if [ "$i18n_needed" = true ]; then
            echo "## 🚨 I18N 同步提醒"
            echo ""
            echo "检测到 strings.xml 变更，请确认以下文件已同步更新："
            echo "- [ ] androidApp/src/main/res/values/strings.xml（英文/默认）"
            echo "- [ ] androidApp/src/main/res/values-zh-rCN/strings.xml（简体中文）"
            echo "- [ ] androidApp/src/main/res/values-zh-rTW/strings.xml（繁体中文）"
            echo ""
        fi
        
        # 文档同步提醒
        if [ -n "$doc_updates_needed" ]; then
            echo "## 📋 文档同步建议"
            echo ""
            
            echo -e "$doc_updates_needed" | while IFS='|' read -r file docs; do
                [ -z "$file" ] && continue
                echo "### $file"
                echo "变更类型: $(analyze_change_type "$file")"
                echo "建议同步文档:"
                echo "$docs" | tr '|' '\n' | while read -r doc; do
                    [ -z "$doc" ] && continue
                    if [ -f "$doc" ]; then
                        echo "- [ ] \`$doc\` ✅ 存在"
                    else
                        echo "- [ ] \`$doc\` ⚠️ 不存在，需创建"
                    fi
                done
                echo ""
            done
        else
            echo "## 📋 文档同步建议"
            echo ""
            echo "✅ 当前变更未检测到需要同步的文档"
            echo ""
        fi
        
        # 架构文档提醒
        if [ "$arch_doc_needed" = true ]; then
            echo "## 🏗️ 架构文档提醒"
            echo ""
            echo "涉及核心模块变更，请确认以下文档是否需要更新："
            echo "- [ ] PRODUCT.md（产品规格）"
            echo "- [ ] docs/01-PRODUCT/FEATURES.md（交互规范）"
            echo "- [ ] 对应模块 AGENTS.md（实现规范）"
            echo ""
        fi
        
        # 自动建议
        if [ "$AUTO_SUGGEST" = true ] && [ -n "$doc_updates_needed" ]; then
            echo "## 🤖 自动变更草案"
            echo ""
            generate_suggestions "$files"
            echo ""
        fi
        
        # 交付检查清单
        echo "## ✅ 交付检查清单"
        echo ""
        echo "- [ ] 代码变更已完成"
        echo "- [ ] 单元测试已补充"
        echo "- [ ] 文档已同步更新"
        echo "- [ ] I18N 三语言已检查"
        echo "- [ ] AI Gate 已通过"
        echo ""
    }
}

# 自动生成变更建议
generate_suggestions() {
    local files="$1"
    local camera_changes=""
    local gallery_changes=""
    local beauty_changes=""
    local other_changes=""
    
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        
        if echo "$file" | grep -q "features/camera/"; then
            camera_changes="${camera_changes}- $file\n"
        elif echo "$file" | grep -q "features/gallery/"; then
            gallery_changes="${gallery_changes}- $file\n"
        elif echo "$file" | grep -q "engines/beauty-engine/"; then
            beauty_changes="${beauty_changes}- $file\n"
        else
            other_changes="${other_changes}- $file\n"
        fi
    done <<< "$files"
    
    if [ -n "$camera_changes" ]; then
        echo "### 相机模块"
        echo -e "$camera_changes"
        echo "建议更新: docs/01-PRODUCT/FEATURES.md §相机功能, androidApp/src/main/java/com/picme/features/camera/AGENTS.md"
        echo ""
    fi
    
    if [ -n "$gallery_changes" ]; then
        echo "### 相册模块"
        echo -e "$gallery_changes"
        echo "建议更新: docs/01-PRODUCT/FEATURES.md §相册功能, androidApp/src/main/java/com/picme/features/gallery/AGENTS.md"
        echo ""
    fi
    
    if [ -n "$beauty_changes" ]; then
        echo "### 美颜引擎"
        echo -e "$beauty_changes"
        echo "建议更新: engines/beauty-engine/AGENTS.md, docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md"
        echo ""
    fi
}

# 主流程
CHANGED_FILES=$(get_changed_files)

if [ -z "$CHANGED_FILES" ]; then
    echo -e "${GREEN}✅ 未检测到变更，无需文档同步${NC}"
    exit 0
fi

if [ -n "$OUTPUT_FILE" ]; then
    generate_sync_report "$CHANGED_FILES" > "$OUTPUT_FILE"
    echo -e "${GREEN}✅ 报告已保存: $OUTPUT_FILE${NC}"
else
    generate_sync_report "$CHANGED_FILES"
fi

# 统计
DOC_COUNT=$(echo "$CHANGED_FILES" | grep -c '\.md$' || echo 0)
CODE_COUNT=$(echo "$CHANGED_FILES" | grep -cE '\.kt$|\.java$' || echo 0)
TEST_COUNT=$(echo "$CHANGED_FILES" | grep -cE 'Test\.kt$' || echo 0)

echo ""
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}  Doc Sync Guardian 完成${NC}"
echo -e "${CYAN}  代码文件: $CODE_COUNT | 文档文件: $DOC_COUNT | 测试文件: $TEST_COUNT${NC}"
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
