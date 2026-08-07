#!/bin/bash
#
# Smart Commit - PoLang 智能 Commit Message 生成工具
# 用途: 基于 git diff 自动生成符合 Conventional Commits 规范的结构化 commit message
# 调用: ./scripts/smart-commit.sh [options]
#
# Options:
#   --staged          基于暂存区变更生成（默认）
#   --dry-run         预览生成的 commit message，不实际提交
#   --edit            生成后打开编辑器修改
#   --output <file>   输出到文件
#
# 示例:
#   ./scripts/smart-commit.sh --dry-run          # 预览 commit message
#   ./scripts/smart-commit.sh                    # 生成并执行 git commit
#   ./scripts/smart-commit.sh --edit             # 生成后在编辑器中修改
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
DRY_RUN=false
EDIT=false
OUTPUT_FILE=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --staged) MODE="staged"; shift ;;
        --dry-run) DRY_RUN=true; shift ;;
        --edit) EDIT=true; shift ;;
        --output) OUTPUT_FILE="$2"; shift 2 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 推断变更类型（feat/fix/docs/refactor/test/chore）
infer_type() {
    local files="$1"
    local has_feat=false
    local has_fix=false
    local has_test=false
    local has_doc=false
    
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        
        # 检查新增功能特征
        if git diff --cached "$file" 2>/dev/null | grep -qE '^\+.*fun |^\+.*class |^\+.*val |^\+.*var '; then
            has_feat=true
        fi
        
        # 检查删除（可能是修复或重构）
        if git diff --cached "$file" 2>/dev/null | grep -qE '^-.*fun |^-.*class '; then
            has_fix=true
        fi
        
        # 测试文件
        if echo "$file" | grep -qE "Test\.kt$|test/|androidTest/"; then
            has_test=true
        fi
        
        # 文档文件
        if echo "$file" | grep -qE "\.md$|\.txt$"; then
            has_doc=true
        fi
    done <<< "$files"
    
    # 优先级：test > feat > fix > doc > refactor
    if [ "$has_test" = true ] && ! $has_feat && ! $has_fix; then
        echo "test"
    elif [ "$has_doc" = true ] && ! $has_feat && ! $has_fix && ! $has_test; then
        echo "docs"
    elif [ "$has_feat" = true ]; then
        echo "feat"
    elif [ "$has_fix" = true ]; then
        echo "fix"
    else
        echo "refactor"
    fi
}

# 推断变更范围（scope）
infer_scope() {
    local files="$1"
    local scopes=""
    
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        
        local scope=""
        if echo "$file" | grep -q "features/camera/"; then
            scope="camera"
        elif echo "$file" | grep -q "features/gallery/"; then
            scope="gallery"
        elif echo "$file" | grep -q "features/editor/"; then
            scope="editor"
        elif echo "$file" | grep -q "features/settings/"; then
            scope="settings"
        elif echo "$file" | grep -q "features/debug/"; then
            scope="debug"
        elif echo "$file" | grep -q "engines/beauty-engine/"; then
            scope="beauty"
        elif echo "$file" | grep -q "data/"; then
            scope="data"
        elif echo "$file" | grep -q "di/"; then
            scope="di"
        elif echo "$file" | grep -qE "scripts/|\.sh$|\.py$"; then
            scope="tools"
        elif echo "$file" | grep -qE "\.md$"; then
            scope="docs"
        elif echo "$file" | grep -qE "build\.gradle|gradle|buildSrc"; then
            scope="build"
        fi
        
        if [ -n "$scope" ]; then
            scopes="${scopes}${scopes:+,}${scope}"
        fi
    done <<< "$files"
    
    # 去重并选择最频繁的范围
    echo "$scopes" | tr ',' '\n' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}' || echo ""
}

# 生成简短的 subject
generate_subject() {
    local type="$1"
    local scope="$2"
    local files="$3"
    
    local scope_str=""
    [ -n "$scope" ] && scope_str="($scope)"
    
    # 根据文件推断具体描述
    local description=""
    
    # 检查是否有新增文件
    local added_count=$(echo "$files" | while read -r f; do git diff --cached --name-status 2>/dev/null | grep "^A.*$f"; done | wc -l | tr -d ' ')
    local modified_count=$(echo "$files" | while read -r f; do git diff --cached --name-status 2>/dev/null | grep "^M.*$f"; done | wc -l | tr -d ' ')
    local deleted_count=$(echo "$files" | while read -r f; do git diff --cached --name-status 2>/dev/null | grep "^D.*$f"; done | wc -l | tr -d ' ')
    
    # 检查是否有特定关键词
    local keywords=""
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        local diff_content=$(git diff --cached "$file" 2>/dev/null | grep "^+" | grep -v "^+++" | head -20)
        
        if echo "$diff_content" | grep -qi "add\|new\|create\|introduce"; then
            keywords="${keywords}add "
        fi
        if echo "$diff_content" | grep -qi "fix\|bug\|repair\|correct"; then
            keywords="${keywords}fix "
        fi
        if echo "$diff_content" | grep -qi "update\|upgrade\|bump"; then
            keywords="${keywords}update "
        fi
        if echo "$diff_content" | grep -qi "remove\|delete\|drop\|clean"; then
            keywords="${keywords}remove "
        fi
    done <<< "$files"
    
    # 生成描述
    if [ "$added_count" -gt 0 ] && [ "$modified_count" -eq 0 ] && [ "$deleted_count" -eq 0 ]; then
        description="add new files"
    elif [ "$deleted_count" -gt 0 ] && [ "$modified_count" -eq 0 ] && [ "$added_count" -eq 0 ]; then
        description="remove deprecated code"
    elif echo "$keywords" | grep -q "fix"; then
        description="fix issues"
    elif echo "$keywords" | grep -q "add"; then
        description="add functionality"
    elif [ "$type" = "test" ]; then
        description="add tests"
    elif [ "$type" = "docs" ]; then
        description="update documentation"
    else
        description="update code"
    fi
    
    # 检查是否有具体的类名可以放入描述
    local class_names=$(git diff --cached --name-only 2>/dev/null | grep "\.kt$" | xargs -I {} git diff --cached {} 2>/dev/null | grep -E "^\+.*(class |fun )" | grep -oE "(class|fun)\s+\w+" | sed 's/class //;s/fun //' | sort -u | head -3 | tr '\n' ',' | sed 's/,$//')
    
    if [ -n "$class_names" ] && [ "${#class_names}" -lt 40 ]; then
        description="update $class_names"
    fi
    
    echo "${type}${scope_str}: ${description}"
}

# 生成详细 body
generate_body() {
    local files="$1"
    local type="$2"
    
    local body=""
    
    # 变更文件列表
    body="${body}Changes:\n"
    
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        local status=$(git diff --cached --name-status 2>/dev/null | grep "$file" | cut -f1)
        local status_icon=""
        case "$status" in
            A) status_icon="+" ;;
            M) status_icon="~" ;;
            D) status_icon="-" ;;
            *) status_icon="?" ;;
        esac
        body="${body}- ${status_icon} ${file}\n"
    done <<< "$files"
    
    # 新增功能详情
    if [ "$type" = "feat" ]; then
        body="${body}\nNew Features:\n"
        while IFS= read -r file; do
            [ -z "$file" ] && continue
            if echo "$file" | grep -qE "\.kt$|\.java$"; then
                local new_api=$(git diff --cached "$file" 2>/dev/null | grep -E "^\+.*(public |fun |val |var )" | grep -v "^+++" | sed 's/^+//;s/^[[:space:]]*//' | head -5)
                if [ -n "$new_api" ]; then
                    body="${body}- ${file}:\n"
                    body="${body}$(echo "$new_api" | sed 's/^/    /')\n"
                fi
            fi
        done <<< "$files"
    fi
    
    # Bug 修复详情
    if [ "$type" = "fix" ]; then
        body="${body}\nFixes:\n"
        body="${body}- Fixed issues in affected files\n"
    fi
    
    # 测试详情
    if [ "$type" = "test" ]; then
        local test_count=$(echo "$files" | grep -cE "Test\.kt$" || echo 0)
        if [ "$test_count" -gt 0 ]; then
            body="${body}\nTests:\n"
            body="${body}- Added/modified ${test_count} test file(s)\n"
        fi
    fi
    
    echo -e "$body"
}

# 生成完整的 commit message
generate_commit_message() {
    local files="$1"
    
    local type=$(infer_type "$files")
    local scope=$(infer_scope "$files")
    local subject=$(generate_subject "$type" "$scope" "$files")
    local body=$(generate_body "$files" "$type")
    
    cat << EOF
$subject

$body
EOF
}

# 主流程
CHANGED_FILES=$(git diff --cached --name-only 2>/dev/null || true)

if [ -z "$CHANGED_FILES" ]; then
    echo -e "${YELLOW}⚠️ 暂存区为空，请先执行 git add${NC}"
    exit 1
fi

echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  🤖 Smart Commit - 智能 Commit Message 生成              ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

COMMIT_MSG=$(generate_commit_message "$CHANGED_FILES")

if [ -n "$OUTPUT_FILE" ]; then
    echo "$COMMIT_MSG" > "$OUTPUT_FILE"
    echo -e "${GREEN}✅ Commit message 已保存: $OUTPUT_FILE${NC}"
fi

echo -e "${BLUE}生成的 Commit Message:${NC}"
echo "────────────────────────────────────────────────────────────"
echo "$COMMIT_MSG"
echo "────────────────────────────────────────────────────────────"
echo ""

if [ "$DRY_RUN" = true ]; then
    echo -e "${YELLOW}💡 Dry-run 模式，未执行 git commit${NC}"
    echo -e "${YELLOW}   使用 --edit 修改后提交，或直接执行 git commit${NC}"
    exit 0
fi

if [ "$EDIT" = true ]; then
    # 创建临时文件
    TEMP_FILE=$(mktemp)
    echo "$COMMIT_MSG" > "$TEMP_FILE"
    ${EDITOR:-vi} "$TEMP_FILE"
    COMMIT_MSG=$(cat "$TEMP_FILE")
    rm -f "$TEMP_FILE"
fi

# 执行 git commit
echo "$COMMIT_MSG" | git commit -F - 2>&1 | tail -5

if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✅ Commit 成功！${NC}"
    git log -1 --oneline
else
    echo ""
    echo -e "${RED}❌ Commit 失败${NC}"
    exit 1
fi
